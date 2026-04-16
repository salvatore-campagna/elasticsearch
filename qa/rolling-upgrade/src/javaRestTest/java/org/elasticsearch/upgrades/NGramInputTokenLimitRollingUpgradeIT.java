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

import java.io.IOException;

public class NGramInputTokenLimitRollingUpgradeIT extends AbstractRollingUpgradeTestCase {

    private static final String DATA_STREAM_NAME = "ngram-input-limit-test-ds";

    private static final String INDEX_TEMPLATE = """
        {
            "index_patterns": ["ngram-input-limit-test-ds*"],
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
                        "body": { "type": "text", "analyzer": "autocomplete", "search_analyzer": "standard" }
                    }
                }
            }
        }""";

    public NGramInputTokenLimitRollingUpgradeIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    public void testRolloverSucceedsWithEdgeNgramAfterUpgrade() throws Exception {
        if (isOldCluster()) {
            final Request putTemplate = new Request("PUT", "/_index_template/ngram-input-limit-template");
            putTemplate.setJsonEntity(INDEX_TEMPLATE);
            client().performRequest(putTemplate);

            final Request createDataStream = new Request("PUT", "/_data_stream/" + DATA_STREAM_NAME);
            client().performRequest(createDataStream);

            indexDocument("old cluster document");

            final Request rollover = new Request("POST", "/" + DATA_STREAM_NAME + "/_rollover");
            client().performRequest(rollover);
        } else if (isUpgradedCluster()) {
            indexDocument("upgraded cluster document");

            final Request rollover = new Request("POST", "/" + DATA_STREAM_NAME + "/_rollover");
            client().performRequest(rollover);

            final Request deleteDataStream = new Request("DELETE", "/_data_stream/" + DATA_STREAM_NAME);
            client().performRequest(deleteDataStream);

            final Request deleteTemplate = new Request("DELETE", "/_index_template/ngram-input-limit-template");
            client().performRequest(deleteTemplate);
        }
    }

    private void indexDocument(String body) throws IOException {
        final Request indexDoc = new Request("POST", "/" + DATA_STREAM_NAME + "/_doc");
        indexDoc.addParameter("refresh", "true");
        indexDoc.setJsonEntity("{\"@timestamp\": \"2026-01-01T00:00:00Z\", \"body\": \"" + body + "\"}");
        client().performRequest(indexDoc);
    }
}
