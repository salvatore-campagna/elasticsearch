/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.es819;

import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.packed.DirectMonotonicReader;
import org.elasticsearch.index.codec.tsdb.AbstractTSDBDocValuesProducer;
import org.elasticsearch.index.codec.tsdb.DocOffsetsCodec;
import org.elasticsearch.index.codec.tsdb.NumericFieldReader;
import org.elasticsearch.index.codec.tsdb.TSDBDocValuesEncoder;
import org.elasticsearch.index.codec.tsdb.TSDBDocValuesFormatConfig;

import java.io.IOException;

/**
 * ES819 doc values producer. Delegates all shared wire-format reading logic to
 * {@link AbstractTSDBDocValuesProducer} and provides the ES819-specific numeric
 * decoding strategy via {@link TSDBDocValuesEncoder}.
 */
final class ES819TSDBDocValuesProducer extends AbstractTSDBDocValuesProducer {

    ES819TSDBDocValuesProducer(
        final SegmentReadState state,
        final String dataCodec,
        final String dataExtension,
        final String metaCodec,
        final String metaExtension,
        final TSDBDocValuesFormatConfig formatConfig,
        final DocOffsetsCodec.Decoder docOffsetsDecoder
    ) throws IOException {
        super(state, dataCodec, dataExtension, metaCodec, metaExtension, formatConfig, docOffsetsDecoder);
    }

    private ES819TSDBDocValuesProducer(final ES819TSDBDocValuesProducer original) {
        super(original);
    }

    @Override
    protected NumericFieldReader createNumericFieldReader(final NumericEntry entry, int numericBlockSize) {
        final TSDBDocValuesEncoder encoder = new TSDBDocValuesEncoder(numericBlockSize);
        return new NumericFieldReader() {
            @Override
            public void readHeader(final IndexInput meta, final NumericEntry e, int numericBlockShift, int indexBlockShift)
                throws IOException {
                e.indexMeta = DirectMonotonicReader.loadMeta(meta, 1 + ((e.numValues - 1) >>> numericBlockShift), indexBlockShift);
            }

            @Override
            public void readBlock(final DataInput input, final long[] values, int count) throws IOException {
                encoder.decode(input, values);
            }

            @Override
            public void readOrdinals(final DataInput input, final long[] values, int bitsPerOrd) throws IOException {
                encoder.decodeOrdinals(input, values, bitsPerOrd);
            }
        };
    }

    @Override
    protected AbstractTSDBDocValuesProducer createMergeInstance() {
        return new ES819TSDBDocValuesProducer(this);
    }
}
