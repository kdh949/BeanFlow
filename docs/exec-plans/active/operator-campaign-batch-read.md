# 운영 캠페인 목록 조립을 일괄 조회한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/active/customer-event-store-batch-read.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

운영 캠페인 한 페이지를 조립할 때 각 캠페인의 대상 메뉴와 매장 표시 정보를 단건 조회하는 구조를 제거한다.
Promotion이 소유한 대상 메뉴를 한 번에 읽고, Merchant의 검증된 매장 표시 snapshot batch Port를 재사용한다.
페이지 크기가 1개이든 100개이든 인증 확인을 제외한 핵심 JDBC statement를 3회로 유지한다.

## Current State

`LimitedCouponCampaignPersistence.list()`는 base query의 RowMapper 안에서 캠페인마다
`eligibleMenus(campaignId)`를 호출한다. 이어서 `OperatorCouponCampaignService.list()`가 각 항목을
`view()`로 바꾸며 `stores.require(storeId)`를 호출한다. 대상 메뉴와 매장 표시 정보가 각각 페이지 크기만큼
증가하고, cursor 판정을 위한 초과 조회 항목에도 메뉴 단건 SQL이 실행된다.

## Definitions

- two-stage hydration: base row를 먼저 읽고 관련 ID를 한 번에 조회한 뒤 메모리에서 최종 snapshot을 조립하는 방식.
- core statement: 운영자 권한 확인 등 공통 요청 SQL을 제외한 캠페인, 대상 메뉴, 매장 표시 조회 SQL.

## Scope

### In Scope

- 목록 조회에 한정한 대상 메뉴 batch query와 snapshot 조립.
- 운영 캠페인 목록의 매장 표시 snapshot batch hydration.
- 대상 메뉴 순서, cursor, 표시 데이터 누락 실패 의미와 statement 수 회귀 검증.
- 동일 개인 스테이징 조건의 변경 전후 부하 비교.

### Non-goals

- 단건 캠페인 조회와 작성·게시·중단 command 경로 변경.
- Campaign 또는 Store Aggregate, 공개 HTTP 계약 변경.
- cache, Redis, schema migration, 비정규화 read model 또는 connection pool 조정.

## Business Rules and Invariants

- `(createdAt, campaignId)` 역순 정렬, signed cursor, 최대 100개와 응답 필드는 바꾸지 않는다.
- 대상 메뉴 ID는 기존처럼 UUID 오름차순으로 반환한다.
- 목록에 포함된 매장 표시 snapshot 하나라도 누락되면 부분 응답이나 placeholder가 아닌
  `DEPENDENCY_UNAVAILABLE`로 전체 요청을 실패시킨다.
- 단건 조회는 기존 query와 오류 동작을 유지한다.

## Architecture and Transaction Boundaries

Promotion persistence는 자신의 `promotion_campaign_eligible_menu`만 batch 조회한다. 운영 Application Service는
Merchant Entity나 Repository를 직접 참조하지 않고 `StoreDisplaySnapshotOperations.requireAll()`을 사용한다.
기존 운영 목록 transaction 안에서 권한 확인, Promotion page query와 Merchant batch query를 순서대로 수행한다.

## Alternatives Considered

- base query에 대상 메뉴를 aggregate: PostgreSQL 전용 aggregate와 중복 row 조립이 생겨 최소 변경보다 복잡하다.
- Promotion SQL에서 Merchant table 직접 JOIN: 데이터 owner 경계를 우회하므로 제외한다.
- 요청 내부 memoization: distinct ID 수만큼 SQL이 남아 페이지 크기 독립 조건을 만족하지 못한다.
- 별도 read model·cache: 현재 최대 100개 목록에 동기화와 장애 정책 비용이 과도하다.

## Failure Semantics

빈 campaign ID 집합은 대상 메뉴 SQL 없이 빈 Map을 반환한다. 대상 메뉴가 없는 캠페인은 정상적으로 빈 List다.
DB 실패는 기존 dependency boundary가 `DEPENDENCY_UNAVAILABLE`로 변환한다. 매장 snapshot 누락·중복·불일치는
고객 이벤트에서 추가한 fail-closed batch 계약을 그대로 따른다.

## Data and Migration

Flyway migration과 데이터 backfill은 없다. 기존 `promotion_campaign_eligible_menu`를
`ANY(?::uuid[])` 조건으로 조회한다.

## API and Event Contracts

`GET /api/v1/operations/coupon-campaigns`의 request, response, cursor와 error envelope는 변경하지 않는다.
새 공개 API 또는 event 계약은 없다.

## Milestones

1. 현재 endpoint의 1개·100개 페이지 statement 수를 회귀 테스트로 측정한다.
2. 목록 대상 메뉴를 base query 뒤 단일 batch query로 조립한다.
3. 운영 목록의 Store ID를 모아 기존 batch Port 결과로 view를 조립한다.
4. focused·구조·전체 품질 검증 뒤 동일 스테이징 조건으로 재측정한다.

## Required Tests

- 대상 메뉴가 없거나 여러 개인 캠페인의 기존 응답과 UUID 정렬을 유지한다.
- 운영 목록 1개와 100개 페이지의 endpoint statement 수가 동일하다.
- cursor를 위한 초과 조회가 있어도 대상 메뉴 SQL은 한 번만 실행된다.
- 요청한 Store ID 일부가 누락되면 `DEPENDENCY_UNAVAILABLE`이다.
- 기존 작성, 단건 조회, cursor, 인증과 media signing 테스트가 유지된다.

## Validation Commands

```bash
./gradlew test --tests '*OperatorCouponCampaignControllerTest'
./gradlew spotlessCheck build
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

- [x] 2026-09-21: 호출 경로, owner 경계, 기존 snapshot과 실패 의미 확인.
- [x] 2026-09-21: 변경 전 statement 수를 1개 페이지 4회, 100개 페이지 202회로 재현.
- [x] 2026-09-21: 대상 메뉴와 매장 표시 batch hydration 구현.
- [ ] 검증과 스테이징 변경 후 측정.

## Surprises & Discoveries

- 목록 persistence는 화면에 반환되지 않을 cursor 초과 항목에도 RowMapper 내부 대상 메뉴 SQL을 실행한다.
- 변경 후 endpoint 테스트는 1개 페이지와 cursor 초과 항목을 포함한 100개 페이지 모두 4회를 확인한다.

## Decision Log

| 날짜 | 결정 | 이유 / 재검토 조건 |
| --- | --- | --- |
| 2026-09-21 | 기존 snapshot `copy()`를 사용한 two-stage 조립 | 새 DTO 계층 없이 목록 경로만 일괄 조회할 수 있음 |
| 2026-09-21 | 고객 이벤트에서 추가한 Store batch Port 재사용 | Merchant owner 경계와 누락 실패 의미를 동일하게 유지 |
| 2026-09-21 | 새 read model·cache·직접 JOIN을 도입하지 않음 | 최대 101개 UUID 배열 조회로 충분하며 운영 비용이 불필요 |

## Outcomes & Retrospective

진행 중. 실제 검증과 측정 전에는 완료 수치를 기록하지 않는다.

## Revision Notes

- 2026-09-21: 운영 캠페인 대상 메뉴와 매장 batch hydration 구현 계획 최초 작성.
