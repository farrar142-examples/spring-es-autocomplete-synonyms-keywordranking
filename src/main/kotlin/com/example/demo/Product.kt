package com.example.demo

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.CompletionField
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import org.springframework.data.elasticsearch.annotations.Setting
import org.springframework.data.elasticsearch.core.suggest.Completion

@Document(indexName = "products")
@Setting(
	settingPath = "elasticsearch/settings.json"
)
data class Product (
	@Field(type= FieldType.Text)
	val name: String,
	@Field(type= FieldType.Text)
	val description:String,
	@Field(type= FieldType.Text)
	val category:String,
	@CompletionField(
		searchAnalyzer = "autocomplete_analyzer"
	)
	val suggestion: Completion,
	@Id
	val id: String? = null
)