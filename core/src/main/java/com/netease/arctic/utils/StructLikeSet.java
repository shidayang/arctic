/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.utils;

import com.netease.arctic.utils.map.StructLikeBaseMap;
import com.netease.arctic.utils.map.StructLikeMemoryMap;
import com.netease.arctic.utils.map.StructLikeSpillableMap;
import java.io.IOException;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.types.Types;

import java.io.Closeable;
import java.io.IOException;

public class StructLikeSet implements Closeable {

  public static StructLikeSet createMemorySet(Types.StructType type) {
    return new StructLikeSet(type);
  }

  public static StructLikeSet createSpillableSet(Types.StructType type,
                                                 Long maxInMemorySizeInBytes, String mapIdentifier) {
    return new StructLikeSet(type, maxInMemorySizeInBytes, mapIdentifier);
  }

  private static final Integer _V = 0;
  private StructLikeBaseMap<Integer> structLikeMap;

  private StructLikeSet(Types.StructType type) {
    this.structLikeMap =  StructLikeMemoryMap.create(type);
  }

  private StructLikeSet(Types.StructType type, Long maxInMemorySizeInBytes, String mapIdentifier) {
    this.structLikeMap =  StructLikeSpillableMap.create(type, maxInMemorySizeInBytes, mapIdentifier);
  }

  public boolean contains(StructLike key) {
    return structLikeMap.get(key) != null;
  }

  public void add(StructLike struct) {
    structLikeMap.put(struct, _V);
  }

  public void remove(StructLike struct) {
    structLikeMap.delete(struct);
  }

  public void close() throws IOException {
    structLikeMap.close();
  }
}