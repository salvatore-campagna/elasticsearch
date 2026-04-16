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

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;

public class NGramInputTokenLimitIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(CommonAnalysisPlugin.class);
    }

    public void testEdgeNgramTruncationPreservesPerWordCoverage() {
        indicesAdmin().prepareCreate("test")
            .setSettings(
                Settings.builder()
                    .put("index.max_ngram_input_token_count", 2)
                    .put("analysis.analyzer.autocomplete.type", "custom")
                    .put("analysis.analyzer.autocomplete.tokenizer", "standard")
                    .put("analysis.analyzer.autocomplete.filter", "my_edge_ngram")
                    .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("analysis.filter.my_edge_ngram.min_gram", 1)
                    .put("analysis.filter.my_edge_ngram.max_gram", 10)
            )
            .setMapping("body", "type=text,analyzer=autocomplete,search_analyzer=standard")
            .get();

        prepareIndex("test").setId("1").setSource("body", "elasticsearch is great for search").get();
        indicesAdmin().prepareRefresh("test").get();

        // NOTE: with input limit of 2, only "elasticsearch" and "is" get n-gram expansion.
        // "great", "for", "search" are discarded entirely by the filter.

        // full n-gram coverage for "elasticsearch" (within limit)
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "e")), 1);
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "elast")), 1);
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "elasticsea")), 1);

        // "great" is beyond the input token limit, not searchable by prefix
        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "gre")), 0);
    }

    public void testIndexCreationSucceedsWithWideGramRange() {
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
            .setMapping("body", "type=text,analyzer=autocomplete")
            .get();

        prepareIndex("test").setId("1").setSource("body", "test document").get();
        indicesAdmin().prepareRefresh("test").get();

        assertHitCount(prepareSearch("test").setQuery(QueryBuilders.matchQuery("body", "test")), 1);
    }
}
