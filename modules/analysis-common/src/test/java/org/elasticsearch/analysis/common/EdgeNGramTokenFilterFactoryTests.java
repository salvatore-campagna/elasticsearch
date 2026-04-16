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
import org.elasticsearch.index.IndexSettings;
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

    public void testMaxNGramDiffException() throws IOException {
        int maxAllowedNgramDiff = IndexSettings.MAX_NGRAM_DIFF_SETTING.getDefault(Settings.EMPTY);
        int minGram = randomIntBetween(1, 5);
        int maxGram = minGram + maxAllowedNgramDiff + randomIntBetween(1, 10);
        int ngramDiff = maxGram - minGram;
        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> AnalysisTestsHelper.createTestAnalysisFromSettings(
                Settings.builder()
                    .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                    .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("index.analysis.filter.my_edge_ngram.min_gram", minGram)
                    .put("index.analysis.filter.my_edge_ngram.max_gram", maxGram)
                    .build(),
                new CommonAnalysisPlugin()
            )
        );
        assertEquals(
            "The difference between max_gram and min_gram in EdgeNGram Tokenizer must be less than or equal to: ["
                + maxAllowedNgramDiff
                + "] but was ["
                + ngramDiff
                + "]. This limit can be set by changing the ["
                + IndexSettings.MAX_NGRAM_DIFF_SETTING.getKey()
                + "] index level setting.",
            ex.getMessage()
        );
    }

    public void testMaxNGramDiffWithCustomSetting() throws IOException {
        int minGram = randomIntBetween(1, 5);
        int ngramDiff = randomIntBetween(2, 10);
        int maxGram = minGram + ngramDiff;
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put("index.max_ngram_diff", ngramDiff)
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", minGram)
                .put("index.analysis.filter.my_edge_ngram.max_gram", maxGram)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        assertNotNull(tokenFilter);
    }

    public void testMaxNGramDiffNotEnforcedForOlderIndices() throws IOException {
        int minGram = randomIntBetween(1, 5);
        int maxGram = minGram + randomIntBetween(2, 10);
        ESTestCase.TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(
            Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put(
                    "index.version.created",
                    IndexVersionUtils.randomPreviousCompatibleVersion(IndexVersions.EDGE_NGRAM_MAX_DIFF_VALIDATION)
                )
                .put("index.analysis.filter.my_edge_ngram.type", "edge_ngram")
                .put("index.analysis.filter.my_edge_ngram.min_gram", minGram)
                .put("index.analysis.filter.my_edge_ngram.max_gram", maxGram)
                .build(),
            new CommonAnalysisPlugin()
        );
        TokenFilterFactory tokenFilter = analysis.tokenFilter.get("my_edge_ngram");
        assertNotNull(tokenFilter);
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
}
