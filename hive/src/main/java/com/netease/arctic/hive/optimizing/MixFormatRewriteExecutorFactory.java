/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.optimizing;

import com.netease.arctic.optimizing.OptimizingExecutor;
import com.netease.arctic.optimizing.OptimizingExecutorFactory;
import com.netease.arctic.optimizing.OptimizingInputProperties;
import com.netease.arctic.optimizing.RewriteFilesInput;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;

import java.util.Map;

/**
 * A factory to create {@link MixFormatRewriteExecutor}
 */
public class MixFormatRewriteExecutorFactory implements OptimizingExecutorFactory<RewriteFilesInput> {

  private Map<String, String> properties;

  @Override
  public void initialize(Map<String, String> properties) {
    this.properties = Maps.newHashMap(properties);
  }

  @Override
  public OptimizingExecutor createExecutor(RewriteFilesInput input) {
    OptimizingInputProperties optimizingConfig = OptimizingInputProperties.parse(properties);
    return new MixFormatRewriteExecutor(input, input.getTable(), optimizingConfig.getStructLikeCollections(),
        optimizingConfig.getOutputDir());
  }
}
