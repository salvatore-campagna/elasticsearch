/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.es95.runtable;

import org.apache.lucene.util.LongValues;
import org.elasticsearch.test.ESTestCase;

/**
 * Unit tests for {@link RunTableSortedSeriesCursor}, the per-segment adapter that turns a segment's series
 * boundaries, its {@code _tsid} global ordinals, and a field's run-table {@link RunTableSortedOrdinalReader.Runs}
 * view into the {@link SortedSeriesCursor} the merger consumes. Covers a field run spanning several series, the
 * field-ordinal remap through the merged terms dictionary, and the absent (sentinel) series mapping to the merged
 * sentinel rather than through the ordinal map.
 */
public class RunTableSortedSeriesCursorTests extends ESTestCase {

    public void testFieldRunSpansMultipleSeriesWithRemap() {
        // Field: run0 ord 5 over docs [0,5), run1 ord 7 over docs [5,6).
        final RunTableSortedOrdinalReader.Runs fieldRuns = runs(new int[] { 0, 5 }, new long[] { 5, 7 }, 6, 8);
        // Three series; the first two both fall inside field run0.
        final int[] seriesStartDocs = { 0, 2, 5 };
        final long[] tsidGlobalOrds = { 10, 20, 30 };
        final LongValues remap = remap(new long[] { 0, 10, 20, 30, 40, 50, 60, 70 });
        final RunTableSortedSeriesCursor cursor = new RunTableSortedSeriesCursor(seriesStartDocs, tsidGlobalOrds, 6, fieldRuns, remap, 8);

        assertTrue(cursor.next());
        assertEquals(10, cursor.tsidOrd());
        assertEquals(2, cursor.docCount());
        assertEquals(50, cursor.fieldOrd());

        assertTrue(cursor.next());
        assertEquals(20, cursor.tsidOrd());
        assertEquals(3, cursor.docCount());
        assertEquals(50, cursor.fieldOrd());

        assertTrue(cursor.next());
        assertEquals(30, cursor.tsidOrd());
        assertEquals(1, cursor.docCount());
        assertEquals(70, cursor.fieldOrd());

        assertFalse(cursor.next());
    }

    public void testAbsentSeriesEmitsMergedSentinel() {
        // Field valueCount 3 -> source sentinel 3. run1 (ord 3) is an absent run over docs [2,4).
        final RunTableSortedOrdinalReader.Runs fieldRuns = runs(new int[] { 0, 2, 4 }, new long[] { 1, 3, 0 }, 5, 3);
        final int[] seriesStartDocs = { 0, 2, 4 };
        final long[] tsidGlobalOrds = { 10, 20, 30 };
        final LongValues remap = remap(new long[] { 0, 1, 2 });
        final int mergedSentinel = 9;
        final RunTableSortedSeriesCursor cursor = new RunTableSortedSeriesCursor(
            seriesStartDocs,
            tsidGlobalOrds,
            5,
            fieldRuns,
            remap,
            mergedSentinel
        );

        assertTrue(cursor.next());
        assertEquals(1, cursor.fieldOrd());

        assertTrue(cursor.next());
        assertEquals(mergedSentinel, cursor.fieldOrd());

        assertTrue(cursor.next());
        assertEquals(0, cursor.fieldOrd());

        assertFalse(cursor.next());
    }

    private static RunTableSortedOrdinalReader.Runs runs(int[] startDocs, long[] ords, int maxDoc, int valueCount) {
        final long[] startDocsLong = new long[startDocs.length];
        for (int i = 0; i < startDocs.length; i++) {
            startDocsLong[i] = startDocs[i];
        }
        return RunTableSortedOrdinalReader.openRuns(remap(startDocsLong), remap(ords), startDocs.length, maxDoc, valueCount);
    }

    private static LongValues remap(long[] values) {
        return new LongValues() {
            @Override
            public long get(long index) {
                return values[(int) index];
            }
        };
    }
}
