/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.upgrades;

import com.carrotsearch.randomizedtesting.annotations.Name;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;

import static org.hamcrest.Matchers.containsString;

public class EdgeNGramDataStreamRollingUpgradeIT extends AbstractRollingUpgradeTestCase {

    private static final String TEMPLATE_NAME = "edge-ngram-test-template";
    private static final String DATA_STREAM_NAME = "edge-ngram-test-ds";

    private static final String INDEX_TEMPLATE = """
        {
            "index_patterns": ["edge-ngram-test-ds*"],
            "data_stream": {},
            "template": {
                "settings": {
                    "analysis": {
                        "analyzer": {
                            "autocomplete": {
                                "type": "custom",
                                "tokenizer": "standard",
                                "filter": ["lowercase", "autocomplete_filter"]
                            }
                        },
                        "filter": {
                            "autocomplete_filter": {
                                "type": "edge_ngram",
                                "min_gram": 1,
                                "max_gram": 20
                            }
                        }
                    }
                },
                "mappings": {
                    "properties": {
                        "@timestamp": { "type": "date" },
                        "body": { "type": "text", "analyzer": "autocomplete" }
                    }
                }
            }
        }""";

    private static final String VALIDATION_ERROR = "The difference between max_gram and min_gram in EdgeNGram Tokenizer "
        + "must be less than or equal to";

    public EdgeNGramDataStreamRollingUpgradeIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    public void testDataStreamWithEdgeNgramAnalyzer() throws Exception {
        final boolean oldClusterHasValidation = oldClusterHasFeature("analysis.edge_ngram.max_ngram_diff_validation");

        if (isOldCluster()) {
            final Request putTemplate = new Request("PUT", "/_index_template/" + TEMPLATE_NAME);
            putTemplate.setJsonEntity(INDEX_TEMPLATE);

            if (oldClusterHasValidation) {
                final ResponseException ex = expectThrows(ResponseException.class, () -> client().performRequest(putTemplate));
                assertThat(ex.getMessage(), containsString(VALIDATION_ERROR));
            } else {
                client().performRequest(putTemplate);

                final Request createDataStream = new Request("PUT", "/_data_stream/" + DATA_STREAM_NAME);
                client().performRequest(createDataStream);

                final Request indexDoc = new Request("POST", "/" + DATA_STREAM_NAME + "/_doc");
                indexDoc.addParameter("refresh", "true");
                indexDoc.setJsonEntity("""
                    {"@timestamp": "2026-01-01T00:00:00Z", "body": "test document"}
                    """);
                client().performRequest(indexDoc);

                final Request rollover = new Request("POST", "/" + DATA_STREAM_NAME + "/_rollover");
                client().performRequest(rollover);
            }
        } else if (isUpgradedCluster()) {
            if (oldClusterHasValidation) {
                // Template creation failed on the old cluster, nothing to rollover.
                // Validate that template creation still fails on the upgraded cluster.
                final Request putTemplate = new Request("PUT", "/_index_template/" + TEMPLATE_NAME);
                putTemplate.setJsonEntity(INDEX_TEMPLATE);
                final ResponseException ex = expectThrows(ResponseException.class, () -> client().performRequest(putTemplate));
                assertThat(ex.getMessage(), containsString(VALIDATION_ERROR));
            } else {
                // Template was created on the old cluster without validation.
                // Rollover fails on the upgraded cluster because the new backing
                // index triggers validation.
                final Request rollover = new Request("POST", "/" + DATA_STREAM_NAME + "/_rollover");
                final ResponseException ex = expectThrows(ResponseException.class, () -> client().performRequest(rollover));
                assertThat(ex.getMessage(), containsString(VALIDATION_ERROR));

                final Request deleteDataStream = new Request("DELETE", "/_data_stream/" + DATA_STREAM_NAME);
                client().performRequest(deleteDataStream);

                final Request deleteTemplate = new Request("DELETE", "/_index_template/" + TEMPLATE_NAME);
                client().performRequest(deleteTemplate);
            }
        }
    }
}
