package com.example.demo

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository


interface ProductRepository : ElasticsearchRepository<Product,String>{
}