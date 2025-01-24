/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.elastic;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.EmptySecretSettings;
import org.elasticsearch.inference.EmptyTaskSettings;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.SecretSettings;
import org.elasticsearch.inference.TaskSettings;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.inference.external.action.ExecutableAction;
import org.elasticsearch.xpack.inference.external.action.elastic.ElasticInferenceServiceActionVisitor;
import org.elasticsearch.xpack.inference.services.ConfigurationParseContext;
import org.elasticsearch.xpack.inference.services.elasticsearch.ElserModels;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.xpack.inference.services.elastic.ElasticInferenceService.ELASTIC_INFERENCE_SERVICE_IDENTIFIER;

public class ElasticInferenceServiceSparseEmbeddingsModel extends ElasticInferenceServiceExecutableActionModel {

    private final URI uri;

    public ElasticInferenceServiceSparseEmbeddingsModel(
        String inferenceEntityId,
        TaskType taskType,
        String service,
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        Map<String, Object> secrets,
        ElasticInferenceServiceComponents elasticInferenceServiceComponents,
        ConfigurationParseContext context
    ) {
        this(
            inferenceEntityId,
            taskType,
            service,
            ElasticInferenceServiceSparseEmbeddingsServiceSettings.fromMap(serviceSettings, context),
            EmptyTaskSettings.INSTANCE,
            EmptySecretSettings.INSTANCE,
            elasticInferenceServiceComponents
        );
    }

    public ElasticInferenceServiceSparseEmbeddingsModel(
        ElasticInferenceServiceSparseEmbeddingsModel model,
        ElasticInferenceServiceSparseEmbeddingsServiceSettings serviceSettings
    ) {
        super(model, serviceSettings);
        this.uri = createUri();
    }

    ElasticInferenceServiceSparseEmbeddingsModel(
        String inferenceEntityId,
        TaskType taskType,
        String service,
        ElasticInferenceServiceSparseEmbeddingsServiceSettings serviceSettings,
        @Nullable TaskSettings taskSettings,
        @Nullable SecretSettings secretSettings,
        ElasticInferenceServiceComponents elasticInferenceServiceComponents
    ) {
        super(
            new ModelConfigurations(inferenceEntityId, taskType, service, serviceSettings, taskSettings),
            new ModelSecrets(secretSettings),
            serviceSettings,
            elasticInferenceServiceComponents
        );
        this.uri = createUri();
    }

    @Override
    public ExecutableAction accept(ElasticInferenceServiceActionVisitor visitor, Map<String, Object> taskSettings) {
        return visitor.create(this);
    }

    @Override
    public ElasticInferenceServiceSparseEmbeddingsServiceSettings getServiceSettings() {
        return (ElasticInferenceServiceSparseEmbeddingsServiceSettings) super.getServiceSettings();
    }

    public URI uri() {
        return uri;
    }

    private URI createUri() throws ElasticsearchStatusException {
        String modelId = getServiceSettings().modelId();
        String modelIdUriPath;

        switch (modelId) {
            case ElserModels.ELSER_V2_MODEL -> modelIdUriPath = "ELSERv2";
            default -> throw new ElasticsearchStatusException(
                String.format(
                    Locale.ROOT,
                    "Unsupported model [%s] for service [%s] and task type [%s]",
                    modelId,
                    ELASTIC_INFERENCE_SERVICE_IDENTIFIER,
                    TaskType.SPARSE_EMBEDDING
                ),
                RestStatus.BAD_REQUEST
            );
        }

        try {
            // TODO, consider transforming the base URL into a URI for better error handling.
            return new URI(
                elasticInferenceServiceComponents().elasticInferenceServiceUrl() + "/api/v1/embed/text/sparse/" + modelIdUriPath
            );
        } catch (URISyntaxException e) {
            throw new ElasticsearchStatusException(
                "Failed to create URI for service ["
                    + this.getConfigurations().getService()
                    + "] with taskType ["
                    + this.getTaskType()
                    + "] with model ["
                    + this.getServiceSettings().modelId()
                    + "]: "
                    + e.getMessage(),
                RestStatus.BAD_REQUEST,
                e
            );
        }
    }
}
