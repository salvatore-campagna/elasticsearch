/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec;

import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.columnar.ColumNARDocValuesFormat;
import org.elasticsearch.columnar.ColumnarFieldType;
import org.elasticsearch.columnar.FormatVersion;
import org.elasticsearch.columnar.numeric.NumericPipeline;
import org.elasticsearch.columnar.string.BestCoverage;
import org.elasticsearch.columnar.string.ColumnarStringBinaryDocValues;
import org.elasticsearch.columnar.string.StringBinaryPayload;
import org.elasticsearch.columnar.string.StringColumnOptions;
import org.elasticsearch.columnar.string.StringColumnReader;
import org.elasticsearch.index.IndexSettings.ColumnarStringLayout;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.hamcrest.Matchers.equalTo;

public class ColumnarStringLayoutExperimentTests extends ESTestCase {

    private static final String FIELD = "value";

    private record Column(
        boolean dictionary,
        long dictionarySize,
        long escapes,
        boolean hasSummary,
        long summaryValues,
        boolean summaryTerms,
        BestCoverage bestCoverage,
        Map<String, Long> summary
    ) {}

    private enum Shape {
        ZIPF_VOCABULARY,
        HEAD_AND_TAIL,
        BUDGET_PRESSURE,
        MOSTLY_UNIQUE
    }

    public void testPlainSurveyedKeepsTheFlushSummaryOfTheAdaptiveArm() throws IOException {
        for (Shape shape : Shape.values()) {
            final BytesRef[] values = values(shape, new Random(randomLong()));
            final int segmentSize = values.length / between(2, 4);
            final List<Column> adaptive = write(ColumnarStringLayout.ADAPTIVE, values, segmentSize, false);
            final List<Column> surveyed = write(ColumnarStringLayout.PLAIN_SURVEYED, values, segmentSize, false);
            assertThat(shape.toString(), surveyed.size(), equalTo(adaptive.size()));
            for (int segment = 0; segment < adaptive.size(); segment++) {
                final Column expected = adaptive.get(segment);
                final Column actual = surveyed.get(segment);
                final String context = shape + " flush segment=" + segment;
                logger.info(
                    "layout-equivalence {} adaptive(dictionary={}, terms={}, escapes={}) summary(terms={}, values={}, bestCoverage={})",
                    context,
                    expected.dictionary(),
                    expected.dictionarySize(),
                    expected.escapes(),
                    expected.summary().size(),
                    expected.summaryValues(),
                    expected.bestCoverage()
                );
                assertFalse(context + ": plain_surveyed admitted a dictionary", actual.dictionary());
                assertThat(context, actual.hasSummary(), equalTo(expected.hasSummary()));
                assertThat(context, actual.summaryValues(), equalTo(expected.summaryValues()));
                assertThat(context, actual.bestCoverage(), equalTo(expected.bestCoverage()));
                assertThat(context + ": summary terms and retained counts", actual.summary(), equalTo(expected.summary()));
            }
        }
    }

    public void testMergedSummariesAgreeOnSharedTermsAndTheArmsDifferInWhetherTheyKeepOne() throws IOException {
        for (Shape shape : Shape.values()) {
            final BytesRef[] values = values(shape, new Random(randomLong()));
            final int segmentSize = values.length / between(2, 4);
            final Column adaptive = write(ColumnarStringLayout.ADAPTIVE, values, segmentSize, true).get(0);
            final Column surveyed = write(ColumnarStringLayout.PLAIN_SURVEYED, values, segmentSize, true).get(0);
            int onlyInAdaptive = 0;
            int differentCount = 0;
            for (Map.Entry<String, Long> entry : adaptive.summary().entrySet()) {
                final Long count = surveyed.summary().get(entry.getKey());
                if (count == null) {
                    onlyInAdaptive++;
                } else if (count.equals(entry.getValue()) == false) {
                    differentCount++;
                }
            }
            final int onlyInSurveyed = (int) surveyed.summary()
                .keySet()
                .stream()
                .filter(t -> adaptive.summary().containsKey(t) == false)
                .count();
            logger.info(
                "layout-equivalence {} merged adaptive(dictionary={}, hasSummary={}, terms={}, summaryTerms={}, bestCoverage={}) "
                    + "surveyed(hasSummary={}, summaryTerms={}, bestCoverage={}) onlyInAdaptive={} onlyInSurveyed={} differentCount={}",
                shape,
                adaptive.dictionary(),
                adaptive.hasSummary(),
                adaptive.dictionarySize(),
                adaptive.summary().size(),
                adaptive.bestCoverage(),
                surveyed.hasSummary(),
                surveyed.summary().size(),
                surveyed.bestCoverage(),
                onlyInAdaptive,
                onlyInSurveyed,
                differentCount
            );
            assertFalse(shape + ": plain_surveyed admitted a dictionary", surveyed.dictionary());
            assertThat(shape + " terms with a different retained count", differentCount, equalTo(0));
            assertTrue(shape + ": plain_surveyed is PLAIN, so its merge always records a summary", surveyed.hasSummary());
        }
    }

    public void testAdaptiveArmAdmitsADictionaryOnDictionaryEligibleShapes() throws IOException {
        for (Shape shape : List.of(Shape.ZIPF_VOCABULARY, Shape.HEAD_AND_TAIL)) {
            final BytesRef[] values = values(shape, new Random(randomLong()));
            final List<Column> adaptive = write(ColumnarStringLayout.ADAPTIVE, values, values.length, false);
            assertTrue(shape + " should have earned a dictionary", adaptive.get(0).dictionary());
        }
    }

    public void testPlainArmDoesNotSurveyOrSummarise() throws IOException {
        final BytesRef[] values = values(Shape.ZIPF_VOCABULARY, new Random(randomLong()));
        final Column plain = write(ColumnarStringLayout.PLAIN, values, values.length, false).get(0);
        assertFalse(plain.dictionary());
        assertFalse(plain.hasSummary());
    }

    private static BytesRef[] values(final Shape shape, final Random random) {
        return switch (shape) {
            case ZIPF_VOCABULARY -> zipf(40_000, between(random, 50, 5_000), 24, 1.5, random);
            case HEAD_AND_TAIL -> headAndTail(40_000, between(random, 4, 64), 0.95 + random.nextDouble() * 0.049, random);
            case BUDGET_PRESSURE -> zipf(60_000, 12_000, 64, 1.2, random);
            case MOSTLY_UNIQUE -> unique(30_000, random);
        };
    }

    private static int between(final Random random, final int min, final int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static BytesRef[] zipf(final int count, final int vocabulary, final int length, final double exponent, final Random random) {
        final BytesRef[] terms = new BytesRef[vocabulary];
        for (int i = 0; i < vocabulary; i++) {
            terms[i] = new BytesRef(pad("term-" + i + "-", length));
        }
        final BytesRef[] values = new BytesRef[count];
        for (int i = 0; i < count; i++) {
            final int pick = (int) (Math.pow(random.nextDouble(), exponent) * vocabulary);
            values[i] = terms[Math.min(pick, vocabulary - 1)];
        }
        return values;
    }

    private static BytesRef[] headAndTail(final int count, final int head, final double headShare, final Random random) {
        final BytesRef[] values = new BytesRef[count];
        for (int i = 0; i < count; i++) {
            values[i] = random.nextDouble() < headShare
                ? new BytesRef(pad("head-" + random.nextInt(head) + "-", 24))
                : new BytesRef(pad("tail-" + i + "-" + Long.toString(random.nextLong(), 36), 24));
        }
        return values;
    }

    private static BytesRef[] unique(final int count, final Random random) {
        final BytesRef[] values = new BytesRef[count];
        for (int i = 0; i < count; i++) {
            values[i] = new BytesRef(pad(Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong()), 32));
        }
        return values;
    }

    private static String pad(final String prefix, final int length) {
        final StringBuilder builder = new StringBuilder(prefix);
        while (builder.length() < length) {
            builder.append('x');
        }
        return builder.substring(0, Math.max(length, prefix.length()));
    }

    private List<Column> write(final ColumnarStringLayout layout, final BytesRef[] values, final int segmentSize, final boolean merge)
        throws IOException {
        final StringColumnOptions options = PerFieldFormatSupplier.ColumnarFields.withLayout(StringColumnOptions.DEFAULT, layout);
        final DocValuesFormat format = new ColumNARDocValuesFormat(
            (fieldName, type) -> NumericPipeline::defaultPipeline,
            field -> ColumnarFieldType.STRING,
            ColumNARDocValuesFormat.DEFAULT_BLOCK_SIZE,
            (fieldName, type) -> options,
            FormatVersion.CURRENT
        );
        final IndexWriterConfig config = new IndexWriterConfig().setCodec(new Elasticsearch96Codec() {
            @Override
            public DocValuesFormat getDocValuesFormatForField(final String field) {
                return format;
            }
        }).setUseCompoundFile(false).setMergePolicy(merge ? new LogByteSizeMergePolicy() : NoMergePolicy.INSTANCE);
        final FieldType type = new FieldType();
        type.setDocValuesType(DocValuesType.BINARY);
        type.freeze();
        try (Directory directory = newDirectory()) {
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (int i = 0; i < values.length; i++) {
                    final Document document = new Document();
                    final BytesRef payload = BytesRef.deepCopyOf(new StringBinaryPayload.Builder().encode(List.of(values[i])));
                    document.add(new Field(FIELD, payload, type));
                    writer.addDocument(document);
                    if (i % segmentSize == segmentSize - 1) {
                        writer.flush();
                    }
                }
                if (merge) {
                    writer.forceMerge(1);
                }
            }
            final List<Column> columns = new ArrayList<>();
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                for (LeafReaderContext leaf : reader.leaves()) {
                    final ColumnarStringBinaryDocValues docValues = (ColumnarStringBinaryDocValues) leaf.reader().getBinaryDocValues(FIELD);
                    columns.add(describe(docValues.reader()));
                }
            }
            return columns;
        }
    }

    private static Column describe(final StringColumnReader reader) throws IOException {
        final Map<String, Long> summary = new TreeMap<>();
        if (reader.hasSummary()) {
            final List<BytesRef> terms = new ArrayList<>();
            final List<Long> counts = new ArrayList<>();
            reader.readSummary(terms, counts);
            for (int i = 0; i < terms.size(); i++) {
                summary.put(terms.get(i).utf8ToString().toLowerCase(Locale.ROOT), counts.get(i));
            }
        }
        return new Column(
            reader.hasDictionary(),
            reader.dictionarySize(),
            reader.escapeCount(),
            reader.hasSummary(),
            reader.summaryValues(),
            reader.hasSummaryTerms(),
            reader.bestCoverage(),
            summary
        );
    }
}
