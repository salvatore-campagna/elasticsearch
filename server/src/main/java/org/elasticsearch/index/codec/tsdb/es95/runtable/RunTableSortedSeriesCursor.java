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
 * Per-segment {@link SortedSeriesCursor} for a {@code Sorted} field during merge. It walks the segment's series
 * boundaries (precomputed once per merge from {@code _tsid}) and, for each series, reports the series' global
 * {@code _tsid} ordinal and the field ordinal read from the field's run-table {@link RunTableSortedOrdinalReader.Runs}
 * view.
 *
 * <p>A field run can span several series when adjacent series share a value, so the field-run pointer co-advances
 * with the series pointer rather than assuming a one-to-one mapping. The field ordinal is remapped to the merged
 * terms dictionary through {@code fieldRemap}, except an absent series, whose source run carries the source
 * sentinel, is emitted as the merged sentinel: the ordinal map covers only the real ordinals {@code [0, K)}.
 */
final class RunTableSortedSeriesCursor implements SortedSeriesCursor {

    private final int[] seriesStartDocs;
    private final long[] seriesTsidGlobalOrds;
    private final int maxDoc;
    private final RunTableSortedOrdinalReader.Runs fieldRuns;
    private final LongValues fieldRemap;
    private final int mergedSentinel;

    private int seriesIndex = -1;
    private int fieldRun = 0;

    RunTableSortedSeriesCursor(
        final int[] seriesStartDocs,
        final long[] seriesTsidGlobalOrds,
        int maxDoc,
        final RunTableSortedOrdinalReader.Runs fieldRuns,
        final LongValues fieldRemap,
        int mergedSentinel
    ) {
        this.seriesStartDocs = seriesStartDocs;
        this.seriesTsidGlobalOrds = seriesTsidGlobalOrds;
        this.maxDoc = maxDoc;
        this.fieldRuns = fieldRuns;
        this.fieldRemap = fieldRemap;
        this.mergedSentinel = mergedSentinel;
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
    public long fieldOrd() {
        final long sourceOrd = fieldRuns.ordinal(fieldRun);
        return sourceOrd == fieldRuns.sentinel() ? mergedSentinel : fieldRemap.get(sourceOrd);
    }
}
