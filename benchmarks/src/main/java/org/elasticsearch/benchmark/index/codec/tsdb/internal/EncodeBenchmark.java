/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.index.codec.tsdb.internal;

import org.apache.lucene.store.ByteArrayDataOutput;

import java.io.IOException;
import java.util.function.Supplier;

public class EncodeBenchmark extends AbstractDocValuesForUtilBenchmark {
    protected ByteArrayDataOutput dataOutput;
    protected long[] input;

    @Override
    public void setupIteration(int unUsedBitsPerValue, Supplier<long[]> arraySupplier) throws IOException {
        this.input = arraySupplier.get();
    }

    @Override
    public void setupInvocation(int unusedBitsPerValue) {
        this.dataOutput = new ByteArrayDataOutput(new byte[Long.BYTES * blockSize]);
    }

    @Override
    public void benchmark(int bitsPerValue) throws IOException {
        forUtil.encode(this.input, bitsPerValue, this.dataOutput);
    }
}
