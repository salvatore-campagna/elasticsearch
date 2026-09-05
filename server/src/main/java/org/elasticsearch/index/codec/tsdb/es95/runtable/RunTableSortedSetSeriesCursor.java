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

/**
 * Per-segment {@link SortedSetSeriesCursor} for a {@code SortedSet} field during merge. It walks the segment's
 * series boundaries (precomputed once per merge from {@code _tsid}) and, for each series, reports the series'
 * global {@code _tsid} ordinal and the ordinal set read from the field's run-table
 * {@link RunTableSortedSetOrdinalReader.Runs} view.
 *
 * <p>A field run can span several series when adjacent series share a set, so the field-run pointer co-advances
 * with the series pointer rather than assuming a one-to-one mapping. Each ordinal is remapped to the merged terms
 * dictionary through {@code fieldRemap}; the remap preserves order, so the set stays ascending. An absent series
 * is the empty set and needs no sentinel.
 */
final class RunTableSortedSetSeriesCursor implements SortedSetSeriesCursor {

    private final int[] seriesStartDocs;
    private final long[] seriesTsidGlobalOrds;
    private final int maxDoc;
    private final RunTableSortedSetOrdinalReader.Runs fieldRuns;
    private final LongValues fieldRemap;

    private int seriesIndex = -1;
    private int fieldRun = 0;

    RunTableSortedSetSeriesCursor(
        final int[] seriesStartDocs,
        final long[] seriesTsidGlobalOrds,
        int maxDoc,
        final RunTableSortedSetOrdinalReader.Runs fieldRuns,
        final LongValues fieldRemap
    ) {
        this.seriesStartDocs = seriesStartDocs;
        this.seriesTsidGlobalOrds = seriesTsidGlobalOrds;
        this.maxDoc = maxDoc;
        this.fieldRuns = fieldRuns;
        this.fieldRemap = fieldRemap;
    }

    @Override
    public boolean next() {
        if (++seriesIndex >= seriesStartDocs.length) {
            return false;
        }
        final int start = seriesStartDocs[seriesIndex];
        while (fieldRun + 1 < fieldRuns.count() && fieldRuns.startDoc(fieldRun + 1) <= start) {
            fieldRun++;
        }
        return true;
    }

    @Override
    public long tsidOrd() {
        return seriesTsidGlobalOrds[seriesIndex];
    }

    @Override
    public int docCount() {
        final int end = seriesIndex + 1 < seriesStartDocs.length ? seriesStartDocs[seriesIndex + 1] : maxDoc;
        return end - seriesStartDocs[seriesIndex];
    }

    @Override
    public int ordCount() {
        return fieldRuns.ordCount(fieldRun);
    }

    @Override
    public int ordAt(int index) {
        return (int) fieldRemap.get(fieldRuns.ordAt(fieldRun, index));
    }
}
