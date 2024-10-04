/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.subphase;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.IgnoredFieldMapper;
import org.elasticsearch.index.mapper.IgnoredSourceFieldMapper;
import org.elasticsearch.index.mapper.LegacyTypeFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.fetch.FetchContext;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.fetch.StoredFieldsContext;
import org.elasticsearch.search.fetch.StoredFieldsSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fetch sub-phase for high-level field retrieval. Given a list of fields, it
 * retrieves the field values through the relevant {@link org.elasticsearch.index.mapper.ValueFetcher}
 * and returns them as document fields.
 */
public final class FetchFieldsPhase implements FetchSubPhase {

    private static final List<FieldAndFormat> DEFAULT_METADATA_FIELDS = List.of(
        new FieldAndFormat(IgnoredFieldMapper.NAME, null),
        new FieldAndFormat(RoutingFieldMapper.NAME, null),
        // will only be fetched when mapped (older archived indices)
        new FieldAndFormat(LegacyTypeFieldMapper.NAME, null)
    );

    @Override
    public FetchSubPhaseProcessor getProcessor(FetchContext fetchContext) {
        final FetchFieldsContext fetchFieldsContext = fetchContext.fetchFieldsContext();
        final StoredFieldsContext storedFieldsContext = fetchContext.storedFieldsContext();

        boolean fetchStoredFields = storedFieldsContext != null && storedFieldsContext.fetchFields();
        if (fetchFieldsContext == null && fetchStoredFields == false) {
            return null;
        }

        final SearchExecutionContext searchExecutionContext = fetchContext.getSearchExecutionContext();
        final FieldFetcher fieldFetcher = fetchFieldsContext == null ? null
            : fetchFieldsContext.fields() == null ? null
            : fetchFieldsContext.fields().isEmpty() ? null
            : FieldFetcher.create(searchExecutionContext, fetchFieldsContext.fields());

        final FieldFetcher metadataFieldFetcher = createMetadataFieldFetcher(
            searchExecutionContext,
            storedFieldsContext,
            fetchFieldsContext
        );
        return new FetchSubPhaseProcessor() {
            @Override
            public void setNextReader(LeafReaderContext readerContext) {
                if (fieldFetcher != null) {
                    fieldFetcher.setNextReader(readerContext);
                }
                metadataFieldFetcher.setNextReader(readerContext);
            }

            @Override
            public StoredFieldsSpec storedFieldsSpec() {
                if (fieldFetcher != null) {
                    return metadataFieldFetcher.storedFieldsSpec().merge(fieldFetcher.storedFieldsSpec());
                }
                return metadataFieldFetcher.storedFieldsSpec();
            }

            @Override
            public void process(HitContext hitContext) throws IOException {
                final Map<String, DocumentField> fields = fieldFetcher != null
                    ? fieldFetcher.fetch(hitContext.source(), hitContext.docId())
                    : Collections.emptyMap();
                final Map<String, DocumentField> metadataFields = metadataFieldFetcher.fetch(hitContext.source(), hitContext.docId());
                hitContext.hit().addDocumentFields(fields, metadataFields);
            }
        };
    }

    private static FieldFetcher createMetadataFieldFetcher(
        final SearchExecutionContext sec,
        final StoredFieldsContext storedFieldsContext,
        final FetchFieldsContext fetchFieldsContext
    ) {
        final Set<FieldAndFormat> metadataFields = new HashSet<>(DEFAULT_METADATA_FIELDS);

        // If a field is requested both via 'fields' and 'stored_fields', the field will be fetched as if requested by 'fields'.
        // We check the FetchFieldsContext first for this reason, as we prefer going through the ValueFetcher interface.
        if (fetchFieldsContext != null && fetchFieldsContext.fields() != null && fetchFieldsContext.fields().isEmpty() == false) {
            // Collect metadata fields requested via 'fields'
            final List<String> fetchFieldNames = fieldAndFormatsToFieldNames(fetchFieldsContext.fields());
            metadataFields.addAll(getMetadataFields(fetchFieldNames, sec));
        }
        if (storedFieldsContext != null
            && storedFieldsContext.fieldNames() != null
            && storedFieldsContext.fieldNames().isEmpty() == false) {
            // Collect metadata fields requested via 'stored_fields'
            final List<String> storedFieldNames = storedFieldsContext.fieldNames();
            metadataFields.addAll(getMetadataFields(storedFieldNames, sec));
        }

        return FieldFetcher.create(sec, metadataFields);
    }

    private static List<String> fieldAndFormatsToFieldNames(final List<FieldAndFormat> fieldAndFormats) {
        final List<String> fieldNames = new ArrayList<>();
        for (FieldAndFormat fieldAndFormat : fieldAndFormats) {
            fieldNames.add(fieldAndFormat.field);
        }
        return fieldNames;
    }

    private static Set<FieldAndFormat> getMetadataFields(final List<String> fields, final SearchExecutionContext searchExecutionContext) {
        final Set<FieldAndFormat> metadataFields = new HashSet<>(DEFAULT_METADATA_FIELDS);
        for (final String field : fields) {
            final Set<String> matchingFieldNames = searchExecutionContext.getMatchingFieldNames(field);
            for (final String matchingFieldName : matchingFieldNames) {
                if (SourceFieldMapper.NAME.equals(matchingFieldName) || IdFieldMapper.NAME.equals(matchingFieldName)) {
                    continue;
                }
                if ("*".equals(field) && IgnoredSourceFieldMapper.NAME.equals(matchingFieldName)) {
                    // _ignored_source file must be requested explicitly through 'stored_fields' or 'fields'
                    // and not returned if requested via wildcard "*"
                    continue;
                }
                final MappedFieldType fieldType = searchExecutionContext.getFieldType(matchingFieldName);
                // NOTE: checking if the field is stored is required for backward compatibility reasons and to make
                // sure we also handle here stored fields requested via `stored_fields`, which was previously a
                // responsibility of StoredFieldsPhase.
                if (searchExecutionContext.isMetadataField(matchingFieldName) && fieldType.isStored()) {
                    metadataFields.add(new FieldAndFormat(matchingFieldName, null));
                }
            }
        }
        return metadataFields;
    }
}
