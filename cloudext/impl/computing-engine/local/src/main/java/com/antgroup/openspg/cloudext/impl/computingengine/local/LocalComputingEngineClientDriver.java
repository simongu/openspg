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

import com.antgroup.openspg.cloudext.interfaces.computingengine.ComputingEngineClient;
import com.antgroup.openspg.cloudext.interfaces.computingengine.ComputingEngineClientDriver;
import com.antgroup.openspg.cloudext.interfaces.computingengine.ComputingEngineClientDriverManager;
import com.antgroup.openspg.common.util.cloudext.CachedCloudExtClientDriver;

/**
 * Local computing-engine driver (scheme = {@code local}).
 *
 * <p>Executes KAG_COMMAND build commands as child processes inside the OpenSPG server container,
 * with an in-memory process registry for asynchronous execution and status polling. Only {@code
 * KAG_COMMAND} commands are executed; other job types are registered as immediately failed tasks in
 * the client.
 */
public class LocalComputingEngineClientDriver
    extends CachedCloudExtClientDriver<ComputingEngineClient>
    implements ComputingEngineClientDriver {

  static {
    ComputingEngineClientDriverManager.registerDriver(new LocalComputingEngineClientDriver());
  }

  @Override
  public String driverScheme() {
    return "local";
  }

  @Override
  protected ComputingEngineClient innerConnect(String connInfo) {
    return new LocalComputingEngineClient(connInfo);
  }
}
