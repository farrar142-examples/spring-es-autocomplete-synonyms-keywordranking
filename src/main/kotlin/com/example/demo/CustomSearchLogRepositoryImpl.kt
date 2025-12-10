package com.example.demo

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonData
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations

class CustomSearchLogRepositoryImpl(
    private val elasticsearchOperations: ElasticsearchOperations
) : CustomSearchLogRepository {

    override fun getPopularSearches(duration: String, size: Int): List<SearchRank> {
        val rangeQuery = Query.Builder()
            .range { r ->
                r.untyped { u ->
                    u.field("timestamp")
                        .gte(JsonData.of("now-$duration"))
                }
            }
            .build()

        val termsAggregation = Aggregation.Builder()
            .terms { t ->
                t.field("query")
                    .size(size)
            }
            .build()

        val nativeQuery = NativeQuery.builder()
            .withQuery(rangeQuery)
            .withAggregation("popular_queries", termsAggregation)
            .withMaxResults(0)
            .build()

        val searchHits = elasticsearchOperations.search(nativeQuery, SearchLog::class.java)

        val aggregations = searchHits.aggregations as? ElasticsearchAggregations
        val esAggregation = aggregations?.aggregations()
            ?.find { it.aggregation().name == "popular_queries" }
        val termsAgg = esAggregation?.aggregation()?.aggregate?.sterms()

        return termsAgg?.buckets()?.array()?.map { bucket: StringTermsBucket ->
            SearchRank(
                query = bucket.key().stringValue(),
                count = bucket.docCount()
            )
        } ?: emptyList()
    }

    override fun getPopularSearchesBetween(from: String, to: String, size: Int): List<SearchRank> {
        val rangeQuery = Query.Builder()
            .range { r ->
                r.untyped { u ->
                    u.field("timestamp")
                        .gte(JsonData.of("now-$from"))
                        .lt(JsonData.of("now-$to"))
                }
            }
            .build()

        val termsAggregation = Aggregation.Builder()
            .terms { t ->
                t.field("query")
                    .size(size)
            }
            .build()

        val nativeQuery = NativeQuery.builder()
            .withQuery(rangeQuery)
            .withAggregation("popular_queries", termsAggregation)
            .withMaxResults(0)
            .build()

        val searchHits = elasticsearchOperations.search(nativeQuery, SearchLog::class.java)

        val aggregations = searchHits.aggregations as? ElasticsearchAggregations
        val esAggregation = aggregations?.aggregations()
            ?.find { it.aggregation().name == "popular_queries" }
        val termsAgg = esAggregation?.aggregation()?.aggregate?.sterms()

        return termsAgg?.buckets()?.array()?.map { bucket: StringTermsBucket ->
            SearchRank(bucket.key().stringValue(), bucket.docCount())
        } ?: emptyList()
    }
}

