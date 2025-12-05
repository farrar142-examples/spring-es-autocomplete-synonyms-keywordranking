package com.example.demo

interface CustomProductRepository {
	fun autocomplete(prefix: String,size:Long): List<Product>
}