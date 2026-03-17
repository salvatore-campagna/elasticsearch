/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;

import java.io.IOException;

/**
 * Reads and decodes numeric doc values for a single field during segment reading.
 *
 * <p>Each codec version provides its own implementation via
 * {@link AbstractTSDBDocValuesProducer#createNumericFieldReader}. A single instance
 * is created per field and used for both header reading and block decoding:
 * <ol>
 *   <li>{@link #readHeader}: once per field during segment open, reads codec-specific metadata</li>
 *   <li>{@link #readBlock}: per block during iteration, decodes numeric values</li>
 *   <li>{@link #readOrdinals}: per block during iteration, decodes ordinal values</li>
 * </ol>
 *
 * @see NumericFieldWriter
 */
public interface NumericFieldReader {

    /**
     * Reads codec-specific numeric field metadata.
     *
     * @param meta              the metadata input stream
     * @param entry             the numeric entry to populate with index metadata
     * @param numericBlockShift the block shift for numeric encoding
     * @param indexBlockShift   the block shift for the direct monotonic index
     */
    void readHeader(IndexInput meta, AbstractTSDBDocValuesProducer.NumericEntry entry, int numericBlockShift, int indexBlockShift)
        throws IOException;

    /**
     * Decodes a block of numeric values.
     *
     * @param input  the input to read compressed bytes from
     * @param values the output array to fill with decoded values
     * @param count  the number of values to decode
     */
    void readBlock(DataInput input, long[] values, int count) throws IOException;

    /**
     * Decodes a block of ordinal values.
     *
     * @param input      the input to read compressed bytes from
     * @param values     the output array to fill with decoded ordinal values
     * @param bitsPerOrd the number of bits per ordinal
     */
    void readOrdinals(DataInput input, long[] values, int bitsPerOrd) throws IOException;
}
