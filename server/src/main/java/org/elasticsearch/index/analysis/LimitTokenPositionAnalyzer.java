/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;

/**
 * An {@link AnalyzerWrapper} that limits the number of token positions emitted by the
 * wrapped analyzer. Once the position limit is reached, all remaining tokens (including
 * tokens at the same position, such as n-gram expansions or synonyms) are discarded.
 *
 * <p>This preserves complete token coverage per position. For example, with a limit of 2
 * applied to an analyzer that produces edge n-grams from "may the force":
 * <ul>
 *   <li>position 1 ("may"): m, ma, may (all emitted)</li>
 *   <li>position 2 ("the"): t, th, the (all emitted)</li>
 *   <li>position 3 ("force"): f, fo, for, forc, force (all discarded)</li>
 * </ul>
 *
 * <p>Unlike Lucene's {@link org.apache.lucene.analysis.miscellaneous.LimitTokenPositionFilter}
 * which tracks cumulative position values (and over-counts when stop filters increase the
 * position increment), this wrapper counts distinct input token boundaries (each
 * {@code positionIncrement > 0} is one boundary regardless of the increment value).
 */
public final class LimitTokenPositionAnalyzer extends AnalyzerWrapper {

    private final Analyzer delegate;
    private final int maxTokenPositions;

    /**
     * @param delegate          the analyzer to wrap
     * @param maxTokenPositions maximum number of input token positions to emit
     */
    public LimitTokenPositionAnalyzer(Analyzer delegate, int maxTokenPositions) {
        super(delegate.getReuseStrategy());
        this.delegate = delegate;
        this.maxTokenPositions = maxTokenPositions;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        return delegate;
    }

    @Override
    protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
        return new TokenStreamComponents(components.getSource(), new LimitTokenPositionFilter(components.getTokenStream()));
    }

    @Override
    public String toString() {
        return "LimitTokenPositionAnalyzer(" + delegate + ", maxTokenPositions=" + maxTokenPositions + ")";
    }

    private final class LimitTokenPositionFilter extends TokenFilter {

        private final PositionIncrementAttribute positionIncrementAttribute = addAttribute(PositionIncrementAttribute.class);
        private int positionCount;

        LimitTokenPositionFilter(TokenStream input) {
            super(input);
        }

        @Override
        public boolean incrementToken() throws IOException {
            while (input.incrementToken()) {
                if (positionIncrementAttribute.getPositionIncrement() > 0) {
                    positionCount++;
                }
                if (positionCount <= maxTokenPositions) {
                    return true;
                }
                return false;
            }
            return false;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            positionCount = 0;
        }
    }
}
