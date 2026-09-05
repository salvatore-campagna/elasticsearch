/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.tsdb;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.index.codec.Elasticsearch93Lucene104Codec;
import org.elasticsearch.index.codec.tsdb.es95.ES95TSDBDocValuesFormatFactory;
import org.elasticsearch.index.codec.tsdb.pipeline.FieldContext;
import org.elasticsearch.index.codec.tsdb.pipeline.FieldContextResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Force-merge time of TSDB dimension fields as the number of docs per series grows while the series (distinct
 * {@code _tsid}) count is held constant. The run-table ordinal codec stores a per-series-constant dimension as one
 * entry per run, so a run-granularity merge processes {@code O(runs) = O(series)} entries regardless of docs per
 * series; the per-doc re-encode merge processes {@code O(docs)}.
 *
 * <p>The benchmark holds the series count fixed and sweeps {@code docsPerSeries}, so total docs grow linearly.
 * Run it on the branch before the run-granularity merge (per-doc re-encode) and on the branch with it, both using
 * the ES95 run-table codec: the before curve grows with docs, the after curve stays roughly flat, bounded by the
 * series count. The segment carries only the dimension fields plus the {@code _tsid} sort key so the merge signal
 * is the dimension ordinal merge rather than metric or timestamp encoding.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(3)
@Threads(1)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class TSDBDocValuesMergeBenchmark {

    static {
        BenchmarkLogging.configure();
    }

    private static final String TIMESTAMP_FIELD = "@timestamp";
    private static final String HOSTNAME_FIELD = "host.name";
    private static final String HOST_IP_FIELD = "host.ip";
    private static final String STATE_FIELD = "state";
    private static final String OS_TYPE_FIELD = "os_type";
    private static final String DIRECTION_FIELD = "direction";
    private static final String MAC_FIELD = "host.mac";

    private static final Set<String> DIMENSION_FIELDS = Set.of(
        HOSTNAME_FIELD,
        HOST_IP_FIELD,
        STATE_FIELD,
        OS_TYPE_FIELD,
        DIRECTION_FIELD,
        MAC_FIELD
    );

    private static final long BASE_TIMESTAMP = 1704067200000L;
    private static final String[] STATES = { "active", "idle", "wait" };
    private static final String[] OS_TYPES = { "linux", "macos", "windows" };
    private static final String[] DIRECTIONS = { "rx", "tx" };

    @State(Scope.Benchmark)
    public static class MergeState {

        @Param({ "1", "4", "16", "64", "256", "1024" })
        private int docsPerSeries;

        @Param("2000")
        private int numSeries;

        // Number of source segments merged into one. Sweep this (at fixed docsPerSeries) to measure the k-way run
        // merge cost as fragmentation rises; keep it fixed while sweeping docsPerSeries for the scaling curve.
        @Param("16")
        private int numSegments;

        private Directory directory;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            directory = FSDirectory.open(Files.createTempDirectory("runtable-merge-"));
            createIndex(directory, numSeries, docsPerSeries, numSegments);
        }
    }

    @Benchmark
    public void forceMerge(MergeState state) throws IOException {
        try (IndexWriter indexWriter = new IndexWriter(state.directory, indexWriterConfig())) {
            indexWriter.forceMerge(1);
        }
    }

    private static void createIndex(final Directory directory, int numSeries, int docsPerSeries, int numSegments) throws IOException {
        final int commitInterval = Math.max(1, numSeries * docsPerSeries / numSegments);
        try (IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig())) {
            int docCount = 0;
            for (int series = 0; series < numSeries; series++) {
                for (int within = 0; within < docsPerSeries; within++) {
                    final Document doc = new Document();
                    doc.add(new SortedDocValuesField(HOSTNAME_FIELD, new BytesRef("host-" + series)));
                    doc.add(new SortedDocValuesField(HOST_IP_FIELD, new BytesRef(ipOf(series))));
                    doc.add(new SortedDocValuesField(STATE_FIELD, new BytesRef(STATES[series % STATES.length])));
                    doc.add(new SortedDocValuesField(OS_TYPE_FIELD, new BytesRef(OS_TYPES[series % OS_TYPES.length])));
                    doc.add(new SortedDocValuesField(DIRECTION_FIELD, new BytesRef(DIRECTIONS[series % DIRECTIONS.length])));
                    doc.add(new SortedSetDocValuesField(MAC_FIELD, new BytesRef("mac-" + (series % 4))));
                    doc.add(new SortedSetDocValuesField(MAC_FIELD, new BytesRef("mac-" + ((series % 4) + 4))));
                    doc.add(new SortedNumericDocValuesField(TIMESTAMP_FIELD, BASE_TIMESTAMP + within));
                    indexWriter.addDocument(doc);
                    if (++docCount % commitInterval == 0) {
                        indexWriter.commit();
                    }
                }
            }
        }
    }

    private static String ipOf(int series) {
        return "10." + (series >> 16 & 0xFF) + "." + (series >> 8 & 0xFF) + "." + (series & 0xFF);
    }

    private static IndexWriterConfig indexWriterConfig() {
        final IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setIndexSort(
            new Sort(
                new SortField(HOSTNAME_FIELD, SortField.Type.STRING, false),
                new SortedNumericSortField(TIMESTAMP_FIELD, SortField.Type.LONG, true)
            )
        );
        config.setLeafSorter(DataStream.TIMESERIES_LEAF_READERS_SORTER);
        config.setMergePolicy(new LogByteSizeMergePolicy());
        final FieldContextResolver dimensionResolver = (fieldName, blockSize) -> new FieldContext(
            blockSize,
            fieldName,
            null,
            null,
            DIMENSION_FIELDS.contains(fieldName)
        );
        final DocValuesFormat docValuesFormat = ES95TSDBDocValuesFormatFactory.create(true, true, false, dimensionResolver, true);
        // The ES codec uses XPerFieldDocValuesFormat, which the optimized (run-granularity) merge path requires;
        // a plain Lucene codec routes merge through the per-doc path regardless of branch.
        config.setCodec(new Elasticsearch93Lucene104Codec() {
            @Override
            public DocValuesFormat getDocValuesFormatForField(String field) {
                return docValuesFormat;
            }
        });
        return config;
    }
}
