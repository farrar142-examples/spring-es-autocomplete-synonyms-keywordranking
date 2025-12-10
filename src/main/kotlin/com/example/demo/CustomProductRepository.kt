package com.example.demo

interface CustomProductRepository {
	fun autocomplete(prefix: String,size:Long): List<Product>
	fun search(query:String, size:Long): List<Product>
}