# 고객 이벤트 매장 표시 정보를 일괄 조회한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/limited-coupon-events.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객 이벤트 한 페이지를 조립할 때 캠페인마다 매장 표시 정보를 단건 조회하는 구조를 제거한다. Merchant가
소유한 검증된 매장 표시 snapshot을 요청 ID 집합으로 한 번에 읽고 Promotion은 그 결과만 조립한다. 페이지
크기가 1개이든 100개이든 핵심 JDBC statement는 캠페인 조회 1회와 매장 조회 1회로 유지한다.

## Current State

`CustomerEventCampaignReadTransaction.list()`는 캠페인 목록을 읽은 뒤 각 항목에서
`StoreDisplaySnapshotOperations.require(storeId)`를 호출한다. `require()`는 호출마다
`merchant_store_discovery_profile`과 `merchant_store`를 조회하므로 페이지 크기만큼 statement가 증가한다.
현재 Port는 단건 `require()`와 전체 매장 picker용 `list()`만 제공하며 ID 집합 batch 계약이 없다.

## Definitions

- batch hydration: 목록의 식별자 집합을 먼저 모아 한 번의 owner query로 표시 정보를 읽고 메모리에서 조립하는 방식.
- fail-closed batch: 요청 ID 중 하나라도 누락·중복·불일치하면 빈 값이나 부분 응답 대신 명시적으로 실패하는 조회.

## Scope

### In Scope

- `StoreDisplaySnapshotOperations.requireAll(Collection<UUID>)` 계약과 PostgreSQL 배열 조회.
- 고객 이벤트 목록의 distinct Store ID batch hydration.
- 빈 입력, 중복 ID, 누락 profile과 페이지 크기 독립 statement 수 검증.
- 동일 스테이징 조건의 변경 전후 부하 비교.

### Non-goals

- 운영 캠페인 목록의 대상 메뉴·매장 batch 조립.
- Campaign, Store Aggregate 또는 공개 HTTP 계약 변경.
- cache, Redis, schema migration, 비정규화 read model 또는 connection pool 조정.

## Business Rules and Invariants

- 고객 목록의 `(claimEndsAt, campaignId)` 정렬, signed cursor, 최대 100개와 응답 필드는 바꾸지 않는다.
- 같은 Store ID가 반복돼도 표시 정보는 한 번만 조회한다.
- 요청한 Store ID가 하나라도 검증된 snapshot으로 반환되지 않으면 기존과 같은
  `DEPENDENCY_UNAVAILABLE`로 전체 요청을 실패시킨다.
- 매장 ID와 캠페인 ID를 log, metric tag 또는 오류 상세에 노출하지 않는다.

## Architecture and Transaction Boundaries

Promotion은 Merchant Entity나 Repository를 직접 참조하지 않고 `StoreDisplaySnapshotOperations` 공개 DTO
Port만 호출한다. 기존 고객 이벤트 read-only transaction 안에서 Promotion page query 뒤 Merchant batch
query를 호출한다. 외부 Provider 호출과 쓰기 transaction은 추가하지 않는다.

## Alternatives Considered

- `StoreDiscoveryQueryOperations.findVisibleStores()` 재사용: 공개 노출 필터와 누락 생략 의미가 필수 snapshot
  계약과 달라 제외한다.
- Promotion SQL에서 Merchant table 직접 JOIN: owner 경계를 우회하므로 제외한다.
- 요청 내부 memoization: distinct store 수만큼 SQL이 남아 페이지 크기 독립 조건을 만족하지 못한다.
- 별도 persistent read model: 동기화·복구 비용이 현재 bounded page 조회에 비해 과도하다.

## Failure Semantics

빈 입력은 빈 Map을 반환한다. 중복 입력은 조회 전에 Set으로 정규화한다. DB 실패, 누락 snapshot, 중복 row,
blank/비정규화 name은 `DEPENDENCY_UNAVAILABLE`이며 부분 목록이나 placeholder로 대체하지 않는다.

## Data and Migration

Flyway migration과 데이터 backfill은 없다. 기존 `merchant_store_discovery_profile`과 `merchant_store`를
`ANY(?::uuid[])` 조건으로 조회한다.

## API and Event Contracts

`GET /api/v1/me/events`의 request, response, cursor와 error envelope는 변경하지 않는다. 새 계약은 JVM 내부
Merchant Query Port에만 추가하며 event 계약은 없다.

## Milestones

1. 현재 endpoint의 1개·100개 페이지 statement 수를 회귀 테스트로 측정한다.
2. Merchant batch Port와 fail-closed 구현을 추가한다.
3. 고객 이벤트 조립을 batch 결과 Map으로 전환한다.
4. focused·구조·전체 품질 검증 뒤 동일 스테이징 조건으로 재측정한다.

## Required Tests

- 빈 Store ID 집합은 SQL 없이 빈 결과를 반환한다.
- 중복 ID를 포함한 다수 Store를 statement 한 번으로 반환한다.
- 요청 ID 일부가 누락되면 `DEPENDENCY_UNAVAILABLE`이다.
- 고객 이벤트 1개와 100개 페이지의 endpoint 핵심 statement 수가 모두 2다.
- 기존 가시성, cursor, 인증과 media signing 테스트가 유지된다.

## Validation Commands

```bash
./gradlew test --tests '*CustomerEventCampaignControllerTest'
./gradlew test --tests '*StoreDisplaySnapshotServiceTest'
./gradlew spotlessCheck test build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

별도 production metric은 추가하지 않는다. statement-count 회귀 테스트와 동일 스테이징의 k6 p95/p99,
Hikari active/pending, `pg_stat_statements.calls`를 변경 전후 비교한다. 비교 조건이 다르면 성능 개선으로
표현하지 않는다.

## Documentation Updates

본 ExecPlan에 실제 statement 수, 스테이징 조건과 검증 결과를 기록한다. 공개 API와 장기 구조 결정은
변경하지 않으므로 ADR, Business Policy와 OpenAPI는 수정하지 않는다.

## Progress

- [x] 2026-09-21: 호출 경로, owner Port, 기존 batch projection과 실패 의미 확인.
- [x] 2026-09-21: 변경 전 statement 수를 1개 페이지 2회, 100개 페이지 101회로 재현.
- [x] 2026-09-21: Merchant batch Port와 고객 이벤트 hydration 구현.
- [ ] 검증과 스테이징 변경 후 측정.

## Surprises & Discoveries

- 첫 스테이징 측정의 100개 캠페인은 85개 distinct store였지만 Store 단건 SQL은 100회 실행됐다.
- `StorefrontImageStorageOperations.access()`는 object-store network 요청이 아닌 로컬 URL 서명이다.
- endpoint statement-count 테스트는 변경 후 빈 페이지 1회, 1개·중복 매장 100개·서로 다른 매장 100개
  페이지 모두 2회를 확인한다.

## Decision Log

| 날짜 | 결정 | 이유 / 재검토 조건 |
| --- | --- | --- |
| 2026-09-21 | 기존 Store display Port에 fail-closed batch 메서드 추가 | owner 경계와 기존 오류 의미를 유지하면서 쿼리를 상수화 |
| 2026-09-21 | 새 read model·cache·직접 JOIN을 도입하지 않음 | 최대 100개 UUID 배열 조회로 충분하며 동기화·운영 비용이 불필요 |

## Outcomes & Retrospective

진행 중. 실제 검증과 측정 전에는 완료 수치를 기록하지 않는다.

## Revision Notes

- 2026-09-21: 고객 이벤트 매장 batch hydration 구현 계획 최초 작성.
