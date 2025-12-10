package com.example.demo

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.suggest.Completion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
class ProductRepositoryTests {
	@Autowired
	lateinit var productRepository: ProductRepository

	@Autowired
	lateinit var elasticsearchOperations: ElasticsearchOperations

	/**
	 * 인덱스를 완전히 삭제하고 재생성 (매핑 및 설정 변경 반영)
	 */
	private fun recreateIndex() {
		val indexOps = elasticsearchOperations.indexOps(Product::class.java)
		if (indexOps.exists()) {
			indexOps.delete()
		}
		// createWithMapping()은 @Setting 어노테이션의 settings.json도 함께 적용
		indexOps.createWithMapping()
	}

	@Test
	fun testConnection(){
		productRepository.deleteAll()
		val product = productRepository.save(
			Product(
				name = "김치찌개",
				description = "맛있는 김치찌개",
				category = "국/찌개",
				suggestion = Completion(listOf("김치찌개"))
			)
		)
		assertNotEquals(product.id,null)
	}
	@Test
	fun testAutocomplete(){
		productRepository.deleteAll()
		val product = productRepository.save(
			Product(
				name = "김치찌개",
				description = "맛있는 김치찌개",
				category = "국/찌개",
				suggestion = Completion(listOf("김치찌개"))
			)
		)
		assertNotEquals(productRepository.count(),0)
		val completes = productRepository.autocomplete("김치찍",10)
		assertEquals(1,completes.size)
		assertEquals("김치찌개",completes[0].name)
		val product2 = productRepository.save(
			Product(
				name = "유러피안 모델 하우스",
				description = "서양풍의 감각적인 인테리어",
				category = "관광",
				suggestion = Completion(listOf("유러피안","모델 하우스","유러피안 모델 하우스"))
			)
		)
		val completes2 = productRepository.autocomplete("유렆",10)// 테스트 종성 분리 안함
		assertEquals(1,completes2.size)
		assertEquals("유러피안 모델 하우스",completes2[0].name)
		val completes3 = productRepository.autocomplete("유러ㅍ",10)
		assertEquals(1,completes3.size)
		assertEquals("유러피안 모델 하우스",completes3[0].name)
		val completes4 = productRepository.autocomplete("유러핑",10)// 테스트 종성 분리 안함
		assertEquals(1,completes4.size)
		assertEquals("유러피안 모델 하우스",completes4[0].name)
	}

	@Test
	fun testSearch() {
		recreateIndex()

		// 테스트 데이터 생성
		val product1 = productRepository.save(
			Product(
				name = "RTX 5080TI 그래픽카드",
				description = "고성능 GPU",
				category = "컴퓨터 부품",
				suggestion = Completion(listOf("RTX 5080TI"))
			)
		)

		val product2 = productRepository.save(
			Product(
				name = "김치찌개",
				description = "맛있는 한식 요리",
				category = "국/찌개",
				suggestion = Completion(listOf("김치찌개"))
			)
		)

		val product3 = productRepository.save(
			Product(
				name = "삼성 모니터",
				description = "고해상도 게이밍 디스플레이",
				category = "컴퓨터 부품",
				suggestion = Completion(listOf("삼성 모니터"))
			)
		)

		// 인덱스 반영 대기
		Thread.sleep(1000)

		// 1. name 필드 검색 테스트
		val searchByName = productRepository.search("RTX", 10)
		assertEquals(1, searchByName.size)
		assertEquals("RTX 5080TI 그래픽카드", searchByName[0].name)

		// 2. description 필드 검색 테스트
		val searchByDescription = productRepository.search("고성능", 10)
		assertEquals(1, searchByDescription.size)
		assertEquals("RTX 5080TI 그래픽카드", searchByDescription[0].name)

		// 3. category 필드 검색 테스트
		val searchByCategory = productRepository.search("컴퓨터 부품", 10)
		assertEquals(2, searchByCategory.size)

		// 4. 동의어 검색 테스트 - "GPU"로 검색하면 "그래픽카드"가 포함된 문서도 검색됨
		val searchBySynonym = productRepository.search("graphics card", 10)
		assertEquals(1, searchBySynonym.size)
		assertEquals("RTX 5080TI 그래픽카드", searchBySynonym[0].name)

		// 5. 동의어 검색 테스트 - "디스플레이"로 검색하면 "모니터"가 포함된 문서도 검색됨
		val searchBySynonym2 = productRepository.search("display", 10)
		assertEquals(1, searchBySynonym2.size)
		assertEquals("삼성 모니터", searchBySynonym2[0].name)

		// 6. 동의어 검색 테스트 - "한식"으로 검색
		val searchBySynonymKorean = productRepository.search("korean food", 10)
		assertEquals(1, searchBySynonymKorean.size)
		assertEquals("김치찌개", searchBySynonymKorean[0].name)

		// 7. 검색 결과 없음 테스트
		val searchNoResult = productRepository.search("존재하지않는검색어", 10)
		assertEquals(0, searchNoResult.size)
	}
}