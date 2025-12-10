package com.example.demo


interface CustomSearchLogRepository {
	/**
	 * 기간별 인기 검색어 조회
	 * @param duration 기간 (예: "5m", "10m", "1h", "1d", "1w")
	 * @param size 상위 N개
	 */
	fun getPopularSearches(duration: String, size: Int = 10): List<SearchRank>

	/**
	 * 특정 기간 사이의 인기 검색어 조회
	 */
	fun getPopularSearchesBetween(from: String, to: String, size: Int = 10): List<SearchRank>

}

data class SearchRank(
	val query: String,
	val count: Long
)

