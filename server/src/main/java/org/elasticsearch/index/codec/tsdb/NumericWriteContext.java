/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;

/**
 * Shared write-path state for numeric field writers. Created once per segment
 * by the consumer and passed to {@link NumericFieldWriter} instances.
 */
public record NumericWriteContext(
    IndexOutput meta,
    IndexOutput data,
    Directory dir,
    IOContext ioContext,
    int maxDoc,
    int blockSize,
    int primarySortFieldNumber,
    TSDBDocValuesFormatConfig formatConfig
) {}
