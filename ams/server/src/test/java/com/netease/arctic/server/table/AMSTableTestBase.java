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

package com.netease.arctic.server.table;

import com.netease.arctic.TableTestHelper;
import com.netease.arctic.ams.api.CatalogMeta;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.catalog.ArcticCatalog;
import com.netease.arctic.catalog.CatalogTestHelper;
import com.netease.arctic.catalog.IcebergCatalogWrapper;
import com.netease.arctic.catalog.MixedTables;
import com.netease.arctic.hive.TestHMS;
import com.netease.arctic.mixed.BasicMixedIcebergCatalog;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.utils.ConvertStructUtil;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;

public class AMSTableTestBase extends TableServiceTestBase {
  @ClassRule
  public static TestHMS TEST_HMS = new TestHMS();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  private final CatalogTestHelper catalogTestHelper;
  private final TableTestHelper tableTestHelper;
  private String catalogWarehouse;
  private MixedTables mixedTables;
  private ArcticCatalog externalCatalog;
  private CatalogMeta catalogMeta;

  private TableMeta tableMeta;

  private final boolean autoInitTable;
  private ServerTableIdentifier serverTableIdentifier;

  public AMSTableTestBase(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    this(catalogTestHelper, tableTestHelper, false);
  }

  public AMSTableTestBase(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper,
      boolean autoInitTable) {
    this.catalogTestHelper = catalogTestHelper;
    this.tableTestHelper = tableTestHelper;
    this.autoInitTable = autoInitTable;
  }

  @Before
  public void init() throws IOException, TException {
    catalogWarehouse = temp.newFolder().getPath();
    catalogMeta = catalogTestHelper.buildCatalogMeta(catalogWarehouse);
    if (catalogTestHelper.isInternalCatalog()) {
      if (TableFormat.MIXED_ICEBERG.equals(catalogTestHelper.tableFormat())) {
        mixedTables = catalogTestHelper.buildMixedTables(catalogMeta);
        tableMeta = buildTableMeta();
      }
    } else {
      if (TableFormat.ICEBERG.equals(catalogTestHelper.tableFormat())) {
        externalCatalog = new IcebergCatalogWrapper(catalogMeta);
      } else if (TableFormat.MIXED_ICEBERG.equals(catalogTestHelper.tableFormat())) {
        externalCatalog = new BasicMixedIcebergCatalog(catalogMeta);
      } else if (TableFormat.MIXED_HIVE.equals(catalogTestHelper.tableFormat())) {
        mixedTables = catalogTestHelper.buildMixedTables(catalogMeta);
        tableMeta = buildTableMeta();
      }
    }

    tableService().createCatalog(catalogMeta);
    try {
      Database database = new Database();
      database.setName(TableTestHelper.TEST_DB_NAME);
      TEST_HMS.getHiveClient().createDatabase(database);
    } catch (AlreadyExistsException e) {
      //pass
    }
    if (autoInitTable) {
      createDatabase();
      createTable();
    }
  }

  @After
  public void dispose() throws TException {
    if (autoInitTable) {
      dropTable();
      dropDatabase();
    }
    if (catalogMeta != null) {
      tableService().dropCatalog(catalogMeta.getCatalogName());
      TEST_HMS.getHiveClient().dropDatabase(TableTestHelper.TEST_DB_NAME, false, true);
    }
  }

  protected TableMeta buildTableMeta() {
    ConvertStructUtil.TableMetaBuilder builder =
        new ConvertStructUtil.TableMetaBuilder(TableTestHelper.TEST_TABLE_ID, tableTestHelper.tableSchema());
    String tableLocation = String.format("%s/%s/%s", catalogWarehouse, TableTestHelper.TEST_DB_NAME,
        TableTestHelper.TEST_TABLE_NAME);
    return builder.withPrimaryKeySpec(tableTestHelper.primaryKeySpec())
        .withProperties(tableTestHelper.tableProperties())
        .withTableLocation(tableLocation)
        .withFormat(catalogTestHelper.tableFormat())
        .withChangeLocation(tableLocation + "/change")
        .withBaseLocation(tableLocation + "/base")
        .build();
  }

  protected void createDatabase() {
    if (externalCatalog == null) {
      if (!tableService().listDatabases(TableTestHelper.TEST_CATALOG_NAME)
          .contains(TableTestHelper.TEST_DB_NAME)) {
        tableService().createDatabase(TableTestHelper.TEST_CATALOG_NAME, TableTestHelper.TEST_DB_NAME);
      }
    } else {
      externalCatalog.createDatabase(TableTestHelper.TEST_DB_NAME);
    }
  }

  protected void dropDatabase() {
    if (externalCatalog == null) {
      tableService().dropDatabase(TableTestHelper.TEST_CATALOG_NAME, TableTestHelper.TEST_DB_NAME);
    } else {
      externalCatalog.dropDatabase(TableTestHelper.TEST_DB_NAME);
    }
  }

  protected void createTable() {
    if (externalCatalog == null) {
      mixedTables.createTableByMeta(tableMeta, tableTestHelper.tableSchema(), tableTestHelper.primaryKeySpec(),
          tableTestHelper.partitionSpec());
      TableMetadata tableMetadata = tableMetadata();
      tableService().createTable(catalogMeta.getCatalogName(), tableMetadata);
    } else {
      externalCatalog.newTableBuilder(tableTestHelper.id(), tableTestHelper.tableSchema())
          .withPartitionSpec(tableTestHelper.partitionSpec())
          .withProperties(tableTestHelper.tableProperties())
          .withPrimaryKeySpec(tableTestHelper.primaryKeySpec())
          .create();
      tableService().exploreExternalCatalog();
    }

    serverTableIdentifier = tableService().listManagedTables().get(0);
  }

  protected void dropTable() {
    if (externalCatalog == null) {
      mixedTables.dropTableByMeta(tableMeta, true);
      tableService().dropTableMetadata(tableMeta.getTableIdentifier(), true);
    } else {
      externalCatalog.dropTable(tableTestHelper.id(), true);
      tableService().exploreExternalCatalog();
    }
  }

  protected CatalogTestHelper catalogTestHelper() {
    return catalogTestHelper;
  }

  protected TableTestHelper tableTestHelper() {
    return tableTestHelper;
  }

  protected TableMeta tableMeta() {
    return tableMeta;
  }

  protected CatalogMeta catalogMeta() {
    return catalogMeta;
  }

  protected TableMetadata tableMetadata() {
    return new TableMetadata(ServerTableIdentifier.of(tableMeta.getTableIdentifier()), tableMeta, catalogMeta);
  }

  protected ServerTableIdentifier serverTableIdentifier() {
    return serverTableIdentifier;
  }

  protected void validateArcticTable(ArcticTable arcticTable) {
    Assert.assertEquals(catalogTestHelper().tableFormat(), arcticTable.format());
    Assert.assertEquals(TableTestHelper.TEST_TABLE_ID, arcticTable.id());
    Assert.assertEquals(tableTestHelper().tableSchema().asStruct(), arcticTable.schema().asStruct());
    Assert.assertEquals(tableTestHelper().partitionSpec(), arcticTable.spec());
    Assert.assertEquals(tableTestHelper().primaryKeySpec().primaryKeyExisted(), arcticTable.isKeyedTable());
  }

  protected void validateTableRuntime(TableRuntime tableRuntime) {
    Assert.assertEquals(serverTableIdentifier(), tableRuntime.getTableIdentifier());
  }
}
