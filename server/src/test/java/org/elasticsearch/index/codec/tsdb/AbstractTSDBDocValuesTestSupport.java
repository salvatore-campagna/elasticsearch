/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.tests.index.BaseDocValuesFormatTestCase;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.codec.tsdb.AbstractTSDBDocValuesProducer.BaseDenseNumericValues;
import org.elasticsearch.index.codec.tsdb.AbstractTSDBDocValuesProducer.BaseSortedDocValues;
import org.elasticsearch.index.codec.tsdb.AbstractTSDBDocValuesProducer.TSDBBinaryDocValues;

import java.io.IOException;

/**
 * Shared base for TSDB doc values format test cases. Provides common constants,
 * TSDB index writer configuration helpers, and static utilities used across all
 * codec test suites. Not intended to be subclassed directly; use
 * {@link AbstractTSDBDocValuesFormatTests} instead, which adds the full shared
 * test suite.
 */
public abstract class AbstractTSDBDocValuesTestSupport extends BaseDocValuesFormatTestCase {

    protected static final String TIMESTAMP_FIELD = "@timestamp";
    protected static final String HOSTNAME_FIELD = "host.name";
    protected static final long BASE_TIMESTAMP = 1704067200000L;

    protected static final int NUMERIC_BLOCK_SHIFT = 7;
    protected static final int NUMERIC_LARGE_BLOCK_SHIFT = 9;
    protected static final int BINARY_DV_BLOCK_BYTES_THRESHOLD_DEFAULT = 128 * 1024;
    protected static final int BINARY_DV_BLOCK_COUNT_THRESHOLD_DEFAULT = 1024;

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
    }

    protected IndexWriterConfig getTimeSeriesIndexWriterConfig(String hostnameField, String timestampField) {
        return getTimeSeriesIndexWriterConfig(hostnameField, false, timestampField);
    }

    protected IndexWriterConfig getTimeSeriesIndexWriterConfig(String hostnameField, boolean multiValued, String timestampField) {
        var config = new IndexWriterConfig();
        if (hostnameField != null) {
            config.setIndexSort(
                new Sort(
                    multiValued ? new SortedSetSortField(hostnameField, false) : new SortField(hostnameField, SortField.Type.STRING, false),
                    new SortedNumericSortField(timestampField, SortField.Type.LONG, true)
                )
            );
        } else {
            config.setIndexSort(new Sort(new SortedNumericSortField(timestampField, SortField.Type.LONG, true)));
        }
        config.setLeafSorter(DataStream.TIMESERIES_LEAF_READERS_SORTER);
        config.setMergePolicy(new LogByteSizeMergePolicy());
        config.setCodec(getCodec());
        return config;
    }

    public static BinaryDVCompressionMode randomBinaryCompressionMode() {
        BinaryDVCompressionMode[] modes = BinaryDVCompressionMode.values();
        return modes[random().nextInt(modes.length)];
    }

    public static int randomNumericBlockSize() {
        return random().nextBoolean() ? NUMERIC_LARGE_BLOCK_SHIFT : NUMERIC_BLOCK_SHIFT;
    }

    protected static TSDBBinaryDocValues getTSDBBinaryValues(LeafReader leafReader, String field) throws IOException {
        return (TSDBBinaryDocValues) leafReader.getBinaryDocValues(field);
    }

    protected static BaseDenseNumericValues getBaseDenseNumericValues(LeafReader leafReader, String field) throws IOException {
        return (BaseDenseNumericValues) DocValues.unwrapSingleton(leafReader.getSortedNumericDocValues(field));
    }

    protected static BaseSortedDocValues getBaseSortedDocValues(LeafReader leafReader, String field) throws IOException {
        var sortedDocValues = leafReader.getSortedDocValues(field);
        if (sortedDocValues == null) {
            sortedDocValues = DocValues.unwrapSingleton(leafReader.getSortedSetDocValues(field));
        }
        return (BaseSortedDocValues) sortedDocValues;
    }
}
