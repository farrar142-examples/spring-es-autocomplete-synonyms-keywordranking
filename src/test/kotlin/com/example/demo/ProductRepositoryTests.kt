package com.example.demo

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.suggest.Completion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
class ProductRepositoryTests {
	@Autowired
	lateinit var productRepository: ProductRepository
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
}