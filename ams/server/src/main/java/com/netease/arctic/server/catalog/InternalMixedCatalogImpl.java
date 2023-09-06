package com.netease.arctic.server.catalog;

import com.netease.arctic.AmoroTable;
import com.netease.arctic.ams.api.CatalogMeta;
import com.netease.arctic.catalog.MixedTables;
import com.netease.arctic.formats.mixed.MixedIcebergTable;
import com.netease.arctic.server.persistence.mapper.TableMetaMapper;
import com.netease.arctic.server.table.TableMetadata;
import com.netease.arctic.table.ArcticTable;

public class InternalMixedCatalogImpl extends InternalCatalog {

  private final MixedTables tables;

  protected InternalMixedCatalogImpl(CatalogMeta metadata) {
    super(metadata);
    this.tables = new MixedTables(metadata);
  }

  protected InternalMixedCatalogImpl(CatalogMeta metadata, MixedTables tables) {
    super(metadata);
    this.tables = tables;
  }

  @Override
  public void updateMetadata(CatalogMeta metadata) {
    super.updateMetadata(metadata);
    this.tables.refreshCatalogMeta(getMetadata());
  }

  @Override
  public AmoroTable<?> loadTable(String database, String tableName) {
    TableMetadata tableMetadata = getAs(TableMetaMapper.class, mapper ->
        mapper.selectTableMetaByName(getMetadata().getCatalogName(), database, tableName));
    if (tableMetadata == null) {
      return null;
    }
    ArcticTable arcticTable = tables.loadTableByMeta(tableMetadata.buildTableMeta());
    return new MixedIcebergTable(arcticTable);
  }

  protected MixedTables tables() {
    return tables;
  }
}
