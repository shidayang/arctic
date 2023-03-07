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

package com.netease.arctic.ams.server.repair;

import com.google.common.collect.Iterables;
import com.netease.arctic.IcebergFileEntry;
import com.netease.arctic.catalog.ArcticCatalog;
import com.netease.arctic.io.ArcticFileIO;
import com.netease.arctic.op.ArcticHadoopTableOperations;
import com.netease.arctic.scan.TableEntriesScan;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.BaseLocationKind;
import com.netease.arctic.table.ChangeLocationKind;
import com.netease.arctic.table.KeyedTable;
import com.netease.arctic.table.LocationKind;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.UnkeyedTable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.CloseableIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class TableAvailableAnalyzer {

  private static final Pattern PATTERN = Pattern.compile("Metadata file for version ([0-9]+) is missing");

  private Map<String, Boolean> fileExistCache = new HashMap<>();

  private ArcticCatalog arcticCatalog;

  private TableIdentifier identifier;

  private ArcticFileIO io;

  private int maxFindSnapshotNum = 100;

  private int maxRollbackSnapNum = 10;

  public TableAvailableAnalyzer(ArcticCatalog arcticCatalog, TableIdentifier identifier, Integer maxFindSnapshotNum,
      Integer maxRollbackSnapNum) {
    this.arcticCatalog = arcticCatalog;
    this.identifier = identifier;
    if (maxFindSnapshotNum != null) {
      this.maxFindSnapshotNum = maxFindSnapshotNum;
    }
    if (maxRollbackSnapNum != null) {
      this.maxRollbackSnapNum = maxRollbackSnapNum;
    }
    this.io = arcticCatalog.getArcticIO();
  }

  public TableAvailableResult check() {
    ArcticTable arcticTable = null;
    try {
      arcticTable = arcticCatalog.loadTable(identifier);
    }catch (NoSuchTableException e) {
      // Now don't resolve this exception
      return TableAvailableResult.tableNotFound(identifier);
    } catch (ValidationException e) {
      Matcher matcher = PATTERN.matcher(e.getMessage());
      Integer version = null;
      while (matcher.find()) {
         version = Integer.parseInt(matcher.group(1));
      }

      //Not version exception
      if (version == null) {
        throw e;
      }

      TableAvailableResult tableAvailableResult = TableAvailableResult.metadataLose(identifier, version);

      ArcticHadoopTableOperations changeTableOperations = arcticCatalog.getChangeTableOperations(identifier);

      if (changeTableOperations != null) {
        boolean changeIsError = true;
        List<Path> metadataCandidateFiles = changeTableOperations.getMetadataCandidateFiles(version);
        for (Path path: metadataCandidateFiles) {
          if (exists(path.toString())) {
            changeIsError = false;
            break;
          }
        }

        if (changeIsError) {
          tableAvailableResult.setLocationKind(ChangeLocationKind.INSTANT);
          tableAvailableResult.setTableOperations(changeTableOperations);
          return tableAvailableResult;
        }
      }
      tableAvailableResult.setTableOperations(arcticCatalog.getBaseTableOperations(identifier));
      tableAvailableResult.setLocationKind(BaseLocationKind.INSTANT);
      return tableAvailableResult;
    }

    if (arcticTable.isKeyedTable()) {
      KeyedTable keyedTable = arcticTable.asKeyedTable();
      TableAvailableResult changeResult = check(keyedTable.changeTable());
      if(!changeResult.isOk()) {
        changeResult.setLocationKind(ChangeLocationKind.INSTANT);
        changeResult.setArcticTable(keyedTable.changeTable());
        return changeResult;
      }
      TableAvailableResult baseResult = check(keyedTable.baseTable());
      baseResult.setLocationKind(BaseLocationKind.INSTANT);
      baseResult.setArcticTable(keyedTable.baseTable());
      return baseResult;
    } else {
      TableAvailableResult result = check(arcticTable.asUnkeyedTable());
      return result;
    }
  }

  private TableAvailableResult check(UnkeyedTable table) {
    Snapshot currentSnapshot = table.currentSnapshot();
    TableAvailableResult tableAvailableResult = checkSnapshot(table, currentSnapshot);
    if (tableAvailableResult.isOk()) {
      return tableAvailableResult;
    }

    //find can roll back snapshot
    Iterable<Snapshot> remainSnapshot = Iterables.filter(table.snapshots(), s -> !s.equals(currentSnapshot));
    Iterable<Snapshot> maxFindSnapshot = Iterables.limit(remainSnapshot, maxFindSnapshotNum);
    Iterable<Snapshot> okSnapshot = Iterables.filter(maxFindSnapshot, s -> checkSnapshot(table, s).isOk());
    Iterable<Snapshot> finalOkSnapshot = Iterables.limit(okSnapshot, maxRollbackSnapNum);
    List<Snapshot> rollbackSnapshot = new ArrayList<>();
    Iterables.addAll(rollbackSnapshot, finalOkSnapshot);
    tableAvailableResult.setRollbackList(rollbackSnapshot);
    tableAvailableResult.setArcticTable(table);
    return tableAvailableResult;
  }

  @NotNull
  private TableAvailableResult checkSnapshot(UnkeyedTable table, Snapshot currentSnapshot) {

    //check manifestList
    if (!exists(currentSnapshot.manifestListLocation())) {
      return TableAvailableResult.manifestListLose(identifier, currentSnapshot);
    }

    //check manifest
    List<ManifestFile> manifestFiles = currentSnapshot.allManifests();

    List<ManifestFile> loseManifests =
        manifestFiles.stream().filter(s -> !exists(s.path())).collect(Collectors.toList());
    if (loseManifests.size() != 0) {
      return TableAvailableResult.manifestLost(identifier, loseManifests);
    }

    //check file
    TableEntriesScan tableEntriesScan = TableEntriesScan.builder(table)
        .withAliveEntry(true)
        .useSnapshot(currentSnapshot.snapshotId())
        .build();
    CloseableIterator<IcebergFileEntry> iterator = tableEntriesScan.entries().iterator();
    List<ContentFile> lostFile = new ArrayList<>();
    while (iterator.hasNext()) {
      IcebergFileEntry icebergFileEntry = iterator.next();
      if (!io.exists(icebergFileEntry.getFile().path().toString())) {
        lostFile.add(icebergFileEntry.getFile());
      }
    }
    if (lostFile.size() != 0) {
      return TableAvailableResult.filesLose(identifier, lostFile);
    }

    //table is available
    return TableAvailableResult.available(identifier);
  }

  private boolean exists(String path) {
    return fileExistCache.computeIfAbsent(path, p -> io.exists(path));
  }
}
