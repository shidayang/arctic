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

package com.netease.arctic.op;

import com.netease.arctic.table.ArcticTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.expressions.Expression;
import java.util.function.Supplier;


public class ArcticRowDelta extends ArcticUpdate<RowDelta> implements RowDelta {

  private final RowDelta rowDelta;

  public static ArcticRowDelta.Builder buildFor(ArcticTable table) {
    return new ArcticRowDelta.Builder(table);
  }

  private ArcticRowDelta(ArcticTable arcticTable, RowDelta rowDelta) {
    super(arcticTable, rowDelta);
    this.rowDelta = rowDelta;
  }

  private ArcticRowDelta(ArcticTable arcticTable, RowDelta rowDelta,
      Transaction transaction, boolean autoCommitTransaction) {
    super(arcticTable, rowDelta, transaction, autoCommitTransaction);
    this.rowDelta = rowDelta;
  }

  @Override
  public RowDelta addRows(DataFile inserts) {
    rowDelta.addRows(inserts);
    addIcebergDataFile(inserts);
    return this;
  }

  @Override
  public RowDelta addDeletes(DeleteFile deletes) {
    rowDelta.addDeletes(deletes);
    addIcebergDeleteFile(deletes);
    return this;
  }

  @Override
  public RowDelta validateFromSnapshot(long snapshotId) {
    rowDelta.validateFromSnapshot(snapshotId);
    return this;
  }

  @Override
  public RowDelta caseSensitive(boolean caseSensitive) {
    rowDelta.caseSensitive(caseSensitive);
    return this;
  }

  @Override
  public RowDelta validateDataFilesExist(Iterable<? extends CharSequence> referencedFiles) {
    rowDelta.validateDataFilesExist(referencedFiles);
    return this;
  }

  @Override
  public RowDelta validateDeletedFiles() {
    rowDelta.validateDeletedFiles();
    return this;
  }

  @Override
  public RowDelta conflictDetectionFilter(Expression conflictDetectionFilter) {
    rowDelta.conflictDetectionFilter(conflictDetectionFilter);
    return this;
  }

  @Override
  public RowDelta validateNoConflictingDataFiles() {
    rowDelta.validateNoConflictingDataFiles();
    return this;
  }

  @Override
  public RowDelta validateNoConflictingDeleteFiles() {
    rowDelta.validateNoConflictingDeleteFiles();
    return this;
  }

  @Override
  protected RowDelta self() {
    return this;
  }

  public static class Builder extends ArcticUpdate.Builder<ArcticRowDelta, RowDelta> {

    private Builder(ArcticTable table) {
      super(table);
      generateWatermark();
    }


    @Override
    protected ArcticRowDelta updateWithWatermark(Transaction transaction, boolean autoCommitTransaction) {
      return new ArcticRowDelta(table, transaction.newRowDelta(), transaction, autoCommitTransaction);
    }

    @Override
    protected ArcticRowDelta updateWithoutWatermark(Supplier<RowDelta> delegateSupplier) {
      return new ArcticRowDelta(table, delegateSupplier.get());
    }

    @Override
    protected Supplier<RowDelta> transactionDelegateSupplier(Transaction transaction) {
      return transaction::newRowDelta;
    }

    @Override
    protected Supplier<RowDelta> tableStoreDelegateSupplier(Table tableStore) {
      return tableStore::newRowDelta;
    }
  }
}
