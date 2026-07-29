const T = (key, title, goal, work, acceptance, tests, files = [], docs = [], labels = [], type = 'type:task') => ({ key, title, goal, work, acceptance, tests, files, docs, labels, type });

module.exports = [
  {
    key: 'E01',
    title: '회원·인증·매장 객체 수준 권한',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:identity', 'area:security', 'area:persistence'],
    risks: ['risk:privacy'],
    goal: '현재 OAuth2 Resource Server 설정 위에 Identity 소유 모델과 store membership을 추가해 역할뿐 아니라 실제 매장 소유권을 모든 점주 API에서 검증한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'docs/architecture/context-map.md', 'docs/architecture/aggregate-invariants.md', 'AGENTS.md'],
    invariants: ['Identity가 actor·role·store membership의 원본이다.', 'STORE_OWNER/STORE_STAFF 역할만으로 다른 매장 자원에 접근할 수 없다.', '인증 실패와 인가 실패를 각각 401과 403으로 구분하고 실패를 익명 actor나 기본 매장으로 대체하지 않는다.', '다른 Context는 Identity Entity가 아니라 공개 membership 조회 API와 actor ID를 사용한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/identity/**', 'src/main/resources/db/migration/V7__create_identity.sql', 'src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'src/test/kotlin/io/github/kdh949/beanflow/identity/**'],
    docs: ['docs/architecture/context-map.md', 'docs/api/api-conventions.md', 'openapi/beanflow-v1.yaml'],
    done: ['Identity 모듈과 membership DB 제약이 구현된다.', '점주·직원 API가 storeId 객체 수준 권한을 검증한다.', 'Spring Modulith와 ArchUnit에서 내부 패키지 접근 위반이 없다.', '인증·인가 계약 테스트가 실제 SecurityFilterChain을 통과한다.'],
    dependsOn: [],
    tasks: [
      T('T1', 'Identity 모듈과 StoreMembership 영속 모델 구현', 'actor, role, store membership의 데이터 소유권을 Identity 모듈에 둔다.', ['`identity` 모듈의 공개 API와 `internal` 패키지를 만든다.', 'Actor/StoreMembership Aggregate와 상태·역할 Value Object를 정의한다.', 'actor identity, `(actor_id, store_id)` membership 유일성, 유효 역할과 상태를 Flyway 제약으로 보강한다.', '다른 모듈이 Entity를 탐색하지 않도록 membership lookup 포트를 공개한다.'], ['동일 actor-store membership 중복 저장이 DB에서 거부된다.', '비활성 actor 또는 membership은 권한 조회에서 허용되지 않는다.', 'package-info와 Modulith 공개 API가 문서의 Context Map과 일치한다.'], ['순수 도메인 역할·상태 테스트', 'PostgreSQL Testcontainers 제약·Repository 테스트', 'Spring Modulith 모듈 검증'], ['src/main/kotlin/io/github/kdh949/beanflow/identity/**', 'src/main/resources/db/migration/V7__create_identity.sql']),
      T('T2', 'JWT actor 해석과 StoreAccessPolicy 구현', 'JWT claim을 BeanFlow actor로 번역하고 Application Service에서 매장 소유권을 재사용 가능하게 검증한다.', ['현재 `SecurityConfiguration`의 issuer/JWK 실패 정책을 유지한다.', 'JWT subject와 roles claim을 actor ID/역할로 번역하는 resolver를 만든다.', '`StoreAccessPolicy.requireReadable/requireWritable(actorId, storeId)`를 구현한다.', 'Controller가 임의 헤더나 요청 body의 actorId를 신뢰하지 않게 한다.'], ['필수 JWK 설정 누락 시 애플리케이션 시작이 실패한다.', '다른 매장 접근은 403이며 존재 여부를 과도하게 노출하지 않는다.', 'Application Service가 정책을 호출하고 Controller가 Repository를 직접 조회하지 않는다.'], ['유효·만료·잘못된 JWT 테스트', '역할은 있으나 membership이 없는 사용자 403 테스트', '자기 매장/다른 매장 객체 수준 인가 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'src/main/kotlin/io/github/kdh949/beanflow/identity/api/**', 'src/main/kotlin/io/github/kdh949/beanflow/identity/internal/**']),
      T('T3', '회원·매장 소속 관리 API와 보안 계약 테스트 추가', 'MVP 운영에 필요한 최소 회원·매장 소속 관리 계약을 정의하고 모든 점주 API의 보안 회귀 기준을 만든다.', ['운영자 전용 membership 생성·비활성화 API를 리소스 중심 URI로 정의한다.', '역할 변경과 membership 변경에 AuditRecord를 같은 로컬 트랜잭션에서 남긴다.', '공통 오류 응답과 correlationId를 적용한다.', 'API가 JPA Entity를 노출하지 않도록 DTO를 사용한다.'], ['권한 변경에는 actor·reason·before/after·correlationId 감사 기록이 남는다.', '일반 고객은 membership 관리 API에 접근하지 못한다.', 'OpenAPI와 REST Docs 예제가 구현 결과와 일치한다.'], ['MockMvc + Spring Security 계약 테스트', '권한 변경과 AuditRecord 원자성 통합 테스트', '민감 claim·토큰이 로그에 남지 않는 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/identity/internal/*Controller.kt', 'src/test/kotlin/io/github/kdh949/beanflow/identity/**', 'openapi/beanflow-v1.yaml'], ['docs/api/error-catalog.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E02',
    title: '매장·메뉴·옵션·영업시간 관리',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:merchant', 'area:persistence', 'area:api'],
    risks: [],
    goal: '주문용 `MenuQuoteUseCase`의 현재 가격 스냅샷 경계를 유지하면서 점주가 매장·영업시간·메뉴·옵션·판매 상태를 관리할 수 있게 한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/merchant/api/MenuQuoteUseCase.kt', 'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/MerchantPersistence.kt', 'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/JpaMenuQuoteService.kt', 'docs/adr/ADR-004-order-price-snapshot.md', 'docs/adr/ADR-026-menu-configuration-sellable-unit-mapping.md'],
    invariants: ['폐점·휴점·영업시간 밖 매장은 새 주문을 받을 수 없다.', '메뉴 가격은 음수가 아니며 주문 이후 과거 OrderLine snapshot을 변경하지 않는다.', 'optionIds는 중복을 거부하고 정렬한 집합으로 MenuConfiguration을 찾는다.', 'Merchant만 메뉴 의미를 Inventory sellable unit 요구량으로 번역한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/merchant/**', 'src/main/resources/db/migration/V8__extend_merchant_management.sql', 'src/test/kotlin/io/github/kdh949/beanflow/merchant/**'],
    docs: ['docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md', 'openapi/beanflow-v1.yaml'],
    done: ['매장·영업시간·메뉴·옵션 관리 API가 객체 수준 인가를 적용한다.', '기존 주문 quote 경로가 관리 모델 변경 후에도 동일한 snapshot 계약을 제공한다.', '가격·옵션·판매 상태 제약이 도메인과 DB에 함께 존재한다.', '과거 주문 가격 불변 회귀 테스트가 통과한다.'],
    dependsOn: ['E01'],
    tasks: [
      T('T1', 'Store·BusinessHours·Menu·Option 관리 모델 확장', '현재 quote 중심 Merchant persistence를 관리 가능한 Aggregate로 확장한다.', ['Store 상태에 OPEN/CLOSED/TEMPORARILY_CLOSED와 pickupAvailable을 명시한다.', 'Asia/Seoul 기준 요일별 영업 구간과 임시 휴점 정보를 저장한다.', 'Menu/Option 판매 상태·정수 KRW 가격·display order를 모델링한다.', '기존 MenuConfiguration의 정규화 option set과 sellable requirement 제약을 보존한다.'], ['겹치는 영업 구간·음수 가격·다른 메뉴 옵션 조합이 거부된다.', '휴점 매장과 판매 중지 메뉴가 quote에 실패한다.', 'Flyway migration이 기존 V1~V6 데이터와 호환된다.'], ['Store/Menu 도메인 테스트', 'PostgreSQL 제약·Repository 테스트', '기존 `JpaMenuQuoteService` 회귀 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/MerchantPersistence.kt', 'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/domain/**', 'src/main/resources/db/migration/V8__extend_merchant_management.sql']),
      T('T2', '점주용 매장·메뉴 관리 Application Service와 REST API 구현', '점주가 자신의 매장 기준정보를 안전하게 변경하도록 유스케이스와 계약을 제공한다.', ['StoreAccessPolicy로 대상 storeId 쓰기 권한을 확인한다.', '매장 상태·영업시간·메뉴·옵션 생성 및 변경 API를 Application Service로 조정한다.', '낙관적 버전 충돌을 409로 변환하고 lost update를 숨기지 않는다.', '관리 명령마다 AuditRecord와 reason code를 남긴다.'], ['Controller가 Merchant Repository를 직접 호출하지 않는다.', '다른 매장의 메뉴 수정은 403이다.', '동시 가격 변경 중 하나만 성공하고 충돌 응답이 명확하다.'], ['Application Service 테스트', 'MockMvc 보안·검증·409 계약 테스트', 'AuditRecord 원자성 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/*Service.kt', 'openapi/beanflow-v1.yaml']),
      T('T3', '주문 가격 snapshot·영업 경계 회귀 검증 강화', 'Merchant 변경이 이미 구현된 주문 snapshot과 reservation 흐름을 깨지 않는다는 증거를 남긴다.', ['가격 변경 전후 주문이 서로 다른 단가 snapshot을 보존하는 통합 테스트를 작성한다.', '자정·영업 시작·종료·임시 휴점 경계에 고정 Clock 테스트를 추가한다.', '판매 상태 변경과 주문 생성 경합에서 quote 시점 계약을 명확히 한다.', '발생 SQL과 쿼리 수를 기록해 quote N+1을 방지한다.'], ['과거 OrderLine 금액이 메뉴 변경으로 수정되지 않는다.', '영업 종료 경계의 요청 결과가 결정적이다.', 'quote 한 번에 필요한 메뉴·옵션·configuration 조회 수가 테스트 또는 보고서로 고정된다.'], ['Ordering-Merchant 통합 테스트', 'Asia/Seoul Clock 경계 테스트', 'SQL count/N+1 회귀 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/merchant/**', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'], ['docs/benchmarks/merchant-quote-query.md'])
    ]
  },
  {
    key: 'E03',
    title: 'PostGIS 기반 가까운 매장 검색',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:discovery', 'area:merchant', 'area:persistence', 'area:performance'],
    risks: ['risk:privacy'],
    goal: 'Merchant 쓰기 Entity를 검색 편의로 확장하지 않고 PostGIS Read Model에서 영업·픽업 가능 매장을 반경과 거리순으로 조회한다.',
    sources: ['docs/adr/ADR-020-nearby-location-privacy.md', 'docs/architecture/architecture-overview.md', 'docs/architecture/context-map.md', 'docs/product/business-policy-decisions.md'],
    invariants: ['요청의 정밀 위·경도는 DB·로그·trace에 영구 저장하지 않는다.', 'Discovery는 위치 검색용 Read Model만 소유한다.', '반경 필터는 `ST_DWithin`, 거리 정렬은 `(distance, storeId)` 결정 순서를 사용한다.', '검색 응답은 영업·픽업 가능 상태의 freshness 의미를 문서화한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/discovery/**', 'src/main/resources/db/migration/V9__create_discovery_postgis.sql', 'src/test/kotlin/io/github/kdh949/beanflow/discovery/**', 'docker-compose.yml'],
    docs: ['docs/adr/ADR-020-nearby-location-privacy.md', 'docs/benchmarks/nearby-store-postgis.md', 'openapi/beanflow-v1.yaml'],
    done: ['PostGIS geography와 GiST 인덱스가 migration으로 구성된다.', '반경·거리·영업·픽업 필터와 안정적인 cursor가 제공된다.', '원본 좌표 비보존·로그 마스킹 테스트가 통과한다.', '1만·10만 합성 데이터의 실행계획과 실제 측정값이 기록된다.'],
    dependsOn: ['E02'],
    tasks: [
      T('T1', 'Discovery Read Model·PostGIS geography·GiST 인덱스 구성', '매장 위치와 검색 상태를 별도 projection으로 저장한다.', ['PostGIS extension 활성화와 `geography(Point, 4326)` 컬럼을 migration으로 추가한다.', 'storeId 유일성, 좌표 범위, GiST 공간 인덱스를 구성한다.', 'Merchant 변경 사실로 projection을 멱등 upsert할 공개 경계를 정의한다.', '사용자 요청 좌표를 별도 Entity에 저장하지 않는다.'], ['`ST_DWithin`이 GiST 인덱스를 사용할 수 있는 스키마다.', '동일 store event 재처리가 중복 row를 만들지 않는다.', 'Merchant 쓰기 테이블에 검색 전용 컬럼·연관관계를 추가하지 않는다.'], ['PostGIS Testcontainers migration 테스트', 'projection 중복 이벤트 테스트', '좌표 유효 범위 제약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/discovery/internal/**', 'src/main/resources/db/migration/V9__create_discovery_postgis.sql']),
      T('T2', '가까운 매장 Query Repository와 cursor API 구현', '반경 내 영업 중·픽업 가능 매장을 거리순으로 반환한다.', ['`GET /api/v1/stores/nearby`의 lat/lng/radiusMeters/cursor 계약을 구현한다.', '`ST_DWithin`으로 후보를 제한하고 `ST_Distance`와 storeId로 정렬한다.', 'cursor에 distance와 storeId를 담고 잘못된 cursor를 400으로 처리한다.', '반경 상한과 페이지 크기 상한을 API convention에 명시한다.'], ['반경 경계 안팎의 결과가 정확하다.', '동일 거리 매장이 페이지 간 누락·중복되지 않는다.', '휴점 또는 pickupAvailable=false 매장은 제외된다.'], ['경계 좌표 통합 테스트', 'cursor 동률·다음 페이지 테스트', '입력 검증·오류 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/discovery/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/discovery/internal/*QueryRepository.kt', 'openapi/beanflow-v1.yaml']),
      T('T3', '위치정보 비보존 검증과 PostGIS 실행계획 측정', '검색 정확성뿐 아니라 개인정보 최소화와 인덱스 사용을 증명한다.', ['구조화 로그·오류 응답·metric tag에 원본 lat/lng가 포함되지 않는 검증을 추가한다.', '1만·10만 매장 합성 데이터 생성기를 만든다.', '`EXPLAIN (ANALYZE, BUFFERS)`에서 index scan, actual rows, buffers를 기록한다.', '인덱스 전후를 동일 조건으로 측정하고 미측정 개선율을 쓰지 않는다.'], ['로그 캡처 테스트에서 원본 좌표가 발견되지 않는다.', '실행계획 보고서에 환경·데이터 크기·쿼리·actual time이 있다.', '공간 인덱스가 사용되지 않으면 원인과 후속 조치를 실패 결과로 기록한다.'], ['로그 redaction 테스트', '10k/100k 데이터 정확성·p95 측정', '실행계획 회귀 검토'], ['src/test/kotlin/io/github/kdh949/beanflow/discovery/**', 'scripts/seed-nearby-stores.*', 'docs/benchmarks/nearby-store-postgis.md'], ['docs/benchmarks/nearby-store-postgis.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E04',
    title: '픽업 슬롯 관리와 수용량 운영',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:fulfillment', 'area:merchant', 'area:api'],
    risks: ['risk:concurrency'],
    goal: '이미 구현된 `PickupReservationOperations`를 유지하면서 점주가 슬롯을 설정하고 고객이 예약 가능한 용량을 조회하도록 확장한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/fulfillment/api/PickupReservationOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/PickupReservationService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/PickupReservationPersistence.kt', 'docs/product/business-policy-decisions.md'],
    invariants: ['예약 수+확정 수는 슬롯 capacity를 넘지 않는다.', '주문당 활성 PickupReservation은 하나다.', '결제 전 5분 lease와 승인·만료 경쟁 규칙을 변경하지 않는다.', 'Fulfillment는 Order 상태를 복제하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/fulfillment/**', 'src/main/resources/db/migration/V10__extend_pickup_slots.sql', 'src/test/kotlin/io/github/kdh949/beanflow/fulfillment/**'],
    docs: ['docs/product/business-policy-decisions.md', 'docs/architecture/transaction-boundaries.md', 'openapi/beanflow-v1.yaml'],
    done: ['점주 슬롯 생성·변경과 고객 가용 슬롯 조회가 구현된다.', '기존 reserve/confirm/release/expire 동작과 DB 제약이 유지된다.', '마지막 슬롯 경합과 lease 경계 테스트가 통과한다.', '긴급 휴점 시 신규 예약 차단과 기존 주문 영향이 명시된다.'],
    dependsOn: ['E01', 'E02'],
    tasks: [
      T('T1', 'PickupSlot 운영 모델과 점주 관리 API 구현', '점주가 매장 영업 정책 안에서 픽업 시간대와 capacity를 관리한다.', ['PickupSlot 시작·종료·capacity·상태와 낙관적 version을 명시한다.', '영업시간 밖·겹치는 슬롯·음수 capacity를 거부한다.', '이미 예약된 수보다 capacity를 낮추는 변경을 409로 거부한다.', 'StoreAccessPolicy와 AuditRecord를 적용한 관리 API를 추가한다.'], ['슬롯 시간 구간과 매장별 유일성이 DB에서 보강된다.', '다른 매장 점주의 변경은 403이다.', '동시 capacity 변경에서 lost update가 발생하지 않는다.'], ['도메인 시간·capacity 테스트', 'PostgreSQL Repository 제약 테스트', '점주 API 보안·409 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/**', 'src/main/resources/db/migration/V10__extend_pickup_slots.sql']),
      T('T2', '고객용 가용 픽업 슬롯 조회 구현', '현재 시각·최소 준비시간·영업 상태·잔여 capacity를 반영한 조회를 제공한다.', ['`GET /api/v1/stores/{storeId}/pickup-slots`를 구현한다.', '예약+확정 수량과 capacity로 available quantity를 계산한다.', 'Asia/Seoul 자정과 최소 준비시간 경계를 고정 Clock으로 처리한다.', '조회 편의를 위해 Order나 Reservation 객체 그래프를 EAGER로 확장하지 않는다.'], ['마감·휴점·capacity 소진 슬롯은 예약 가능으로 표시되지 않는다.', '목록 조회는 Projection 또는 Query Repository를 사용한다.', 'API 시간 형식과 cursor/기간 제한이 문서화된다.'], ['가용량 계산 테스트', '영업·자정·최소 준비시간 경계 테스트', 'SQL 수/N+1 회귀 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/*QueryRepository.kt', 'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/*Controller.kt']),
      T('T3', '마지막 슬롯 경합·긴급 휴점 회귀 테스트 보강', '기존 예약 코드가 운영 API 추가 후에도 oversubscription 0을 보장하는지 검증한다.', ['동일 마지막 슬롯에 다수 동시 주문을 실행한다.', '수락·결제·lease 만료와 슬롯 변경의 경쟁 조건을 고정한다.', '긴급 휴점 후 신규 예약은 실패하고 기존 PAID 주문은 별도 취소 흐름 대상으로 남긴다.', 'Lock Wait·실패 유형·최종 예약/확정 수를 기록한다.'], ['성공 예약+확정 합계가 capacity 이하이다.', '중복 confirm/release가 수량을 두 번 바꾸지 않는다.', '휴점이 기존 결제 주문을 암묵적으로 취소하거나 성공 처리하지 않는다.'], ['Executor 동시성 테스트', 'Testcontainers row lock/conditional update 테스트', '수량 tie-out assertion'], ['src/test/kotlin/io/github/kdh949/beanflow/fulfillment/**', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'], ['docs/benchmarks/pickup-slot-concurrency.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E05',
    title: '판매 재고 관리와 수동 조정',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:inventory', 'area:operations', 'area:api'],
    risks: ['risk:concurrency'],
    goal: '현재 StockReservation reserve/confirm/release 경계를 유지하며 점주가 sellable unit 재고를 조회·보충·조정하고 품절을 운영할 수 있게 한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/inventory/api/StockReservationOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/StockReservationService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/StockReservationPersistence.kt', 'docs/adr/ADR-026-menu-configuration-sellable-unit-mapping.md'],
    invariants: ['available/reserved/committed 수량은 음수가 될 수 없다.', '주문·sellable unit별 활성 예약은 하나다.', '수동 조정은 actor·reason·before/after 감사 기록이 필수다.', 'Inventory는 menu/option 의미를 해석하지 않고 sellableUnitId와 수량만 소유한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/inventory/**', 'src/main/resources/db/migration/V11__extend_inventory_management.sql', 'src/test/kotlin/io/github/kdh949/beanflow/inventory/**'],
    docs: ['docs/architecture/aggregate-invariants.md', 'docs/product/business-policy-decisions.md', 'openapi/beanflow-v1.yaml'],
    done: ['재고 조회·보충·조정 API와 감사 기록이 구현된다.', '기존 예약 흐름의 음수·이중 확정·이중 복원이 방지된다.', '품절 상태가 메뉴 주문 가능 응답에 반영된다.', '동시 조정·마지막 재고 테스트가 통과한다.'],
    dependsOn: ['E01', 'E02'],
    tasks: [
      T('T1', 'SellableStock 조회·보충·수동 조정 유스케이스 구현', '점주가 자신의 매장 sellable unit 수량을 운영하되 원장을 설명할 수 있게 한다.', ['현재 SellableStock 요약과 reservation 수량 정의를 정리한다.', '재고 보충과 절대값 덮어쓰기 대신 delta 기반 수동 조정 명령을 구현한다.', '조정 reason·actor·source reference를 AuditRecord에 남긴다.', 'StoreAccessPolicy와 optimistic version/row lock 중 현재 코드에 맞는 방식을 사용한다.'], ['조정 후 available/reserved/committed tie-out이 성립한다.', '필수 reason 없는 수동 변경은 거부된다.', '다른 매장의 재고 조회·변경은 403이다.'], ['재고 합계 도메인 테스트', '동시 수동 조정 통합 테스트', 'AuditRecord 원자성 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/**', 'src/main/resources/db/migration/V11__extend_inventory_management.sql']),
      T('T2', '점주 재고 API와 고객 품절 조회 연동', '점주 운영 화면과 고객 주문 전 조회에서 같은 원본 재고 상태를 일관되게 제공한다.', ['점주용 재고 목록·조정 API를 DTO Projection으로 구현한다.', '고객 메뉴 응답에 sellable requirements 충족 여부를 계산해 soldOut을 제공한다.', '목록 API에서 Reservation 컬렉션을 로딩하지 않는다.', '현재 quote 시점 최종 검증은 유지해 조회와 주문 사이 TOCTOU를 안전하다고 오해하지 않게 한다.'], ['품절 표시와 실제 주문 실패 의미가 문서화된다.', '목록 조회가 N+1 없이 동작한다.', '재고가 조회 후 소진되면 주문 생성이 명확한 재고 충돌 오류를 반환한다.'], ['MockMvc 계약 테스트', 'DTO Projection SQL count 테스트', '조회-주문 경합 통합 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/**', 'openapi/beanflow-v1.yaml']),
      T('T3', '마지막 재고·중복 확정·복원 동시성 검증', '기존 StockReservation 구현의 최종 방어선을 실제 PostgreSQL에서 검증한다.', ['마지막 수량에 다수 주문을 동시에 예약한다.', 'PaymentApproved·OrderRejected·lease expiry 중복 실행을 반복한다.', '성공/실패 후 stock summary와 reservations를 SQL로 tie-out한다.', 'Lock Wait와 재시도 횟수를 수집할 metric 지점을 추가한다.'], ['oversell과 음수 수량이 0건이다.', '중복 confirm/release가 최종 수량을 바꾸지 않는다.', '테스트 실패 시 재시도 루프로 숨기지 않고 원인 SQL/lock을 기록한다.'], ['PostgreSQL 동시성 반복 테스트', 'Unique/check constraint 테스트', '중복 이벤트 멱등성 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/inventory/**', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'], ['docs/benchmarks/stock-concurrency.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E06',
    title: '캠페인·쿠폰 발급과 운영 API',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:promotion', 'area:merchant', 'area:api'],
    risks: ['risk:concurrency', 'risk:money'],
    goal: '현재 주문 생성에서 사용하는 CouponReservation을 바탕으로 정액·정률 Campaign과 쿠폰 발급·조회·상태 관리 API를 완성한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/promotion/api/CouponReservationOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/CouponReservationService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/CouponReservationPersistence.kt', 'docs/adr/ADR-024-coupon-calculation-model.md'],
    invariants: ['한 주문에는 쿠폰 최대 하나만 적용한다.', 'Campaign은 FIXED_KRW 또는 RATE_BPS 계약과 대상 메뉴·minimum·maximum을 지킨다.', 'CouponIssuance는 동시에 두 주문에 예약·사용될 수 없다.', '쿠폰 비용 부담 주체와 비율은 주문 확정 시 snapshot으로 남는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/promotion/**', 'src/main/resources/db/migration/V12__extend_campaign_coupon.sql', 'src/test/kotlin/io/github/kdh949/beanflow/promotion/**'],
    docs: ['docs/adr/ADR-024-coupon-calculation-model.md', 'docs/product/business-policy-decisions.md', 'openapi/beanflow-v1.yaml'],
    done: ['Campaign 생성·변경과 쿠폰 발급·목록 API가 구현된다.', '기간·한도·대상·비용 부담 제약이 도메인과 DB에 반영된다.', '기존 주문 할인 배분 회귀 테스트가 유지된다.', '마지막 발급/동일 쿠폰 사용 경합이 초과 없이 끝난다.'],
    dependsOn: ['E01', 'E02'],
    tasks: [
      T('T1', 'Campaign 관리 모델과 점주·운영자 API 구현', '정액·정률 할인 정책과 비용 부담 정보를 변경 이력과 함께 관리한다.', ['Campaign type별 필수/금지 금액 필드를 검증한다.', 'rate 1..10000 bps, minimum, maximum, 대상 menu IDs와 share 합계 100%를 보장한다.', 'Asia/Seoul 기간 경계를 Instant로 저장한다.', '매장 부담 Campaign은 StoreAccessPolicy, 플랫폼 Campaign은 운영자 권한을 적용한다.'], ['잘못된 type/value/share 조합이 DB 또는 도메인에서 거부된다.', '시작된 Campaign의 과거 주문 snapshot을 소급 변경하지 않는다.', '동시 Campaign 수정은 409로 드러난다.'], ['Campaign 정책 도메인 테스트', 'PostgreSQL check/unique 테스트', '관리 API 권한·계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/**', 'src/main/resources/db/migration/V12__extend_campaign_coupon.sql']),
      T('T2', '쿠폰 발급·내 쿠폰 조회 API와 발급 한도 구현', '회원별 쿠폰 발급 이력과 사용 가능 상태를 명시적으로 제공한다.', ['CouponIssuance 발급·만료·예약·사용·복원 상태 전이를 정리한다.', 'Campaign 총 발급 수량과 회원별 발급 제한을 DB 최종 방어와 함께 구현한다.', '`POST /campaigns/{id}/coupon-issuances`, 고객 쿠폰 목록 API를 구현한다.', '동일 발급 요청에 source reference 또는 idempotency key를 적용한다.'], ['총량·회원 한도를 초과하지 않는다.', '만료·사용된 쿠폰은 주문 예약에 사용할 수 없다.', '같은 source 요청 재실행이 중복 issuance를 만들지 않는다.'], ['발급 상태 머신 테스트', '마지막 발급 동시성 테스트', '목록 Projection 및 API 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/CouponReservationPersistence.kt', 'openapi/beanflow-v1.yaml']),
      T('T3', '쿠폰 계산·동시 사용·복원 회귀 검증', '관리 기능 추가 뒤에도 주문 할인 snapshot과 쿠폰 생명주기가 결정적으로 재현되게 한다.', ['대상/비대상 혼합 주문 minimum과 할인 배분을 검증한다.', '같은 쿠폰으로 두 주문을 동시에 생성한다.', 'lease expiry·결제 거절·매장 거절에서 release/restore를 중복 실행한다.', 'Campaign 변경 후 과거 OrderLine couponDiscountKrw가 불변인지 확인한다.'], ['할인 배분 합계와 payable 합계가 항상 일치한다.', '동시 주문 중 하나만 쿠폰을 예약한다.', '중복 release/restore가 사용 가능 횟수를 증가시키지 않는다.'], ['OrderPricingCalculator 회귀 테스트', 'CouponReservation PostgreSQL 동시성 테스트', '중복 보상 멱등성 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/promotion/**', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'], ['docs/benchmarks/coupon-concurrency.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E07',
    title: '매장 주문 수락·거절·timeout 보상',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:ordering', 'area:payment', 'area:fulfillment', 'area:operations'],
    risks: ['risk:money', 'risk:concurrency', 'risk:data-consistency'],
    goal: '현재 PAID까지만 있는 Order 상태 머신을 매장 수락·거절로 확장하고 2분 경고·3분 자동 거절과 owner Context별 보상을 명시적으로 운영한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt', 'docs/adr/ADR-015-store-acceptance-timeout-compensation.md', 'docs/product/business-policy-decisions.md'],
    invariants: ['Ordering이 수락 deadline과 Order 전이의 owner다.', 'PAID에서 ACCEPTED 또는 REJECTED 중 하나의 guarded transition만 성공한다.', 'REJECTED를 환불·복원 완료로 간주하지 않는다.', '보상 실패는 retry 상태와 ReprocessingCase로 남고 Order를 되돌리지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/**', 'src/main/kotlin/io/github/kdh949/beanflow/payment/**', 'src/main/resources/db/migration/V13__add_store_acceptance.sql', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'],
    docs: ['docs/adr/ADR-015-store-acceptance-timeout-compensation.md', 'docs/architecture/transaction-boundaries.md', 'docs/architecture/event-catalog.md', 'openapi/beanflow-v1.yaml'],
    done: ['Order 상태와 수락 deadline이 구현된다.', '매장 수락·거절 API가 객체 수준 권한과 멱등성을 가진다.', '2분 경고·3분 timeout worker가 재실행 가능하다.', '환불·슬롯·재고·쿠폰·포인트 보상 실패가 관측·재처리 가능하다.'],
    dependsOn: ['E01', 'E04', 'E05', 'E06'],
    tasks: [
      T('T1', 'Order ACCEPTED·REJECTED 상태와 수락 deadline 모델링', '현재 `PENDING_PAYMENT/PAID/EXPIRED/CANCELLED` 상태에 매장 결정 상태를 안전하게 추가한다.', ['OrderState에 ACCEPTED와 REJECTED를 추가하고 허용 전이 메서드를 Aggregate에 둔다.', 'Payment 승인 Tx2에서 `acceptanceDeadlineAt = paidAt+3m`와 warningAt을 저장한다.', 'PAID가 아닌 주문 수락·거절과 ACCEPTED 이후 단순 거절을 거부한다.', '상태·deadline·version을 Flyway와 Entity에 반영한다.'], ['상태 변경이 setter가 아니라 Aggregate 명령으로 보호된다.', '수락과 거절 동시 실행에서 하나만 성공한다.', '기존 결제·만료 상태 회귀 테스트가 통과한다.'], ['Order 상태 전이 단위 테스트', 'Pessimistic/optimistic race 통합 테스트', 'migration 및 기존 데이터 호환 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt', 'src/main/resources/db/migration/V13__add_store_acceptance.sql']),
      T('T2', '매장 주문 목록·수락·거절 API와 보상 시작 구현', '점주가 결제된 주문을 조회하고 수락 또는 명시 거절하도록 한다.', ['`store-orders`는 별도 Aggregate가 아니라 Ordering의 매장 관점 DTO로 구현한다.', 'StoreAccessPolicy를 적용한 목록·상세·수락·거절 API를 만든다.', '거절 원본 전이 후 refund와 각 owner release/restore를 명령 또는 영속 event로 시작한다.', '거절 명령 idempotency와 AuditRecord를 적용한다.'], ['다른 매장 주문 접근은 403이다.', '중복 거절이 환불·복원을 다시 시작하지 않는다.', 'Order REJECTED 응답이 보상 완료를 거짓으로 나타내지 않는다.'], ['점주 API 계약·인가 테스트', '거절 idempotency 테스트', '보상 일부 실패와 ReprocessingCase 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*StoreOrder*', 'src/main/kotlin/io/github/kdh949/beanflow/payment/api/**', 'openapi/beanflow-v1.yaml']),
      T('T3', '2분 경고·3분 자동 거절 worker와 경쟁 조건 구현', '응답 없는 PAID 주문을 결정적으로 경고하고 자동 거절한다.', ['warningAt 도달 주문을 chunk 조회해 order/deadline reference로 경고 요청을 한 번만 생성한다.', 'deadlineAt 도달 주문을 lock하고 여전히 PAID일 때 REJECTED로 전환한다.', '수락과 timeout worker 경쟁을 guarded transition으로 해결한다.', '자동 환불 timeout은 UNKNOWN/RECONCILING으로 남기고 manual review를 연결한다.'], ['worker 재실행이 중복 경고·환불·복원을 만들지 않는다.', 'ACCEPTED가 먼저면 timeout이 상태를 바꾸지 않는다.', 'REJECTED가 먼저면 늦은 accept가 409다.', 'Clock 경계가 정확히 2분·3분에서 테스트된다.'], ['고정 Clock 경계 테스트', '수락-vs-timeout 동시성 테스트', 'worker 중단·재시작 및 보상 장애 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*Acceptance*Worker.kt', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'], ['docs/incidents/store-acceptance-timeout.md'])
    ]
  },
  {
    key: 'E08',
    title: '제조·준비 완료·픽업 완료 상태 머신',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:ordering', 'area:fulfillment', 'area:api'],
    risks: ['risk:data-consistency'],
    goal: 'Ordering이 소유하는 주문 상태를 ACCEPTED 이후 PREPARING·READY·COMPLETED로 확장하고 후속 이벤트의 신뢰 가능한 원본 사실을 만든다.',
    sources: ['docs/architecture/context-map.md', 'docs/architecture/transaction-boundaries.md', 'docs/architecture/event-catalog.md', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt'],
    invariants: ['Ordering이 접수·제조·준비·픽업 완료 상태를 소유한다.', 'Fulfillment는 PickupReservation을 소유하되 Order 상태를 복제하지 않는다.', 'OrderReady와 OrderCompleted는 원본 트랜잭션 커밋 뒤 후속 처리된다.', '부수효과 실패로 COMPLETED 주문을 되돌리지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/**', 'src/main/resources/db/migration/V14__add_order_fulfillment_states.sql', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**'],
    docs: ['docs/architecture/event-catalog.md', 'docs/architecture/transaction-boundaries.md', 'openapi/beanflow-v1.yaml'],
    done: ['PREPARING·READY·COMPLETED 상태와 허용 전이가 구현된다.', '점주 상태 변경과 고객 조회 API가 일치한다.', 'OrderReady·OrderCompleted 이벤트 envelope가 생성된다.', '중복 상태 명령과 이벤트가 후속 부수효과를 중복시키지 않는다.'],
    dependsOn: ['E07'],
    tasks: [
      T('T1', 'Order PREPARING·READY·COMPLETED 전이와 완료 시각 구현', '제조부터 픽업 완료까지 Aggregate가 허용 전이를 보호한다.', ['OrderState와 Entity/migration에 PREPARING, READY, COMPLETED를 추가한다.', 'ACCEPTED→PREPARING→READY→COMPLETED 전이와 idempotent same-command 정책을 정의한다.', '각 상태 시각과 completedAt을 저장해 정산 귀속일 원본으로 사용한다.', '역전·건너뛰기·terminal 상태 변경을 409로 거부한다.'], ['완료 주문의 항목·금액·completedAt은 불변이다.', '잘못된 상태 전이는 DB를 변경하지 않는다.', '기존 PAID/EXPIRED/CANCELLED 상태 테스트가 유지된다.'], ['순수 상태 머신 단위 테스트', 'JPA version 충돌 테스트', 'migration 회귀 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt', 'src/main/resources/db/migration/V14__add_order_fulfillment_states.sql']),
      T('T2', '점주 상태 변경·고객 주문 상태 조회 API 구현', '점주 명령과 고객 조회가 같은 Ordering 원본 상태를 사용하도록 한다.', ['`PATCH /api/v1/store-orders/{orderId}/status` 또는 상태 하위 리소스 계약을 확정한다.', 'StoreAccessPolicy와 actor별 허용 명령을 적용한다.', '고객은 자신의 주문만 조회하고 매장 내부 reason은 노출하지 않는다.', '상태 변경마다 AuditRecord와 correlationId를 남긴다.'], ['점주·고객 객체 수준 인가가 모두 적용된다.', 'Controller가 Repository를 직접 호출하지 않는다.', '상태 충돌은 일관된 409 error code를 반환한다.'], ['MockMvc 인증·인가·상태 계약 테스트', 'Application Service 전이 테스트', 'AuditRecord 원자성 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderController.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*StoreOrder*']),
      T('T3', 'OrderReady·OrderCompleted 이벤트와 중복 방지 계약 추가', 'READY와 COMPLETED를 알림·포인트·정산·분석이 소비할 수 있는 원본 사실로 발행한다.', ['eventId, aggregateVersion, occurredAt, payloadVersion, correlationId, causationId envelope를 만든다.', '상태 전이 커밋과 publication 기록의 원자성 경계를 준비한다.', 'OrderCompleted payload에 정산·포인트가 필요한 snapshot ID와 금액을 포함하되 Entity를 노출하지 않는다.', '같은 Order version에서 event 하나만 생성되게 source reference를 둔다.'], ['중복 완료 명령이 추가 event를 만들지 않는다.', 'event payload version과 소비자 멱등 키가 문서화된다.', 'publication 실패가 상태 성공으로 숨겨지지 않는다.'], ['이벤트 envelope 단위 테스트', '상태 전이+publication 통합 테스트', '중복 command/event 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**Event*.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/**'], ['docs/architecture/event-catalog.md'])
    ]
  },
  {
    key: 'E09',
    title: '빠른 재주문',
    milestone: 'R1 — 주문 진입·매장 운영',
    priority: 'priority:P0',
    areas: ['area:ordering', 'area:merchant', 'area:api'],
    risks: ['risk:concurrency'],
    goal: '과거 Order snapshot을 복제하되 과거 가격·재고·프로모션을 재사용하지 않고 현재 조건을 다시 검증해 새 주문을 생성한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/api/CreateOrderUseCase.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/GetOrderService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderSnapshotAssembler.kt', 'docs/adr/ADR-004-order-price-snapshot.md'],
    invariants: ['재주문은 과거 Order를 수정하지 않고 새 Order와 새 idempotency scope를 생성한다.', '메뉴·옵션·가격·재고·슬롯·쿠폰·포인트는 현재 상태로 재검증한다.', '과거 쿠폰·포인트 사용을 자동 복사하지 않는다.', '품절·판매중지·옵션 변경은 부분 성공으로 숨기지 않고 구체적 오류를 반환한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/**', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/**', 'openapi/beanflow-v1.yaml'],
    docs: ['docs/product/end-to-end-flow.md', 'docs/api/error-catalog.md', 'openapi/beanflow-v1.yaml'],
    done: ['과거 주문 기반 reorder draft/command가 구현된다.', '현재 조건 재검증과 새 주문 생성이 기존 CreateOrderUseCase를 재사용한다.', '가격 변경·품절·옵션 삭제·슬롯 만료 실패가 계약화된다.', '재주문 idempotency와 객체 수준 인가 테스트가 통과한다.'],
    dependsOn: ['E02', 'E04', 'E05', 'E06', 'E08'],
    tasks: [
      T('T1', '과거 주문 snapshot 기반 재주문 후보 조회 구현', '고객 소유 주문의 메뉴·옵션 구성을 재주문 입력 후보로 변환한다.', ['GetOrderService에서 JPA Entity가 아닌 재주문용 DTO를 제공한다.', '고객 소유권을 검증하고 다른 고객 주문은 404/403 정책에 맞게 차단한다.', '과거 lineSequence와 menuId/optionIds만 후보로 사용하고 과거 단가·할인을 실행 금액으로 쓰지 않는다.', 'COMPLETED 또는 정책상 허용 상태만 후보로 제한한다.'], ['과거 금액 필드가 새 주문 command 금액으로 전달되지 않는다.', '삭제된 옵션도 후보 응답에서 명시적인 unavailable 상태로 표시하거나 생성 시 실패한다.', '목록/상세 조회에 N+1이 없다.'], ['소유권 테스트', 'snapshot→candidate 매핑 단위 테스트', 'Projection SQL count 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/GetOrderService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**']),
      T('T2', '재주문 명령에서 현재 가격·재고·슬롯 재검증 후 새 주문 생성', '기존 주문 생성 유스케이스를 우회하지 않고 현재 계약으로 새 거래를 만든다.', ['`POST /api/v1/orders/{orderId}/reorders`의 idempotency key 계약을 정의한다.', '현재 MenuQuoteUseCase로 메뉴·옵션·가격을 다시 조회한다.', '새 pickupSlotId와 선택적 새 coupon/points를 명시적으로 입력받는다.', '검증 후 기존 CreateOrderUseCase를 호출해 네 자원 예약과 5분 lease를 동일하게 적용한다.'], ['가격 변경은 새 OrderLine에 현재 가격으로 반영된다.', '품절·슬롯 마감·판매중지 중 하나라도 실패하면 새 주문이 생성되지 않는다.', '동일 key 재요청은 최초 response를 재생한다.'], ['가격 변경 재주문 통합 테스트', '마지막 재고/슬롯 경합 테스트', 'idempotency payload hash 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*Reorder*', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/CreateOrderUseCase.kt']),
      T('T3', '재주문 API 계약·실패 UX·회귀 테스트 작성', '클라이언트가 재주문 불가 이유를 구분하고 새 선택으로 복구할 수 있게 한다.', ['판매중지 메뉴, 옵션 변경, 가격 변경, 품절, 닫힌 매장, 만료 슬롯 오류 코드를 정리한다.', '가격 변경을 자동 승인할지 클라이언트 확인이 필요한지 현재 MVP 정책을 문서화한다.', 'REST Docs/OpenAPI 요청·응답과 오류 예제를 추가한다.', '기존 주문 생성 계약 테스트를 함께 실행한다.'], ['오류 코드가 내부 Entity/SQL 정보를 노출하지 않는다.', '실패 원인이 empty result나 200으로 숨겨지지 않는다.', 'OpenAPI와 MockMvc 스니펫이 일치한다.'], ['MockMvc 정상·오류 계약 테스트', 'Spring Security 소유권 테스트', '기존 CreateOrderService 회귀 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/ordering/**', 'openapi/beanflow-v1.yaml'], ['docs/api/error-catalog.md', 'openapi/beanflow-v1.yaml'])
    ]
  }
];
