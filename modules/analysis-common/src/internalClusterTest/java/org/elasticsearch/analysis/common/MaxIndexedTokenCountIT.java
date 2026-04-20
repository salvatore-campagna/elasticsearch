/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;

public class MaxIndexedTokenCountIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(CommonAnalysisPlugin.class);
    }

    public void testTokenTruncationAffectsSearch() {
        indicesAdmin().prepareCreate("test")
            .setSettings(
                Settings.builder()
                    .put("index.max_indexed_token_count", 2)
                    .put("analysis.analyzer.autocomplete.type", "custom")
                    .put("analysis.analyzer.autocomplete.tokenizer", "standard")
                    .put("analysis.analyzer.autocomplete.filter", "my_edge_ngram")
                    .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("analysis.filter.my_edge_ngram.min_gram", 1)
                    .put("analysis.filter.my_edge_ngram.max_gram", 10)
            )
            .setMapping("body", "type=text,analyzer=autocomplete,search_analyzer=standard")
            .get();

        prepareIndex("test").setId("1").setSource("body", "may the force be with you").get();
        indicesAdmin().prepareRefresh("test").get();

        // "may" and "the" are within the limit
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "ma")), 1);
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "th")), 1);

        // "force" is beyond the limit
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "for")), 0);
    }

    public void testLargeDocumentWithRandomLimit() {
        int limit = randomIntBetween(5000, 6000);
        int totalWords = 8000;
        int maxGram = 10;

        indicesAdmin().prepareCreate("test")
            .setSettings(
                Settings.builder()
                    .put("index.max_indexed_token_count", limit)
                    .put("analysis.analyzer.autocomplete.type", "custom")
                    .put("analysis.analyzer.autocomplete.tokenizer", "standard")
                    .put("analysis.analyzer.autocomplete.filter", "my_edge_ngram")
                    .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("analysis.filter.my_edge_ngram.min_gram", 1)
                    .put("analysis.filter.my_edge_ngram.max_gram", maxGram)
            )
            .setMapping("body", "type=text,analyzer=autocomplete,search_analyzer=standard")
            .get();

        // "w0 w1 w2 ... w7999": each token is short enough to be within max_gram=10
        final String body = IntStream.range(0, totalWords).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        prepareIndex("test").setId("1").setSource("body", body).get();
        indicesAdmin().prepareRefresh("test").get();

        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "w")), 1);

        int wordWithinLimit = randomIntBetween(0, limit - 1);
        final String withinToken = "w" + wordWithinLimit;
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", withinToken)), 1);

        int wordBeyondLimit = randomIntBetween(limit + 100, totalWords - 1);
        final String beyondToken = "w" + wordBeyondLimit;
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", beyondToken)), 0);
    }

    public void testNoLimitByDefault() {
        indicesAdmin().prepareCreate("test")
            .setSettings(
                Settings.builder()
                    .put("analysis.analyzer.autocomplete.type", "custom")
                    .put("analysis.analyzer.autocomplete.tokenizer", "standard")
                    .put("analysis.analyzer.autocomplete.filter", "my_edge_ngram")
                    .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("analysis.filter.my_edge_ngram.min_gram", 1)
                    .put("analysis.filter.my_edge_ngram.max_gram", 20)
            )
            .setMapping("body", "type=text,analyzer=autocomplete,search_analyzer=standard")
            .get();

        prepareIndex("test").setId("1").setSource("body", "may the force be with you").get();
        indicesAdmin().prepareRefresh("test").get();

        // all words searchable with default (no limit)
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "for")), 1);
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "you")), 1);
    }

    public void testWorksWithStandardAnalyzer() {
        indicesAdmin().prepareCreate("test")
            .setSettings(Settings.builder().put("index.max_indexed_token_count", 3))
            .setMapping("body", "type=text")
            .get();

        prepareIndex("test").setId("1").setSource("body", "may the force be with you").get();
        indicesAdmin().prepareRefresh("test").get();

        // standard analyzer: "may", "the", "force" within limit (3 positions)
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "may")), 1);
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "force")), 1);

        // "be" is at position 4, beyond limit
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "be")), 0);
    }
}
