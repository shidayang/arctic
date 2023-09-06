/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.catalog;

import com.netease.arctic.ams.api.CatalogMeta;
import com.netease.arctic.ams.api.TableIdentifier;
import com.netease.arctic.mixed.BasicMixedIcebergCatalog;
import com.netease.arctic.table.ArcticTable;
import java.util.List;
import java.util.stream.Collectors;

public class MixedIcebergCatalogImpl extends ExternalCatalog {
  private BasicMixedIcebergCatalog mixedIcebergCatalog;

  protected MixedIcebergCatalogImpl(CatalogMeta metadata) {
    super(metadata);
    this.mixedIcebergCatalog = new BasicMixedIcebergCatalog(metadata);
  }

  @Override
  public void updateMetadata(CatalogMeta metadata) {
    super.updateMetadata(metadata);
    this.mixedIcebergCatalog = new BasicMixedIcebergCatalog(metadata);
  }

  @Override
  public boolean exist(String database) {
    return mixedIcebergCatalog.listDatabases().contains(database);
  }

  @Override
  public boolean exist(String database, String tableName) {
    return mixedIcebergCatalog.tableExists(com.netease.arctic.table.TableIdentifier.of(name(), database, tableName));
  }

  @Override
  public List<String> listDatabases() {
    return mixedIcebergCatalog.listDatabases();
  }

  @Override
  public List<TableIdentifier> listTables() {
    return listDatabases().stream()
        .map(this::listTables)
        .flatMap(List::stream)
        .collect(Collectors.toList());
  }

  @Override
  public List<TableIdentifier> listTables(String database) {
    return mixedIcebergCatalog.listTables(database)
        .stream()
        .map(com.netease.arctic.table.TableIdentifier::buildTableIdentifier)
        .collect(Collectors.toList());
  }

  @Override
  public ArcticTable loadTable(String database, String tableName) {
    return mixedIcebergCatalog.loadTable(com.netease.arctic.table.TableIdentifier.of(name(), database, tableName));
  }
}
