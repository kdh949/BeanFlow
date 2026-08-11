# 거래 코어를 로그인·주문·운영이 가능한 제품으로 연결한다

> **Status:** `ACTIVE`
> **Kind:** `ORCHESTRATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 이 문서는 dependency, release gate와 evidence만
관리하며 직접 코드·스키마를 변경하지 않는다.

## Purpose / Big Picture

BeanFlow는 주문 가격 스냅샷, 예약 lease, 결제 멱등성과 `UNKNOWN` reconciliation, 부분 환불,
포인트 원장, 정산 조정과 이의제기까지 갖춘 거래 코어를 가지고 있다. 그러나 **그 코어를 쓸 수 있는
사람이 없다.** 계정도, 로그인도, 목록도, 사람이 읽을 수 있는 주문번호도 없다.

이 프로그램은 거래 코어를 재작성하지 않고, 그 위에 다음 경계를 얹어 제품으로 만든다.

1. Identity: 고객·점주 계정과 Session, 운영자 Keycloak 유지
2. 사람용 식별자: 공개 주문번호와 매장·영업일 픽업번호
3. 조회 Projection: 내 주문 목록, 매장 주문보드, 운영 실패·정산·감사 조회
4. 탐색과 actor facade: 검색·즐겨찾기·최근 매장, 포인트·재주문·부분 환불
5. 브라우저 통합: 고객·점주 Session/CSRF와 운영자 Keycloak PKCE
6. 계약 정합화: 원본 디자인 48화면과 제품 완결성에 필요한 신규 운영 화면의 Backend capability·우선순위 확정

완료 기준은 "화면이 늘어난 것"이 아니라 **Capability Map의 P0 24화면이 모두 실제 API와 연결되고,
고객이 로그인해 주문하고, 점주가 처리하고, 운영자가 실패를 복구할 수 있는 것**이다. M6의 고객
주문 목록과 점주 주문보드는 이 완료점으로 가는 첫 수직 흐름이지 프로그램 완료점이 아니다.

## Current State

- 거래 코어는 구현·검증됐다. 상세는 [README](../../../README.md)의 현재 상태 절을 따른다.
- `SecurityConfiguration.kt`는 단일 FilterChain, `STATELESS`, `csrf disable`이며 외부 JWK로 검증한
  JWT만 받는다. 토큰을 발급하는 주체가 저장소에 없다.
- `identity_store_membership`은 있으나 계정·자격증명 테이블이 없다.
- `ordering_order`에 `public_reference`, `pickup_business_date`, `pickup_sequence`와 매장명·픽업
  시간 스냅샷이 없다.
- 공개 API 43 path 중 목록 조회는 정산 Item과 포인트 거래뿐이다. "내 주문", "이 매장의 오늘 주문",
  "실패 작업 목록"이 없다.
- 프론트엔드는 `CustomerPages.tsx`, `ConsolePages.tsx`에 화면이 집중돼 있고 Access Token 입력 UI와
  UUID 입력창을 사용한다.
- 디자인 48화면의 요구와 계약 충돌은 [Design Contract Conflicts](../../product/design-contract-conflicts.md)에
  정리됐고 화면별 우선순위는 [Design to Capability Map](../../product/design-to-capability-map.md)에 있다.

## Definitions

- **Productization:** 기존 거래 capability를 사람이 실제로 사용할 수 있는 경로에 연결하는 작업이다.
- **CurrentActor:** 인증 구현과 무관하게 Application 계층이 사용하는 행위자 표현이다.
- **Public order reference:** 전역 유일한 외부 노출 주문번호(`BF-7K3M-9Q2P`)다. 권한 증명이 아니다.
- **Pickup number:** 매장·영업일 단위 순번의 표시값(`A-142`)이다. 조회 키가 아니다.
- **Query Projection:** 같은 PostgreSQL에서 Aggregate를 로딩하지 않고 DTO만 조회하는 읽기 코드다.
- **allowedActions:** 서버가 계산해 반환하는, 현재 상태에서 수행 가능한 행동의 닫힌 집합이다.

## Scope

### In Scope

- P0 24화면을 소유하는 ExecPlan의 순서, dependency, migration lane과 release gate 관리
- 각 plan의 완료 증거 수집과 다음 plan의 `Implementation-Ready` 전환
- 프로그램 수준 지표와 미결정 항목 추적

### Non-goals

- 코드·스키마 직접 변경
- 48화면 정적 UI 일괄 구현
- 기존 주문·결제·정산 코어 재작성
- UUID를 제거하고 짧은 문자열을 PK로 사용
- 모든 사용자를 Keycloak으로 이전
- 자체 Refresh Token JWT 체계
- 주문보드를 위한 WebFlux·WebSocket 도입
- 검색을 위한 Elasticsearch 도입
- Session을 위한 Redis 즉시 도입
- 실제 정산 지급·가맹점 KYC
- Wallet·AI·POS·배달 동시 착수

## Business Rules and Invariants

프로그램 전체에서 훼손하면 안 되는 규칙이다. 각 plan은 자신의 범위에서 이를 검증한다.

1. 내부 UUID는 PK, FK, 이벤트 Aggregate ID로 계속 사용한다.
2. 사람용 주문번호는 UUID를 자른 값이 아니라 별도 컬럼이다.
3. 주문번호는 권한 증명이 아니다. 소유권·매장 소속을 함께 검증한다.
4. 행위자 ID는 요청 Body에서 받지 않는다. Session 또는 SecurityContext에서 가져온다.
5. 점주는 `StoreMembership`이 허용한 매장만 조작한다. 역할만으로 통과하지 않는다.
6. 주문 스냅샷(메뉴명, 옵션명, 단가, 매장명, 픽업 시간)은 변경하지 않는다.
7. 클라이언트 금액을 신뢰하지 않는다. Checkout에서 서버가 재계산한다.
8. PG 결과가 불명확하면 재승인하지 않고 조회·reconciliation한다.
9. 금전성 변경은 원장과 감사 흔적을 남긴다.
10. 비동기 실패를 성공으로 숨기지 않는다.
11. 재주문은 복제가 아니라 재검증이다.
12. PG·알림 호출을 긴 DB 트랜잭션 안에 넣지 않는다.

## Architecture and Transaction Boundaries

```text
Customer Web / Merchant Console / Operations UI
                    │
     Session Cookie │ Keycloak JWT
                    ▼
        Spring Security (4 FilterChain)
   Customer / Merchant / Operations / Public
                    │
                    ▼
              CurrentActor
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
  Command / Aggregate     Query Projection
        │                       │
        ▼                       ▼
              PostgreSQL
  Aggregate / Session / Ledger / Outbox / Index
```

- 쓰기는 기존 Aggregate와 트랜잭션 경계를 그대로 사용한다.
- 조회는 Query Repository가 DTO만 반환하고 `readOnly` 트랜잭션에서 실행한다.
- 인증 구현체는 Application·Domain에 노출하지 않는다.

## Alternatives Considered

프로그램 수준 순서에 대한 대안이다. 개별 기술 대안은 각 ADR에 있다.

### 1. 화면 우선 순서(48화면을 순서대로 구현)

- 장점: 진척이 눈에 보인다.
- 단점: 인증과 사람용 식별자가 없으면 대부분의 화면이 UUID 입력창으로 끝난다. 나중에 전부 다시 만든다.

### 2. 인증을 마지막에 추가

- 장점: 기능 개발이 먼저 진행된다.
- 단점: 모든 API의 actor 해석과 인가를 나중에 소급 적용해야 한다. 목록 API는 actor 없이는 정의 자체가 불가능하다.

### 3. 식별자를 나중에 추가

- 장점: 초기 마이그레이션이 없다.
- 단점: 목록·상세·전이 API 경로가 모두 UUID 기준으로 먼저 굳는다. 이후 경로 변경 비용이 커진다.

### 채택

`계약 정합화 → 사람용 식별자 → 인증 기반 → 계정 → 목록 Projection` 순서다. 식별자와 인증은
서로 의존하지 않으므로 병렬 가능하지만, migration lane이 하나이므로 실제로는 직렬 실행한다.

## Failure Semantics

- 각 plan의 실패는 다음 plan을 자동으로 시작시키지 않는다. `Implementation-Ready` 전환은 실제
  검증 증거가 있을 때만 수행한다.
- migration을 쓰는 plan은 [ADR-072](../../adr/ADR-072-execplan-unattended-execution-and-migration-lane.md)의
  writer lease 없이 시작하지 않는다.
- 미완성 required 기능을 feature flag나 profile로 2xx 성공처럼 노출하지 않는다.
- 기존 JWT 기반 고객·점주 인증 경로와 새 Session 경로를 같은 URI에서 동시에 허용하지 않는다.
  Plan 20에서 두 경로를 Session-only로 먼저 바꾸며, Plan 30/40 전까지 고객·점주 보호 경로가 401인
  중간 가용성 중단을 허용한다. 이를 fake Session, 기본 actor 또는 JWT fallback으로 숨기지 않는다.

## Data and Migration

| Plan | Migration | 대상 |
|---|---|---|
| 00 | 없음 | 문서·계약만 |
| 10 | 있음 | `ordering_order` 식별자·스냅샷 컬럼, `ordering_pickup_counter`, backfill |
| 20 | 있음 | Spring Session 테이블 |
| 30 | 있음 | 고객 계정·자격증명·로그인 시도 |
| 40 | 있음 | 점주 계정·자격증명·잠금 |
| 50 | 있음 | 고객 주문 목록 인덱스 |
| 60 | 있음 | 주문보드 인덱스 |
| 70 | 있음 | 즐겨찾기, `pg_trgm` 검색 인덱스 |
| 80 | 없음 | 고객 actor facade와 frontend 통합 |
| 90 | 없음 | 기존 환불·정산·이의제기 조회와 frontend 통합 |
| 100 | 있음 | 운영 read permission vocabulary와 검증된 query index |

migration writer lease는 한 번에 하나만 보유한다. 현재 순서는 10 → 20 → 30 → 40 → 50 → 60 → 70 →
100이다. 80과 90은 migration을 쓰지 않으며 선행 dependency가 끝나면 lease 없이 실행할 수 있다.
Plan 90의 기존 dispute index가 측정 결과 부족해 schema 변경이 필요해지면 문서와 lease 순서를 먼저
갱신한다.

## API and Event Contracts

- 새 operation은 target OpenAPI(`openapi/beanflow-v1.yaml`)에 먼저 추가하고, Controller와 계약·인가·
  실패 테스트가 존재할 때 runtime OpenAPI에 반영한다.
- `RuntimeOpenApiParityTest`가 drift를 차단한다.
- 이벤트 계약은 이 프로그램에서 변경하지 않는다. 스냅샷 컬럼 추가는 이벤트 payload에 자동
  포함되지 않는다([ADR-068](../../adr/ADR-068-immutable-integration-event-snapshots.md)).

## Milestones

| # | Plan | 산출 | 상태 |
|---|---|---|---|
| M0 | [productization-00](productization-00-design-capability-contract.md) | 화면별 capability 계약, 충돌 해소, ADR 승인 | 진행 예정 |
| M1 | [productization-10](productization-10-public-order-reference.md) | 공개 주문번호, 픽업번호, 표시 스냅샷, backfill | 대기 |
| M2 | [productization-20](productization-20-authentication-foundation.md) | 4 FilterChain, Session, CSRF, `CurrentActor`, `/me`, credential 관리 permission | 대기 |
| M3 | [productization-30](productization-30-customer-account-and-login.md) | 고객 가입·로그인·로그아웃 | 대기 |
| M4 | [productization-40](productization-40-merchant-account-and-initial-password.md) | 점주 계정+최초 membership 운영 발급, 최초 비밀번호 강제 변경, 매장 목록 | 대기 |
| M5 | [productization-50](productization-50-customer-order-read-model.md) | 내 주문 목록·상세, `allowedActions` | 대기 |
| M6 | [productization-60](productization-60-store-order-board.md) | 매장 주문보드, 주문번호 기반 상태 전이 | 대기 |
| M7 | [productization-70](productization-70-customer-store-discovery.md) | 검색·즐겨찾기·최근 매장·추천 | 대기 |
| M8 | [productization-80](productization-80-customer-web-p0-integration.md) | 고객 P0 13화면 Session/API 통합 | 대기 |
| M9 | [productization-90](productization-90-merchant-financial-workflows.md) | 점주 부분 환불·정산·이의제기 | 대기 |
| M10 | [productization-100](productization-100-operations-work-queues.md) | 운영자 PKCE, 실패 큐·정산 대사·감사·Support·점주 발급 UI | 대기 |

M6 완료 시점은 다음 수직 흐름이 연결되는 **P0 Core 통합 지점**이다.

```text
고객 backend: 가입·로그인 → 공개 주문번호 기반 내 주문 목록·상세
점주 core: 로그인 → 비밀번호 변경 → 매장 선택 → 주문보드 → 접수·제조·완료
운영자 backend: 기존 Keycloak Resource Server와 보상·재처리 command 유지
```

이 지점만으로 P0 프로그램을 완료 처리하지 않는다. M7~M10의 매장 검색, 고객 포인트·재주문·결제
복구, 점주 부분 환불·정산·이의제기, 운영 실패 큐·대사·감사·Support와 각 frontend 상태 계약을
실제 검증해야 P0가 완료된다.

## Required Tests

프로그램 수준 gate다. 개별 테스트는 각 plan이 소유한다.

- 프로그램 완료 시 `frontend/src/components/Shells.tsx`의 Access Token 입력 UI가 제거됐는지 확인한다.
- 고객·점주 화면에서 UUID 입력창이 남아 있지 않은지 확인한다.
- 로컬 데모 smoke가 계정 생성 → 로그인 → 주문 → 점주 처리까지 토큰 붙여넣기 없이 통과하는지 확인한다.
- 운영자 smoke가 Keycloak PKCE login → 점주 계정+membership 발급·임시 비밀번호 1회 표시 → typed
  failure queue → 정산 대사 → reason이 있는 감사 조회로 이어지고 token/UUID 입력이 없는지 확인한다.
- 고객·점주 unsafe request가 actor별 CSRF token을 교차 수용하지 않는지 확인한다.
- 기존 거래 코어 테스트가 회귀 없이 통과하는지 각 plan 완료 시 확인한다.

## Validation Commands

```bash
./gradlew spotlessCheck
./gradlew build --stacktrace
bash scripts/verify-docs.sh
git diff --check
git diff --cached --check
```

## Observability

프로그램 수준으로 관찰할 지표다. 목표값은 측정 전에 정하지 않는다.

- 인증 성공률, 실패 유형 분포, Lockout 수, 활성 Session 수
- 주문 목록 p95와 SQL 수
- 주문보드 p95, Polling RPS, 상태 전이 충돌률
- 주문번호 충돌 재생성 수, 픽업 순번 잠금 대기 p95
- 결제 `UNKNOWN` 건수와 해소 시간(기존 지표 유지)

## Documentation Updates

- [Design to Capability Map](../../product/design-to-capability-map.md)
- [Design Contract Conflicts](../../product/design-contract-conflicts.md)
- [Actors and Goals](../../product/actors-and-goals.md)
- [Non-goals](../../product/non-goals.md)
- [Authorization Matrix](../../security/authorization-matrix.md)
- ADR-092~ADR-105, ADR-107~ADR-110
- 각 plan 완료 시 [Quality Evidence Map](../../quality/quality-evidence-map.md)

## Progress

- 2026-08-11: 프로그램 정의와 Plan 00~60 골격, ADR-092~105 초안 작성. 코드 변경 없음.
- 2026-08-12: P0 화면의 누락 owner로 Plan 70~100을 추가하고 ADR-107~110, BR-38~45 결정 반영.
- 2026-08-12: 운영 웹 점주 계정 발급·최초 membership·임시 비밀번호 1회 표시를 BR-46으로 확정하고
  P0 범위를 24화면으로 확장.

## Surprises & Discoveries

- 저장소에 고객·점주 계정 테이블이 **전혀 없었다.** `identity_customer_support_profile`은 Support
  목적의 보호 프로필이며 로그인 계정이 아니다. Identity plan의 범위가 예상보다 크다.
- 디자인의 운영자 개요 화면이 "결제사 지연 3초 초과 시 자동 전환"을 포함하고 있었다. 이는
  `AGENTS.md`의 자동 fallback 금지와 정면으로 충돌한다. C-4로 기록했다.
- 디자인의 고객 로그인은 전화번호 OTP 기반 passwordless였다. P0 인증 결정(ID/PW)과 충돌하며,
  SMS Provider가 저장소에 없어 P0에서 구현할 수 없다. C-1로 기록했다.
- 디자인의 선착순 한정 쿠폰은 **모델 자체가 없었다.** `promotion_campaign`에 발급 한도·1인 한도·
  발급 기간 컬럼이 없고, `promotion_coupon_issuance`는 이미 고객별로 부여된 쿠폰이다. 고객이
  발급을 요청하는 경로가 존재하지 않는다. C-15와 ADR-107로 기록했다.
- 알림 템플릿 6종이 모두 거래성이고 전부 `orderId`를 가진다. 마케팅 알림은 존재하지 않는다.
  그래서 분류 규칙을 데이터로 판정할 수 있다. C-17로 기록했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | 고객·점주는 Session, 운영자는 Keycloak | [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-11 | P0 고객 인증은 ID/PW. 전화번호 OTP는 P1 | [C-1](../../product/design-contract-conflicts.md) |
| 2026-08-11 | 내부 UUID 유지, 공개 주문번호·픽업번호 신설 | [ADR-096](../../adr/ADR-096-public-order-reference.md), [ADR-097](../../adr/ADR-097-store-pickup-number.md) |
| 2026-08-11 | 물리적 CQRS 없이 같은 DB의 Query Projection | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-11 | 주문보드는 조건부 Polling으로 시작 | [ADR-102](../../adr/ADR-102-polling-before-sse.md) |
| 2026-08-11 | 저장 결제수단은 Checkout 승인 원천이 아니다 | [ADR-101](../../adr/ADR-101-payment-method-checkout-scope.md) |
| 2026-08-11 | 실제 정산 지급·가맹점 KYC는 Non-goal | [ADR-105](../../adr/ADR-105-sandbox-settlement-payout.md) |
| 2026-08-12 | 주문 내역은 기본 30일 + 기간 필터, 과거 조회 상한 없음 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-12 | 한정 쿠폰은 원자적 발급, 잔여 수량은 근사, 카운터는 발급 기준 고정 | [ADR-107](../../adr/ADR-107-limited-coupon-issuance.md) |
| 2026-08-12 | 점주 매출 지표는 Analytics가 단독 소유하고 점주 화면은 소비자 | [MD-2026-012](../../decisions/minor-decisions.md) |
| 2026-08-12 | 알림 분류는 `orderId` 유무로 판정, 매장 알림은 예외 없이 거래성 | [ADR-104](../../adr/ADR-104-notification-inbox.md) |
| 2026-08-12 | P0 완료는 Capability Map의 24화면 전체다. M6는 P0 Core 중간 통합 지점이며 누락 capability의 후속 ExecPlan이 필요하다 | [Capability Map](../../product/design-to-capability-map.md) |
| 2026-08-12 | 원본에 없던 점주 계정 발급 화면을 Operations P0에 추가하고 account+membership을 원자 생성 | [BR-46](../../product/business-policy-decisions.md) |
| 2026-08-12 | Plan 20에서 고객·점주 경로를 Session-only로 먼저 전환하고 Plan 30/40까지의 401 중간 단절을 허용한다 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-12 | 고객 13화면은 Plan 80, 점주 금융 3화면은 Plan 90, 운영자 5화면은 Plan 100이 최종 상태 검증을 소유한다 | [Capability Map](../../product/design-to-capability-map.md) |
| 2026-08-12 | Operations SPA는 Keycloak PKCE S256, 실패 큐는 source-owned typed Projection을 사용한다 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [ADR-110](../../adr/ADR-110-federated-operations-failure-queues.md) |

## Outcomes & Retrospective

아직 없다. 각 plan 완료 시 실제 결과와 측정값으로 갱신한다.

## Revision Notes

- 2026-08-11: 최초 작성.
