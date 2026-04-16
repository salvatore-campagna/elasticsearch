/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.elasticsearch.action.admin.indices.template.put.TransportPutComposableIndexTemplateAction;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Collection;
import java.util.List;

import static org.hamcrest.Matchers.containsString;

public class EdgeNGramMaxDiffValidationIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(CommonAnalysisPlugin.class);
    }

    public void testIndexCreationFailsWithExcessiveEdgeNgramDiff() {
        int minGram = randomIntBetween(1, 5);
        int maxGram = minGram + randomIntBetween(2, 10);
        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> indicesAdmin().prepareCreate("test")
                .setSettings(
                    Settings.builder()
                        .put("analysis.analyzer.my_analyzer.type", "custom")
                        .put("analysis.analyzer.my_analyzer.tokenizer", "standard")
                        .put("analysis.analyzer.my_analyzer.filter", "my_edge_ngram")
                        .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                        .put("analysis.filter.my_edge_ngram.min_gram", minGram)
                        .put("analysis.filter.my_edge_ngram.max_gram", maxGram)
                )
                .get()
        );
        assertThat(ex.getMessage(), containsString("The difference between max_gram and min_gram in EdgeNGram Tokenizer"));
    }

    public void testIndexCreationSucceedsWithCustomMaxNgramDiff() {
        int minGram = randomIntBetween(1, 5);
        int ngramDiff = randomIntBetween(2, 10);
        int maxGram = minGram + ngramDiff;
        indicesAdmin().prepareCreate("test")
            .setSettings(
                Settings.builder()
                    .put("index.max_ngram_diff", ngramDiff)
                    .put("analysis.analyzer.my_analyzer.type", "custom")
                    .put("analysis.analyzer.my_analyzer.tokenizer", "standard")
                    .put("analysis.analyzer.my_analyzer.filter", "my_edge_ngram")
                    .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                    .put("analysis.filter.my_edge_ngram.min_gram", minGram)
                    .put("analysis.filter.my_edge_ngram.max_gram", maxGram)
            )
            .get();
    }

    public void testIndexTemplateValidationFailsWithExcessiveEdgeNgramDiff() {
        int minGram = randomIntBetween(1, 5);
        int maxGram = minGram + randomIntBetween(2, 10);

        TransportPutComposableIndexTemplateAction.Request templateRequest = new TransportPutComposableIndexTemplateAction.Request(
            "test-template"
        );
        templateRequest.indexTemplate(
            ComposableIndexTemplate.builder()
                .indexPatterns(List.of("test-ds-*"))
                .template(
                    new Template(
                        Settings.builder()
                            .put("analysis.analyzer.my_analyzer.type", "custom")
                            .put("analysis.analyzer.my_analyzer.tokenizer", "standard")
                            .put("analysis.analyzer.my_analyzer.filter", "my_edge_ngram")
                            .put("analysis.filter.my_edge_ngram.type", "edge_ngram")
                            .put("analysis.filter.my_edge_ngram.min_gram", minGram)
                            .put("analysis.filter.my_edge_ngram.max_gram", maxGram)
                            .build(),
                        null,
                        null
                    )
                )
                .dataStreamTemplate(new ComposableIndexTemplate.DataStreamTemplate(false, false))
                .build()
        );

        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> client().execute(TransportPutComposableIndexTemplateAction.TYPE, templateRequest).actionGet()
        );
        assertThat(ex.getCause().getMessage(), containsString("The difference between max_gram and min_gram in EdgeNGram Tokenizer"));
    }
}
