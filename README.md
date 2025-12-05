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
3. Product Document 정의

#### 왜 `@Setting`을 사용하는가?

`@Setting(settingPath = "elasticsearch/settings.json")`은 인덱스 생성 시 커스텀 분석기(analyzer), 필터(filter), 토크나이저(tokenizer) 등을 정의하기 위해 사용합니다.

- **Edge N-gram을 통한 부분 매칭**: `edge_ngram_filter`를 사용하여 "유러피안"을 ["유", "유러", "유러피", "유러피안"]으로 분해합니다. 이를 통해 사용자가 "유러"만 입력해도 "유러피안"이 매칭됩니다.
- **Nori 형태소 분석기 통합**: `analysis-nori` 플러그인의 `nori_tokenizer`를 커스텀 분석기에 포함시켜 한국어 형태소 분석을 수행합니다.
- **분석기 재사용**: 한 번 정의한 분석기를 여러 필드에서 재사용할 수 있습니다.

#### 왜 `@CompletionField`를 사용하는가?

`@CompletionField`는 Elasticsearch의 **Completion Suggester** 기능을 위한 특수 필드 타입입니다.

- **빠른 자동완성**: Completion 타입은 FST(Finite State Transducer) 자료구조를 사용해 메모리에서 매우 빠른 prefix 검색을 수행합니다.
- **가중치(weight) 지원**: 각 suggestion에 weight를 부여하여 검색 결과 우선순위를 제어할 수 있습니다.
- **일반 Text 필드와 다름**: 일반 `text` 필드는 full-text 검색용이고, `completion` 필드는 실시간 자동완성에 최적화되어 있습니다.

#### 왜 `searchAnalyzer`를 정의하는가?

`searchAnalyzer = "autocomplete_analyzer"`는 **검색 시점에 사용자 입력을 어떻게 분석할지** 정의합니다.

- **Edge N-gram 기반 매칭**: 검색어 "유렆"이 edge_ngram을 통해 ["유", "유렆"]으로 분해되고, 이 중 "유"가 인덱싱된 "유러피안"의 edge_ngram 토큰 "유"와 매칭되어 결과가 반환됩니다.
- **인덱스 분석기와 동일한 분석기 사용**: 인덱싱과 검색 모두 동일한 `edge_ngram_filter`를 적용하여 일관된 토큰 매칭을 보장합니다.
- **부분 문자열 검색 지원**: "김치"만 입력해도 "김치찌개", "김치볶음밥" 등 "김치"로 시작하는 모든 항목이 매칭됩니다.

#### Edge N-gram의 동작 원리

| 원본 | Edge N-gram 결과 |
|------|------------------|
| "유러피안" | ["유", "유러", "유러피", "유러피안"] |
| "김치찌개" | ["김", "김치", "김치찌", "김치찌개"] |

검색 시 "유러"를 입력하면:
- 검색어 → edge_ngram → ["유", "유러"]
- 인덱스 토큰 중 "유", "유러"와 매칭 → "유러피안" 반환 ✅

```kotlin
@Document(indexName = "products")
@Setting(
	settingPath = "elasticsearch/settings.json"  // edge_ngram 등 커스텀 분석기 정의
)
data class Product (
	@Field(type= FieldType.Text)
	val name: String,
	@Field(type= FieldType.Text)
	val description:String,
	@Field(type= FieldType.Text)
	val category:String,
	@CompletionField(
		searchAnalyzer = "autocomplete_analyzer"  // edge_ngram 기반 검색 분석기
	)
	val suggestion: Completion,  // Completion 타입: 자동완성용 특수 필드
	@Id
	val id: String? = null
)
```
4. ProductRepositor 정의
```kotlin
// src/main/kotlin/com/example/demo/repository/ProductRepository.kt
interface ProductRepository : ElasticsearchRepository<Product, String> {
}
```