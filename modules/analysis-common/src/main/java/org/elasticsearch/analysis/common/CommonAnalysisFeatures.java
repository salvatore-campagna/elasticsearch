/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.elasticsearch.features.FeatureSpecification;
import org.elasticsearch.features.NodeFeature;

import java.util.Set;

public class CommonAnalysisFeatures implements FeatureSpecification {

    public static final NodeFeature EDGE_NGRAM_MAX_DIFF_VALIDATION = new NodeFeature("analysis.edge_ngram.max_ngram_diff_validation");

    @Override
    public Set<NodeFeature> getTestFeatures() {
        return Set.of(EDGE_NGRAM_MAX_DIFF_VALIDATION);
    }
}
