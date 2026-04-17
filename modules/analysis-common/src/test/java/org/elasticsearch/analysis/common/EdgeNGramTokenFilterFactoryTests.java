/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.analysis.AnalysisTestsHelper;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.ESTokenStreamTestCase;
import org.elasticsearch.test.index.IndexVersionUtils;

import java.io.IOException;
import java.io.StringReader;

import static org.apache.lucene.tests.analysis.BaseTokenStreamTestCase.assertTokenStreamContents;

public class EdgeNGramTokenFilterFactoryTests extends ESTokenStreamTestCase {

    public void testDefault() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        String source = "foo";
        String[] expected = new String[] { "f", "fo" };
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader(source));
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testInputTokenLimitTruncatesAtWordBoundary() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_input_token_count", 2)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", 1)
                .put("index.analysis.filter.my_edge_ngram.max_gram", 2)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("foo bar baz"));
        // edge_ngram(1,2) on "foo bar baz" produces: f, fo, b, ba, b, ba
        // with input token limit of 2, only "foo" and "bar" are expanded
        // "baz" n-grams are discarded entirely
        String[] expected = new String[] { "f", "fo", "b", "ba" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testInputTokenLimitNotAppliedForOlderIndices() throws IOException {
        int randomLimit = randomIntBetween(1, 3);
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put(
                    "index.version.created",
                    IndexVersionUtils.randomPreviousCompatibleVersion(IndexVersions.NGRAM_INPUT_TOKEN_COUNT_LIMIT)
                )
                .put("index.max_ngram_input_token_count", randomLimit)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", 1)
                .put("index.analysis.filter.my_edge_ngram.max_gram", 2)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("foo bar baz"));
        // no limit on old index versions regardless of setting value, all 6 tokens produced
        String[] expected = new String[] { "f", "fo", "b", "ba", "b", "ba" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testInputTokenLimitWithKeywordTokenizer() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_input_token_count", 1)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", 1)
                .put("index.analysis.filter.my_edge_ngram.max_gram", 3)
                .build(),
            new CommonAnalysisPlugin()
        );
        // NOTE: keyword tokenizer emits the entire input as a single token,
        // so with limit=1 all n-grams of that single token are produced
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        Tokenizer tokenizer = new KeywordTokenizer();
        tokenizer.setReader(new StringReader("hello"));
        String[] expected = new String[] { "h", "he", "hel" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testInputTokenLimitWithWhitespaceTokenizer() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_input_token_count", 1)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", 1)
                .put("index.analysis.filter.my_edge_ngram.max_gram", 3)
                .build(),
            new CommonAnalysisPlugin()
        );
        // NOTE: whitespace tokenizer treats "hello-world" as 1 token (no split on hyphen),
        // while standard tokenizer would produce 2 tokens
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader("hello-world foo"));
        // limit=1: only "hello-world" (first token) gets n-grams, "foo" is discarded
        String[] expected = new String[] { "h", "he", "hel" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testPreserveOriginal() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.preserve_original", true)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        String source = "foo";
        String[] expected = new String[] { "f", "fo", "foo" };
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader(source));
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testSingleLongTokenBoundedByMaxGram() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_input_token_count", 1)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", 1)
                .put("index.analysis.filter.my_edge_ngram.max_gram", 5)
                .build(),
            new CommonAnalysisPlugin()
        );
        // NOTE: a base64 string through a keyword tokenizer is 1 input token.
        // The input token limit does not cap n-grams within a single token,
        // but edge_ngram output is naturally bounded by max_gram regardless
        // of input length: a 100-char token still produces only max_gram edge n-grams.
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        final Tokenizer tokenizer = new KeywordTokenizer();
        final String base64Token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9";
        tokenizer.setReader(new StringReader(base64Token));
        String[] expected = new String[] { "e", "ey", "eyJ", "eyJh", "eyJhb" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }
}
