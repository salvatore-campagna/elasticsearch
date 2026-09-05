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
import org.apache.lucene.util.ArrayUtil;

import java.io.IOException;

/**
 * Series boundaries of one source segment, derived from its dense {@code _tsid} sorted doc values (the primary
 * sort field). A series is a maximal run of docs sharing a {@code _tsid} ordinal; because the segment is sorted
 * by {@code _tsid}, equal ordinals are contiguous, so a single walk records each series' first doc and local
 * {@code _tsid} ordinal.
 *
 * <p>These boundaries are computed once per merge and shared across every dimension field, so the {@code O(docs)}
 * {@code _tsid} walk is paid once rather than once per field. A dimension field's series-level values are then
 * read at run granularity from its run-table columns.
 */
final class TsidSeriesBoundaries {

    private final int[] startDocs;
    private final int[] tsidOrds;

    private TsidSeriesBoundaries(final int[] startDocs, final int[] tsidOrds) {
        this.startDocs = startDocs;
        this.tsidOrds = tsidOrds;
    }

    int count() {
        return startDocs.length;
    }

    int[] startDocs() {
        return startDocs;
    }

    int[] tsidOrds() {
        return tsidOrds;
    }

    static TsidSeriesBoundaries enumerate(final SortedDocValues tsid, int maxDoc) throws IOException {
        int[] startDocs = new int[16];
        int[] tsidOrds = new int[16];
        int count = 0;
        int previousOrd = -1;
        for (int doc = 0; doc < maxDoc; doc++) {
            final boolean present = tsid.advanceExact(doc);
            assert present : "_tsid must be dense but doc " + doc + " has no value";
            final int ord = tsid.ordValue();
            if (ord != previousOrd) {
                startDocs = ArrayUtil.grow(startDocs, count + 1);
                tsidOrds = ArrayUtil.grow(tsidOrds, count + 1);
                startDocs[count] = doc;
                tsidOrds[count] = ord;
                count++;
                previousOrd = ord;
            }
        }
        return new TsidSeriesBoundaries(ArrayUtil.copyOfSubArray(startDocs, 0, count), ArrayUtil.copyOfSubArray(tsidOrds, 0, count));
    }
}
