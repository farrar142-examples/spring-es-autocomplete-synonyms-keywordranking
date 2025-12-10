package com.example.demo

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class SearchLogRepositoryTests {

    @Autowired
    lateinit var searchLogRepository: SearchLogRepository

    @Autowired
    lateinit var elasticsearchOperations: ElasticsearchOperations

    private fun recreateIndex() {
        val indexOps = elasticsearchOperations.indexOps(SearchLog::class.java)
        if (indexOps.exists()) {
            indexOps.delete()
        }
        indexOps.createWithMapping()
    }

    @Test
    fun testSaveSearchLog() {
        recreateIndex()

        val log = searchLogRepository.save(
            SearchLog(
                query = "그래픽카드",
                timestamp = Instant.now(),
                resultCount = 10
            )
        )

        assertTrue(log.id != null)
        assertEquals("그래픽카드", log.query)
    }

    @Test
    fun testGetPopularSearches() {
        recreateIndex()

        // 검색어별 빈도: 그래픽카드(5회), 모니터(3회), 키보드(1회)
        repeat(5) {
            searchLogRepository.save(
                SearchLog(query = "그래픽카드", timestamp = Instant.now())
            )
        }
        repeat(3) {
            searchLogRepository.save(
                SearchLog(query = "모니터", timestamp = Instant.now())
            )
        }
        searchLogRepository.save(
            SearchLog(query = "키보드", timestamp = Instant.now())
        )

        // 인덱스 반영 대기
        Thread.sleep(1000)

        // 인기 검색어 조회 (최근 1시간)
        val popular = searchLogRepository.getPopularSearches("1h", 10)

        assertEquals(3, popular.size)
        assertEquals("그래픽카드", popular[0].query)
        assertEquals(5, popular[0].count)
        assertEquals("모니터", popular[1].query)
        assertEquals(3, popular[1].count)
        assertEquals("키보드", popular[2].query)
        assertEquals(1, popular[2].count)
    }

    @Test
    fun testGetPopularSearchesByDuration() {
        recreateIndex()

        // 현재 시간 기준 로그 저장
        repeat(10) {
            searchLogRepository.save(
                SearchLog(query = "gpu", timestamp = Instant.now())
            )
        }

        Thread.sleep(1000)

        // 5분 단위 조회
        val popular5m = searchLogRepository.getPopularSearches("5m", 10)
        assertTrue(popular5m.isNotEmpty())
        assertEquals("gpu", popular5m[0].query)
        assertEquals(10, popular5m[0].count)

        // 10분 단위 조회
        val popular10m = searchLogRepository.getPopularSearches("10m", 10)
        assertTrue(popular10m.isNotEmpty())

        // 1시간 단위 조회
        val popular1h = searchLogRepository.getPopularSearches("1h", 10)
        assertTrue(popular1h.isNotEmpty())

        // 1일 단위 조회
        val popular1d = searchLogRepository.getPopularSearches("1d", 10)
        assertTrue(popular1d.isNotEmpty())
    }

    @Test
    fun testSearchLogWithUserId() {
        recreateIndex()

        val log = searchLogRepository.save(
            SearchLog(
                query = "모니터",
                timestamp = Instant.now(),
                userId = "user123",
                resultCount = 5
            )
        )

        assertTrue(log.id != null)
        assertEquals("user123", log.userId)
        assertEquals(5, log.resultCount)
    }

    @Test
    fun testPopularSearchesEmpty() {
        recreateIndex()

        Thread.sleep(500)

        // 빈 인덱스에서 조회
        val popular = searchLogRepository.getPopularSearches("1h", 10)
        assertTrue(popular.isEmpty())
    }

    @Test
    fun testPopularSearchesSize() {
        recreateIndex()

        // 15개의 다른 검색어 저장
        for (i in 1..15) {
            searchLogRepository.save(
                SearchLog(query = "검색어$i", timestamp = Instant.now())
            )
        }

        Thread.sleep(1000)

        // 상위 5개만 조회
        val popular5 = searchLogRepository.getPopularSearches("1h", 5)
        assertEquals(5, popular5.size)

        // 상위 10개 조회
        val popular10 = searchLogRepository.getPopularSearches("1h", 10)
        assertEquals(10, popular10.size)
    }
}

