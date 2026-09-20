/*
 * Copyright 2023 OpenSPG Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 */

package com.antgroup.openspg.cloudext.impl.computingengine.local;

import com.alibaba.fastjson.JSONObject;
import com.antgroup.openspg.builder.model.BuilderConstants;
import com.antgroup.openspg.cloudext.interfaces.computingengine.ComputingEngineClient;
import com.antgroup.openspg.cloudext.interfaces.computingengine.model.ComputingStatusEnum;
import com.antgroup.openspg.cloudext.interfaces.computingengine.model.ComputingTask;
import com.antgroup.openspg.common.constants.BuilderConstant;
import com.antgroup.openspg.server.common.model.bulider.BuilderJob;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local computing-engine client.
 *
 * <p>Manages build commands as child processes through an in-memory task registry ({@link
 * ConcurrentHashMap}): - {@link #submitBuilderJob} launches {@code extension.command} via {@code
 * /bin/sh -c} (only for {@code KAG_COMMAND} jobs); a daemon thread waits for the process, captures
 * its exit code and streams stdout/stderr into a log file. Returns a {@link ComputingTask} carrying
 * the task id and log path. - {@link #queryStatus} maps a task id to {@code RUNNING / SUCCESS /
 * FAILED / NOTFOUND / STOP}. - {@link #stop} kills the process.
 *
 * <p>It is compatible with the scheduler retry/termination semantics: resubmitting the same {@link
 * BuilderJob} reuses an unfinished task (idempotent), and failure is decided by the process exit
 * code. The registry is memory-only, so a server restart loses in-flight tasks; the scheduler
 * treats the unknown id as {@code NOTFOUND} and triggers re-submission.
 */
public class LocalComputingEngineClient implements ComputingEngineClient<JSONObject> {

  private static final Logger log = LoggerFactory.getLogger(LocalComputingEngineClient.class);

  private static final String DEFAULT_WORK_DIR = "/tmp/kag-worker";
  private static final long DEFAULT_TIMEOUT_SEC = 86400L;

  private final String connUrl;
  private final String workDir;
  private final long timeoutSec;

  private final Map<String, LocalTask> tasks = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong(1);

  public LocalComputingEngineClient(String connUrl) {
    this.connUrl = connUrl;
    // e.g. local://exec?workdir=/data/worker&timeoutSec=86400
    int q = connUrl == null ? -1 : connUrl.indexOf("?");
    String query = q >= 0 ? connUrl.substring(q + 1) : "";
    this.workDir = parseParam(query, "workdir", DEFAULT_WORK_DIR);
    this.timeoutSec = parseLongParam(query, "timeoutSec", DEFAULT_TIMEOUT_SEC);
  }

  private static String parseParam(String query, String key, String def) {
    for (String pair : StringUtils.split(query, "&")) {
      int eq = pair.indexOf("=");
      if (eq > 0 && key.equals(pair.substring(0, eq))) {
        return pair.substring(eq + 1);
      }
    }
    return def;
  }

  private static long parseLongParam(String query, String key, long def) {
    String v = parseParam(query, key, null);
    if (v == null) {
      return def;
    }
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return def;
    }
  }

  @Override
  public String getConnUrl() {
    return connUrl;
  }

  @Override
  public ComputingTask submitBuilderJob(BuilderJob builderJob, JSONObject extension) {
    String command = extension == null ? null : extension.getString(BuilderConstant.COMMAND);
    // Idempotent: reuse an unfinished task of the same BuilderJob to avoid spawning
    // duplicate processes on scheduler retries.
    LocalTask existing = findActiveByJob(builderJob.getId());
    if (existing != null) {
      return toComputingTask(existing);
    }

    LocalTask task = new LocalTask(builderJob.getId());
    task.taskId = "local-" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
    task.logFile = workDir + File.separator + "logs" + File.separator + task.taskId + ".log";
    task.command = command;

    try {
      File logParent = new File(task.logFile).getParentFile();
      if (!logParent.exists() && !logParent.mkdirs()) {
        log.warn("create log dir failed: {}", logParent);
      }
      PrintStream ps = new PrintStream(new FileOutputStream(task.logFile), true, "UTF-8");

      if (!BuilderConstant.KAG_COMMAND.equals(builderJob.getType())
          || StringUtils.isBlank(command)) {
        // Only KAG_COMMAND is supported; other types fail immediately without throwing so
        // that the state machine (queryStatus) expresses the result.
        ps.println("# unsupported builder type or empty command: " + builderJob.getType());
        ps.close();
        task.markDone(-1);
      } else {
        ProcessBuilder pb =
            new ProcessBuilder("/bin/sh", "-c", command).directory(new File(workDir));
        pb.redirectErrorStream(true);
        // Only non-secret connection URLs are injected; credentials are never placed in the
        // child process environment.
        pb.environment().putAll(buildEnv(extension));
        Process process = pb.start();
        task.process = process;
        new Thread(() -> drain(process, ps, task), "local-computing-drain-" + task.taskId).start();
      }
    } catch (IOException | RuntimeException e) {
      log.error("submit local builder job failed", e);
      task.markDone(-1);
    }

    tasks.put(task.taskId, task);
    return toComputingTask(task);
  }

  /** Finds an unfinished task of the given BuilderJob (for idempotent reuse). */
  private LocalTask findActiveByJob(Long jobId) {
    if (jobId == null) {
      return null;
    }
    for (LocalTask t : tasks.values()) {
      if (jobId.equals(t.jobId) && !t.done) {
        return t;
      }
    }
    return null;
  }

  private Map<String, String> buildEnv(JSONObject extension) {
    Map<String, String> env = new HashMap<>();
    if (extension != null) {
      // Only non-secret connection URLs are passed; credentials are not exposed to the
      // child process.
      putIfPresent(
          env, "SPG_GRAPH_STORE_URL", extension.getString(BuilderConstants.GRAPH_STORE_URL_OPTION));
      putIfPresent(
          env,
          "SPG_SEARCH_ENGINE_URL",
          extension.getString(BuilderConstants.SEARCH_ENGINE_URL_OPTION));
      putIfPresent(env, "SPG_SCHEMA_URL", extension.getString(BuilderConstants.SCHEMA_URL_OPTION));
      putIfPresent(
          env, "SPG_PYTHON_EXEC", extension.getString(BuilderConstants.PYTHON_EXEC_OPTION));
    }
    return env;
  }

  private void putIfPresent(Map<String, String> env, String key, String value) {
    if (StringUtils.isNotBlank(value)) {
      env.put(key, value);
    }
  }

  private void drain(Process process, PrintStream ps, LocalTask task) {
    try (InputStream is = process.getInputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = is.read(buf)) >= 0) {
        if (n > 0) {
          ps.write(buf, 0, n);
          ps.flush();
        }
      }
    } catch (IOException ignore) {
      // Closing the pipe after the process exits is expected.
    } finally {
      int code;
      try {
        code = process.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        code = -1;
      }
      task.markDone(code);
      ps.close();
      log.info("local builder task {} finished with exitCode {}", task.taskId, code);
    }
  }

  @Override
  public ComputingStatusEnum queryStatus(JSONObject extension, String id) {
    LocalTask task = tasks.get(id);
    if (task == null) {
      return ComputingStatusEnum.NOTFOUND;
    }
    // Timeout -> STOP (handled by the scheduler as FAILED).
    if (!task.done && System.currentTimeMillis() - task.startTime > timeoutSec * 1000) {
      if (task.process != null) {
        task.process.destroyForcibly();
      }
      task.markDone(124);
      return ComputingStatusEnum.STOP;
    }
    if (!task.done) {
      return ComputingStatusEnum.RUNNING;
    }
    return task.exitCode == 0 ? ComputingStatusEnum.SUCCESS : ComputingStatusEnum.FAILED;
  }

  @Override
  public Boolean stop(JSONObject extension, String id) {
    LocalTask task = tasks.get(id);
    if (task == null || task.process == null) {
      return false;
    }
    task.process.destroy();
    try {
      if (!task.process.waitFor(5, TimeUnit.SECONDS)) {
        task.process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    task.markDone(130);
    return true;
  }

  private ComputingTask toComputingTask(LocalTask task) {
    ComputingTask computingTask = new ComputingTask();
    computingTask.setTaskId(task.taskId);
    computingTask.setLogUrl(task.logFile);
    return computingTask;
  }

  /** A single local task record; volatile fields are written by the drain thread. */
  static class LocalTask {
    final Long jobId;
    String taskId;
    String command;
    String logFile;
    Process process;
    volatile boolean done;
    volatile int exitCode;
    long startTime = System.currentTimeMillis();

    LocalTask(Long jobId) {
      this.jobId = jobId;
    }

    void markDone(int code) {
      this.exitCode = code;
      this.done = true;
    }
  }
}
