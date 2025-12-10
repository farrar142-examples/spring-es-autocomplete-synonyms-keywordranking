package com.example.demo

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "search_logs")
data class SearchLog(
    @Field(type = FieldType.Keyword)
    val query: String,

    @Field(type = FieldType.Date)
    val timestamp: Instant,

    @Field(type = FieldType.Keyword)
    val userId: String? = null,

    @Field(type = FieldType.Long)
    val resultCount: Long = 0,

    @Id
    val id: String? = null
)

