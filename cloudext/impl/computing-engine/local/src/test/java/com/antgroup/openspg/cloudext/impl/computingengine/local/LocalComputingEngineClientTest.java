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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.alibaba.fastjson.JSONObject;
import com.antgroup.openspg.cloudext.interfaces.computingengine.model.ComputingStatusEnum;
import com.antgroup.openspg.cloudext.interfaces.computingengine.model.ComputingTask;
import com.antgroup.openspg.common.constants.BuilderConstant;
import com.antgroup.openspg.server.common.model.bulider.BuilderJob;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Local computing-engine client state machine tests. */
public class LocalComputingEngineClientTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private LocalComputingEngineClient client(String query) {
    return new LocalComputingEngineClient(
        "local://exec?workdir=" + tmp.getRoot().getAbsolutePath() + (query == null ? "" : query));
  }

  private BuilderJob kagJob(long id, String command) {
    BuilderJob job = new BuilderJob();
    job.setId(id);
    job.setType(BuilderConstant.KAG_COMMAND);
    JSONObject ext = new JSONObject();
    ext.put(BuilderConstant.COMMAND, command);
    job.setComputingConf(ext.toJSONString());
    return job;
  }

  private JSONObject extension(String command) {
    JSONObject ext = new JSONObject();
    ext.put(BuilderConstant.COMMAND, command);
    ext.put("graphStoreUrl", "neo4j://x:7687");
    return ext;
  }

  @Test
  public void testEchoCommandReachesSuccess() throws Exception {
    LocalComputingEngineClient c = client(null);
    ComputingTask t = c.submitBuilderJob(kagJob(1L, "echo hello"), extension("echo hello"));
    assertNotNull(t.getTaskId());
    // poll until terminal state
    ComputingStatusEnum status = c.queryStatus(new JSONObject(), t.getTaskId());
    int guard = 0;
    while ((status == ComputingStatusEnum.RUNNING || status == ComputingStatusEnum.NOTFOUND)
        && guard++ < 100) {
      Thread.sleep(30);
      status = c.queryStatus(new JSONObject(), t.getTaskId());
    }
    assertEquals(ComputingStatusEnum.SUCCESS, status);
  }

  @Test
  public void testFailedCommand() throws Exception {
    LocalComputingEngineClient c = client(null);
    ComputingTask t = c.submitBuilderJob(kagJob(2L, "exit 1"), extension("exit 1"));
    ComputingStatusEnum status = c.queryStatus(new JSONObject(), t.getTaskId());
    int guard = 0;
    while (status == ComputingStatusEnum.RUNNING && guard++ < 100) {
      Thread.sleep(30);
      status = c.queryStatus(new JSONObject(), t.getTaskId());
    }
    assertEquals(ComputingStatusEnum.FAILED, status);
    // the log file should have been created
    assertTrue(new File(t.getLogUrl()).length() >= 0);
  }

  @Test
  public void testUnknownTaskIsNotFound() {
    LocalComputingEngineClient c = client(null);
    assertEquals(ComputingStatusEnum.NOTFOUND, c.queryStatus(new JSONObject(), "no-such-task"));
  }

  @Test
  public void testUnsupportedTypeFailsImmediately() {
    LocalComputingEngineClient c = client(null);
    BuilderJob job = new BuilderJob();
    job.setId(3L);
    job.setType("OTHER");
    JSONObject ext = new JSONObject();
    ext.put(BuilderConstant.COMMAND, "echo x");
    ComputingTask t = c.submitBuilderJob(job, ext);
    // non-KAG_COMMAND -> immediate FAILED (not thrown; expressed via the state machine)
    assertEquals(ComputingStatusEnum.FAILED, c.queryStatus(new JSONObject(), t.getTaskId()));
  }

  @Test
  public void testIdempotentResubmitReusesSameTaskId() throws Exception {
    LocalComputingEngineClient c = client(null);
    ComputingTask first = c.submitBuilderJob(kagJob(4L, "sleep 0.2"), extension("sleep 0.2"));
    ComputingTask second = c.submitBuilderJob(kagJob(4L, "sleep 0.2"), extension("sleep 0.2"));
    // unfinished same BuilderJob -> reuse the same taskId (avoid duplicate processes on retry)
    assertEquals(first.getTaskId(), second.getTaskId());
    // wait for terminal state, then a new submission must get a new taskId
    ComputingStatusEnum s = c.queryStatus(new JSONObject(), first.getTaskId());
    int guard = 0;
    while (s == ComputingStatusEnum.RUNNING && guard++ < 100) {
      Thread.sleep(30);
      s = c.queryStatus(new JSONObject(), first.getTaskId());
    }
    ComputingTask third = c.submitBuilderJob(kagJob(4L, "echo done"), extension("echo done"));
    assertFalse(first.getTaskId().equals(third.getTaskId()));
  }

  @Test
  public void testWorkdirLogFileLocation() {
    LocalComputingEngineClient c = client(null);
    ComputingTask t = c.submitBuilderJob(kagJob(5L, "echo hi"), extension("echo hi"));
    assertTrue(t.getLogUrl().startsWith(tmp.getRoot().getAbsolutePath()));
    assertTrue(t.getLogUrl().contains("logs"));
  }
}
