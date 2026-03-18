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
 * {@link AbstractTSDBDocValuesProducer#createNumericFieldReader}. The lifecycle is:
 * <ol>
 *   <li>{@link #read}: once per field during segment open, reads the full numeric
 *       entry metadata including value counts, ordinal detection, codec-specific header,
 *       offsets, and DISI metadata</li>
 *   <li>{@link #readBlock}: per block during iteration, decodes numeric values</li>
 *   <li>{@link #readOrdinals}: per block during iteration, decodes ordinal values</li>
 * </ol>
 *
 * @see NumericFieldWriter
 */
public interface NumericFieldReader {

    /**
     * Reads the full numeric field: entry metadata, ordinal detection, and DISI.
     *
     * @param meta              the metadata input stream
     * @param entry             the numeric entry to populate
     * @param numericBlockShift the block shift for numeric encoding
     */
    void read(IndexInput meta, AbstractTSDBDocValuesProducer.NumericEntry entry, int numericBlockShift) throws IOException;

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
