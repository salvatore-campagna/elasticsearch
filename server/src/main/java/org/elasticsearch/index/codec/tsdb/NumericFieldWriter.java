/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;

/**
 * Writes numeric doc values for a single field during segment writing.
 *
 * <p>Each codec version provides its own implementation via
 * {@link AbstractTSDBDocValuesConsumer#createNumericFieldWriter}. The lifecycle is:
 * <ol>
 *   <li>{@link #write}: once per field, writes the full numeric field including
 *       stats, ordinal detection, codec-specific metadata, block data, offsets, and DISI.
 *       Calls {@link #writeBlock} and {@link #writeOrdinals} for each block.</li>
 *   <li>{@link #writeBlock}: per block, encodes numeric values</li>
 *   <li>{@link #writeOrdinals}: per block, encodes ordinal values</li>
 * </ol>
 *
 * @see NumericFieldReader
 */
public interface NumericFieldWriter {

    /**
     * Writes the full numeric field: entry metadata, block data, and DISI.
     *
     * @param field               the field being written
     * @param valuesSource        the source of doc values
     * @param maxOrd              the maximum ordinal value, or -1 if not using ordinals
     * @param offsetsAccumulator  accumulator for sorted-numeric offsets, or null
     * @return array of [numDocsWithValue, numValues]
     */
    long[] write(FieldInfo field, TsdbDocValuesProducer valuesSource, long maxOrd, OffsetsAccumulator offsetsAccumulator)
        throws IOException;

    /**
     * Encodes a block of numeric values.
     *
     * @param values    the values to encode; only the first {@code blockSize} entries are valid
     * @param blockSize the number of valid values in the array
     * @param data      the output to write compressed bytes to
     */
    void writeBlock(long[] values, int blockSize, IndexOutput data) throws IOException;

    /**
     * Encodes a block of ordinal values using a fixed number of bits per ordinal.
     *
     * @param values     the ordinal values to encode
     * @param data       the output to write compressed bytes to
     * @param bitsPerOrd the number of bits per ordinal
     */
    void writeOrdinals(long[] values, IndexOutput data, int bitsPerOrd) throws IOException;
}
