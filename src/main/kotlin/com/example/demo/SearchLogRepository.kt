package com.example.demo

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface SearchLogRepository : ElasticsearchRepository<SearchLog, String>, CustomSearchLogRepository

