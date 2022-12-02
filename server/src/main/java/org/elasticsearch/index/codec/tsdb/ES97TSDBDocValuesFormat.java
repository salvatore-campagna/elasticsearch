/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

public class ES97TSDBDocValuesFormat extends org.apache.lucene.codecs.DocValuesFormat {

    static final int DEFAULT_NUMERIC_BLOCK_SHIFT = 7;
    static final int DEFAULT_NUMERIC_BLOCK_SIZE = 1 << DEFAULT_NUMERIC_BLOCK_SHIFT;
    static final int DEFAULT_DIRECT_MONOTONIC_BLOCK_SHIFT = 16;
    static final String CODEC_NAME = "ES97TSDB";
    static final String DATA_CODEC = "ES97TSDBDocValuesData";
    static final String DATA_EXTENSION = "dvd";
    static final String META_CODEC = "ES97TSDBDocValuesMetadata";
    static final String META_EXTENSION = "dvm";
    static final int VERSION_START = 0;
    static final int VERSION_CURRENT = VERSION_START;
    static final byte NUMERIC = 0;
    static final byte BINARY = 1; // unused at the moment
    static final byte SORTED = 2; // unused at the moment
    static final byte SORTED_SET = 3; // unused at the moment
    static final byte SORTED_NUMERIC = 4;

    private final int numericBlockShift;
    private final int numericBlockSize;
    private final int numericBlockMask;
    private final int directMonotonicBlockShift;

    public ES97TSDBDocValuesFormat() {
        this(DEFAULT_NUMERIC_BLOCK_SHIFT, DEFAULT_DIRECT_MONOTONIC_BLOCK_SHIFT);
    }

    public ES97TSDBDocValuesFormat(int numericBlockShift, int directMonotonicBlockShift) {
        super(CODEC_NAME);
        this.numericBlockShift = numericBlockShift;
        this.numericBlockSize = 1 << numericBlockShift;
        this.numericBlockMask = numericBlockSize - 1;
        this.directMonotonicBlockShift = directMonotonicBlockShift;
    }

    @Override
    public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        return new ES97TSDBDocValuesConsumer(
            state,
            DATA_CODEC,
            DATA_EXTENSION,
            META_CODEC,
            META_EXTENSION,
            numericBlockShift,
            numericBlockSize,
            directMonotonicBlockShift
        );
    }

    @Override
    public DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
        return new ES97TSDBDocValuesProducer(
            state,
            DATA_CODEC,
            DATA_EXTENSION,
            META_CODEC,
            META_EXTENSION,
            numericBlockShift,
            numericBlockSize,
            numericBlockMask
        );
    }

    public int getNumericBlockShift() {
        return numericBlockShift;
    }

    public int getNumericBlockSize() {
        return numericBlockSize;
    }

    public int getNumericBlockMask() {
        return numericBlockMask;
    }

    public int getDirectMonotonicBlockShift() {
        return directMonotonicBlockShift;
    }
}
