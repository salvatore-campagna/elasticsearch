/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.EmptyDocValuesProducer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;

import java.io.IOException;

public class TsdbDocValuesProducer extends EmptyDocValuesProducer {

    public final DocValuesConsumerUtil.MergeStats mergeStats;
    private final DocValuesProducer actual;

    public TsdbDocValuesProducer(DocValuesConsumerUtil.MergeStats mergeStats) {
        this.mergeStats = mergeStats;
        this.actual = null;
    }

    public TsdbDocValuesProducer(DocValuesProducer valuesProducer) {
        if (valuesProducer instanceof TsdbDocValuesProducer tsdb) {
            mergeStats = tsdb.mergeStats;
        } else {
            mergeStats = DocValuesConsumerUtil.UNSUPPORTED;
        }
        this.actual = valuesProducer;
    }

    @Override
    public NumericDocValues getNumeric(FieldInfo field) throws IOException {
        if (actual != null) {
            return actual.getNumeric(field);
        } else {
            return super.getNumeric(field);
        }
    }

    @Override
    public BinaryDocValues getBinary(FieldInfo field) throws IOException {
        if (actual != null) {
            return actual.getBinary(field);
        } else {
            return super.getBinary(field);
        }
    }

    @Override
    public SortedDocValues getSorted(FieldInfo field) throws IOException {
        if (actual != null) {
            return actual.getSorted(field);
        } else {
            return super.getSorted(field);
        }
    }

    @Override
    public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
        if (actual != null) {
            return actual.getSortedSet(field);
        } else {
            return super.getSortedSet(field);
        }
    }

    @Override
    public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
        if (actual != null) {
            return actual.getSortedNumeric(field);
        } else {
            return super.getSortedNumeric(field);
        }
    }
}
