/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.segment.index;

import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.core.query.request.context.utils.QueryContextConverterUtils;
import org.apache.pinot.core.segment.creator.JsonIndexCreator;
import org.apache.pinot.core.segment.creator.impl.V1Constants;
import org.apache.pinot.core.segment.creator.impl.inv.json.OffHeapJsonIndexCreator;
import org.apache.pinot.core.segment.creator.impl.inv.json.OnHeapJsonIndexCreator;
import org.apache.pinot.core.segment.index.readers.JsonIndexReader;
import org.apache.pinot.core.segment.memory.PinotDataBuffer;
import org.apache.pinot.sql.parsers.CalciteSqlParser;
import org.roaringbitmap.buffer.MutableRoaringBitmap;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;


/**
 * Unit test for {@link JsonIndexCreator} and {@link JsonIndexReader}.
 */
public class JsonIndexTest {
  private static final File INDEX_DIR = new File(FileUtils.getTempDirectory(), "JsonIndexTest");

  @BeforeMethod
  public void setUp()
      throws IOException {
    FileUtils.forceMkdir(INDEX_DIR);
  }

  @AfterMethod
  public void tearDown()
      throws IOException {
    FileUtils.deleteDirectory(INDEX_DIR);
  }

  @Test
  public void testSmallIndex()
      throws Exception {
    //@formatter:off
    String[] records = new String[]{
        "{\"name\":\"adam\",\"age\":20,\"addresses\":[{\"street\":\"street-00\",\"country\":\"us\"},{\"street\":\"street-01\",\"country\":\"us\"},{\"street\":\"street-02\",\"country\":\"ca\"}],\"skills\":[\"english\",\"programming\"]}",
        "{\"name\":\"bob\",\"age\":25,\"addresses\":[{\"street\":\"street-10\",\"country\":\"ca\"},{\"street\":\"street-11\",\"country\":\"us\"},{\"street\":\"street-12\",\"country\":\"in\"}],\"skills\":[]}",
        "{\"name\":\"charles\",\"age\":30,\"addresses\":[{\"street\":\"street-20\",\"country\":\"jp\"},{\"street\":\"street-21\",\"country\":\"kr\"},{\"street\":\"street-22\",\"country\":\"cn\"}],\"skills\":[\"japanese\",\"korean\",\"chinese\"]}",
        "{\"name\":\"david\",\"age\":35,\"addresses\":[{\"street\":\"street-30\",\"country\":\"ca\",\"types\":[\"home\",\"office\"]},{\"street\":\"street-31\",\"country\":\"ca\"},{\"street\":\"street-32\",\"country\":\"ca\"}],\"skills\":null}"
    };
    //@formatter:on

    String onHeapColumnName = "onHeap";
    try (JsonIndexCreator onHeapIndexCreator = new OnHeapJsonIndexCreator(INDEX_DIR, onHeapColumnName)) {
      for (String record : records) {
        onHeapIndexCreator.add(record);
      }
      onHeapIndexCreator.seal();
    }
    File onHeapIndexFile = new File(INDEX_DIR, onHeapColumnName + V1Constants.Indexes.JSON_INDEX_FILE_EXTENSION);
    assertTrue(onHeapIndexFile.exists());

    String offHeapColumnName = "offHeap";
    try (JsonIndexCreator offHeapIndexCreator = new OffHeapJsonIndexCreator(INDEX_DIR, offHeapColumnName)) {
      for (String record : records) {
        offHeapIndexCreator.add(record);
      }
      offHeapIndexCreator.seal();
    }
    File offHeapIndexFile = new File(INDEX_DIR, offHeapColumnName + V1Constants.Indexes.JSON_INDEX_FILE_EXTENSION);
    assertTrue(offHeapIndexFile.exists());

    try (PinotDataBuffer onHeapDataBuffer = PinotDataBuffer.mapReadOnlyBigEndianFile(onHeapIndexFile);
        PinotDataBuffer offHeapDataBuffer = PinotDataBuffer.mapReadOnlyBigEndianFile(offHeapIndexFile);
        JsonIndexReader onHeapIndexReader = new JsonIndexReader(onHeapDataBuffer, records.length);
        JsonIndexReader offHeapIndexReader = new JsonIndexReader(offHeapDataBuffer, records.length)) {
      JsonIndexReader[] indexReaders = new JsonIndexReader[]{onHeapIndexReader, offHeapIndexReader};
      for (JsonIndexReader indexReader : indexReaders) {
        MutableRoaringBitmap matchingDocIds = getMatchingDocIds(indexReader, "name='bob'");
        assertEquals(matchingDocIds.toArray(), new int[]{1});

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.street = 'street-21'");
        assertEquals(matchingDocIds.toArray(), new int[]{2});

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.street NOT IN ('street-10', 'street-22')");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 3});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses[0].country\" IN ('ca', 'us')");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 1, 3});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses.types[1]\" = 'office'");
        assertEquals(matchingDocIds.toArray(), new int[]{3});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses[0].types[0]\" = 'home'");
        assertEquals(matchingDocIds.toArray(), new int[]{3});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses[1].types\" = 'home'");
        assertEquals(matchingDocIds.toArray(), new int[0]);

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.types IS NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 1, 2});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses[1].types\" IS NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 1, 2, 3});

        matchingDocIds = getMatchingDocIds(indexReader, "abc IS NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 1, 2, 3});

        matchingDocIds = getMatchingDocIds(indexReader, "skills IS NOT NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 2});

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.country = 'ca' AND skills IS NOT NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0});

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.country = 'us' OR skills IS NOT NULL");
        assertEquals(matchingDocIds.toArray(), new int[]{0, 1, 2});
      }
    }
  }

  @Test
  public void testLargeIndex()
      throws Exception {
    int numRecords = 123_456;
    String[] records = new String[numRecords];
    for (int i = 0; i < numRecords; i++) {
      records[i] = String.format(
          "{\"name\":\"adam-%d\",\"addresses\":[{\"street\":\"us-%d\",\"country\":\"us\"},{\"street\":\"ca-%d\",\"country\":\"ca\"}]}",
          i, i, i);
    }

    String onHeapColumnName = "onHeap";
    try (JsonIndexCreator onHeapIndexCreator = new OnHeapJsonIndexCreator(INDEX_DIR, onHeapColumnName)) {
      for (String record : records) {
        onHeapIndexCreator.add(record);
      }
      onHeapIndexCreator.seal();
    }
    File onHeapIndexFile = new File(INDEX_DIR, onHeapColumnName + V1Constants.Indexes.JSON_INDEX_FILE_EXTENSION);
    assertTrue(onHeapIndexFile.exists());

    String offHeapColumnName = "offHeap";
    try (JsonIndexCreator offHeapIndexCreator = new OffHeapJsonIndexCreator(INDEX_DIR, offHeapColumnName)) {
      for (String record : records) {
        offHeapIndexCreator.add(record);
      }
      offHeapIndexCreator.seal();
    }
    File offHeapIndexFile = new File(INDEX_DIR, offHeapColumnName + V1Constants.Indexes.JSON_INDEX_FILE_EXTENSION);
    assertTrue(offHeapIndexFile.exists());

    try (PinotDataBuffer onHeapDataBuffer = PinotDataBuffer.mapReadOnlyBigEndianFile(onHeapIndexFile);
        PinotDataBuffer offHeapDataBuffer = PinotDataBuffer.mapReadOnlyBigEndianFile(offHeapIndexFile);
        JsonIndexReader onHeapIndexReader = new JsonIndexReader(onHeapDataBuffer, records.length);
        JsonIndexReader offHeapIndexReader = new JsonIndexReader(offHeapDataBuffer, records.length)) {
      JsonIndexReader[] indexReaders = new JsonIndexReader[]{onHeapIndexReader, offHeapIndexReader};
      for (JsonIndexReader indexReader : indexReaders) {
        MutableRoaringBitmap matchingDocIds = getMatchingDocIds(indexReader, "name = 'adam-123'");
        assertEquals(matchingDocIds.toArray(), new int[]{123});

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.street = 'us-456'");
        assertEquals(matchingDocIds.toArray(), new int[]{456});

        matchingDocIds = getMatchingDocIds(indexReader, "\"addresses[1].street\" = 'us-456'");
        assertEquals(matchingDocIds.toArray(), new int[0]);

        matchingDocIds = getMatchingDocIds(indexReader, "addresses.street = 'us-456' AND addresses.country = 'ca'");
        assertEquals(matchingDocIds.toArray(), new int[0]);

        matchingDocIds = getMatchingDocIds(indexReader, "name = 'adam-100000' AND addresses.street = 'us-100000' AND addresses.country = 'us'");
        assertEquals(matchingDocIds.toArray(), new int[]{100000});

        matchingDocIds = getMatchingDocIds(indexReader, "name = 'adam-100000' AND addresses.street = 'us-100001'");
        assertEquals(matchingDocIds.toArray(), new int[0]);

        matchingDocIds = getMatchingDocIds(indexReader, "name != 'adam-100000'");
        assertEquals(matchingDocIds.getCardinality(), 123_455);
      }
    }
  }

  private MutableRoaringBitmap getMatchingDocIds(JsonIndexReader indexReader, String filter)
      throws Exception {
    return indexReader
        .getMatchingDocIds(QueryContextConverterUtils.getFilter(CalciteSqlParser.compileToExpression(filter)));
  }
}
