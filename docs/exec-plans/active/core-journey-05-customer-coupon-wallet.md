# 고객이 보유 쿠폰을 매장별로 조회하고 주문 전에 선택한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 이 plan은 source에 없던 customer coupon wallet을
OpenAPI-first로 구현하기 위한 계약과 검증 경계를 기록한다. Stage 03 no-op outcome과 Stage 04
customer Session/CSRF contract integration 위에서 backend/API slice가 구현되었다. Storybook MCP resource는
wallet UI와 Storybook proof만 차단하며, backend/API slice의 prerequisite가 아니었다.

## Purpose / Big Picture

로그인한 고객이 주문 전에 이미 발급받은 쿠폰 중 요청 매장에 적용 가능한 항목을 확인하고 하나를
선택할 수 있게 한다. 이 조회는 할인 quote, coupon reservation 또는 사용 확정이 아니다. 주문 생성의
`CouponReservationService`가 실제 cart와 동시성을 포함한 모든 조건을 계속 최종 검증한다.

## Current State

- Customer Session의 `GET /api/v1/me/coupons`가 target/runtime OpenAPI, generated frontend schema와
  Promotion read projection으로 구현되었다. Customer actor와 store-bound signed cursor만 받아
  `AVAILABLE`/`RESTORED` row를 DTO projection으로 반환한다.
- normal issuance는 live active Campaign과 issuance expiry를, restored compensation issuance는 complete
  immutable snapshot과 issuance expiry를 각각 사용한다. 다른 매장 row는 제외하지 않고
  `STORE_NOT_APPLICABLE`로 표시한다.
- [BR-09 amendment](../../product/business-policy-decisions.md)와
  [ADR-070 amendment](../../adr/ADR-070-signed-cursor-and-pagination-contract.md)가 일반 issuance와
  restored compensation issuance의 eligibility, store-only scope와 cursor binding을 고정한다.
- `CouponReservationService`는 ownership, issuance state, expiry, store scope, 실제 minimum order,
  one-coupon rule과 concurrent consumption의 final authority다.
- representative PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`는 issuance seq scan, Campaign primary-key lookup과
  sort를 보였고 execution time은 0.199 ms였다. 이 fixture는 새 index 필요성을 입증하지 않았으므로 migration과
  index는 추가하지 않았다.
- Storybook MCP는 계속 사용할 수 없으므로 wallet route/UI와 Storybook proof는 **Not run**이며, 대체 도구로
  우회하지 않는다.

## Definitions

- **Normal issuance:** live Campaign을 따르는 일반 CouponIssuance다. reservation 뒤 원 issuance가
  `RESTORED`가 된 경우도 여기에 속하며, wallet eligibility는 `Campaign.active=true`와 issuance 자체의
  미만료를 요구한다.
- **Restored compensation issuance:** 종료 보상으로 만들어진 CouponIssuance다. [ADR-043](../../adr/ADR-043-compensation-coupon-terms-snapshot.md)의
  complete immutable terms snapshot과 issuance 자체의 미만료를 요구하며 live Campaign을 읽지 않는다.
- **Applicable:** 이번 요청의 `storeId`와 현재 coupon store scope가 일치하는 wallet row다. brand scope와
  brand hierarchy는 이 plan의 scope가 아니다.
- **Wallet selection:** client가 다음 order request에 `couponIssuanceId`를 넣기 위해 local UI state를
  정하는 행위다. reservation, quote 또는 state transition이 아니다.

## Scope

### In Scope

- Customer Session actor의 `GET /api/v1/me/coupons?storeId=&cursor=&limit=` read projection
- `AVAILABLE`/`RESTORED` issuance의 normal-versus-restored eligibility와 visible
  `STORE_NOT_APPLICABLE` result
- target OpenAPI, runtime OpenAPI, generated frontend client, wallet loading/empty/unavailable/selection UI
- ADR-070 common signed cursor adapter와 required backend/frontend/contract/Storybook tests

### Non-goals

- Campaign administration, coupon issuance, coupon history, wallet balance, limited issuance, quote 또는
  coupon reservation API
- brand scope, brand hierarchy 또는 cross-store wallet behavior
- Campaign expiry/title response field 또는 inferred eligibility
- client-side discount calculation, `MINIMUM_ORDER_NOT_MET`, checkout authority 변경
- cache, stale/fake list, local fallback, new pagination store, separate cursor codec
- pre-authorized DB index or this plan에서의 Flyway migration

## Business Rules and Invariants

1. CurrentActor customer는 자신의 `AVAILABLE` 또는 `RESTORED` issuance만 읽는다. 다른 customer 또는
   actor의 issuance는 body, count, cursor로 존재를 드러내지 않는다.
2. normal issuance(원 issuance가 `RESTORED`가 된 경우 포함)는 live `Campaign.active=true`와 issuance 자체
   미만료가 모두 필요하다. restored
   compensation issuance는 complete immutable terms snapshot과 issuance 자체 미만료가 필요하고 live
   Campaign lifecycle과 무관하다. snapshot을 live Campaign으로 보완하지 않는다.
3. `storeId`는 필수다. 이 plan의 applicability는 current store scope만 결정하며, non-applicable issuance는
   `applicable=false`, `reasonCode=STORE_NOT_APPLICABLE`로 visible하게 남긴다. brand matching은 defer한다.
4. `minimumOrderKrw`는 정보다. request에 order amount가 없으므로 `MINIMUM_ORDER_NOT_MET`를 만들지 않는다.
5. Response는 coupon issuance identifier, benefit summary, `minimumOrderKrw`, `couponExpiresAt`,
   `applicable`, applicable하지 않을 때의 reason code와 signed next cursor만 제공한다. Campaign expiry나
   title을 이 계약에 추가하지 않는다.
6. `POST /orders`의 `CouponReservationService`는 customer ownership, state, expiry, store scope, cart의
   actual minimum order, one-coupon rule과 concurrent consumption을 같은 order transaction에서 재검증한다.
   wallet 200이나 selection은 할인 확정 또는 reservation이 아니다.
7. Sorting은 `(couponExpiresAt ASC, couponIssuanceId ASC)`이다. endpoint, authenticated customer ID,
   requested store ID의 canonical signed filter hash와 24시간 TTL을 사용한다.

## Architecture and Transaction Boundaries

```text
Customer wallet UI
  -> generated customer client
  -> Customer Session / CurrentActor
  -> CouponWalletQueryService (@Transactional(readOnly = true))
  -> Promotion query projection + common signed cursor codec
  -> PostgreSQL DTO rows

Checkout order command
  -> CouponReservationService (separate authoritative write transaction)
```

- Controller는 query repository를 직접 호출하지 않는다. Application query service가 actor scope, cursor
  adapter와 read-only transaction을 조정한다.
- Query projection은 aggregate object graph를 확장하지 않고 DTO만 반환한다.
- Wallet query는 CouponIssuance/Campaign을 변경하지 않고, checkout의 reservation/write transaction과
  공유 성공 상태를 만들지 않는다.
- Query plan이 현재 inventory로 충분하지 않음을 actual PostgreSQL `EXPLAIN`으로 보인 경우에만 별도
  migration-writer lease와 `Writes-Migration: true` successor plan을 열어 index를 판단한다.

## Alternatives Considered

### 모든 issuance에 live Campaign을 요구

- 장점: 하나의 eligibility predicate다.
- 단점: ADR-043의 restored compensation snapshot을 무시해 종료 Campaign 보상을 즉시 쓸 수 없게 한다.
- 결정: 기각한다.

### non-applicable coupon을 응답에서 제외

- 장점: 목록이 짧다.
- 단점: 고객이 보유하지 않음과 매장 제한을 구분할 수 없다.
- 결정: 기각한다. `STORE_NOT_APPLICABLE`를 visible하게 반환한다.

### wallet query로 coupon을 예약하거나 할인액을 계산

- 장점: checkout 전에 확정된 것처럼 보일 수 있다.
- 단점: cart, state, expiry, concurrent consumption 변화를 우회한다.
- 결정: 기각한다. Order transaction만 authority다.

### index를 문서 단계에서 미리 추가

- 장점: implementation 속도가 빨라 보인다.
- 단점: actual query/representative data의 evidence 없이 schema를 소유하게 된다.
- 결정: 기각한다. EXPLAIN과 writer lease가 있는 별도 successor만 판단한다.

## Failure Semantics

- Customer Session이 없으면 existing authentication contract의 401, 다른 actor면 existing actor isolation의
  403을 사용하며 다른 customer issuance를 추론할 단서를 반환하지 않는다.
- `storeId`, cursor, limit 또는 cursor scope가 invalid하면 query 전에 400이다. 다른 customer/store cursor와
  malformed/expired/signature-mismatched cursor는 모두 400이며 repository query를 실행하지 않는다.
- Projection, required snapshot read 또는 database dependency failure는 typed 5xx(통상 503)다. empty 200,
  cached/stale list, fake coupon, local in-memory list 또는 success fallback으로 대체하지 않는다.
- Empty 200은 eligibility를 만족하는 issuance가 없을 때만 가능하다. incomplete restored snapshot은 empty와
  섞지 않고 typed 5xx로 종료한다.
- Client는 loading, empty, unavailable와 selection state를 구분한다. query 실패로 기존 list를 성공처럼
  유지하거나 checkout selection을 자동 확정하지 않는다.

## Data and Migration

이 문서 선행 commit과 현재 plan은 `Writes-Migration: false`다. Query SQL과 representative PostgreSQL data의
`EXPLAIN (ANALYZE, BUFFERS)`가 필요성을 보이기 전에는 index, cache, materialized view 또는 Flyway 파일을
추가하지 않는다. 필요성이 입증되면 current migration inventory를 다시 읽고 ADR-072 migration-writer
lease 아래 별도 successor plan이 migration을 소유한다.

## API and Event Contracts

구현 전 target OpenAPI가 먼저 다음 read contract를 추가하고, Controller와 authorization/failure tests가
완성된 뒤 runtime OpenAPI와 generated client를 같은 slice에 반영한다.

```http
GET /api/v1/me/coupons?storeId={storeId}&cursor={opaque?}&limit={1..100?}
```

- request actor는 Customer Session에서만 얻으며 customer ID를 query/body로 받지 않는다.
- `storeId`는 required UUID query parameter다. `cursor`는 ADR-070 signed opaque token이고 `limit`의
  default는 20, maximum은 100이다.
- success page는 `items`와 nullable `nextCursor`를 반환한다. item은 `couponIssuanceId`, benefit summary,
  `minimumOrderKrw`, `couponExpiresAt`, `applicable`, 그리고 `applicable=false`일 때
  `STORE_NOT_APPLICABLE`만 반환한다.
- error response는 current common error schema를 사용한다. target contract에는 400, 401, 403, 503을 명시하고,
  no typed failure path를 empty success로 바꾸지 않는다.
- 이 read는 event를 발행하지 않고 Campaign/CouponIssuance/Order 상태를 변경하지 않는다.

## Milestones

1. Stage 03 no-op outcome과 Stage 04 Session/CSRF contract integration을 current evidence로 재확인한다.
2. BR-09/ADR-070/this ExecPlan의 contract를 검토한 뒤 target OpenAPI의 request/page/error schema와
   runtime mapping을 함께 추가한다.
3. Promotion query projection, customer actor authorization, normal/restored eligibility와 signed cursor
   adapter를 구현한다.
4. actual query와 representative PostgreSQL fixture에 `EXPLAIN (ANALYZE, BUFFERS)`를 실행한다. index가
   필요하면 여기서 멈추고 migration-writer lease를 가진 successor plan을 만든다.
5. Storybook MCP가 실제로 이용 가능해진 뒤 generated client와 wallet route를 구현하고
   loading/empty/unavailable/selection, `STORE_NOT_APPLICABLE`, checkout revalidation failure를 canonical
   Storybook에서 검증한다. 그 전에는 UI work를 시작하거나 대체 도구로 proof를 만들지 않는다.
6. target/runtime parity, security/contract/integration/frontend/Storybook tests와 docs evidence를 실행하고
   actual results만 plan의 completion record에 남긴다.

## Required Tests

- customer ownership filter와 cursor replay/mismatch가 다른 issuance 존재를 드러내지 않는 테스트
- `AVAILABLE` normal issuance와 reservation 뒤 `RESTORED`가 된 원 issuance의 active Campaign + issuance
  expiry boundary 테스트
- complete snapshot restored compensation의 inactive Campaign eligibility와 missing/incomplete snapshot
  typed 5xx 테스트
- current store applicable/visible `STORE_NOT_APPLICABLE`, mandatory `storeId`, no brand matching 테스트
- `minimumOrderKrw` informational response와 absent `MINIMUM_ORDER_NOT_MET` 테스트
- `(couponExpiresAt ASC, couponIssuanceId ASC)` keyset no-gap/no-duplicate, 24-hour TTL, canonical
  endpoint+customerId+storeId filter hash, tamper/cross-customer/cross-store cursor 400 테스트
- projection/snapshot/DB failure가 empty/stale/fake success로 대체되지 않는 503 테스트
- order query 후 state/expiry/store/minimum/concurrent consumption 변경을 `CouponReservationService`가
  다시 거절하는 integration test
- generated client and wallet loading/empty/unavailable/selection Storybook states, a11y and browser interaction
  tests

## Validation Commands

```bash
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
git diff --check
git diff --cached --check
./gradlew test --tests '*Coupon*' --tests '*OpenApi*' --tests '*RuntimeOpenApiParityTest'
npm run typecheck
npm run test:unit
npm run build-storybook
npm run test:storybook:docs
```

현재 문서 선행 commit에서는 첫 세 문서/patch hygiene command만 실행 후보이며 backend, frontend,
Storybook, browser, `EXPLAIN`과 migration validation은 implementation 뒤에만 실행한다. 이 plan은 실행하지
않은 명령을 통과로 주장하지 않는다.

## Observability

- `coupon-wallet.query` success/failure count와 latency는 result/error class만 tag로 사용한다.
- customer, coupon issuance, campaign, store ID, cursor, filter hash, benefit summary와 raw query parameters는
  log, metric tag 또는 trace attribute에 기록하지 않는다.
- 503, invalid cursor 400, `STORE_NOT_APPLICABLE` count와 query plan measurement는 actual implementation
  이후 관찰한다. 현재 목표값이나 성능 결과는 측정하지 않았다.

## Documentation Updates

- [Business Policy Decisions](../../product/business-policy-decisions.md) BR-09 amendment
- [ADR-070 signed cursor](../../adr/ADR-070-signed-cursor-and-pagination-contract.md) endpoint binding
- [Core User Journey Contract](../../product/core-user-journey.md) Stage 05 boundary
- [Product usability orchestration](product-usability-program-orchestration.md) current Stage 05 plan link
- implementation commit에서 target/runtime OpenAPI, Error Catalog, generated-client provenance and release gate
  evidence를 actual change에 맞게 갱신한다.

## Progress

- 2026-08-18: documentation prerequisite created.
- 2026-08-18: backend/API vertical slice implemented: target/runtime OpenAPI, generated frontend schema,
  Customer authorization, Promotion JDBC DTO projection, normal/restored eligibility, store-only visible
  inapplicability, ADR-070 `(couponExpiresAt, couponIssuanceId)` cursor, typed dependency/snapshot 503 and
  closed outcome observability.
- 2026-08-18: focused green evidence: wallet integration/contract, runtime parity, authentication registry,
  `CouponReservationRepositoryTest`, `CustomerPointFacadeIntegrationTest`, `ModularityTests` and `spotlessCheck`
  passed; `npm run typecheck` regenerated the schema and passed; `scripts/verify-docs.sh` and `git diff --check`
  passed.
- 2026-08-18: representative PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` recorded 0.199 ms with no evidence requiring
  a new index; no migration was added.
- 2026-08-18: wallet UI route, Storybook build/docs tests, browser interaction and a11y proof remain **Not run**
  because Storybook MCP is unavailable. No frontend UI source changed in this backend/API slice, so wallet frontend
  unit tests were not added or run.

## Surprises & Discoveries

- Storybook MCP resource is unavailable at plan creation. This is a UI validation blocker, not permission to use
  a local/fake/stale wallet list or a substitute browser proof.

## Decision Log

- 2026-08-18: normal issuance uses live `Campaign.active`; restored compensation issuance uses its immutable
  snapshot and does not read live Campaign lifecycle. Source: ADR-043 and BR-09 amendment.
- 2026-08-18: current wallet applicability is store-only; brand scope and hierarchy remain deferred.
- 2026-08-18: wallet uses ADR-070 common signed cursor with `(couponExpiresAt ASC, couponIssuanceId ASC)`,
  endpoint+customerId+storeId filter binding and 24-hour TTL.
- 2026-08-18: index creation is conditional on actual `EXPLAIN` and a separately held migration-writer lease.

## Outcomes & Retrospective

The backend/API vertical slice is locally complete with its focused contract, security, integration, modularity,
formatting, generated-schema and documentation evidence. It does not create a migration or index, and order
reservation remains the authority.

This ExecPlan remains `ACTIVE`: wallet route/UI, Storybook build/docs tests, browser interaction and a11y proof are
**Not run** while Storybook MCP is unavailable. No wallet frontend unit test was added or run because this slice
changed no frontend UI source. Draft PR publication, remote CI and release-gate closure are also outstanding.

## Revision Notes

- 2026-08-18: initial Stage 05 customer coupon wallet implementation plan and policy/cursor prerequisites.
