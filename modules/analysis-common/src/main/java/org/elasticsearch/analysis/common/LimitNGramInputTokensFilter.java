/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;

/**
 * A token filter that limits the number of original input tokens whose n-gram expansions
 * are passed through. It detects input token boundaries by checking the
 * {@link PositionIncrementAttribute}: a position increment greater than zero signals a new
 * input token. N-gram tokens (which share position with their source) are passed through as
 * long as the source token is within the limit.
 *
 * <p>This preserves complete n-gram coverage for tokens within the limit. For example, with
 * {@code maxInputTokens=2} and edge_ngram(1,4) applied to "quick brown fox":
 * <ul>
 *   <li>"quick" produces: q, qu, qui, quic, quick (all passed through)</li>
 *   <li>"brown" produces: b, br, bro, brow, brown (all passed through)</li>
 *   <li>"fox" produces: f, fo, fox (all discarded)</li>
 * </ul>
 *
 * <p>A warning is logged when the limit is reached. Controlled by the
 * {@code index.max_ngram_input_token_count} index setting.
 */
final class LimitNGramInputTokensFilter extends TokenFilter {

    private static final Logger logger = LogManager.getLogger(LimitNGramInputTokensFilter.class);

    private final int maxInputTokens;
    private final String filterName;
    private int inputTokenCount;

    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);

    /**
     * @param input          the token stream from the upstream n-gram filter
     * @param maxInputTokens maximum number of input tokens to allow n-gram expansion for
     * @param filterName     the name of the upstream filter, used in the warning message
     */
    LimitNGramInputTokensFilter(TokenStream input, int maxInputTokens, String filterName) {
        super(input);
        this.maxInputTokens = maxInputTokens;
        this.filterName = filterName;
    }

    /**
     * Returns the next token from the upstream n-gram filter if the originating input token
     * is within the limit. Once the limit is reached, consumes and discards all remaining
     * tokens from the stream and returns {@code false}.
     *
     * @return {@code true} if a token was emitted, {@code false} if the stream is exhausted
     *         or the input token limit was reached
     */
    @Override
    public boolean incrementToken() throws IOException {
        while (input.incrementToken()) {
            if (posIncrAtt.getPositionIncrement() > 0) {
                inputTokenCount++;
            }

            if (inputTokenCount <= maxInputTokens) {
                return true;
            }

            // TODO: this warning needs rate limiting to avoid flooding logs when many
            // documents exceed the limit.
            logger.warn(
                "the [{}] token filter processed more than [{}] input tokens. "
                    + "N-gram expansion for remaining tokens has been skipped. "
                    + "This limit can be configured using the [index.max_ngram_input_token_count] index setting.",
                filterName,
                maxInputTokens
            );
            return false;
        }
        return false;
    }

    /** Resets the input token counter for the next field value. */
    @Override
    public void reset() throws IOException {
        super.reset();
        inputTokenCount = 0;
    }
}
