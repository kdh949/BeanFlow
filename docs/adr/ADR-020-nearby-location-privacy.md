# ADR-020: 가까운 매장 검색과 정밀 위치 최소 보존

- **Status:** Accepted
- **Date:** 2026-07-28
- **Implementation owner:** [Nearby Store Discovery](../exec-plans/completed/nearby-store-discovery.md)

## Context

가까운 매장 검색에는 정밀 위·경도가 필요하지만 BR-28은 요청 처리 밖의 영구 저장과
로그·추적 노출을 금지한다.

## Decision

- latitude와 longitude는 nearby query의 요청 범위에서만 사용한다.
- 사용자 원본 좌표를 DB, cache, application log, trace attribute 또는 AuditRecord에
  저장하지 않는다.
- Discovery가 필요한 매장 좌표는 Merchant 소유 Store 위치를 검색용 Read Model로
  투영할 수 있다.
- **2026-08-01 Store profile ownership clarification:** 검색 가능한 매장 정보는
  `merchant_store` 쓰기 Entity에 검색 편의 필드를 추가하지 않고 Merchant가 별도 1:1
  `StoreDiscoveryProfile`로 소유한다. profile은 `store_id` PK/FK, 검증된 공개 매장명과
  `geography(Point,4326)` 위치를 가지며 GiST index를 둔다. Discovery는 영속 복제본이나
  동기화 event를 만들지 않고 Merchant public Query API가 반환하는 DTO projection만 소비한다.
- 기존 Store가 없으면 empty profile migration path를 허용한다. 기존 Store가 하나라도 있으면
  모든 Store에 검증 가능한 owner source의 profile이 있어야 endpoint를 활성화할 수 있다.
  unresolved row를 placeholder 이름, `(0,0)` 또는 임의 좌표로 보완하지 않고 migration/deployment와
  애플리케이션 시작을 실패시킨다.
- 검색 결과 분석은 선택된 storeId와 사전에 정의된 반경 구간처럼 비정밀 정보만 쓴다.
- PostgreSQL/PostGIS 장애를 빈 목록이나 local 거리 계산 fallback으로 바꾸지 않는다.

## Alternatives Considered

- 사용자 검색 좌표 장기 저장
- 좌표를 일반 request log에 포함
- 요청 전용 사용과 비정밀 분석
- `merchant_store`에 이름·geometry·검색 index 직접 추가
- Discovery-owned 영속 복제와 event/outbox 동기화

## Rationale

기능에 필요하지 않은 정밀 위치 보존과 노출 면적을 줄인다.

## Consequences

- 문제 조사 시 원본 사용자 좌표를 재생할 수 없다.
- test fixture는 합성 좌표만 사용하고 log redaction을 검증해야 한다.
- Merchant profile source가 없는 기존 Store는 Nearby 배포를 차단한다.
- Discovery 조회는 Merchant public Query API에 의존하지만 Merchant JPA Entity나 Repository를
  직접 사용하지 않는다.

### Saved address evaluation (2026-08-15) — 승격하지 않음

`productization-70` 통합 검색 설계 중 "설정 주소지 인근 검색" 요구가 제기됐다. BR-28의 Revisit
Condition("위치 기반 개인화에 대한 명시적 동의와 보존 정책이 도입될 때")에 해당하는지 검토했고
**이 결정을 개정하지 않기로 했다.**

저장된 주소지는 브라우저 client storage에만 두고 매 요청 좌표를 전송한다. 서버는 좌표를 계속
요청 범위에서만 사용하며 `identity_customer_account`에 주소·좌표 컬럼을 추가하지 않는다. 공개 API
계약은 변경되지 않는다. 검토한 대안과 기각 사유는 [MD-2026-017](../decisions/minor-decisions.md)에
있다.

이 평가는 요구 자체를 기각한 것이 아니라 **서버 보존 없이 충족 가능하다**는 판단이다. 기기 간
동기화나 계정 귀속 주소가 실제 요구가 되면 그때 동의·보존·삭제 정책과 함께 이 결정을 다시 연다.

## Verification

- DB schema, log와 trace에 원본 사용자 좌표 부재
- 저장된 주소지가 server persistence, log, metric tag에 나타나지 않음
- 잘못된 좌표·반경의 명시적 오류
- PostGIS 장애 시 503, 빈 성공 응답 없음
- empty/verified/unresolved Store profile inventory와 startup gate
- `merchant_store`에 검색용 이름·geometry가 추가되지 않고 별도 profile만 존재함

## Implementation evidence (2026-08-06)

Milestone 1~3 구현이 이 결정을 다음과 같이 실현했다.

- V33이 PostGIS extension, 별도 `merchant_store_discovery_profile`
  (`store_id` PK/FK, non-blank `name` CHECK, `geography(Point,4326)`)과
  `idx_store_discovery_profile_location` GiST index를 만든다. `merchant_store`에는 검색용 이름,
  geometry 또는 spatial index가 추가되지 않았고 통합 테스트가 그 컬럼 집합을 고정한다.
- migration은 profile 없는 `merchant_store` row가 하나라도 있으면 중단한다. `StoreDiscoveryProfilePrecheck`가
  startup에서 PostGIS 설치, 양방향 coverage, non-blank name과 SRID 4326 point를 다시 확인하고
  위반 시 readiness DOWN이 아니라 애플리케이션 시작을 실패시킨다.
- **정정 (2026-08-08):** coverage gate는 V33이 아니라 V34다. 두 단계가 한 migration에 있으면
  기존 store가 있는 환경에서 profile을 적재할 창 자체가 없어 배포가 불가능했다. V33은 스키마만
  만들고 V34가 coverage를 단언하므로, 배포는 `target=33` → 검증된 dataset 적재 → 나머지
  migration이다. 같은 정정에서 `POINT EMPTY`를 table CHECK와 startup precheck 양쪽에서 거부한다.
  column type, `GeometryType()`, `ST_IsValid()`를 모두 통과하지만 `ST_DWithin`에는 잡히지 않아
  해당 store가 조용히 검색에서 빠지기 때문이다.
- Merchant `StoreDiscoveryQueryOperations`가 유일한 접근 경로다. Discovery는 Merchant JPA Entity나
  Repository를 쓰지 않고 영속 복제본과 동기화 event도 만들지 않는다. Spring Modulith가
  `discovery -> {shared :: api, merchant :: api}` 경계를 검증한다.
- 좌표는 raw 문자열로 바인딩돼 Discovery 검증에서만 쓰인다. 검증 실패 메시지는 값을 포함하지
  않으므로 error body와 log에 원본 좌표가 남지 않는다. 응답, metric tag와 `AuditRecord`에도
  좌표가 없음을 통합 테스트가 확인한다.
- **보강 (2026-08-08):** 비노출은 이제 root logger에 붙인 Logback appender로 검증한다. 성공,
  검증 실패, PostGIS 실패 세 경로에서 formatted message, argument array, MDC, throwable chain
  전체를 검사한다. tracer가 classpath에 없어 span은 존재하지 않으며 MDC가 유일한 요청별 진단
  context다. 한 가지 남은 경로는 Spring의 `StatementCreatorUtils` TRACE 로깅으로, bind된 좌표를
  그대로 기록한다. `application.yaml`이 이 logger를 `DEBUG`로 고정하고 runbook이 운영 제약으로
  금지하지만, deployment가 level을 덮어쓸 수 있으므로 보장이 아니라 제약이다. 테스트가 TRACE에서
  실제로 노출되는 것과 DEBUG에서 노출되지 않는 것을 양방향으로 고정한다.
- **Startup Logging Guard Amendment (2026-08-09):** `JdbcParameterLoggingSafetyConfiguration`이
  startup에서 `StatementCreatorUtils`의 **effective** logger level을 확인한다. TRACE 또는 ALL로
  override되어 `isTraceEnabled()`이면 application startup을 실패시킨다. 따라서 외부 configuration이
  `application.yaml`의 DEBUG를 덮어써도 정상 서비스로 기동하지 않는다. root appender privacy test와
  이 startup guard의 DEBUG/TRACE 양방향 test를 함께 유지한다. 지원하지 않는 in-process logger mutation은
  runtime control surface로 제공하지 않는다.
- PostGIS/DB 실패는 `beanflow.discovery.spatial.failure{reason}`와 함께 503이며 빈 200,
  Haversine 계산 또는 cache로 대체되지 않는다.

2026-08-07 Milestone 4~5가 메뉴·픽업 슬롯 read endpoint를 같은 경계로 추가했다. Merchant가
메뉴·옵션을, Fulfillment가 슬롯 잔여 capacity를 public Query API DTO projection으로 제공하고
Discovery는 HTTP 계약과 응답 투영만 소유한다. 두 read 모두 durable write, event, AuditRecord를
만들지 않으며 없는 Store는 404, 영속 실패는 503으로 분리한다.

## Metrics

- **Measured (2026-08-07):** `scripts/perf/nearby-store-search.sh`의 고정 조건에서 10,000/100,000
  profile 두 규모의 실행계획과 200회 반복 latency. 두 규모 모두 GiST bounding-box index condition을
  사용했고 p50은 0.397 ms → 1.850 ms였다. 컨테이너가 emulation으로 실행됐고 비교 기준선이 없어
  성능 개선이나 SLA를 주장하지 않는다.
- **Not measured:** native amd64 timing, 동시 부하와 RPS, 부하 시 오류율, 100,000건을 넘는 규모.
  상세는 [nearby query plan evidence](../quality/nearby-store-discovery-performance-evidence.md).

## Revisit Conditions

명시적 동의를 받은 위치 개인화와 보존·삭제 정책이 도입될 때

## Related Decisions

- BR-28
- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
