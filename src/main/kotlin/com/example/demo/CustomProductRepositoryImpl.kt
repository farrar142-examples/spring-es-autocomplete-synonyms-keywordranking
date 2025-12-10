package com.example.demo

import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate
import org.springframework.data.elasticsearch.core.ElasticsearchOperations

import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.CompletionSuggester
import co.elastic.clients.elasticsearch.core.search.FieldSuggester
import co.elastic.clients.elasticsearch.core.search.Suggester


class CustomProductRepositoryImpl(
	private val elasticsearchOperations: ElasticsearchOperations
) : CustomProductRepository {

	override fun autocomplete(prefix: String, size: Long): List<Product> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }

		val completionSuggester = CompletionSuggester.Builder()
			.field("suggestion")
			.size(size.toInt())
			.skipDuplicates(true)
			.build()

		val fieldSuggester = FieldSuggester.Builder()
			.prefix(prefix)
			.completion(completionSuggester)
			.build()

		val suggester = Suggester.Builder()
			.suggesters("prod-suggest", fieldSuggester)
			.build()

		val searchRequest = SearchRequest.Builder()
			.index("products")
			.suggest(suggester)
			.size(0)
			.build()

		val response = client.search(searchRequest, Product::class.java)

		val suggestMap = response.suggest()
		val suggestions = suggestMap["prod-suggest"]?:emptyList()
		return suggestions.map{it.completion()?.options()}
			.filterNotNull()
			.flatMap{it}
			.map{it.source()}
			.filterNotNull()
	}

	override fun search(query: String, size: Long): List<Product> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }
		val searchRequest = SearchRequest.Builder()
			.index("products")
			.query { q ->
				q.multiMatch { m ->
					m.query(query)
						.fields("name^3", "description", "category^2")
				}
			}
			.size(size.toInt())
			.build()

		val response = client.search(searchRequest, Product::class.java)

		return response.hits().hits()
			.mapNotNull { it.source() }
	}
}