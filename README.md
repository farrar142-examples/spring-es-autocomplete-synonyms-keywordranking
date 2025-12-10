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
4. ProductRepository 정의
```kotlin
// src/main/kotlin/com/example/demo/repository/ProductRepository.kt
interface ProductRepository : ElasticsearchRepository<Product, String> {
}
```
5. Autocomplete사용을 위한 CustomProductRepository 정의
#### 왜 CustomProductRepository를 사용하는가?

Spring Data Elasticsearch의 기본 `ElasticsearchRepository`는 Completion Suggester를 직접 지원하지 않습니다. 따라서 **커스텀 리포지토리**를 통해 Elasticsearch의 저수준 클라이언트에 접근하여 Suggest API를 직접 호출해야 합니다.

#### Autocomplete 메서드의 동작 원리

```
사용자 입력 "유러"
       ↓
┌─────────────────────────────────────────────────────┐
│ 1. ElasticsearchTemplate에서 ES 클라이언트 획득     │
│    template.execute { it }                          │
└─────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────┐
│ 2. CompletionSuggester 생성                         │
│    - field: "suggestion" (Completion 필드)          │
│    - size: 반환할 최대 결과 수                      │
│    - skipDuplicates: 중복 제거                      │
└─────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────┐
│ 3. FieldSuggester에 prefix와 completion 설정        │
│    - prefix: 사용자가 입력한 검색어 ("유러")        │
└─────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────┐
│ 4. Suggester로 FieldSuggester를 이름과 함께 등록    │
│    - "prod-suggest": suggester 식별자 (응답 조회용) │
│    - 복수 suggester 등록 가능                       │
└─────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────┐
│ 5. SearchRequest 생성 및 실행                       │
│    - index: "products"                              │
│    - suggest: 위에서 만든 suggester                 │
│    - size: 0 (문서 본문 검색 결과는 불필요)         │
└─────────────────────────────────────────────────────┘
       ↓
┌─────────────────────────────────────────────────────┐
│ 6. 응답에서 Product 객체 추출                       │
│    suggest → "prod-suggest" → options → source      │
└─────────────────────────────────────────────────────┘
       ↓
   List<Product> 반환
```

#### 각 컴포넌트의 역할

| 컴포넌트 | 역할 |
|----------|------|
| `ElasticsearchTemplate` | Spring의 ES 추상화 레이어, 저수준 클라이언트 접근 제공 |
| `CompletionSuggester` | Completion 필드에 대한 자동완성 쿼리 정의 |
| `FieldSuggester` | prefix(검색어)와 completion suggester를 연결 |
| `Suggester` | 여러 suggester를 이름으로 그룹화 ("prod-suggest") |
| `SearchRequest` | ES에 보낼 최종 검색 요청 (suggest 포함) |

#### 왜 `size(0)`을 설정하는가?

```kotlin
val searchRequest = SearchRequest.Builder()
    .index("products")
    .suggest(suggester)
    .size(0)  // ← 이 설정
    .build()
```

- **목적**: 일반 검색 결과(hits)는 필요 없고, **suggest 결과만 필요**
- **성능 최적화**: 불필요한 문서 본문을 가져오지 않아 응답 속도 향상
- **suggest 결과는 별도**: `response.suggest()`에서 가져옴

```kotlin
// src/main/kotlin/com/example/demo/repository/CustomProductRepository.kt
interface CustomProductRepository {
	fun autocomplete(prefix: String, size: Int = 10): List<Product>
}
```
```kotlin
// src/main/kotlin/com/example/demo/repository/CustomProductRepositoryImpl.kt

class CustomProductRepositoryImpl(
	private val elasticsearchOperations: ElasticsearchOperations
) : CustomProductRepository {

	override fun autocomplete(prefix: String, size: Long): List<Product> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }

		val completionSuggester = CompletionSuggester.Builder()
			.field("suggestion")
			.size(size.toInt())
			.skipDuplicates(true)
			.build()

		val fieldSuggester = FieldSuggester.Builder()
			.prefix(prefix)
			.completion(completionSuggester)
			.build()

		val suggester = Suggester.Builder()
			.suggesters("prod-suggest", fieldSuggester)
			.build()

		val searchRequest = SearchRequest.Builder()
			.index("products")
			.suggest(suggester)
			.size(0)
			.build()

		val response = client.search(searchRequest, Product::class.java)

		val suggestMap = response.suggest()
		val suggestions = suggestMap["prod-suggest"]?:emptyList()
		return suggestions.map{it.completion()?.options()}
			.filterNotNull()
			.flatMap{it}
			.map{it.source()}
			.filterNotNull()
	}
}
```
5. 동의어 검색을 위한 Synonym Filter 설정

#### 역인덱스(Inverted Index)란?

Elasticsearch의 핵심 자료구조입니다. 일반적인 "문서 → 단어" 방식이 아닌 **"단어 → 문서"** 방식으로 저장합니다.

**일반 인덱스 (Forward Index)**
```
문서1: "RTX 5080TI 그래픽카드"
문서2: "삼성 모니터"
문서3: "GPU 쿨러"
```

**역인덱스 (Inverted Index)**
```
┌────────────┬─────────────────┐
│ 토큰       │ 문서 ID         │
├────────────┼─────────────────┤
│ rtx        │ [문서1]         │
│ 5080ti     │ [문서1]         │
│ 그래픽카드 │ [문서1]         │
│ 삼성       │ [문서2]         │
│ 모니터     │ [문서2]         │
│ gpu        │ [문서3]         │
│ 쿨러       │ [문서3]         │
└────────────┴─────────────────┘
```

**검색 과정:**
1. 사용자가 "그래픽카드" 검색
2. 역인덱스에서 "그래픽카드" 토큰 조회
3. 해당 토큰이 있는 문서 ID [문서1] 반환
4. 문서1의 내용 "RTX 5080TI 그래픽카드" 반환

→ 전체 문서를 스캔하지 않고 **O(1)** 에 가까운 속도로 검색 가능

#### 동의어 검색의 원리

동의어 검색은 **검색 시점에 검색어를 확장**하여 역인덱스에서 여러 토큰을 조회하는 방식입니다.

```
┌─────────────────────────────────────────────────────────┐
│ 역인덱스 (인덱싱 완료 상태)                             │
├────────────┬────────────────────────────────────────────┤
│ 토큰       │ 문서 ID                                    │
├────────────┼────────────────────────────────────────────┤
│ rtx        │ [문서1]                                    │
│ 5080ti     │ [문서1]                                    │
│ 그래픽카드 │ [문서1]  ← "GPU" 검색 시 여기서 매칭!     │
│ 삼성       │ [문서2]                                    │
│ 모니터     │ [문서2]  ← "디스플레이" 검색 시 여기서 매칭!│
│ gpu        │ [문서3]                                    │
│ 쿨러       │ [문서3]                                    │
└────────────┴────────────────────────────────────────────┘

사용자 입력: "GPU"
     ↓
synonym_graph_filter 적용
     ↓
확장된 검색어: ["gpu", "그래픽카드", "graphics", "card", "지포스", "라데온"]
     ↓
역인덱스에서 각 토큰 조회:
  - "gpu" → [문서3] ✅
  - "그래픽카드" → [문서1] ✅
  - "graphics" → []
  - "card" → []
  - "지포스" → []
  - "라데온" → []
     ↓
결과: [문서1, 문서3] 반환
```

#### 인덱싱 시 확장 vs 검색 시 확장

| 방식 | 장점 | 단점 |
|------|------|------|
| **인덱싱 시 확장** | 검색 속도 빠름 | 동의어 변경 시 Reindex 필요, 인덱스 크기 증가 |
| **검색 시 확장** (현재 방식) | 동의어 변경 용이, 인덱스 크기 작음 | 검색 시 약간의 오버헤드 |

현재 구현은 **검색 시 확장** 방식을 사용하여 동의어 관리가 용이합니다.

#### `analyzer` vs `searchAnalyzer`의 차이

Elasticsearch에서는 **인덱싱 시점**과 **검색 시점**에 서로 다른 분석기를 적용할 수 있습니다.

| 항목 | `analyzer` | `searchAnalyzer` |
|------|------------|------------------|
| **적용 시점** | 문서 저장(인덱싱) 시 | 검색 쿼리 실행 시 |
| **역할** | 문서를 토큰화하여 역인덱스 생성 | 검색어를 토큰화하여 역인덱스와 매칭 |
| **변경 시 Reindex** | ✅ 필요 | ❌ 불필요 |

```kotlin
@Field(
    type = FieldType.Text,
    analyzer = "korean_index_analyzer",        // 인덱싱 시 사용
    searchAnalyzer = "korean_search_synonym_analyzer"  // 검색 시 사용
)
val name: String
```

#### 왜 분리하는가?

**Synonym Filter의 특성** 때문입니다:

1. **`synonym` 타입**: Nori 분석기와 함께 사용 시 position increment 충돌 발생
2. **`synonym_graph` 타입**: **검색 시에만 사용 가능** (인덱싱 시 사용 불가)

따라서 동의어 검색을 위해서는:
- **인덱싱**: `synonym_graph_filter` 없이 기본 형태소 분석만 수행
- **검색**: `synonym_graph_filter`를 포함하여 동의어 확장

#### Synonym Graph Filter의 동작 원리

```
┌─────────────────────────────────────────────────────────┐
│ settings.json에 동의어 정의                              │
│ "GPU, 그래픽카드, graphics card, 지포스, 라데온"         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 인덱싱 시 (korean_index_analyzer)                       │
│                                                         │
│ "RTX 5080TI 그래픽카드"                                 │
│     ↓ nori_tokenizer                                    │
│ ["rtx", "5080ti", "그래픽카드"]                         │
│     ↓ 역인덱스에 저장                                   │
│                                                         │
│ ※ synonym 확장 없음 - 원본 토큰만 저장                  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 검색 시 (korean_search_synonym_analyzer)                │
│                                                         │
│ 사용자 입력: "GPU"                                      │
│     ↓ nori_tokenizer                                    │
│ ["gpu"]                                                 │
│     ↓ synonym_graph_filter (동의어 확장!)               │
│ ["gpu", "그래픽카드", "graphics", "card", "지포스", "라데온"] │
│     ↓ 역인덱스에서 검색                                 │
│ "그래픽카드" 매칭! → "RTX 5080TI 그래픽카드" 반환 ✅    │
└─────────────────────────────────────────────────────────┘
```

#### 동의어 정의 방식

```json
// settings.json
"synonym_graph_filter": {
  "type": "synonym_graph",
  "synonyms": [
    "GPU, 그래픽카드, graphics card, 지포스, 라데온",
    "CPU, 프로세서, processor",
    "모니터, 디스플레이, display, 화면"
  ]
}
```

- **쉼표(,)로 구분**: 양방향 동의어 (GPU ↔ 그래픽카드)
- **화살표(=>)**: 단방향 동의어 (`GPU => 그래픽카드`는 GPU 검색 시만 그래픽카드 매칭)

#### 동의어 업데이트 시 Reindex 필요 여부

| 변경 항목 | Reindex 필요 |
|-----------|--------------|
| `synonym_graph_filter` 동의어 추가/삭제 | ❌ 불필요 (검색 시에만 적용) |
| `analyzer` (인덱싱 분석기) 변경 | ✅ 필요 |
| 필드 타입 변경 | ✅ 필요 |

**단, 동의어 변경을 적용하려면 인덱스 close/open 또는 재생성 필요:**
```bash
curl -X POST "localhost:9200/products/_close"
curl -X POST "localhost:9200/products/_open"
```

```kotlin
// src/main/kotlin/com/example/demo/document/Product.kt
@Document(indexName = "products")
@Setting(
	settingPath = "elasticsearch/settings.json"
)
data class Product (
	@Field(type= FieldType.Text, analyzer = "korean_index_analyzer", searchAnalyzer = "korean_search_synonym_analyzer")
	val name: String,
	@Field(type= FieldType.Text, analyzer = "korean_index_analyzer", searchAnalyzer = "korean_search_synonym_analyzer")
	val description:String,
	@Field(type= FieldType.Text, analyzer = "korean_index_analyzer", searchAnalyzer = "korean_search_synonym_analyzer")
	val category:String,
	@CompletionField(
		searchAnalyzer = "autocomplete_analyzer"
	)
	val suggestion: Completion,
	@Id
	val id: String? = null
)
```
6. 동의어 검색을 위한 쿼리 작성

#### Multi-Match Query란?

`multi_match`는 **여러 필드에 대해 동시에 검색**을 수행하는 쿼리입니다. 각 필드에 설정된 `searchAnalyzer`가 자동으로 적용되어 동의어 확장이 이루어집니다.

```
사용자 입력: "GPU"
     ↓
┌─────────────────────────────────────────────────────────┐
│ multi_match 쿼리 실행                                   │
│                                                         │
│ 검색 대상 필드:                                         │
│   - name (searchAnalyzer: korean_search_synonym_analyzer)│
│   - description (searchAnalyzer: korean_search_synonym_analyzer)│
│   - category (searchAnalyzer: korean_search_synonym_analyzer)│
│                                                         │
│ 각 필드에서 "GPU" → ["gpu", "그래픽카드", ...] 확장     │
└─────────────────────────────────────────────────────────┘
     ↓
┌─────────────────────────────────────────────────────────┐
│ 역인덱스에서 검색                                       │
│                                                         │
│ name 필드: "그래픽카드" 매칭 → score × 3 (boost)       │
│ description 필드: 매칭 없음                             │
│ category 필드: "컴퓨터 부품" 매칭 없음                  │
└─────────────────────────────────────────────────────────┘
     ↓
   결과 반환 (score 기준 정렬)
```

#### 필드별 가중치 (Boost)

`^` 기호를 사용하여 필드별 **가중치(boost)**를 설정합니다. 숫자가 클수록 해당 필드에서 매칭될 때 점수가 높아집니다.

```kotlin
.fields("name^3", "description", "category^2")
```

| 필드 | 표현 | 가중치 | 의미 |
|------|------|--------|------|
| name | `name^3` | 3배 | 상품명 매칭을 가장 중요하게 |
| category | `category^2` | 2배 | 카테고리 매칭도 중요 |
| description | `description` | 1배 (기본) | 설명은 기본 가중치 |

#### 가중치가 점수에 미치는 영향

```
검색어: "모니터"

문서1: { name: "삼성 모니터", description: "고해상도", category: "전자제품" }
문서2: { name: "키보드", description: "모니터 받침대 포함", category: "모니터 악세서리" }

점수 계산:
┌─────────┬────────────────┬────────────────┬─────────────────┬───────────┐
│ 문서    │ name 매칭      │ description    │ category 매칭   │ 총 점수   │
│         │ (×3)           │ (×1)           │ (×2)            │           │
├─────────┼──��─────────────┼────────────────┼─────────────────┼───────────┤
│ 문서1   │ ✅ 1.0 × 3 = 3 │ ❌ 0           │ ❌ 0            │ 3.0       │
│ 문서2   │ ❌ 0           │ ✅ 1.0 × 1 = 1 │ ✅ 1.0 × 2 = 2  │ 3.0       │
└���────────┴────────────────┴────────────────┴─────────────────┴──���────────┘

→ 두 문서 점수가 같지만, name 매칭이 더 "정확한" 결과로 판단 가능
→ 실제로는 TF-IDF 등 추가 요소가 점수에 반영됨
```

#### Multi-Match의 타입

```kotlin
.fields("name^3", "description", "category^2")
.type(TextQueryType.BestFields)  // 기본값
```

| 타입 | 설명 | 사용 시점 |
|------|------|-----------|
| `best_fields` | 가장 높은 점수의 필드만 사용 (기본값) | 하나의 필드에서 완전히 매칭되길 원할 때 |
| `most_fields` | 모든 필드의 점수를 합산 | 여러 필드에 분산된 정보를 합칠 때 |
| `cross_fields` | 필드를 하나로 합쳐서 검색 | 이름이 first_name, last_name으로 분리된 경우 |
| `phrase` | 구문(phrase) 검색 | 단어 순서가 중요할 때 |

#### 왜 Multi-Match를 사용하는가?

| 방식 | 코드 | 단점 |
|------|------|------|
| 개별 match 쿼리 | `bool { should { match(name) } should { match(desc) } }` | 코드 복잡, 중복 |
| **multi_match** | `multiMatch { fields("name", "desc") }` | ✅ 간결, 가중치 설정 용이 |

```kotlin
// src/main/kotlin/com/example/demo/repository/CustomProductRepository.kt

    fun search(query:String, size:Long): List<Product>

```
```kotlin
// src/main/kotlin/com/example/demo/repository/CustomProductRepositoryImpl.kt

	override fun search(query: String, size: Long): List<Product> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }
		val searchRequest = SearchRequest.Builder()
			.index("products")
			.query { q ->
				q.multiMatch { m ->
					m.query(query)
						.fields("name^3", "description", "category^2")
				}
			}
			.size(size.toInt())
			.build()

		val response = client.search(searchRequest, Product::class.java)

		return response.hits().hits()
			.mapNotNull { it.source() }
	}
```

7. SearchLog Document 정의

#### 구조 설계

```
┌─────────────────────────────────────────────────────────────────┐
│ 검색 요청 발생                                                  │
│                                                                 │
│ 1. 검색 실행 (products 인덱스)                                  │
│ 2. 검색 로그 저장 (search_logs 인덱스)                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ search_logs 인덱스                                              │
│                                                                 │
│ { "query": "그래픽카드", "timestamp": "2025-12-10T14:35:00Z" }  │
│ { "query": "모니터", "timestamp": "2025-12-10T14:35:01Z" }      │
│ { "query": "그래픽카드", "timestamp": "2025-12-10T14:35:02Z" }  │
│ ...                                                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Aggregation으로 기간별 랭킹 조회                                │
│                                                                 │
│ - 5분 랭킹: now-5m ~ now                                        │
│ - 10분 랭킹: now-10m ~ now                                      │
│ - 1시간 랭킹: now-1h ~ now                                      │
│ - 1일 랭킹: now-1d ~ now                                        │
│ - 1주 랭킹: now-1w ~ now                                        │
└─────────────────────────────────────────────────────────────────┘
```

#### 필드 설계

| 필드 | 타입 | 설명 |
|------|------|------|
| `query` | Keyword | 검색어 (집계용 - 분석되지 않은 원본 필요) |
| `timestamp` | Date | 검색 시간 (범위 쿼리용) |
| `userId` | Keyword | 사용자 ID (선택, 사용자별 분석용) |
| `resultCount` | Long | 검색 결과 수 (선택) |

#### 왜 `query`는 Keyword 타입인가?

- **Text 타입**: 분석기에 의해 토큰화됨 → "그래픽카드" → ["그래픽", "카드"]
- **Keyword 타입**: 원본 그대로 저장 → "그래픽카드"

집계(Aggregation)에서는 **원본 검색어 그대로** 카운트해야 하므로 Keyword 타입을 사용합니다.

```kotlin
// src/main/kotlin/com/example/demo/SearchLog.kt
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
```

8. SearchLogRepository 정의

Aggregation을 사용한 기간별 인기 검색어 조회 구현입니다.

#### Aggregation이란?

Elasticsearch의 집계 기능으로, SQL의 `GROUP BY`와 유사합니다.

```
SQL:     SELECT query, COUNT(*) FROM search_logs GROUP BY query ORDER BY COUNT(*) DESC
ES:      terms aggregation on "query" field
```

```kotlin
// src/main/kotlin/com/example/demo/SearchLogRepository.kt
interface SearchLogRepository : ElasticsearchRepository<SearchLog, String>, CustomSearchLogRepository
```
```kotlin
// src/main/kotlin/com/example/demo/CustomSearchLogRepository.kt
interface CustomSearchLogRepository {
    fun getPopularSearches(duration: String, size: Int): List<SearchRank>
    fun getPopularSearchesBetween(from: String, to: String, size: Int): List<SearchRank>
}   
```
```kotlin
// src/main/kotlin/com/example/demo/CustomSearchLogRepositoryImpl.kt
class CustomSearchLogRepositoryImpl(
    private val elasticsearchOperations: ElasticsearchOperations
) : CustomSearchLogRepository {


	override fun getPopularSearches(duration: String, size: Int): List<SearchRank> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }

		val searchRequest = SearchRequest.Builder()
			.index("search_logs")
			.size(0)
			.query { q ->
				q.range { r ->
					r.untyped { u ->
						u.field("timestamp")
							.gte(JsonData.of("now-$duration"))
					}
				}
			}
			.aggregations("popular_queries") { agg ->
				agg.terms { t ->
					t.field("query")
						.size(size)
				}
			}
			.build()

		val response = client.search(searchRequest, SearchLog::class.java)
		val termsAgg = response.aggregations()["popular_queries"]?.sterms()

		return termsAgg?.buckets()?.array()?.map { bucket ->
			SearchRank(
				query = bucket.key().stringValue(),
				count = bucket.docCount()
			)
		} ?: emptyList()
	}

	override fun getPopularSearchesBetween(from: String, to: String, size: Int): List<SearchRank> {
		val template = elasticsearchOperations as ElasticsearchTemplate
		val client = template.execute { it }

		val searchRequest = SearchRequest.Builder()
			.index("search_logs")
			.size(0)
			.query { q ->
				q.range { r ->
					r.untyped { u ->
						u.field("timestamp")
							.gte(JsonData.of("now-$from"))
							.lt(JsonData.of("now-$to"))
					}
				}
			}
			.aggregations("popular_queries") { agg ->
				agg.terms { t ->
					t.field("query")
						.size(size)
				}
			}
			.build()

		val response = client.search(searchRequest, SearchLog::class.java)
		val termsAgg = response.aggregations()["popular_queries"]?.sterms()

		return termsAgg?.buckets()?.array()?.map { bucket ->
			SearchRank(bucket.key().stringValue(), bucket.docCount())
		} ?: emptyList()
	}

}
```

#### 쿼리 구조 설명

```
┌─────────────────────────────────────────────────────────────────┐
│ SearchRequest                                                   │
├─────────────────────────────────────────────────────────────────┤
│ index: "search_logs"                                            │
│ size: 0  ← 문서 본문 불필요, 집계 결과만 필요                   │
│                                                                 │
│ query:                                                          │
│   range:                                                        │
│     timestamp >= "now-1h"  ← 최근 1시간 내 로그만 필터          │
│                                                                 │
│ aggregations:                                                   │
│   "popular_queries":                                            │
│     terms:                                                      │
│       field: "query"  ← 검색어별로 그룹화                       │
│       size: 10        ← 상위 10개만                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 응답 (Aggregation 결과)                                         │
├─────────────────────────────────────────────────────────────────┤
│ buckets:                                                        │
│   - key: "그래픽카드", doc_count: 150                           │
│   - key: "모니터", doc_count: 120                               │
│   - key: "키보드", doc_count: 80                                │
│   ...                                                           │
└─────────────────────────────────────────────────────────────────┘
```

#### Aggregation 응답 구조

일반 검색과 Aggregation의 응답 구조는 다릅니다.

**일반 검색 응답:**
```json
{
  "hits": {
    "total": { "value": 100 },
    "hits": [
      { "_source": { "query": "그래픽카드", ... } },
      { "_source": { "query": "모니터", ... } }
    ]
  }
}
```

**Aggregation 응답:**
```json
{
  "hits": { "total": { "value": 100 }, "hits": [] },
  "aggregations": {
    "popular_queries": {
      "buckets": [
        { "key": "그래픽카드", "doc_count": 150 },
        { "key": "모니터", "doc_count": 120 },
        { "key": "키보드", "doc_count": 80 }
      ]
    }
  }
}
```

#### `aggregations`와 `buckets`란?

| 용어 | 설명 |
|------|------|
| `aggregations` | 집계 결과를 담는 최상위 필드. 여러 개의 집계를 이름으로 구분 |
| `popular_queries` | 우리가 지정한 집계 이름 (`.aggregations("popular_queries")`) |
| `buckets` | 그룹화된 결과 배열. SQL의 `GROUP BY` 결과 행들과 유사 |
| `key` | 그룹화 기준 값 (검색어) |
| `doc_count` | 해당 그룹에 속한 문서 수 (검색 횟수) |

#### 코드에서 Aggregation 결과 추출

```kotlin
val response = client.search(searchRequest, SearchLog::class.java)

// 1. aggregations에서 "popular_queries" 집계 결과 가져오기
val termsAgg = response.aggregations()["popular_queries"]?.sterms()

// 2. buckets 배열에서 각 bucket의 key와 doc_count 추출
return termsAgg?.buckets()?.array()?.map { bucket ->
    SearchRank(
        query = bucket.key().stringValue(),  // "그래픽카드"
        count = bucket.docCount()            // 150
    )
} ?: emptyList()
```

#### 왜 `sterms()`를 사용하는가?

Elasticsearch Java Client에서 aggregation 타입에 따라 다른 메서드를 사용합니다:

| 메서드 | Aggregation 타입 | 용도 |
|--------|-----------------|------|
| `sterms()` | String Terms | 문자열 필드 집계 (Keyword) |
| `lterms()` | Long Terms | 숫자 필드 집계 |
| `dterms()` | Double Terms | 실수 필드 집계 |
| `dateHistogram()` | Date Histogram | 날짜 히스토그램 |
| `histogram()` | Histogram | 숫자 히스토그램 |
