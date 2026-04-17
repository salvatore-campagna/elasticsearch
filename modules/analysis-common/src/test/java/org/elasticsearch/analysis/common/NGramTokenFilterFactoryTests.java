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

public class NGramTokenFilterFactoryTests extends ESTokenStreamTestCase {

    public void testDefault() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.analysis.filter.my_ngram.type", "ngram")
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_ngram");
        String source = "foo";
        String[] expected = new String[] { "f", "fo", "o", "oo", "o" };
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader(source));
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testInputTokenLimitTruncatesAtWordBoundary() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_input_token_count", 1)
                .put("index.analysis.filter.my_ngram.type", "ngram")
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_ngram");
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("foo bar"));
        // ngram(1,2) on "foo bar" with input limit 1: only "foo" is expanded
        // "foo" produces: f, fo, o, oo, o = 5 tokens
        // "bar" is discarded entirely
        String[] expected = new String[] { "f", "fo", "o", "oo", "o" };
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
                .put("index.analysis.filter.my_ngram.type", "ngram")
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_ngram");
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("foo bar"));
        // no limit on old index versions regardless of setting value
        String[] expected = new String[] { "f", "fo", "o", "oo", "o", "b", "ba", "a", "ar", "r" };
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }

    public void testPreserveOriginal() throws IOException {
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.analysis.filter.my_ngram.type", "ngram")
                .put("index.analysis.filter.my_ngram.preserve_original", true)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_ngram");
        String source = "foo";
        String[] expected = new String[] { "f", "fo", "o", "oo", "o", "foo" };
        Tokenizer tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader(source));
        assertTokenStreamContents(tokenFilter.create(tokenizer), expected);
    }
}
