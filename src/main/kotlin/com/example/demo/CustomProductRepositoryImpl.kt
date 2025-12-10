package com.example.demo

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.search.CompletionSuggester
import co.elastic.clients.elasticsearch.core.search.FieldSuggester
import co.elastic.clients.elasticsearch.core.search.Suggester
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.suggest.response.CompletionSuggestion


class CustomProductRepositoryImpl(
	private val elasticsearchOperations: ElasticsearchOperations
) : CustomProductRepository {

	/**
	 * Autocomplete - NativeQuery와 Completion Suggester 사용
	 * - @Document 어노테이션에서 인덱스 이름 자동 인식
	 */
	override fun autocomplete(prefix: String, size: Long): List<Product> {
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

		val nativeQuery = NativeQuery.builder()
			.withSuggester(suggester)
			.withMaxResults(0)
			.build()

		val searchHits = elasticsearchOperations.search(nativeQuery, Product::class.java)

		// Spring Data Elasticsearch의 Suggest 응답 파싱
		val suggest = searchHits.suggest ?: return emptyList()

		return suggest.suggestions
			.filterIsInstance<CompletionSuggestion<Product>>()
			.flatMap { completionSuggestion ->
				completionSuggestion.entries.flatMap { entry ->
					entry.options.mapNotNull { option ->
						option.searchHit?.content
					}
				}
			}
	}

	/**
	 * 일반 검색은 NativeQuery 사용
	 * - @Document 어노테이션에서 인덱스 이름 자동 인식
	 * - 타입 안전한 SearchHits<T> 응답
	 */
	override fun search(query: String, size: Long): List<Product> {
		val multiMatchQuery = Query.Builder()
			.multiMatch { m ->
				m.query(query)
					.fields("name^3", "description", "category^2", "synonyms")
			}
			.build()

		val nativeQuery = NativeQuery.builder()
			.withQuery(multiMatchQuery)
			.withMaxResults(size.toInt())
			.build()

		val searchHits = elasticsearchOperations.search(nativeQuery, Product::class.java)

		return searchHits.searchHits.mapNotNull { it.content }
	}
}