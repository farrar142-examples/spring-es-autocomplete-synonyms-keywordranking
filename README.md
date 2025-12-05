1. Elasticsearch docker 설치
    ```Dockerfile
    # ./Dockerfile
    FROM docker.elastic.co/elasticsearch/elasticsearch:9.2.2
    RUN bin/elasticsearch-plugin install analysis-nori
    ```
    ```yml
    services:
      elasticsearch:
      build: .
      container_name: elasticsearch
      image: elasticsearch:nori
      ports:
        - "9200:9200"
        - "9300:9300"
      environment:
        - discovery.type=single-node
        - ES_JAVA_OPTS=-Xms512m -Xmx512m
        - xpack.security.enabled=false
      volumes:
        - esdata:/usr/share/elasticsearch/data
    volumes:
      esdata:
     ```
2. Spring Data Elasticsearch 설정
```yml
# src/main/resources/application.yml
spring:
  application:
    name: demo

  elasticsearch:
    uris: localhost:9200
logging:
  level:
    '[org.springframework.data.elasticsearch]': DEBUG
    '[org.elasticsearch.client]': DEBUG
```
```kotlin
// src/main/kotlin/com/example/demo/config/ElasticsearchConfig.kt
@Configuration
@EnableElasticsearchRepositories(basePackages = ["com.example.demo"])
class ElasticsearchConfig {

}
```