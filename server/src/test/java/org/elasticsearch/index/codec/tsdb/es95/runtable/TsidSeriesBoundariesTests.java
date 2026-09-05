/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.es95.runtable;

import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

/**
 * Unit tests for {@link TsidSeriesBoundaries}, which walks a segment's dense {@code _tsid} sorted doc values
 * (the primary sort field) into series boundaries: the first doc of each maximal run of equal {@code _tsid}
 * ordinal, together with that series' local {@code _tsid} ordinal. These boundaries are computed once per merge
 * and shared across every dimension field.
 */
public class TsidSeriesBoundariesTests extends ESTestCase {

    public void testEnumeratesSeriesBoundaries() throws IOException {
        final int[] perDocTsid = { 0, 0, 0, 1, 1, 2, 2, 2 };
        final TsidSeriesBoundaries boundaries = TsidSeriesBoundaries.enumerate(dense(perDocTsid), perDocTsid.length);
        assertArrayEquals(new int[] { 0, 3, 5 }, boundaries.startDocs());
        assertArrayEquals(new int[] { 0, 1, 2 }, boundaries.tsidOrds());
        assertEquals(3, boundaries.count());
    }

    public void testSingleSeries() throws IOException {
        final int[] perDocTsid = { 4, 4, 4, 4 };
        final TsidSeriesBoundaries boundaries = TsidSeriesBoundaries.enumerate(dense(perDocTsid), perDocTsid.length);
        assertArrayEquals(new int[] { 0 }, boundaries.startDocs());
        assertArrayEquals(new int[] { 4 }, boundaries.tsidOrds());
    }

    public void testEveryDocDistinctSeries() throws IOException {
        final int[] perDocTsid = { 0, 1, 2, 3 };
        final TsidSeriesBoundaries boundaries = TsidSeriesBoundaries.enumerate(dense(perDocTsid), perDocTsid.length);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, boundaries.startDocs());
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, boundaries.tsidOrds());
    }

    private static SortedDocValues dense(int[] perDocOrd) {
        int max = 0;
        for (final int ord : perDocOrd) {
            max = Math.max(max, ord);
        }
        final int valueCount = max + 1;
        return new SortedDocValues() {
            private int doc = -1;

            @Override
            public int ordValue() {
                return perDocOrd[doc];
            }

            @Override
            public boolean advanceExact(int target) {
                doc = target;
                return true;
            }

            @Override
            public int docID() {
                return doc;
            }

            @Override
            public int nextDoc() {
                return advance(doc + 1);
            }

            @Override
            public int advance(int target) {
                doc = target >= perDocOrd.length ? NO_MORE_DOCS : target;
                return doc;
            }

            @Override
            public long cost() {
                return perDocOrd.length;
            }

            @Override
            public int getValueCount() {
                return valueCount;
            }

            @Override
            public BytesRef lookupOrd(int ord) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
