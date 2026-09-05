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
import java.util.concurrent.TimeUnit;

/**
 * Force-merge time of a TSDB segment as its shape is varied, isolating the dimension ordinal merge. The run-table
 * ordinal codec stores a per-series-constant dimension as one entry per run, so a run-granularity merge processes
 * {@code O(runs) = O(series)} entries regardless of docs per series, while the per-doc re-encode processes
 * {@code O(docs)}. Run it on the branch before the run-granularity merge and on the branch with it, both on the ES95
 * run-table codec, and compare.
 *
 * <p>Every knob is a parameter, but sweep ONE at a time via {@code -p} overrides (JMH runs the cartesian product of
 * all parameter value lists, so listing several sweeps at once explodes the run matrix). Around a fixed operating
 * point (for example {@code numSeries=2000 numSegments=16 numDimensions=10 numMetrics=5 docsPerSeries=256}):
 * <ul>
 *   <li>docs-per-series sweep: the scaling curve (before grows with docs, after bounded by series);</li>
 *   <li>segment-count sweep: the k-way run merge cost as fragmentation rises;</li>
 *   <li>metric-count sweep: the dilution of the whole-merge speedup toward the dimension fraction (metrics and
 *       {@code @timestamp} merge per-doc and are unchanged);</li>
 *   <li>dimension-count sweep: how the saving scales with the number of run-table dimensions.</li>
 * </ul>
 * Dimensions are generated as {@code dim_0..dim_{n-1}}: even-indexed are per-series-distinct (high cardinality,
 * runs == series), odd-indexed are low cardinality; plus one multi-valued {@code host.mac}. {@code host.name} is the
 * primary sort key and merges per-doc.
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
    private static final String MAC_FIELD = "host.mac";
    private static final String DIM_PREFIX = "dim_";
    private static final long BASE_TIMESTAMP = 1704067200000L;

    @State(Scope.Benchmark)
    public static class MergeState {

        @Param({ "1", "4", "16", "64", "256", "1024" })
        private int docsPerSeries;

        @Param("2000")
        private int numSeries;

        // Source segments merged into one (controls commit frequency during the untimed build).
        @Param("16")
        private int numSegments;

        // Run-table Sorted dimension fields, plus one multi-valued host.mac. Even-indexed are per-series-distinct.
        @Param("10")
        private int numDimensions;

        // Per-doc numeric metric fields (not run-table). 0 isolates the dimension-merge signal; larger values
        // dilute the whole-merge speedup toward the dimension fraction, modeling a realistic metrics doc.
        @Param("0")
        private int numMetrics;

        private Directory directory;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            directory = FSDirectory.open(Files.createTempDirectory("runtable-merge-"));
            createIndex(directory, numSeries, docsPerSeries, numSegments, numDimensions, numMetrics);
        }
    }

    @Benchmark
    public void forceMerge(MergeState state) throws IOException {
        try (IndexWriter indexWriter = new IndexWriter(state.directory, indexWriterConfig(state.numDimensions))) {
            indexWriter.forceMerge(1);
        }
    }

    // Even-indexed dimensions are per-series-distinct (runs == series); odd-indexed are low cardinality.
    private static int dimCardinality(int dimension, int numSeries) {
        return (dimension % 2 == 0) ? numSeries : (2 + (dimension / 2) % 30);
    }

    private static void createIndex(
        final Directory directory,
        int numSeries,
        int docsPerSeries,
        int numSegments,
        int numDimensions,
        int numMetrics
    ) throws IOException {
        final int commitInterval = Math.max(1, numSeries * docsPerSeries / numSegments);
        try (IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig(numDimensions))) {
            int docCount = 0;
            for (int series = 0; series < numSeries; series++) {
                for (int within = 0; within < docsPerSeries; within++) {
                    final Document doc = new Document();
                    doc.add(new SortedDocValuesField(HOSTNAME_FIELD, new BytesRef("host-" + series)));
                    // Dimensions, constant per series so they form runs under the (host.name, @timestamp desc) sort.
                    for (int dimension = 0; dimension < numDimensions; dimension++) {
                        final int value = series % dimCardinality(dimension, numSeries);
                        doc.add(new SortedDocValuesField(DIM_PREFIX + dimension, new BytesRef("v" + value)));
                    }
                    doc.add(new SortedSetDocValuesField(MAC_FIELD, new BytesRef("mac-" + (series % 4))));
                    doc.add(new SortedSetDocValuesField(MAC_FIELD, new BytesRef("mac-" + ((series % 4) + 4))));
                    // Per-doc numeric metrics: distinct per doc so they do not run-length compress (per-doc merge).
                    for (int metric = 0; metric < numMetrics; metric++) {
                        doc.add(new SortedNumericDocValuesField("metric_" + metric, ((long) series << 20) + within + metric));
                    }
                    doc.add(new SortedNumericDocValuesField(TIMESTAMP_FIELD, BASE_TIMESTAMP + within));
                    indexWriter.addDocument(doc);
                    if (++docCount % commitInterval == 0) {
                        indexWriter.commit();
                    }
                }
            }
        }
    }

    private static IndexWriterConfig indexWriterConfig(int numDimensions) {
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
            fieldName.startsWith(DIM_PREFIX) || fieldName.equals(MAC_FIELD)
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
