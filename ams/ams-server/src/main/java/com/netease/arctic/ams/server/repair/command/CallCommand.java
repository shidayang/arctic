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

package com.netease.arctic.ams.server.repair.command;

import com.netease.arctic.ams.server.repair.Context;
import com.netease.arctic.table.TableIdentifier;

public interface CallCommand {

  String call(Context context)  throws Exception;

  default String OK() {
    return "OK";
  }

  default TableIdentifier fullTableName(Context context, String tablePath) {
    TableIdentifier tableIdentifier = TableIdentifier.of(tablePath);
    if (tableIdentifier.getCatalog() == null) {
      if (context.getCatalog() == null) {
        throw new FullTableNameException("Can not find catalog name, your can support full table path or use 'USE " +
            "{catalog}' statement");
      }
      tableIdentifier.setCatalog(context.getCatalog());
    }
    if (tableIdentifier.getDatabase() == null) {
      if (context.getDb() == null) {
        throw new FullTableNameException("Can not find db name, your can support full table path or use 'USE " +
            "{database}' statement");
      }
      tableIdentifier.setDatabase(context.getDb());
    }
    return tableIdentifier;
  }

  class FullTableNameException extends RuntimeException {
    public FullTableNameException(String message) {
      super(message);
    }
  }
}
