/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.columnar;

import org.apache.lucene.codecs.perfield.PerFieldDocValuesFormat;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.columnar.string.ColumnarStringBinaryDocValues;
import org.elasticsearch.columnar.string.StringBinaryPayload;
import org.elasticsearch.columnar.string.StringColumnReader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads what a closed Lucene index holds for its binary doc values fields, without Elasticsearch: the segments, the bytes of each
 * file kind, and for every ColumNAR string column its layout, dictionary, escapes, summary and the distribution and physical order
 * of its stored values. The doc values format of every field comes from the field info the writer recorded, so an index written
 * by another format is identified, not guessed.
 *
 * <pre>{@code ./gradlew -p benchmarks inspectColumnar --args="<index directory> <output directory>"}</pre>
 *
 * <p>Writes {@code segments.csv} (bytes per segment, scope and file extension, where an inner row is a file inside a compound file) and {@code fields.csv} (one row per segment and field).
 * The index directory must be stable: a closed index, or a copy taken while the node is stopped.
 */
public final class ColumnarIndexInspector {

    private static final int TOP_TERMS = 16;
    private static final long DICTIONARY_BUDGET_BYTES = 512 * 1024;
    private static final double MIN_COVERAGE = 0.9;
    private static final int MAX_DISTINCT = 2_000_000;
    private static final double MAX_SHARE_OF_COLUMN = 0.2;
    private static final int FIELD_COLUMNS = 34;

    private static final String SEGMENT_HEADER = "segment,max_doc,live_docs,codec,compound,scope,extension,files,bytes\n";
    private static final String FIELD_HEADER = "segment,field,dv_format,layout,values,docs_with_field,value_bytes,dictionary_terms,"
        + "escapes,has_summary,summary_values,summary_terms,summary_term_bytes,best_coverage_share,scanned_values,null_slots,runs,"
        + "ascending_steps,descending_steps,distinct,distinct_capped,top1_share,top16_share,singleton_share,mean_bytes,"
        + "exact_best_coverage_share,exact_term_bytes_for_min_coverage,value_multiset_hash,value_sequence_hash,dictionary_hash,"
        + "dictionary_set_hash,dictionary_term_bytes,policy_cap_bytes,exact_best_coverage_policy_cap\n";

    private ColumnarIndexInspector() {}

    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: inspectColumnar --args=\"<index directory> <output directory>\"");
        }
        final Path output = Files.createDirectories(Path.of(args[1]));
        try (
            Directory directory = new MMapDirectory(Path.of(args[0]));
            DirectoryReader reader = DirectoryReader.open(directory);
            BufferedWriter segments = Files.newBufferedWriter(output.resolve("segments.csv"), StandardCharsets.UTF_8);
            BufferedWriter fields = Files.newBufferedWriter(output.resolve("fields.csv"), StandardCharsets.UTF_8)
        ) {
            segments.write(SEGMENT_HEADER);
            fields.write(FIELD_HEADER);
            for (LeafReaderContext context : reader.leaves()) {
                final SegmentReader segment = (SegmentReader) FilterLeafReader.unwrap(context.reader());
                writeSegment(segment, segments);
                for (FieldInfo info : segment.getFieldInfos()) {
                    if (info.getDocValuesType() == DocValuesType.BINARY) {
                        writeField(segment, info, fields);
                    }
                }
            }
        }
    }

    private static void writeSegment(final SegmentReader segment, final BufferedWriter out) throws IOException {
        final SegmentCommitInfo commit = segment.getSegmentInfo();
        final String name = segment.getSegmentName();
        writeFiles(segment, name, "outer", segment.directory(), out);
        if (commit.info.getUseCompoundFile()) {
            try (Directory compound = commit.info.getCodec().compoundFormat().getCompoundReader(commit.info.dir, commit.info)) {
                writeFiles(segment, name, "inner", compound, out);
            }
        }
    }

    private static void writeFiles(
        final SegmentReader segment,
        final String name,
        final String scope,
        final Directory directory,
        final BufferedWriter out
    ) throws IOException {
        final Map<String, long[]> bytesByExtension = new TreeMap<>();
        for (String file : directory.listAll()) {
            if (file.startsWith(name) == false) {
                continue;
            }
            final String extension = IndexFileNames.getExtension(file);
            final long[] totals = bytesByExtension.computeIfAbsent(extension == null ? "" : extension, key -> new long[2]);
            totals[0]++;
            totals[1] += directory.fileLength(file);
        }
        for (Map.Entry<String, long[]> entry : bytesByExtension.entrySet()) {
            out.write(
                String.join(
                    ",",
                    name,
                    Integer.toString(segment.maxDoc()),
                    Integer.toString(segment.numDocs()),
                    segment.getSegmentInfo().info.getCodec().getName(),
                    Boolean.toString(segment.getSegmentInfo().info.getUseCompoundFile()),
                    scope,
                    entry.getKey(),
                    Long.toString(entry.getValue()[0]),
                    Long.toString(entry.getValue()[1])
                )
            );
            out.write('\n');
        }
    }

    private static void writeField(final SegmentReader segment, final FieldInfo info, final BufferedWriter out) throws IOException {
        final String format = info.getAttribute(PerFieldDocValuesFormat.PER_FIELD_FORMAT_KEY);
        final BinaryDocValues docValues = segment.getBinaryDocValues(info.name);
        final List<String> row = new ArrayList<>();
        row.add(segment.getSegmentName());
        row.add(info.name);
        row.add(format == null ? "" : format);
        if (docValues instanceof ColumnarStringBinaryDocValues columnar) {
            final StringColumnReader column = columnar.reader();
            row.add(column.hasDictionary() ? "DICTIONARY" : "PLAIN");
            row.add(Long.toString(column.numValues()));
            row.add(Integer.toString(column.numDocsWithField()));
            row.add(Long.toString(column.valueBytes()));
            row.add(Integer.toString(column.dictionarySize()));
            row.add(Long.toString(column.escapeCount()));
            row.add(Boolean.toString(column.hasSummary()));
            row.add(Long.toString(column.summaryValues()));
            final List<BytesRef> summaryTerms = new ArrayList<>();
            final List<Long> summaryCounts = new ArrayList<>();
            if (column.hasSummaryTerms()) {
                column.readSummary(summaryTerms, summaryCounts);
            }
            long summaryTermBytes = 0;
            for (BytesRef term : summaryTerms) {
                summaryTermBytes += term.length;
            }
            row.add(Integer.toString(summaryTerms.size()));
            row.add(Long.toString(summaryTermBytes));
            row.add(column.bestCoverage().known() ? Double.toString(column.bestCoverage().share()) : "");
            final long policyCap = Math.min(DICTIONARY_BUDGET_BYTES, (long) (MAX_SHARE_OF_COLUMN * column.valueBytes()));
            final long[] scanned = scan((ColumnarStringBinaryDocValues) segment.getBinaryDocValues(info.name), row, policyCap);
            row.add(Long.toHexString(scanned[0]));
            row.add(Long.toHexString(scanned[1]));
            addDictionary(columnar, column, row);
            row.add(Long.toString(policyCap));
            row.add(scanned[3] == 0 ? "" : Double.toString((double) scanned[2] / scanned[3]));
        } else {
            row.add("");
            while (row.size() < FIELD_COLUMNS) {
                row.add("");
            }
        }
        out.write(String.join(",", row));
        out.write('\n');
    }

    /** The hash of the exact bytes of a term, wide enough that a collision among the terms of one column is not a concern. */
    private static long hash64(final BytesRef term) {
        final long high = StringHelper.murmurhash3_x86_32(term, 0x9747b28c);
        final long low = StringHelper.murmurhash3_x86_32(term, 0x1b873593);
        return (high << 32) | (low & 0xffffffffL);
    }

    /** Adds the hash of the dictionary terms in ordinal order, the hash of the same terms as a set, and their bytes. */
    private static void addDictionary(
        final ColumnarStringBinaryDocValues docValues,
        final StringColumnReader column,
        final List<String> row
    ) throws IOException {
        if (column.hasDictionary() == false) {
            row.add("");
            row.add("");
            row.add("");
            return;
        }
        long ordered = 1;
        long set = 0;
        long bytes = 0;
        BytesRef term = new BytesRef();
        for (int ordinal = 0; ordinal < column.dictionarySize(); ordinal++) {
            term = docValues.termAt(ordinal, term);
            final long hash = hash64(term);
            ordered = ordered * 31 + hash;
            set += hash;
            bytes += term.length;
        }
        row.add(Long.toHexString(ordered));
        row.add(Long.toHexString(set));
        row.add(Long.toString(bytes));
    }

    /**
     * Scans the values and appends their distribution columns to the row.
     *
     * @return the order-independent hash of the values, the order-dependent hash of the values and their nulls, the values covered
     *         by the most frequent terms that fit {@code policyCapBytes}, and the number of values
     */
    private static long[] scan(final ColumnarStringBinaryDocValues docValues, final List<String> row, final long policyCapBytes)
        throws IOException {
        final StringBinaryPayload.Decoder decoder = new StringBinaryPayload.Decoder();
        final Map<BytesRef, long[]> counts = new HashMap<>();
        final boolean singleValued = docValues.singleValued();
        long values = 0;
        long nulls = 0;
        long bytes = 0;
        long runs = 0;
        long ascending = 0;
        long descending = 0;
        boolean capped = false;
        BytesRef previous = null;
        long multiset = 0;
        long sequence = 1;
        for (int doc = docValues.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docValues.nextDoc()) {
            final BytesRef blob = docValues.binaryValue();
            final int slots = singleValued ? 1 : decoder.reset(blob);
            for (int slot = 0; slot < slots; slot++) {
                final BytesRef value = singleValued ? blob : decoder.next();
                if (value == null) {
                    nulls++;
                    sequence = sequence * 31 - 1;
                    continue;
                }
                final long valueHash = hash64(value);
                multiset += valueHash;
                sequence = sequence * 31 + valueHash;
                values++;
                bytes += value.length;
                final int order = previous == null ? 0 : previous.compareTo(value);
                if (previous == null || order != 0) {
                    runs++;
                    if (previous != null) {
                        if (order < 0) {
                            ascending++;
                        } else {
                            descending++;
                        }
                    }
                    previous = BytesRef.deepCopyOf(value);
                }
                final long[] count = counts.get(value);
                if (count != null) {
                    count[0]++;
                } else if (counts.size() < MAX_DISTINCT) {
                    counts.put(BytesRef.deepCopyOf(value), new long[] { 1 });
                } else {
                    capped = true;
                }
            }
        }
        final List<Map.Entry<BytesRef, long[]>> byCount = new ArrayList<>(counts.entrySet());
        byCount.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        long coveredInBudget = 0;
        long budgetBytes = 0;
        long coveredForBar = 0;
        long barBytes = 0;
        long barBytesNeeded = -1;
        long policyCapUsed = 0;
        long coveredInPolicyCap = 0;
        for (Map.Entry<BytesRef, long[]> entry : byCount) {
            final long cost = Math.max(1, entry.getKey().length);
            if (budgetBytes + cost <= DICTIONARY_BUDGET_BYTES) {
                budgetBytes += cost;
                coveredInBudget += entry.getValue()[0];
            }
            if (policyCapUsed + cost <= policyCapBytes) {
                policyCapUsed += cost;
                coveredInPolicyCap += entry.getValue()[0];
            }
            if (barBytesNeeded < 0) {
                barBytes += cost;
                coveredForBar += entry.getValue()[0];
                if (values > 0 && (double) coveredForBar / values >= MIN_COVERAGE) {
                    barBytesNeeded = barBytes;
                }
            }
        }
        final long[] sorted = counts.values().stream().mapToLong(count -> count[0]).sorted().toArray();
        long head = 0;
        for (int i = 0; i < Math.min(TOP_TERMS, sorted.length); i++) {
            head += sorted[sorted.length - 1 - i];
        }
        long singletons = 0;
        for (long count : sorted) {
            if (count == 1) {
                singletons++;
            }
        }
        row.add(Long.toString(values));
        row.add(Long.toString(nulls));
        row.add(Long.toString(runs));
        row.add(Long.toString(ascending));
        row.add(Long.toString(descending));
        row.add(Integer.toString(sorted.length));
        row.add(Boolean.toString(capped));
        row.add(values == 0 || sorted.length == 0 ? "" : Double.toString((double) sorted[sorted.length - 1] / values));
        row.add(values == 0 ? "" : Double.toString((double) head / values));
        row.add(values == 0 ? "" : Double.toString((double) singletons / values));
        row.add(values == 0 ? "" : Double.toString((double) bytes / values));
        row.add(values == 0 ? "" : Double.toString((double) coveredInBudget / values));
        row.add(values == 0 ? "" : Long.toString(barBytesNeeded));
        return new long[] { multiset, sequence, coveredInPolicyCap, values };
    }
}
