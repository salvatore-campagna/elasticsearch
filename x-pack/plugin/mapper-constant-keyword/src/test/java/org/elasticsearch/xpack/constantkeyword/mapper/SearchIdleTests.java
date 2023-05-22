/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.constantkeyword.mapper;

import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.WildcardQueryBuilder;
import org.elasticsearch.index.refresh.RefreshStats;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.constantkeyword.ConstantKeywordMapperPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SearchIdleTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(ConstantKeywordMapperPlugin.class);
    }

    public void testSearchIdleConstantKeywordMatchNoIndex() throws InterruptedException {
        // GIVEN
        final String idleIndex = "test1";
        final String activeIndex = "test2";
        // NOTE: we need many shards because shard pre-filtering and the "can match" phase
        // are executed only if we have enough shards.
        int idleIndexShardsCount = 3;
        int activeIndexShardsCount = 3;
        createIndex(
            idleIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, idleIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant_value1",
            "keyword",
            "type=keyword"
        );
        createIndex(
            activeIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, activeIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant_value2",
            "keyword",
            "type=keyword"
        );

        assertEquals(RestStatus.CREATED, client().prepareIndex(idleIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.CREATED, client().prepareIndex(activeIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.OK, client().admin().indices().prepareRefresh(idleIndex, activeIndex).get().getStatus());

        waitUntil(
            () -> Arrays.stream(client().admin().indices().prepareStats(idleIndex, activeIndex).get().getShards())
                .allMatch(ShardStats::isSearchIdle),
            2,
            TimeUnit.SECONDS
        );

        final IndicesStatsResponse beforeStatsResponse = client().admin().indices().prepareStats("test*").get();
        assertIdleShard(beforeStatsResponse);

        // WHEN
        final SearchResponse searchResponse = search("test*", "constant_keyword", randomAlphaOfLength(5), 5);
        assertEquals(RestStatus.OK, searchResponse.status());
        // NOTE: we need an empty result from at least one shard
        assertEquals(idleIndexShardsCount + activeIndexShardsCount - 1, searchResponse.getSkippedShards());
        assertEquals(0, searchResponse.getFailedShards());
        assertEquals(0, searchResponse.getHits().getHits().length);

        // THEN
        final IndicesStatsResponse afterStatsResponse = client().admin().indices().prepareStats("test*").get();
        final List<ShardStats> activeShards = Arrays.stream(afterStatsResponse.getShards())
            .filter(shardStats -> shardStats.isSearchIdle() == false)
            .toList();
        // NOTE: we need an empty result from at least one shard
        assertEquals(1, activeShards.size());

        assertIdleShardsRefreshStats(beforeStatsResponse, afterStatsResponse);
    }

    public void testSearchIdleConstantKeywordMatchOneIndex() throws InterruptedException {
        // GIVEN
        final String idleIndex = "test1";
        final String activeIndex = "test2";
        // NOTE: we need many shards because shard pre-filtering and the "can match" phase
        // are executed only if we have enough shards.
        int idleIndexShardsCount = 3;
        int activeIndexShardsCount = 3;
        createIndex(
            idleIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, idleIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant_value1",
            "keyword",
            "type=keyword"
        );
        createIndex(
            activeIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, activeIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant_value2",
            "keyword",
            "type=keyword"
        );

        assertEquals(RestStatus.CREATED, client().prepareIndex(idleIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.CREATED, client().prepareIndex(activeIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.OK, client().admin().indices().prepareRefresh(idleIndex, activeIndex).get().getStatus());

        waitUntil(
            () -> Arrays.stream(client().admin().indices().prepareStats(idleIndex, activeIndex).get().getShards())
                .allMatch(ShardStats::isSearchIdle),
            2,
            TimeUnit.SECONDS
        );

        final IndicesStatsResponse idleIndexStatsBefore = client().admin().indices().prepareStats("test1").get();
        assertIdleShard(idleIndexStatsBefore);

        final IndicesStatsResponse activeIndexStatsBefore = client().admin().indices().prepareStats("test2").get();
        assertIdleShard(activeIndexStatsBefore);

        // WHEN
        final SearchResponse searchResponse = search("test*", "constant_keyword", "constant_value2", 5);
        assertEquals(RestStatus.OK, searchResponse.status());
        assertEquals(idleIndexShardsCount, searchResponse.getSkippedShards());
        assertEquals(0, searchResponse.getFailedShards());
        Arrays.stream(searchResponse.getHits().getHits()).forEach(searchHit -> assertEquals("test2", searchHit.getIndex()));

        // THEN
        final IndicesStatsResponse idleIndexStatsAfter = client().admin().indices().prepareStats(idleIndex).get();
        Arrays.stream(idleIndexStatsAfter.getShards()).forEach(shardStats -> assertTrue(shardStats.isSearchIdle()));

        final IndicesStatsResponse activeIndexStatsAfter = client().admin().indices().prepareStats(activeIndex).get();
        Arrays.stream(activeIndexStatsAfter.getShards()).forEach(shardStats -> assertFalse(shardStats.isSearchIdle()));

        assertIdleShardsRefreshStats(idleIndexStatsBefore, idleIndexStatsAfter);
    }

    public void testSearchIdleConstantKeywordMatchTwoIndices() throws InterruptedException {
        // GIVEN
        final String idleIndex = "test1";
        final String activeIndex = "test2";
        // NOTE: we need many shards because shard pre-filtering and the "can match" phase
        // are executed only if we have enough shards.
        int idleIndexShardsCount = 3;
        int activeIndexShardsCount = 3;
        createIndex(
            idleIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, idleIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant",
            "keyword",
            "type=keyword"
        );
        createIndex(
            activeIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, activeIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=constant",
            "keyword",
            "type=keyword"
        );

        assertEquals(RestStatus.CREATED, client().prepareIndex(idleIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.CREATED, client().prepareIndex(activeIndex).setSource("keyword", randomAlphaOfLength(10)).get().status());
        assertEquals(RestStatus.OK, client().admin().indices().prepareRefresh(idleIndex, activeIndex).get().getStatus());

        waitUntil(
            () -> Arrays.stream(client().admin().indices().prepareStats(idleIndex, activeIndex).get().getShards())
                .allMatch(ShardStats::isSearchIdle),
            2,
            TimeUnit.SECONDS
        );

        final IndicesStatsResponse beforeStatsResponse = client().admin().indices().prepareStats("test*").get();
        assertIdleShard(beforeStatsResponse);

        // WHEN
        final SearchResponse searchResponse = search("test*", "constant_keyword", "constant", 5);
        assertEquals(RestStatus.OK, searchResponse.status());
        assertEquals(0, searchResponse.getSkippedShards());
        assertEquals(0, searchResponse.getFailedShards());
        assertArrayEquals(
            new String[] { "test1", "test2" },
            Arrays.stream(searchResponse.getHits().getHits()).map(SearchHit::getIndex).sorted().toArray()
        );

        // THEN
        final IndicesStatsResponse afterStatsResponse = client().admin().indices().prepareStats("test*").get();
        Arrays.stream(afterStatsResponse.getShards()).forEach(shardStats -> assertFalse(shardStats.isSearchIdle()));

        assertIdleShardsRefreshStats(beforeStatsResponse, afterStatsResponse);
    }

    public void testSearchIdleWildcardQueryMatchOneIndex() throws InterruptedException {
        // GIVEN
        final String idleIndex = "test1";
        final String activeIndex = "test2";
        // NOTE: we need many shards because shard pre-filtering and the "can match" phase
        // are executed only if we have enough shards.
        int idleIndexShardsCount = 3;
        int activeIndexShardsCount = 3;
        createIndex(
            idleIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, idleIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=test1_value"
        );
        createIndex(
            activeIndex,
            Settings.builder()
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), "500ms")
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, activeIndexShardsCount)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                .build(),
            "doc",
            "constant_keyword",
            "type=constant_keyword,value=test2_value"
        );

        assertEquals(RestStatus.CREATED, client().prepareIndex(idleIndex).setSource("keyword", "value").get().status());
        assertEquals(RestStatus.CREATED, client().prepareIndex(activeIndex).setSource("keyword", "value").get().status());
        assertEquals(RestStatus.OK, client().admin().indices().prepareRefresh(idleIndex, activeIndex).get().getStatus());

        waitUntil(
            () -> Arrays.stream(client().admin().indices().prepareStats(idleIndex, activeIndex).get().getShards())
                .allMatch(ShardStats::isSearchIdle),
            2,
            TimeUnit.SECONDS
        );

        final IndicesStatsResponse idleIndexStatsBefore = client().admin().indices().prepareStats("test1").get();
        assertIdleShard(idleIndexStatsBefore);

        final IndicesStatsResponse activeIndexStatsBefore = client().admin().indices().prepareStats("test2").get();
        assertIdleShard(activeIndexStatsBefore);

        // WHEN
        final SearchResponse searchResponse = client().prepareSearch("test*")
            .setQuery(new WildcardQueryBuilder("constant_keyword", "test2*"))
            .setPreFilterShardSize(5)
            .get();
        assertEquals(RestStatus.OK, searchResponse.status());
        assertEquals(idleIndexShardsCount, searchResponse.getSkippedShards());
        assertEquals(0, searchResponse.getFailedShards());
        Arrays.stream(searchResponse.getHits().getHits()).forEach(searchHit -> assertEquals("test2", searchHit.getIndex()));

        // THEN
        final IndicesStatsResponse idleIndexStatsAfter = client().admin().indices().prepareStats(idleIndex).get();
        Arrays.stream(idleIndexStatsAfter.getShards()).forEach(shardStats -> assertTrue(shardStats.isSearchIdle()));

        final IndicesStatsResponse activeIndexStatsAfter = client().admin().indices().prepareStats(activeIndex).get();
        Arrays.stream(activeIndexStatsAfter.getShards()).forEach(shardStats -> assertFalse(shardStats.isSearchIdle()));

        assertIdleShardsRefreshStats(idleIndexStatsBefore, idleIndexStatsAfter);
    }

    private SearchResponse search(final String index, final String field, final String value, int preFilterShardSize) {
        return client().prepareSearch(index)
            .setQuery(new MatchPhraseQueryBuilder(field, value))
            .setPreFilterShardSize(preFilterShardSize)
            .get();
    }

    private static void assertIdleShard(final IndicesStatsResponse statsResponse) {
        Arrays.stream(statsResponse.getShards()).forEach(shardStats -> assertTrue(shardStats.isSearchIdle()));
        Arrays.stream(statsResponse.getShards()).forEach(shardStats -> assertTrue(shardStats.getSearchIdleTime() >= 100));
    }

    private static void assertIdleShardsRefreshStats(final IndicesStatsResponse before, final IndicesStatsResponse after) {
        assertNotEquals(0, before.getShards().length);
        assertNotEquals(0, after.getShards().length);
        final List<RefreshStats> refreshStatsBefore = Arrays.stream(before.getShards()).map(x -> x.getStats().refresh).toList();
        final List<RefreshStats> refreshStatsAfter = Arrays.stream(after.getShards()).map(x -> x.getStats().refresh).toList();
        assertEquals(refreshStatsBefore.size(), refreshStatsAfter.size());
        assertTrue(refreshStatsAfter.containsAll(refreshStatsBefore));
        assertTrue(refreshStatsBefore.containsAll(refreshStatsAfter));
    }
}
