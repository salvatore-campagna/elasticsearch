/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.columnar;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.columnar.ColumNARDocValuesFormat;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

public class ColumnarIndexInspectorTests extends ESTestCase {

    private static final int DOCS = 20_000;

    public void testReportsTheFormatLayoutAndDistributionOfEachArm() throws IOException {
        final BytesRef[] values = StringData.HOSTNAME.generate(DOCS, new Random(randomLong()));
        final Set<BytesRef> distinct = new HashSet<>(Arrays.asList(values));

        final Map<String, String> adaptive = inspectField(StringFormat.COLUMNAR, values);
        final Map<String, String> plain = inspectField(StringFormat.COLUMNAR_PLAIN, values);
        final Map<String, String> es819 = inspectField(StringFormat.ES819_BINARY, values);

        final String columnarFormat = new ColumNARDocValuesFormat().getName();
        assertThat(adaptive.get("dv_format"), equalTo(columnarFormat));
        assertThat(plain.get("dv_format"), equalTo(columnarFormat));
        assertThat(es819.get("dv_format"), not(equalTo(columnarFormat)));

        assertThat(adaptive.get("layout"), equalTo("DICTIONARY"));
        assertThat(plain.get("layout"), equalTo("PLAIN"));
        assertThat(es819.get("layout"), equalTo(""));

        for (Map<String, String> arm : List.of(adaptive, plain)) {
            assertThat(arm.get("values"), equalTo(Integer.toString(DOCS)));
            assertThat(arm.get("scanned_values"), equalTo(Integer.toString(DOCS)));
            assertThat(arm.get("distinct"), equalTo(Integer.toString(distinct.size())));
            assertThat(arm.get("distinct_capped"), equalTo("false"));
        }
        assertThat(Integer.parseInt(adaptive.get("dictionary_terms")), greaterThan(0));
        assertThat(plain.get("dictionary_terms"), equalTo("0"));
        assertThat(plain.get("has_summary"), equalTo("false"));

        assertThat(adaptive.get("value_multiset_hash"), equalTo(plain.get("value_multiset_hash")));
        assertThat(adaptive.get("value_sequence_hash"), equalTo(plain.get("value_sequence_hash")));
        assertThat(adaptive.get("dictionary_hash"), not(equalTo("")));
        assertThat(adaptive.get("dictionary_set_hash"), not(equalTo("")));
        assertThat(Long.parseLong(adaptive.get("dictionary_term_bytes")), greaterThan(0L));
        assertThat(plain.get("dictionary_hash"), equalTo(""));
        final long policyCap = Long.parseLong(adaptive.get("policy_cap_bytes"));
        assertThat(policyCap, lessThanOrEqualTo(512L * 1024));
        assertThat(Double.parseDouble(adaptive.get("exact_best_coverage_policy_cap")), lessThanOrEqualTo(1.0));
    }

    public void testReportsTheBytesOfEachFileKind() throws IOException {
        final BytesRef[] values = StringData.HOSTNAME.generate(DOCS, new Random(randomLong()));
        final Map<String, Long> columnar = bytesByExtension(StringFormat.COLUMNAR, values);
        final Map<String, Long> es819 = bytesByExtension(StringFormat.ES819_BINARY, values);
        assertThat(columnar.get("cnd"), greaterThan(0L));
        assertThat(columnar.containsKey("dvd"), equalTo(false));
        assertThat(es819.get("dvd"), greaterThan(0L));
        assertThat(es819.containsKey("cnd"), equalTo(false));
    }

    public void testReportsTheOrderOfAnOrderedColumn() throws IOException {
        final BytesRef[] values = StringData.SORTED_HOSTNAME.generate(DOCS, new Random(randomLong()));
        final Map<String, String> field = inspectField(StringFormat.COLUMNAR, values);
        assertThat(field.get("descending_steps"), equalTo("0"));
        assertThat(Long.parseLong(field.get("ascending_steps")), greaterThan(0L));
        assertThat(field.get("runs"), equalTo(Long.toString(Long.parseLong(field.get("ascending_steps")) + 1)));
    }

    private Map<String, String> inspectField(final StringFormat format, final BytesRef[] values) throws IOException {
        final List<Map<String, String>> rows = readCsv(inspect(format, values).resolve("fields.csv"));
        assertThat(rows.size(), equalTo(1));
        return rows.get(0);
    }

    private Map<String, Long> bytesByExtension(final StringFormat format, final BytesRef[] values) throws IOException {
        final Map<String, Long> bytes = new HashMap<>();
        for (Map<String, String> row : readCsv(inspect(format, values).resolve("segments.csv"))) {
            bytes.merge(row.get("extension"), Long.parseLong(row.get("bytes")), Long::sum);
        }
        return bytes;
    }

    private Path inspect(final StringFormat format, final BytesRef[] values) throws IOException {
        final Path index = createTempDir();
        try (Directory directory = new MMapDirectory(index)) {
            format.writeSegments(directory, values, DOCS, false);
        }
        final Path output = createTempDir();
        ColumnarIndexInspector.main(new String[] { index.toString(), output.toString() });
        return output;
    }

    private static List<Map<String, String>> readCsv(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final String[] header = lines.get(0).split(",", -1);
        final List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            final String[] cells = line.split(",", -1);
            assertThat(line, cells.length, equalTo(header.length));
            final Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], cells[i]);
            }
            rows.add(row);
        }
        return rows;
    }
}
