/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.ByteBuffersIndexOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Arrays;

public class OrdinalBlockSizeStorageComparisonTests extends ESTestCase {

    private static final int TOTAL_DOCS = 4096;
    private static final int[] BLOCK_SIZES = { 128, 512, 1024, 2048, 4096 };

    public void testConstantOrdinal() throws IOException {
        assertSizes(constantOrdinals(), 1, new long[] { 32, 8, 4, 2, 1 });
    }

    public void testCyclicOrdinalsWith10Terms() throws IOException {
        assertSizes(cyclicOrdinals(10), 10, new long[] { 384, 96, 48, 24, 12 });
    }

    public void testCyclicOrdinalsWith100Terms() throws IOException {
        assertSizes(cyclicOrdinals(100), 100, new long[] { 3616, 816, 408, 204, 102 });
    }

    // NOTE: BS=4096 breaks out of the bit-packed path here because maxCycleLength = 4096/4 = 1024 >= 1000,
    // while all smaller block sizes have maxCycleLength < 1000 and fall back to bit-packed.
    public void testCyclicOrdinalsWith1000Terms() throws IOException {
        assertSizes(cyclicOrdinals(1000), 1000, new long[] { 5152, 5128, 5124, 5122, 1874 });
    }

    public void testSortedOrdinalsWith10Terms() throws IOException {
        assertSizes(sortedOrdinals(10), 10, new long[] { 48, 30, 2052, 2050, 2049 });
    }

    public void testSortedOrdinalsWith100Terms() throws IOException {
        assertSizes(sortedOrdinals(100), 100, new long[] { 3616, 3592, 3588, 3586, 3585 });
    }

    public void testSortedOrdinalsWith1000Terms() throws IOException {
        assertSizes(sortedOrdinals(1000), 1000, new long[] { 5152, 5128, 5124, 5122, 5121 });
    }

    private void assertSizes(long[] ordinals, int numTerms, long[] expectedSizes) throws IOException {
        for (int i = 0; i < BLOCK_SIZES.length; i++) {
            final int blockSize = BLOCK_SIZES[i];
            final long actual = encodeTotal(blockSize, ordinals, numTerms);
            logger.info("bs={} numTerms={} bytes={}", blockSize, numTerms, actual);
            assertEquals("bs=" + blockSize + " numTerms=" + numTerms, expectedSizes[i], actual);
        }
    }

    private static long encodeTotal(int blockSize, long[] allOrdinals, int numTerms) throws IOException {
        final TSDBDocValuesEncoder encoder = new TSDBDocValuesEncoder(blockSize);
        final int bitsPerOrd = PackedInts.bitsRequired(numTerms - 1);
        final ByteBuffersDataOutput bufferOut = new ByteBuffersDataOutput();
        try (IndexOutput out = new ByteBuffersIndexOutput(bufferOut, "test", "test")) {
            for (int start = 0; start < TOTAL_DOCS; start += blockSize) {
                encoder.encodeOrdinals(Arrays.copyOfRange(allOrdinals, start, start + blockSize), out, bitsPerOrd);
            }
        }
        return bufferOut.size();
    }

    private static long[] constantOrdinals() {
        return new long[TOTAL_DOCS];
    }

    private static long[] cyclicOrdinals(int numTerms) {
        final long[] ordinals = new long[TOTAL_DOCS];
        for (int i = 0; i < TOTAL_DOCS; i++) {
            ordinals[i] = i % numTerms;
        }
        return ordinals;
    }

    private static long[] sortedOrdinals(int numTerms) {
        final long[] ordinals = new long[TOTAL_DOCS];
        for (int i = 0; i < TOTAL_DOCS; i++) {
            ordinals[i] = (long) i * numTerms / TOTAL_DOCS;
        }
        return ordinals;
    }
}
