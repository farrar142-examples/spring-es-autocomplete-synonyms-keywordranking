package com.example.demo

import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate
import org.springframework.data.elasticsearch.core.ElasticsearchOperations

class CustomSearchLogRepositoryImpl(
    private val elasticsearchOperations: ElasticsearchOperations
) : CustomSearchLogRepository {

    override fun getPopularSearches(duration: String, size: Int): List<SearchRank> {
        val template = elasticsearchOperations as ElasticsearchTemplate
        val client = template.execute { it }

        val searchRequest = SearchRequest.Builder()
            .index("search_logs")
            .size(0)
            .query { q ->
                q.range { r ->
                    r.untyped { u ->
                        u.field("timestamp")
                            .gte(JsonData.of("now-$duration"))
                    }
                }
            }
            .aggregations("popular_queries") { agg ->
                agg.terms { t ->
                    t.field("query")
                        .size(size)
                }
            }
            .build()

        val response = client.search(searchRequest, SearchLog::class.java)
        val termsAgg = response.aggregations()["popular_queries"]?.sterms()

        return termsAgg?.buckets()?.array()?.map { bucket ->
            SearchRank(
                query = bucket.key().stringValue(),
                count = bucket.docCount()
            )
        } ?: emptyList()
    }

    override fun getPopularSearchesBetween(from: String, to: String, size: Int): List<SearchRank> {
        val template = elasticsearchOperations as ElasticsearchTemplate
        val client = template.execute { it }

        val searchRequest = SearchRequest.Builder()
            .index("search_logs")
            .size(0)
            .query { q ->
                q.range { r ->
                    r.untyped { u ->
                        u.field("timestamp")
                            .gte(JsonData.of("now-$from"))
                            .lt(JsonData.of("now-$to"))
                    }
                }
            }
            .aggregations("popular_queries") { agg ->
                agg.terms { t ->
                    t.field("query")
                        .size(size)
                }
            }
            .build()

        val response = client.search(searchRequest, SearchLog::class.java)
        val termsAgg = response.aggregations()["popular_queries"]?.sterms()

        return termsAgg?.buckets()?.array()?.map { bucket ->
            SearchRank(bucket.key().stringValue(), bucket.docCount())
        } ?: emptyList()
    }

}

