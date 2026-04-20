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
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountFilter;
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.CharsRef;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.lucene.tests.analysis.BaseTokenStreamTestCase.assertTokenStreamContents;

public class LimitTokenPositionAnalyzerTests extends ESTestCase {

    public void testLimitsTokenPositions() throws IOException {
        final Analyzer base = new WhitespaceAnalyzer();
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 2);
        assertTokenStreamContents(limited.tokenStream("field", "may the force be with you"), new String[] { "may", "the" });
    }

    public void testNoLimitWhenBelowThreshold() throws IOException {
        final Analyzer base = new WhitespaceAnalyzer();
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 100);
        assertTokenStreamContents(limited.tokenStream("field", "may the force"), new String[] { "may", "the", "force" });
    }

    public void testPreservesNgramCoveragePerPosition() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new StandardTokenizer();
                final TokenStream filter = new EdgeNGramTokenFilter(tokenizer, 1, 3, false);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 2);
        // "may the force": positions 1 and 2 get full n-gram coverage, position 3 discarded
        assertTokenStreamContents(limited.tokenStream("field", "may the force"), new String[] { "m", "ma", "may", "t", "th", "the" });
    }

    public void testStopFilterDoesNotInflatePositionCount() throws IOException {
        final Analyzer base = new StandardAnalyzer(org.apache.lucene.analysis.en.EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 2);
        // "the" and "is" removed by stop filter; "force" (posIncr=2) and "strong" (posIncr=2)
        // each count as 1 position despite the gap
        assertTokenStreamContents(limited.tokenStream("field", "the force is strong"), new String[] { "force", "strong" });
    }

    public void testSingleLongTokenFullyCovered() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new KeywordTokenizer();
                final TokenStream filter = new EdgeNGramTokenFilter(tokenizer, 1, 5, false);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
        // A base64 string through keyword tokenizer is 1 position, all n-grams emitted
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 1);
        final String base64Token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9";
        assertTokenStreamContents(limited.tokenStream("field", base64Token), new String[] { "e", "ey", "eyJ", "eyJh", "eyJhb" });
    }

    public void testRandomLimit() throws IOException {
        final Analyzer base = new WhitespaceAnalyzer();
        int limit = randomIntBetween(1, 5);
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, limit);
        final String[] words = new String[] { "may", "the", "force", "be", "with", "you" };
        final String[] expected = new String[Math.min(limit, words.length)];
        System.arraycopy(words, 0, expected, 0, expected.length);
        assertTokenStreamContents(limited.tokenStream("field", String.join(" ", words)), expected);
    }

    public void testTokenCountWithEdgeNgram() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new StandardTokenizer();
                final TokenStream filter = new EdgeNGramTokenFilter(tokenizer, 1, 5, false);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };

        // without limit: "may the force" produces 3+3+5 = 11 edge n-gram tokens
        List<String> unlimitedTokens = collectTokens(base, "may the force");
        assertEquals(11, unlimitedTokens.size());

        // with limit of 2 positions: only "may" (3 tokens) and "the" (3 tokens) = 6 tokens
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 2);
        List<String> limitedTokens = collectTokens(limited, "may the force");
        assertEquals(6, limitedTokens.size());
    }

    public void testTokenCountWithNgram() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new StandardTokenizer();
                final TokenStream filter = new NGramTokenFilter(tokenizer, 1, 2, false);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };

        // without limit: "ab cd" produces ngram(1,2) for each word
        // "ab" -> a, ab, b = 3 tokens; "cd" -> c, cd, d = 3 tokens; total = 6
        List<String> unlimitedTokens = collectTokens(base, "ab cd");
        assertEquals(6, unlimitedTokens.size());

        // with limit of 1 position: only "ab" -> a, ab, b = 3 tokens
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 1);
        List<String> limitedTokens = collectTokens(limited, "ab cd");
        assertEquals(3, limitedTokens.size());
        assertEquals(List.of("a", "ab", "b"), limitedTokens);
    }

    public void testPreservesSynonymsAtSamePosition() throws IOException {
        // "force" -> "power" synonym
        final SynonymMap.Builder builder = new SynonymMap.Builder();
        builder.add(new CharsRef("force"), new CharsRef("power"), true);
        final SynonymMap synonymMap = builder.build();

        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new StandardTokenizer();
                final TokenStream filter = new SynonymGraphFilter(tokenizer, synonymMap, true);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };

        // "may the force": with limit=2, only "may" and "the" pass through
        // "force" and its synonym "power" are both at position 3, both discarded
        final LimitTokenPositionAnalyzer limited = new LimitTokenPositionAnalyzer(base, 2);
        List<String> tokens = collectTokens(limited, "may the force");
        assertEquals(List.of("may", "the"), tokens);

        // with limit=3, "force" and "power" both at position 3, both emitted
        final LimitTokenPositionAnalyzer limited3 = new LimitTokenPositionAnalyzer(base, 3);
        List<String> tokens3 = collectTokens(limited3, "may the force");
        assertEquals(4, tokens3.size());
        assertTrue(tokens3.contains("may"));
        assertTrue(tokens3.contains("the"));
        assertTrue(tokens3.contains("force"));
        assertTrue(tokens3.contains("power"));
    }

    public void testUserLimitTokenCountFilterLowerThanOurs() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new WhitespaceTokenizer();
                final TokenStream limited = new LimitTokenCountFilter(tokenizer, 3);
                return new TokenStreamComponents(tokenizer, limited);
            }
        };
        final LimitTokenPositionAnalyzer wrapped = new LimitTokenPositionAnalyzer(base, 5);
        List<String> tokens = collectTokens(wrapped, "may the force be with you");
        assertEquals(List.of("may", "the", "force"), tokens);
    }

    public void testUserLimitTokenCountFilterHigherThanOurs() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new WhitespaceTokenizer();
                final TokenStream limited = new LimitTokenCountFilter(tokenizer, 100);
                return new TokenStreamComponents(tokenizer, limited);
            }
        };
        final LimitTokenPositionAnalyzer wrapped = new LimitTokenPositionAnalyzer(base, 2);
        List<String> tokens = collectTokens(wrapped, "may the force be with you");
        assertEquals(List.of("may", "the"), tokens);
    }

    public void testUserLimitTokenCountFilterWithNoSystemLimit() throws IOException {
        final Analyzer base = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new WhitespaceTokenizer();
                final TokenStream limited = new LimitTokenCountFilter(tokenizer, 3);
                return new TokenStreamComponents(tokenizer, limited);
            }
        };
        List<String> tokens = collectTokens(base, "may the force be with you");
        assertEquals(List.of("may", "the", "force"), tokens);
    }

    private List<String> collectTokens(Analyzer analyzer, String text) throws IOException {
        final List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("field", text)) {
            final CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(termAtt.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
