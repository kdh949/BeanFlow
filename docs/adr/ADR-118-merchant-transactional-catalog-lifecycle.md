# ADR-118: 점주 거래 카탈로그 수명주기와 주문 직렬화 경계

- **Status:** Accepted
- **Date:** 2026-08-26
- **Implementation owner:** [점주 거래 카탈로그와 주문 정책 완성](../exec-plans/active/merchant-transactional-catalog-and-ordering-policy.md)
- **Amends:** ADR-076의 쓰기 상한, ADR-103의 Menu 검색어 동기화, ADR-116의 Merchant owner-state 직렬화와 fingerprint material

## Context

고객 주문은 Menu 이름·가격·판매 가능 상태, Option 이름·추가 금액·판매 가능 상태,
MenuConfiguration과 sellable-unit 요구량, Store의 `acceptingOrders`와 `pickupEnabled`를 현재 값으로
다시 계산한다. 그러나 현재 production에는 이 거래 상태를 점주가 변경하는 command가 없다. row는
migration, seed 또는 직접 DML로만 바뀌므로 점주 콘솔의 `메뉴·가격` 화면을 구현할 수 없고,
실제 writer와 최종 주문을 경합시키는 PostgreSQL 검증도 만들 수 없다.

ADR-116은 최종 주문 transaction을 owner state의 유일한 serialization point로 정했지만 현재 구현의
Merchant read는 Store, Menu, Option, Configuration과 정산 조건을 일반 SELECT로 읽는다. 이후 일부
Fulfillment·Inventory·Promotion·Loyalty state만 잠그므로, 향후 Merchant writer가 추가되면 final
fingerprint 검사와 Order snapshot 저장 사이에 거래 상태가 바뀔 수 있다.

기존 `Store.version`과 `Menu.version`은 이미지 pointer와 Menu display metadata에도 사용된다. 이를
quote fingerprint에 넣으면 가격·선택 구성과 무관한 이미지나 설명 변경까지 `ORDER_QUOTE_STALE`로
만든다. 반대로 Option이나 requirement child row 변경은 parent JPA version을 자동 증가시키지 않으므로
그 version만으로 거래 의미를 대표할 수도 없다.

제품 결정으로 ACTIVE same-store `OWNER | STAFF`가 일상적인 Store 주문 정책과 Menu 거래 카탈로그를
관리한다. Menu와 Option은 생성·수정·보관할 수 있지만 public API로 물리 삭제하지 않는다.

## Decision

### 1. ACTIVE same-store OWNER와 STAFF가 거래 카탈로그를 관리한다

다음 command와 authoring read는 실행할 때마다 Identity의 ACTIVE membership을 확인한다.

- Store `acceptingOrders`, `pickupEnabled` 조회·교체
- Menu 생성, 거래 내용 전체 교체, 보관
- Option 생성·수정·보관
- MenuConfiguration과 sellable-unit requirement 전체 교체

허용 역할은 `OWNER | STAFF`다. revoked membership은 즉시 거절하고, 다른 Store의 Menu·Option·
Configuration 식별자는 존재 여부를 누설하지 않는다. UI가 보관한 membership role이나 Store 목록은
인가 근거가 아니다.

Authoring transaction은 Identity의 대상 Store membership row를 shared lock으로 먼저 읽는다. membership
row가 없으면 Store 또는 하위 자원의 존재 여부와 무관하게 `404 RESOURCE_NOT_FOUND`, row는 있지만
account/membership이 inactive·revoked이거나 역할이 허용되지 않으면 `403 ACCESS_DENIED`다. shared lock은
transaction 종료까지 유지되므로 먼저 시작한 authoring은 그 권한으로 완결되고, revoke가 먼저 commit되면
뒤 command는 403이다. 이 lock 뒤에만 같은 Store의 commerce-root exclusive lock을 획득한다.

Audit actor type은 실제 membership role에 따라 `STORE_OWNER | STORE_STAFF`를 기록한다. 초기 범위에는
STAFF 가격 상한, OWNER 승인, 이중 승인 또는 시간대별 제한을 두지 않는다.

### 2. Menu는 생성·수정·보관하며 물리 삭제와 복원은 제공하지 않는다

Menu lifecycle은 `ACTIVE | ARCHIVED`다. Option과 MenuConfiguration도 parent Menu 안에서 같은 의미의
active/archived 상태를 가진다.

- 새 Menu는 client가 보낸 거래 정의 전체를 한 transaction으로 생성한다.
- Menu 교체는 name, base price, availability, Option, Configuration, requirement의 원하는 전체 상태를
  제출한다. 요청에서 제거된 기존 child는 물리 삭제하지 않고 보관한다.
- Menu 보관은 Menu와 모든 active child를 한 transaction에서 보관하고 검색 색인과 customer catalogue에서
  제거한다.
- archived Menu/Option/Configuration은 새 quote와 새 Order에 사용할 수 없다.
- 기존 OrderLine의 immutable 이름·가격·Option·requirement snapshot은 현재 catalogue lifecycle과 무관하게
  그대로 유지한다.
- v1 public API에는 archived item 복원과 hard delete를 두지 않는다.

Menu가 `available=true`이면 최소 한 개의 active MenuConfiguration이 있어야 한다. Configuration의
정규화 Option 집합은 같은 Menu의 active Option만 참조하며 같은 집합은 하나만 존재한다. 각 active
Configuration은 ADR-026에 따라 하나 이상의 positive sellable-unit requirement를 가진다. unavailable
Menu는 저장 중인 draft를 허용하므로 Configuration이 없을 수 있지만 customer quote에는 사용할 수 없다.

### 3. 거래 version은 표시·이미지 version과 분리한다

Store는 `ordering_policy_version`, Menu는 `trade_version`을 소유한다.

- Store 두 flag가 실제로 바뀌면 `ordering_policy_version`이 증가한다.
- Menu name, price, availability, lifecycle, Option, Configuration 또는 requirement의 정규화된 거래 의미가
  바뀌면 `trade_version`이 정확히 한 번 증가한다.
- 정규화 후 같은 full replacement는 version, updatedAt과 Audit를 바꾸지 않는 no-op이다.
- image pointer, `displayCategory`, `publicDescription`과 Store customer display profile은 거래 version을
  증가시키지 않는다.
- authoring response는 해당 거래 version을 노출하고 mutation은 `expectedVersion`으로 optimistic conflict를
  확인한다. stale expected version은 기존 점주 콘텐츠 writer와 같은 `409 MERCHANT_CONTENT_STALE`로
  거절하며, customer catalogue에는 version을 노출하지 않는다.

기존 JPA `@Version`은 해당 row의 기술적 lost-update 방어로 남길 수 있지만 quote 거래 의미나 점주
authoring contract로 사용하지 않는다.

### 4. Store commerce root가 Merchant 거래 상태의 lock protocol을 소유한다

`merchant_store` row를 Store commerce root로 사용한다.

- 최종 Order transaction은 Store row를 PostgreSQL shared lock으로 먼저 획득한다. 같은 Store의 여러 Order는
  함께 진행할 수 있다.
- Store 주문 정책과 Menu 거래 catalogue writer는 Identity membership row의 shared lock을 먼저 획득한 뒤
  Store row의 exclusive lock을 획득한다. 모든 writer는 이 순서를 고정하고 역순으로 잠그지 않는다.
- 두 lock 뒤 target ownership, expected version, active state, catalogue 상한을 검증한다.
- 최종 Order는 shared lock을 transaction commit/rollback까지 유지한 채 Store policy, Store order-display
  snapshot, 요청 Menu와 그 Option/Configuration/requirement, 적용 가능한 StoreSettlementTerms를 읽는다.
- 향후 Store 이름이나 정산 조건처럼 quote/Order snapshot에 들어가는 Merchant writer도 같은 exclusive
  Store-root protocol을 따라야 한다.
- 이미지와 display-only metadata writer는 quote 거래 의미를 바꾸지 않으므로 이 protocol의 대상이 아니다.

shared Order lock과 exclusive writer lock의 선형화 결과는 둘 중 하나다.

```text
writer가 먼저 commit
  -> final Order가 새 거래 상태를 읽고 이전 fingerprint를 ORDER_QUOTE_STALE로 거절

Order가 shared lock을 먼저 획득
  -> writer는 Order commit 뒤 진행하고 Order는 자신이 잠근 기존 거래 상태로 완료
```

Merchant lock은 Order의 기존 downstream lock보다 먼저 획득한다. transaction 안에서 Provider를 호출하지
않으며, Store lock을 잡은 뒤 다른 Store의 lock을 획득하지 않는다.

### 5. fingerprint는 거래 의미만 포함한다

ADR-116 `order-quote-fingerprint/v1`의 Merchant material에서 coarse `Store.version`과 `Menu.version`을
제거하고 다음을 포함한다.

- Store ordering policy values와 `ordering_policy_version`
- Menu/Option/Configuration/requirement의 canonical 거래 값과 Menu `trade_version`
- Store 표시명과 실제 적용 StoreSettlementTerms identity/value

표시 설명·카테고리와 Store/Menu image pointer 변경은 fingerprint를 바꾸지 않는다. Menu 이름·가격·
판매 가능 상태, active Option의 이름·추가 금액·판매 가능 상태, Configuration/requirement, Store 주문 정책은
fingerprint를 바꾼다. canonical field 집합이나 serializer 호환성이 바뀌면 ADR-116 규칙대로 fingerprint
prefix version을 올리고 quote와 final Order를 같은 PR에서 바꾼다.

### 6. 모든 mutation은 command-transaction 멱등성을 사용한다

모든 Store/Menu mutation은 `Idempotency-Key`, canonical payload hash와 최초 terminal response를 사용한다.
Store commerce root가 이미 존재하고, 검색 색인과 Audit를 포함한 모든 부수효과가 하나의 local DB
transaction 안에 있으며 Provider 호출이 없으므로 ADR-064의 command-transaction 모델이다.

- 같은 actor·operation·key·payload는 최초 status/body를 재생한다.
- 같은 key의 다른 payload는 `IDEMPOTENCY_KEY_REUSED` 409다.
- command ledger의 DB uniqueness는 `actor_id + operation + idempotency_key`이며 Store ID는 scope에
  포함하지 않는다. 서로 다른 Store의 같은 actor·operation·key도 membership shared lock 뒤 동일한
  transaction-scoped advisory lock으로 직렬화한 다음 replay를 다시 읽고, 그 뒤에만 Store commerce-root
  exclusive lock을 획득한다. 따라서 concurrent changed-payload 재사용은 unique violation/503이 아니라
  결정적인 `IDEMPOTENCY_KEY_REUSED` 409다.
- rollback된 명령은 terminal command row, owner 변경, 검색 색인 또는 Audit를 남기지 않는다.
- command record는 BR-26 보존 규칙과 bounded cleanup을 따른다.

### 7. 검색 색인은 거래 command와 같은 transaction에서 갱신한다

Menu name, availability 또는 lifecycle이 바뀌면 ADR-103의 `MENU_NAME` term을 같은 transaction에서
교체한다. 색인 갱신 실패는 owner 변경과 Audit를 전부 rollback한다. archived/unavailable Menu는 검색
term을 만들지 않는다. batch, event 또는 주기 rebuild를 정상 write path의 성공 대체로 사용하지 않는다.

### 8. 쓰기 상한을 public catalogue bound 앞에서 강제한다

ADR-076의 Store당 active Menu 1,000개, active Option 5,000개 상한을 write transaction에서 먼저
검증한다. 추가 초기 상한은 다음과 같다.

- Menu당 active Option 최대 100개
- Menu당 active Configuration 최대 500개
- Configuration당 active sellable-unit requirement 최대 50개

상한 초과는 400 validation failure이며 partial catalogue, 잘린 성공 또는 503으로 저장하지 않는다.
DB constraint가 표현 가능한 positivity/uniqueness/lifecycle tuple은 DB에서도 보호하고, Store 전체 count와
cross-row 의미는 Store exclusive lock 아래 Application Service가 보호한다.

## Alternatives Considered

### 기존 row 수정 command만 추가

ADR-116 동시성 재현 범위는 작지만 점주 `메뉴·가격` 화면이 새 Menu를 만들거나 종료할 수 없다. seed와
직접 DML 의존이 남으므로 채택하지 않았다.

### 물리 DELETE 제공

현재 Order가 immutable snapshot을 갖더라도 Audit, idempotent replay, stale cart와 검색 source 추적이
어려워지고 복구가 불가능하다. 보관 상태로 current sale에서 제외하는 쪽을 택했다.

### Store와 모든 Menu row를 exclusive lock

구현은 직관적이지만 같은 Store의 최종 Order끼리 직렬화된다. Store shared/exclusive protocol은 Order
동시성을 유지하면서 writer만 배제하므로 채택하지 않았다.

### 각 child row에 개별 FOR UPDATE

Option/Configuration 추가·삭제 phantom과 안정 lock 순서가 복잡해진다. 모든 거래 writer가 Store root를
먼저 잠그면 child 전체의 coherent snapshot을 더 단순하게 보장할 수 있다.

### 기존 Store/Menu JPA version을 fingerprint에 유지

이미지·표시 변경을 거래 stale로 만들면서 child row 변경을 완전히 대표하지 못한다. 별도 거래 version과
canonical value를 사용한다.

### 이벤트로 검색 색인 갱신

owner write 성공 직후 검색에 과거 Menu가 남는 window와 retry/reconciliation 상태가 새로 생긴다.
현재 두 table은 같은 PostgreSQL transaction에 참여하므로 동기 원자 갱신을 유지한다.

## Consequences

- 최종 Order는 Merchant shared lock을 먼저 잡으므로 Store 단위 writer와 짧은 lock wait가 생긴다.
- Store의 여러 Order는 shared lock끼리 충돌하지 않지만 실제 PostgreSQL lock-wait와 throughput은 구현 PR에서
  측정해야 한다.
- Store/Menu image와 display content를 바꿔도 quote는 stale되지 않는다.
- Menu 생성·교체 payload가 Option/Configuration/requirement를 포함하므로 API와 UI validation이 커진다.
- archived data가 남으므로 retention/hard-delete 요구가 실제로 생기면 별도 정책과 migration이 필요하다.
- 기존 seed와 migration은 새 version/lifecycle column의 deterministic backfill을 거쳐 모두 ACTIVE가 된다.

## Verification

- PostgreSQL 두 transaction으로 writer-first와 Order-first interleaving을 각각 강제한다.
- 같은 Store의 두 final Order가 shared lock 아래 동시에 Merchant snapshot을 읽을 수 있는지 검증한다.
- Menu price/Option/Configuration/Store policy 변경은 stale, display description/category와 Store/Menu image
  변경은 동일 fingerprint인지 검증한다.
- create/update/archive의 OWNER·STAFF, revoked, cross-store, stale version, no-op, idempotent replay와
  changed-payload conflict를 검증한다.
- membership shared lock을 먼저 얻은 command와 revoke의 경합, revoke가 먼저 commit된 command 거절,
  membership 없는 cross-store/없는 Store의 동일 404를 PostgreSQL에서 검증한다.
- Menu/Option/Configuration/requirement invariant와 1,000/5,000/100/500/50 상한을 Application/DB 양쪽에서
  검증한다.
- owner row, child rows, command response, Audit와 `MENU_NAME` search term이 함께 commit/rollback하는지
  Testcontainers로 검증한다.
- archived/current customer catalogue와 과거 Order snapshot이 섞이지 않는지 검증한다.

## Metrics

- `beanflow.merchant.catalog.command.count{operation,outcome,role}`
- `beanflow.merchant.catalog.lock.wait{mode,outcome}`
- `beanflow.merchant.catalog.limit_rejected.count{resource}`
- `beanflow.order.quote.stale.count{owner=merchant}`

actor, Store/Menu/Option ID, 가격, payload, fingerprint와 `Idempotency-Key`는 tag나 구조화 log field에 넣지
않는다. 실제 성능 목표나 개선률은 측정 전 주장하지 않는다.

## Revisit Conditions

- STAFF 가격 상한이나 OWNER 승인 요구가 생길 때
- archived Menu 복원 또는 법적 hard-delete/retention 요구가 생길 때
- 한 Store의 실제 Order throughput에서 Store shared lock 병목이 측정될 때
- 100/500/50 상한에 정상 catalogue가 근접할 때
- Merchant가 별도 database/service로 분리되어 local shared/exclusive lock이 불가능해질 때

## Related Decisions

- BR-03, BR-05, BR-25, BR-26, BR-30, BR-47, BR-49, BR-50, BR-52
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-026](ADR-026-menu-configuration-sellable-unit-mapping.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [ADR-103](ADR-103-store-search-strategy.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
- ADR-116 — 비예약 주문 quote와 전체 fingerprint 사전조건. 현재 checkout의 stacked predecessor에만
  있으므로 Milestone 0 docs-only child PR에서 정식 상대 링크로 바꾼다.
- ADR-117 — Store 고객 표시 profile과 주문 가능성 분리. 현재 checkout의 stacked predecessor에만
  있으므로 Milestone 0 docs-only child PR에서 정식 상대 링크로 바꾼다.
