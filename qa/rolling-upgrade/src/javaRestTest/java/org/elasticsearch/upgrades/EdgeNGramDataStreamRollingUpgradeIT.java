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

    public EdgeNGramDataStreamRollingUpgradeIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    public void testDataStreamWithEdgeNgramAnalyzer() throws Exception {
        if (isOldCluster()) {
            Request putTemplate = new Request("PUT", "/_index_template/" + TEMPLATE_NAME);
            putTemplate.setJsonEntity(INDEX_TEMPLATE);
            client().performRequest(putTemplate);

            Request createDataStream = new Request("PUT", "/_data_stream/" + DATA_STREAM_NAME);
            client().performRequest(createDataStream);

            Request indexDoc = new Request("POST", "/" + DATA_STREAM_NAME + "/_doc");
            indexDoc.addParameter("refresh", "true");
            indexDoc.setJsonEntity("""
                {"@timestamp": "2026-01-01T00:00:00Z", "body": "test document"}
                """);
            client().performRequest(indexDoc);
        } else if (isUpgradedCluster()) {
            Request rollover = new Request("POST", "/" + DATA_STREAM_NAME + "/_rollover");
            ResponseException ex = expectThrows(ResponseException.class, () -> client().performRequest(rollover));
            assertThat(
                ex.getMessage(),
                containsString("The difference between max_gram and min_gram in EdgeNGram Tokenizer must be less than or equal to")
            );

            Request deleteDataStream = new Request("DELETE", "/_data_stream/" + DATA_STREAM_NAME);
            client().performRequest(deleteDataStream);

            Request deleteTemplate = new Request("DELETE", "/_index_template/" + TEMPLATE_NAME);
            client().performRequest(deleteTemplate);
        }
    }
}
