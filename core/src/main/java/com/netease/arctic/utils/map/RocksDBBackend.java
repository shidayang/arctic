/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.utils.map;

import com.netease.arctic.ArcticIOException;
import com.netease.arctic.utils.LocalFileUtils;
import org.apache.commons.lang.Validate;
import org.rocksdb.AbstractImmutableNativeReference;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.InfoLogLevel;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class RocksDBBackend<K, T> {
  private static final Logger LOG = LoggerFactory.getLogger(RocksDBBackend.class);
  private static final String BACKEND_BASE_DIR = System.getProperty("rocksdb.dir");
  private static final ThreadLocal<RocksDBBackend> instance =
          new ThreadLocal<>();

  public static RocksDBBackend getOrCreateInstance() {
    RocksDBBackend backend = instance.get();
    if (backend == null) {
      backend = create();
    }
    if (backend.closed) {
      backend = create();
      instance.set(backend);
    }
    return backend;
  }

  public static <K, T> RocksDBBackend<K, T> getOrCreateInstance(Serializer<K> keySerializer,
      Serializer<T> valueSerializer) {
    RocksDBBackend backend = instance.get();
    if (backend == null) {
      backend = create(keySerializer, valueSerializer);
    }
    if (backend.closed) {
      backend = create(keySerializer, valueSerializer);
      instance.set(backend);
    }
    return backend;
  }

  private Map<String, ColumnFamilyHandle> handlesMap = new HashMap<>();
  private Map<String, ColumnFamilyDescriptor> descriptorMap = new HashMap<>();
  private RocksDB rocksDB;
  private boolean closed = false;
  private final String rocksDBBasePath;
  private long totalBytesWritten;
  private Serializer<K> keySerializer;
  private Serializer<T> valueSerializer;

  private static RocksDBBackend create() {
    return new RocksDBBackend();
  }

  private static <K, T> RocksDBBackend create(Serializer<K> keySerializer, Serializer<T> valueSerializer) {
    return new RocksDBBackend(keySerializer, valueSerializer);
  }

  private RocksDBBackend() {
    this(JavaSerializer.INSTANT, JavaSerializer.INSTANT);
  }

  private RocksDBBackend(Serializer<K> keySerializer, Serializer<T> valueSerializer) {
    this.rocksDBBasePath = BACKEND_BASE_DIR == null ? UUID.randomUUID().toString() :
        String.format("%s/%s", BACKEND_BASE_DIR, UUID.randomUUID().toString());
    totalBytesWritten = 0L;
    setup();
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
  }

  /**
   * Initialized Rocks DB instance.
   */
  private void setup() {
    try {
      LOG.info("DELETING RocksDB instance persisted at " + rocksDBBasePath);
      LocalFileUtils.deleteDirectory(new File(rocksDBBasePath));

      final DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
              .setWalDir(rocksDBBasePath).setStatsDumpPeriodSec(300).setStatistics(new Statistics());
      dbOptions.setLogger(new org.rocksdb.Logger(dbOptions) {
        @Override
        protected void log(InfoLogLevel infoLogLevel, String logMsg) {
          LOG.info("From Rocks DB : " + logMsg);
        }
      });
      final List<ColumnFamilyDescriptor> managedColumnFamilies = loadManagedColumnFamilies(dbOptions);
      final List<ColumnFamilyHandle> managedHandles = new ArrayList<>();
      LocalFileUtils.mkdir(new File(rocksDBBasePath));
      rocksDB = RocksDB.open(dbOptions, rocksDBBasePath, managedColumnFamilies, managedHandles);

      Validate.isTrue(managedHandles.size() == managedColumnFamilies.size(),
              "Unexpected number of handles are returned");
      for (int index = 0; index < managedHandles.size(); index++) {
        ColumnFamilyHandle handle = managedHandles.get(index);
        ColumnFamilyDescriptor descriptor = managedColumnFamilies.get(index);
        String familyNameFromHandle = new String(handle.getName());
        String familyNameFromDescriptor = new String(descriptor.getName());

        Validate.isTrue(familyNameFromDescriptor.equals(familyNameFromHandle),
                "Family Handles not in order with descriptors");
        handlesMap.put(familyNameFromHandle, handle);
        descriptorMap.put(familyNameFromDescriptor, descriptor);
      }
      addShutDownHook();
    } catch (RocksDBException | IOException re) {
      LOG.error("Got exception opening Rocks DB instance ", re);
      if (rocksDB != null) {
        close();
      }
      throw new ArcticIOException(re);
    }
  }

  private void addShutDownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::close));
  }

  /**
   * Helper to load managed column family descriptors.
   */
  private List<ColumnFamilyDescriptor> loadManagedColumnFamilies(DBOptions dbOptions) throws RocksDBException {
    final List<ColumnFamilyDescriptor> managedColumnFamilies = new ArrayList<>();
    final Options options = new Options(dbOptions, new ColumnFamilyOptions());
    List<byte[]> existing = RocksDB.listColumnFamilies(options, rocksDBBasePath);

    if (existing.isEmpty()) {
      LOG.info("No column family found. Loading default");
      managedColumnFamilies.add(getColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
    } else {
      LOG.info("Loading column families :" + existing.stream().map(String::new).collect(Collectors.toList()));
      managedColumnFamilies
              .addAll(existing.stream().map(this::getColumnFamilyDescriptor).collect(Collectors.toList()));
    }
    return managedColumnFamilies;
  }

  private ColumnFamilyDescriptor getColumnFamilyDescriptor(byte[] columnFamilyName) {
    return new ColumnFamilyDescriptor(columnFamilyName, new ColumnFamilyOptions());
  }

  /**
   * Perform single PUT on a column-family.
   *
   * @param columnFamilyName Column family name
   * @param key Key
   * @param value Payload
   */
  public void put(String columnFamilyName, K key, T value) {
    try {
      Validate.isTrue(key != null && value != null,
              "values or keys in rocksdb can not be null!");
      byte[] payload = serializePayload(value);
      rocksDB.put(handlesMap.get(columnFamilyName), keySerializer.serialize(key), payload);
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Perform single PUT on a column-family.
   *
   * @param columnFamilyName Column family name
   * @param key Key
   * @param value Payload
   */
  public void put(String columnFamilyName, byte[] key, byte[] value) {
    try {
      Validate.isTrue(key != null && value != null,
          "values or keys in rocksdb can not be null!");
      ColumnFamilyHandle cfHandler = handlesMap.get(columnFamilyName);
      Validate.isTrue(cfHandler != null, "column family " +
          columnFamilyName + " does not exists in rocksdb");
      rocksDB.put(cfHandler, key, payload(value));
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Perform a single Delete operation.
   *
   * @param columnFamilyName Column Family name
   * @param key Key to be deleted
   */
  public void delete(String columnFamilyName, K key) {
    try {
      Validate.isTrue(key != null, "keys in rocksdb can not be null!");
      rocksDB.delete(handlesMap.get(columnFamilyName), keySerializer.serialize(key));
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Perform a single Delete operation.
   *
   * @param columnFamilyName Column Family name
   * @param key Key to be deleted
   */
  public void delete(String columnFamilyName, byte[] key) {
    try {
      Validate.isTrue(key != null, "keys in rocksdb can not be null!");
      rocksDB.delete(handlesMap.get(columnFamilyName), key);
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Retrieve a value for a given key in a column family.
   *
   * @param columnFamilyName Column Family Name
   * @param key Key to be retrieved
   */
  public T get(String columnFamilyName, K key) {
    Validate.isTrue(!closed);
    try {
      Validate.isTrue(key != null, "keys in rocksdb can not be null!");
      byte[] val = rocksDB.get(handlesMap.get(columnFamilyName), keySerializer.serialize(key));
      return val == null ? null : valueSerializer.deserialize(val);
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Retrieve a value for a given key in a column family.
   *
   * @param columnFamilyName Column Family Name
   * @param key Key to be retrieved
   */
  public byte[] get(String columnFamilyName, byte[] key) {
    Validate.isTrue(!closed);
    try {
      Validate.isTrue(key != null, "keys in rocksdb can not be null!");
      byte[] val = rocksDB.get(handlesMap.get(columnFamilyName), key);
      return val;
    } catch (Exception e) {
      throw new ArcticIOException(e);
    }
  }

  /**
   * Return Iterator of key-value pairs from RocksIterator.
   *
   * @param columnFamilyName Column Family Name
   */
  public Iterator<T> iterator(String columnFamilyName) {
    return new IteratorWrapper<>(rocksDB.newIterator(handlesMap.get(columnFamilyName)), valueSerializer);
  }

  /**
   * Add a new column family to store.
   *
   * @param columnFamilyName Column family name
   */
  public void addColumnFamily(String columnFamilyName) {
    Validate.isTrue(!closed);

    descriptorMap.computeIfAbsent(columnFamilyName, colFamilyName -> {
      try {
        ColumnFamilyDescriptor descriptor = getColumnFamilyDescriptor(colFamilyName.getBytes());
        ColumnFamilyHandle handle = rocksDB.createColumnFamily(descriptor);
        handlesMap.put(colFamilyName, handle);
        return descriptor;
      } catch (RocksDBException e) {
        throw new ArcticIOException(e);
      }
    });
  }

  /**
   * Note : Does not delete from underlying DB. Just closes the handle.
   *
   * @param columnFamilyName Column Family Name
   */
  public void dropColumnFamily(String columnFamilyName) {
    Validate.isTrue(!closed);

    descriptorMap.computeIfPresent(columnFamilyName, (colFamilyName, descriptor) -> {
      ColumnFamilyHandle handle = handlesMap.get(colFamilyName);
      try {
        rocksDB.dropColumnFamily(handle);
        handle.close();
      } catch (RocksDBException e) {
        throw new ArcticIOException(e);
      }
      handlesMap.remove(columnFamilyName);
      return null;
    });
  }

  public List<ColumnFamilyDescriptor> listColumnFamilies() {
    return new ArrayList<>(descriptorMap.values());
  }

  /**
   * Close the DAO object.
   */
  public void close() {
    if (!closed) {
      closed = true;
      handlesMap.values().forEach(AbstractImmutableNativeReference::close);
      handlesMap.clear();
      descriptorMap.clear();
      rocksDB.close();
      try {
        LocalFileUtils.deleteDirectory(new File(rocksDBBasePath));
      } catch (IOException e) {
        throw new ArcticIOException(e.getMessage(), e);
      }
    }
  }

  public String getRocksDBBasePath() {
    return rocksDBBasePath;
  }

  public long getTotalBytesWritten() {
    return totalBytesWritten;
  }

  private byte[] serializePayload(T value) throws IOException {
    byte[] payload = valueSerializer.serialize(value);
    totalBytesWritten += payload.length;
    return payload;
  }

  private byte[] payload(byte[] value) {
    totalBytesWritten += value.length;
    return value;
  }

  /**
   * {@link Iterator} wrapper for RocksDb Iterator {@link RocksIterator}.
   */
  private static class IteratorWrapper<R> implements Iterator<R> {

    private final RocksIterator iterator;

    private final Serializer<R> serializer;

    public IteratorWrapper(final RocksIterator iterator, Serializer<R> serializer) {
      this.iterator = iterator;
      iterator.seekToFirst();
      this.serializer = serializer;
    }

    @Override
    public boolean hasNext() {
      return iterator.isValid();
    }

    @Override
    public R next() {
      if (!hasNext()) {
        throw new IllegalStateException("next() called on rocksDB with no more valid entries");
      }
      R val = serializer.deserialize(iterator.value());
      iterator.next();
      return val;
    }
  }
}
