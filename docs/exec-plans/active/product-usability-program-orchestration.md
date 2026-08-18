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
포인트 원장, 정산 조정과 이의제기까지 갖춘 거래 코어를 가지고 있다. M0~M9를 거치며 고객·점주
계정과 Session, 사람용 주문번호, 조회 Projection, 탐색과 고객·점주 frontend도 현재 소스에
추가됐다. 이 문서는 그 완료 기록과 남은 운영·release gate를 함께 관리한다.

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

- M0~M9는 completed ExecPlan으로 남아 있다. 고객·점주 Session/CSRF, account, public order reference,
  customer order projection, store board, discovery, customer P0 integration과 merchant financial workflow가
  현재 source baseline에 있다. M10 operations work queue만 이 orchestration의 대기 slice다.
- runtime OpenAPI는 150 path, target OpenAPI는 161 path다. runtime은 target의 의도적인 구현 부분집합이며
  동일 path 수를 요구하지 않는다. 대응 검증은 `RuntimeOpenApiParityTest`가 소유한다.
- 고객 frontend는 `/app` 아래 로그인·가입·탐색·매장/메뉴/픽업·장바구니·결제·주문·포인트·마이를,
  점주 frontend는 `/store` 아래 로그인·최초 비밀번호 변경·매장 주문보드·부분 환불·정산·이의제기를
  route로 가진다. 이 사실은 현재 source inventory이며 새로운 browser 실행 증거는 아니다.
- 현재 core journey의 알려진 기능 공백은 customer coupon wallet query/selection이다. 이미 발급된 쿠폰의
  actor-scoped read만 [Goal Stage 05 ExecPlan](core-journey-05-customer-coupon-wallet.md)이 소유한다.
  plan status는 `ACTIVE`, backend/API slice의 `Implementation-Ready=true`, `Writes-Migration=false`다.
  Storybook MCP가 unavailable인 동안 UI slice는 blocked다. 현재 slice는 store scope만 판단하고 brand scope는
  defer하며, Campaign 관리·발급·history와 wallet balance는 범위 밖이다.
  주문 생성은 계속 coupon의 소유권·상태·만료·적용 조건·동시 소비를 최종 재검증한다.
- 정식 여정, source evidence와 release 상태는 [Core User Journey Contract](../../product/core-user-journey.md)와
  [Core Journey Release Gate](../../quality/core-journey-release-gate.md)에 분리해 기록한다.
- 디자인 48화면의 요구와 계약 충돌은 [Design Contract Conflicts](../../product/design-contract-conflicts.md)에
  정리됐고 화면별 capability와 우선순위는 [Design to Capability Map](../../product/design-to-capability-map.md)에 있다.

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
- [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md)에 따른 Stack A(Plan 00~60)의
  정확히 일곱 개의 직렬 Draft PR chain, shared migration-writer lease와 final topology gate 관리

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
- Wallet balance·AI·POS·배달 동시 착수. 단, 이미 발급된 쿠폰의 actor-scoped 조회·선택은 Goal Stage 05의
  좁은 core-journey 예외이며 wallet balance나 Campaign 관리로 확장하지 않는다.

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
- Stack A 실행 중 exact predecessor/head SHA가 달라지거나 required validation, migration lease,
  branch/PR topology가 불명확하면 다음 plan을 시작하지 않고 Goal을 중단한다. observed `origin/main`
  변화와 Support commit의 `origin/main` 비조상 관계는 `SUPPORT_INTEGRATION_PENDING`으로 기록하지만
  중단·restack·force-push 사유로 쓰지 않는다.
- 2026-08-12 사용자 결정에 따라 Plan 10 완료 뒤 Stack A를 동결했다. 2026-08-13 Support S70~S100과
  PR #63 remediation completion, V49 lease release, `origin/main` 통합과 Plan 10 V50/V51 재번호화 기준을
  확인해 동결을 해제했다. Plan 10 전체 재검증이 끝나기 전에는 Plan 20을 시작하지 않는다.

## Stack A Execution Contract

Stack A는 P0 Core 중간 통합점인 Plan 60까지만 다음 고정 순서로 실행한다.

```text
00 → 10 → 20 → 30 → 40 → 50 → 60
```

아래 Stack A topology와 checkpoint 설명은 당시의 실행 기록이다. 현재 source에서는 M0~M9가 completed이고,
후속 작업의 readiness는 이 절의 옛 queue가 아니라 각 active ExecPlan·dependency·release gate로 판단한다.

Plan 00의 verified completion head를 provisional baseline으로 기록한다. Support 구현 commit
`35d662d0deb5808c0df12b3ae822d9ec128aa28e`와 완료 commit
`ae9fa0b9c97a75134131106a1818f04315611860`이 Plan 00의 ancestor이고 필수 Support 파일/migration이
존재하면 Stack A를 계속한다. 각 후속 plan은 직전 verified completion head에서 branch를 만들고 바로
이전 branch를 base로 하는 Draft PR을 연다.

| Plan | Branch | Draft PR base |
|---|---|---|
| 00 | `feature/productization-00-contract` | `main` |
| 10 | `feature/productization-10-order-reference` | Plan 00 branch |
| 20 | `feature/productization-20-auth-foundation` | Plan 10 branch |
| 30 | `feature/productization-30-customer-account` | Plan 20 branch |
| 40 | `feature/productization-40-merchant-account` | Plan 30 branch |
| 50 | `feature/productization-50-customer-orders` | Plan 40 branch |
| 60 | `feature/productization-60-store-order-board` | Plan 50 branch |

Plan completion은 required validation과 atomic active→completed/successor update가 exact predecessor
위에서 끝났음을 뜻한다. merge나 deploy를 뜻하지 않는다. Plan 10 시작 전 획득한 migration-writer
lease는 최초 completion 뒤 Support S70에 넘겼고, Support S100/PR #63이 V49까지 완료·release된 뒤
Productization Stack A가 다시 획득했다. Resume Plan 10 completion은 ADR-111에 기록한 Plan 10 remote
head와 `origin/main` 두 parent의 history-preserving merge 예외를 사용한다.

Plan 60 완료 뒤 release branch나 combined PR을 만들지 않는다. 최종 상태는 표의 정확히 일곱 open Draft
PR이며, Plan 00은 `main`, Plan 10~60은 정확히 직전 branch를 base로 한다. base만 틀린 기존 PR은 head를
바꾸지 않고 정정할 수 있다. `feature/productization-plans` head PR, Plan 70+ branch/PR, merge, close와
force-push는 하지 않는다. 상세 규칙은 ADR-111을 따른다.

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

migration writer lease는 한 번에 하나만 보유한다. Productization 10 최초 완료 → Support
S70 → S80 → S90 → S100 → PR #63 remediation까지 V45~V49를 사용하고 release했다. 이어서
Productization 10 V50/V51, 20→70 migration이 historical execution record로 완료됐다. Productization 80과
90은 migration을 쓰지 않았고, 새 schema work는 현재 Flyway inventory·writer lease·실제 query evidence를
다시 확인한 뒤에만 시작한다.
Plan 90의 기존 dispute index가 측정 결과 부족해 schema 변경이 필요해지면 문서와 lease 순서를 먼저
갱신한다.

Plan 10 branch의 기존 V43/V44와 중간 보정 V45/V46은 배포·적용되지 않았고 최종 tree에 남기지 않는다.
Support V43~V49를 포함한 `origin/main`을 history-preserving merge한 뒤 Plan 10 migration은 V50/V51을
사용하고 전체 regression을 다시 통과해야 한다. PR #57 base는 Plan 00 branch로 유지하고 rebase나
force-push를 하지 않는다.

## API and Event Contracts

- 새 operation은 target OpenAPI(`openapi/beanflow-v1.yaml`)에 먼저 추가하고, Controller와 계약·인가·
  실패 테스트가 존재할 때 runtime OpenAPI에 반영한다.
- `RuntimeOpenApiParityTest`가 drift를 차단한다.
- 이벤트 계약은 이 프로그램에서 변경하지 않는다. 스냅샷 컬럼 추가는 이벤트 payload에 자동
  포함되지 않는다([ADR-068](../../adr/ADR-068-immutable-integration-event-snapshots.md)).

## Milestones

| # | Plan | 산출 | 상태 |
|---|---|---|---|
| M0 | [productization-00](../completed/productization-00-design-capability-contract.md) | 화면별 capability 계약, 충돌 해소, ADR 승인 | 완료 |
| M1 | [productization-10](../completed/productization-10-public-order-reference.md) | 공개 주문번호, 픽업번호, 표시 스냅샷, backfill | 완료 |
| M2 | [productization-20](../completed/productization-20-authentication-foundation.md) | 4 FilterChain, Session, CSRF, `CurrentActor`, `/me`, credential 관리 permission | 완료 |
| M3 | [productization-30](../completed/productization-30-customer-account-and-login.md) | 고객 가입·로그인·로그아웃 | 완료 |
| M4 | [productization-40](../completed/productization-40-merchant-account-and-initial-password.md) | 점주 계정+최초 membership 운영 발급, 최초 비밀번호 강제 변경, 매장 목록 | 완료 |
| M5 | [productization-50](../completed/productization-50-customer-order-read-model.md) | 내 주문 목록·상세, `allowedActions` | 완료 |
| M6 | [productization-60](../completed/productization-60-store-order-board.md) | 매장 주문보드, 주문번호 기반 상태 전이 | 완료 |
| M7 | [productization-70](../completed/productization-70-customer-store-discovery.md) | 검색·즐겨찾기·최근 매장·추천 | 완료 |
| M8 | [productization-80](../completed/productization-80-customer-web-p0-integration.md) | 고객 P0 13화면 Session/API 통합 | 완료 |
| M9 | [productization-90](../completed/productization-90-merchant-financial-workflows.md) | 점주 부분 환불·정산·이의제기 | 완료 |
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
- [Core User Journey Contract](../../product/core-user-journey.md)
- [Core Journey Release Gate](../../quality/core-journey-release-gate.md)
- [Actors and Goals](../../product/actors-and-goals.md)
- [Non-goals](../../product/non-goals.md)
- [Authorization Matrix](../../security/authorization-matrix.md)
- ADR-092~ADR-105, ADR-107~ADR-111
- 각 plan 완료 시 [Quality Evidence Map](../../quality/quality-evidence-map.md)

## Progress

- 2026-08-11: 프로그램 정의와 Plan 00~60 골격, ADR-092~105 초안 작성. 코드 변경 없음.
- 2026-08-12: P0 화면의 누락 owner로 Plan 70~100을 추가하고 ADR-107~110, BR-38~45 결정 반영.
- 2026-08-12: 운영 웹 점주 계정 발급·최초 membership·임시 비밀번호 1회 표시를 BR-46으로 확정하고
  P0 범위를 24화면으로 확장.
- 2026-08-12: Plan 00~60을 직렬 Draft PR로 검증하고 final combined release PR 하나로 전달하는
  최초 Stack A 실행 정책을 ADR-111로 확정. 같은 날 Support 통합 보류 결정에 따라 combined PR을
  폐기하고 정확히 일곱 Draft PR topology로 개정.
- 2026-08-12: Stack A root를 push된 `feature/productization-plans`의
  `3b67425e1761f883dded3ef04b715789f495e8d7`로, 당시 `origin/main`을
  `d8db63089a1d61a13069ab352819bc9479e4faa2`로 기록. Plan 00은 exact root에서 분기한
  `feature/productization-00-contract`에서 required validation과 atomic completion을 마쳤다.
  executor의 다음 후보는 고정 순서상 Plan 10이며 Plan 10 구현은 아직 시작하지 않았다.
- 2026-08-12: 사용자는 Support PR의 별도 merge를 선택했다. Support 두 commit이 Plan 00에 포함되고
  필수 파일이 존재하면 `origin/main` 비조상 관계를 `SUPPORT_INTEGRATION_PENDING`으로 기록하고
  비차단하기로 했다. Plan 00 PR base는 `main`, Plan 10~60은 exact predecessor branch, 최종 산출은
  combined release PR 없는 일곱 open Draft PR로 고정했다.
- 2026-08-12 Checkpoint 0: `.agent/PLANS.md`, ADR-111과 이 orchestration을 seven-PR 정책으로 교정하고
  `aeeb3caa6cb4f10353e84c6cffb2a63b2d6a2704`를 push했다. `bash scripts/verify-docs.sh`,
  `git diff --check`, `git diff --cached --check`가 통과했고 local/remote/PR #55 head가 모두 그 SHA,
  PR #55는 open Draft, head `feature/productization-00-contract`, base `main`임을 확인했다.
- 2026-08-12 Checkpoint 1: provisional baseline branch는 `feature/productization-00-contract`, 관측한
  Plan 00 head는 `aeeb3caa6cb4f10353e84c6cffb2a63b2d6a2704`, observed `origin/main`은
  `d8db63089a1d61a13069ab352819bc9479e4faa2`다. Support 구현
  `35d662d0deb5808c0df12b3ae822d9ec128aa28e`와 완료
  `ae9fa0b9c97a75134131106a1818f04315611860`는 모두 Plan 00의 ancestor(`true`)이고
  `origin/main`의 ancestor는 둘 다 `false`다. 후자는 `SUPPORT_INTEGRATION_PENDING`이며 비차단이다.
- 2026-08-12 Checkpoint 1 migration lease: V42 Support migration, Support verification service,
  `PersonalDataReveal` 계약과 completed S40 ExecPlan이 exact tree에 존재하고 Support 완료 이후 Plan 00의
  migration diff는 0건이다. migration version 중복 없이 마지막은 V42이며 Plan 10의 다음 사용 번호는
  V43이다. open PR은 완료된 Support writer #54와 migration을 추가하지 않은 Plan 00 #55뿐이고 Plan 10+
  또는 Analytics 구현 branch/PR은 없다. `STACK_A_MIGRATION_WRITER_LEASE`를 Plan 10→60에 획득했으며
  Plan 60 Draft PR과 최종 topology/전체 검증 뒤 해제한다.
- 2026-08-12 Plan 10: exact predecessor `3c02752c114a271cec2458a1f9fcc00873d0ae1f`에서 V43 expand와
  V44 contract migration, 공개 주문번호·픽업 순번·표시 snapshot, bounded backfill, 공개번호 기반 고객
  조회·취소와 매장 조회·전이를 구현했다. Ordering 224 tests, 전체 782 tests(1 skipped), Spotless와
  문서/OpenAPI 검증이 모두 통과했다.
- 2026-08-12 Support 우선 결정: Plan 10 completion 뒤 `STACK_A_MIGRATION_WRITER_LEASE`를 해제하고
  Plan 20의 `Implementation-Ready`를 false로 전환했다. PR #57은 open Draft로 보존하며 Support
  S70~S100 완료 전 productization schema/code를 추가하지 않는다. 기존 V43/V44는 resume 시 Support
  통합 tree의 마지막 번호 다음으로 재번호화해야 한다.
- 2026-08-13 resume preflight: `origin/main`
  `48a0b6166751d2f4e991408ce618d1182b592380`에 Support S50~S100과 PR #63 remediation, V43~V49,
  941-test completion과 lease release가 포함된 것을 확인했다. 사용자는 이 워크트리 외 병렬 작업이
  없음을 명시하고 최신 main 통합을 요청했다.
- 2026-08-13 Plan 10 resume: remote Plan 10 head
  `8aa3704014c0943aa7e80e8205c007caaf3a28d2`를 first parent로 유지한 채 `origin/main`을
  `--no-ff` merge하고, 충돌한 주문/Fulfillment model과 target OpenAPI를 양쪽 계약이 공존하도록
  해소했다. 미적용 Plan 10 migration은 combined inventory 다음 V50 expand/V51 contract로 옮겼다.
  첫 full build의 964 tests 중 Support S80 fixture/latest-version 회귀 17건이 실패한 것을 숨기지 않고
  공통 주문 표시 fixture와 V51 assertion으로 교정했다. Ordering 231 tests, 실패 집중 20 tests, 최종
  full build 964 tests(0 failures, 0 errors, 1 skipped), Spotless와 문서/OpenAPI 검증이 통과해 Plan 20
  `Implementation-Ready`를 true로 복원했다. PR #57 head 동기화는 이 merge completion commit 뒤 수행한다.
- 2026-08-13 Plan 10 resume completion: history-preserving merge commit
  `50cfad0d63e69fedbc343459d29a9044c84b2c2b`을 push했다. local HEAD, remote branch와 PR #57 head가
  모두 그 SHA이고, PR #57은 open Draft, head `feature/productization-10-order-reference`, base
  `feature/productization-00-contract`임을 확인했다. PR 본문은 V50/V51, 첫 full build 17 failures와
  최종 964-test 성공 증거로 갱신했다. `SUPPORT_INTEGRATION_PENDING`은 이 branch에서
  `origin/main` V43~V49를 merge해 해소됐으며 Stack A PR merge나 force-push는 수행하지 않았다.
- 2026-08-13 Plan 20 completion: exact Plan 10 head 위에 V52 Spring Session schema, 네 FilterChain,
  actor별 Session/CSRF, typed CurrentActor, transaction-bound login Session lifecycle와 Operations `/me`를
  구현했다. 첫 full build의 995 tests 중 보안 계약 기대 2건이 실패한 것을 교정하고 최종 995 tests
  (0 failures, 0 errors, 1 skipped), Spotless와 target/runtime OpenAPI·문서 검증을 통과했다. Plan 30의
  direct dependency를 completed path로 바꾸고 readiness를 true로 전환했다.
- 2026-08-13 Plan 30 completion: exact Plan 20 head `a6bf720` 위에 V53 CustomerAccount/login-attempt,
  Argon2id/HMAC credential 경계, 원자적 0원 PointAccount provisioning과 Customer Session 가입·로그인·
  logout을 구현했다. 최종 backend build 1,026 tests, customer demo checkpoint 17 HTTP 단계,
  Spotless와 문서/OpenAPI 검증이 통과해 Plan 40 dependency를 completed path로 바꾸고 readiness를
  true로 전환했다. Merchant 전환·환불 기본 전체 smoke는 Plan 40 required gate로 보존했다.
- 2026-08-13 Plan 30 Draft release: branch `feature/productization-30-customer-account`, base
  `feature/productization-20-auth-foundation`, completion/local/remote/PR head
  `c44653efaaa58b5127666deffcff9c7d1d90cdf1`이 일치한다. [Draft PR #65](https://github.com/kdh949/BeanFlow/pull/65)는
  OPEN/Draft이고 중복 PR이 없다. required backend/demo/docs 검증은 위 completion evidence와 같으며,
  추가 frontend build의 Plan 80 CSRF client 미구현 실패도 PR과 completed Plan에 기록했다.
  `SUPPORT_INTEGRATION_PENDING`은 Plan 10의 history-preserving `origin/main` 통합으로 현재 stack tree에서
  해소됐고 merge·ready 전환·force-push는 수행하지 않았다.
- 2026-08-13 Plan 40 completion: exact Plan 30 head `c44653e` 위에 V54 MerchantAccount와 credential
  command schema, account-backed Merchant Session, 최초 비밀번호 변경 gate, ACTIVE 매장 목록과
  `MERCHANT_CREDENTIAL_MANAGE` 운영 API를 구현했다. 첫 full build의 35 failures, Modulith cycle,
  compiler/daemon race와 첫 demo bootstrap `DEPENDENCY_UNAVAILABLE`를 각각 fixture·port 방향·clean
  재검증·`char(64)` JPA 매핑으로 해소했다. 최종 build 1,058 tests(0 failures, 0 errors, 1 skipped),
  Identity/StoreOrder/Spotless, clean 47-step Customer→Merchant demo와 문서/OpenAPI 검증이 통과했다.
  Plan 50 dependency를 completed Plan 40으로 닫고 readiness를 true로 전환했으며 Plan 60은 Plan 50을
  direct execution dependency로 유지해 아직 실행 가능하지 않다.
- 2026-08-13 Plan 40 Draft release: branch `feature/productization-40-merchant-account`, base
  `feature/productization-30-customer-account`, completion/local/remote/PR head
  `2642a7ede4597bcee9fa257ed17a3ff0915d01d9`이 일치한다. [Draft PR #66](https://github.com/kdh949/BeanFlow/pull/66)은
  OPEN/Draft이고 같은 head의 open PR은 하나다. GitHub build check는 조회 시점에 `pending`이라
  `mergeStateStatus=UNSTABLE`이며 실패 성공으로 기록하지 않는다. Support 두 commit은 모두
  `origin/main` ancestor이고 PR #54는 MERGED라 `SUPPORT_INTEGRATION_PENDING=false`다. merge·ready
  전환·force-push는 수행하지 않았다.
- 2026-08-14 Plan 50 completion: exact Plan 40 head `2642a7e` 위에 V55 고객 최신 주문 인덱스,
  R1/W1/R2 DTO Projection, customer/filter/date-bound signed cursor, 서버 상태 분류와 `allowedActions`,
  공개번호 목록·상세 및 고객 주문 화면을 구현했다. 첫 Spotless formatting 실패, 첫 build의 MVC slice와
  Flyway-head assertion 실패, 두 번째 build의 test scheduler/fixture `TRUNCATE` deadlock을 각각 formatting,
  version-local fixture와 test-only scheduler 격리로 해소했다. 최종 ordering 251 tests,
  `*CustomerOrderQuery*`, Spotless, full build 1,078 tests(0 failures, 0 errors, 1 skipped)와 문서/OpenAPI
  검증이 통과했다. Plan 60과 Plan 70은 completed Plan 50을 직접 소비하므로 readiness를 true로
  전환했지만, 이 Stack A Goal에서는 Plan 60만 실행하고 Plan 70 구현은 시작하지 않는다.
- 2026-08-14 Plan 50 Draft release: branch `feature/productization-50-customer-orders`, base
  `feature/productization-40-merchant-account`, completion/local/remote/PR head
  `fcd5a2319a9e44ac2c7eb242b5db789319b82e0a`이 일치한다. [Draft PR #67](https://github.com/kdh949/BeanFlow/pull/67)은
  OPEN/Draft이고 같은 head의 open PR은 하나다. GitHub 조회 시 `mergeStateStatus=UNSTABLE`이어서 CI 성공으로
  과장하지 않았으며, Stack A merge·ready 전환·force-push는 수행하지 않았다.
- 2026-08-14 Plan 60 completion: exact Plan 50 head `fcd5a23` 위에 V56 주문보드 index, store-scoped
  2-statement JDBC Projection, canonical ETag/304, membership-first 목록·상세·전이, `expectedStatus` 기반
  409/422와 별도 멱등 namespace를 구현했다. 디자인 ZIP의 3열 POS 계약에 맞춘 다점포 보드와 3초
  polling·hidden pause·권한 상실 상태도 연결했다. 최종 backend build 1,091 tests(0 failures, 0 errors,
  1 skipped), `*StoreOrder*`, `*OrderBoard*`, Spotless, frontend 35 tests, 문서/OpenAPI와 desktop/mobile
  브라우저 검증이 통과했다. 추가 frontend build의 기존 Plan 80/90 CSRF 세 오류는 범위 밖 미해결로
  기록했다. Plan 90은 completed Plan 60 dependency를 소비해 readiness만 true로 전환하고 구현은 시작하지
  않았다. Plan 60 Draft PR과 final seven-PR topology 검증 전까지 migration lease는 유지한다.
- 2026-08-14 Plan 60 Draft release: completion commit `97412d59198cf2459cd9cb3ca7706e91e86ac452`를
  push했고 생성 직후 local/remote/[Draft PR #68](https://github.com/kdh949/BeanFlow/pull/68) head가
  모두 그 SHA였다. PR #68은 OPEN/Draft, head `feature/productization-60-store-order-board`, base
  `feature/productization-50-customer-orders`이며 같은 head의 중복 PR은 없다. GitHub
  `mergeStateStatus=UNSTABLE`을 CI 성공으로 과장하지 않았고 merge·ready 전환·force-push는 수행하지 않았다.
- 2026-08-14 Stack A final topology gate: PR #55/#57/#64/#65/#66/#67/#68이 정확히 일곱 개의 open
  Draft이며 각 base는 `main → 00 → 10 → 20 → 30 → 40 → 50` 순서와 일치했다. 일곱 remote branch SHA는
  각 PR head와 일치했고, `feature/productization-stack-a-release`, Plan 70+ 구현 branch/PR,
  `feature/productization-plans` head PR과 combined release PR은 없었다. Stack A PR 중 merge된 것은 0개다.
  Support PR #54는 별도 범위에서 MERGED이고 `SUPPORT_INTEGRATION_PENDING=false`다. 이 최종 gate 뒤
  `STACK_A_MIGRATION_WRITER_LEASE`를 해제했다. 이 release-evidence 문서 commit은 migration을 쓰지 않는다.
- 2026-08-14 Stack A merge-readiness repair: `origin/feature/productization-00-contract`가
  `3c02752`에서 `7be2ab3`으로 force-update되어 PR #57이 14개 문서·OpenAPI 충돌로 `CONFLICTING`이 됐다.
  Plan 10 tree가 V50/V51 계약과 완료 증거를 포함한 strict superset임을 대조해 그 내용을 보존하고,
  자동 추가된 obsolete active Plan 10 파일만 제외한 history-preserving merge commit `61ed520`을 push했다.
  이 부모를 Plan 20→60에 순차 merge했다. 새 PR #64 CI는
  `session-and-authentication-runbook.md` EOF blank line으로 실패했으며, `d14e6dd`에서 해당 한 줄을
  제거하고 Plan 30→60에 다시 순차 merge했다. 각 merge 뒤 `git diff --check`와 문서/OpenAPI 검증이
  통과했고, 최종 `00→10→20→30→40→50→60` 조상 관계는 모두 true였다. GitHub 조회 시 PR
  #55/#57/#64/#65/#66/#67/#68은 모두 OPEN/non-Draft이며 #57~#68은 `MERGEABLE`이다. 새 CI가 끝나기 전의
  `BLOCKED` 상태는 성공으로 기록하지 않았다. rebase, force-push, PR merge와 ready-state 변경은 수행하지 않았다.
- 2026-08-14 CodeQL CSRF closure: PR #64가 Public과 Operations의 `csrf.disable()`에 대해 high
  `java/spring-disabled-csrf-protection` 경고 두 건으로 실패했다. 사용자 결정에 따라 Public Chain의
  disable을 제거해 기본 CSRF를 복원하고, unsafe Public route 추가 시 별도 계약을 요구하도록 ADR-094를
  보강했다. Operations는 Cookie·Session을 받지 않는 stateless Bearer Resource Server라는 ADR-092/094
  계약을 유지해 initial alert #3을 `false positive`로 dismiss했다. 새 분석에서 CodeQL fingerprint가
  남은 Operations result를 기존 alert #2에 재연결해, 실제 67행 결과인 #2에도 같은 감사 comment와
  dismissal을 남겼다. Public code result는 남지 않았고, 두 closed alert record는 모두 Cookie/Session 또는 자동
  첨부 credential 도입 시 재검토 조건을 가진다. final tip의 7개 인증 통합 테스트는 Public POST 403,
  Operations Bearer POST의 무-CSRF 200, 무자격 401과 Customer/Merchant Session Cookie 403을 검증했다.
  첫 probe의 204 기대(실제 200)와 import ordering Spotless 실패는 각각 기대 상태·정렬 보정 뒤 재실행해
  통과했다. 첫 full CI는 top-level test `@RestController`가 component scan에 포함되어 Runtime OpenAPI
  parity 997 tests 중 1건을 실패시켰다. controller를 nested test configuration으로 옮긴 첫 시도는 duplicate
  handler mapping으로 인증 테스트 7건을 실패시켰고, bean factory를 제거해 test context에만 한 번 등록하도록
  고친 뒤 인증 7건과 RuntimeOpenApiParityTest 1건을 모두 통과했다. 보안 commit `6af1eee`과 test fix
  `a824e4a`는 Plan 30→60에 순차 merge했으며, `spotlessCheck`, 문서/OpenAPI 검증도 통과했다. 새 GitHub
  CI가 끝나기 전에는 성공으로 기록하지 않는다.
- 2026-08-16 M8 구현 완료: [productization-80](../completed/productization-80-customer-web-p0-integration.md)이
  고객 P0 13화면을 Session과 실제 거래 API에 연결하고 완료됐다. Plan 50이 Plan 80까지 미뤄 둔 CSRF
  mutation과 실제 취소 버튼도 함께 들어갔다. frontend 98 tests, typecheck, build, 대상 backend test와
  문서/OpenAPI 검증에 더해 `scripts/demo` stack을 띄운 420px 브라우저 전체 흐름을 확인했다. 그
  smoke에서만 드러난 결제 결과 화면 결함 2건과 주문 화면 표기 결함 2건을 고쳤다. 결과가 있는 매장
  검색 화면과 외부 결제창 이후 흐름은 실행하지 못했고 그 이유를 Plan 80에 남겼다.
- 2026-08-16 M8 재개: 그 browser 확인에서 고객이 볼 수 있는 매장 단건 조회 endpoint가 없다는 계약
  공백을 찾았다. 매장 상세를 URL로 열면 이름을 가져올 곳이 없어 화면과 장바구니에 일반 명칭이
  남았다. 공개 API 계약 추가는 plan 소유가 필요하므로, 사용자 결정에 따라 Plan 80을 다시 `ACTIVE`로
  열고 Milestone 10으로 `GET /stores/{storeId}`를 소유하게 했다. 매장 상세와 장바구니가 이름을
  서버에서 읽도록 바꾸고 frontend 103 tests, 대상 backend test, `spotlessCheck`, 문서/OpenAPI 검증과
  같은 demo stack의 브라우저 확인을 통과한 뒤 M8을 다시 완료로 표시했다.

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
- Stack A preflight 시 local root commit은 clean했지만 remote `feature/productization-plans` branch가
  없었다. 동일 remote branch·Plan 00 branch·PR 충돌이 없음을 확인한 뒤 root commit만 먼저 push해
  ADR-111의 immutable predecessor를 만들었다.
- Plan 00은 아직 `origin/main`에 merge되지 않은 Support 구현·완료 commit을 포함할 수 있다. 이 상태는
  Stack A의 제품 코드 충돌이 아니라 사용자가 별도로 관리하는 통합 순서이며
  `SUPPORT_INTEGRATION_PENDING`으로 관측한다.
- `analytics-refund-and-late-event-projection`은 metadata상 ready migration candidate지만 구현 branch와
  open PR이 없어 active writer는 아니다. Stack A lease가 해제될 때까지 실행 대상에서 제외한다.
- 최초 V44가 주문 표시 필드를 `NOT NULL`로 닫자 Ordering 밖의 결제·정산·분쟁 테스트 fixture도 영향을
  받았다. 첫 전체 build의 78 failures를 숨기지 않고 유효한 registry·snapshot fixture로 교정한 뒤 동일
  전체 build 782 tests가 통과했다.
- `origin/main` 통합 시 target OpenAPI 양쪽 branch가 서로 다른 path/schema key를 같은 삽입 위치에
  추가해 줄 단위 union은 invalid YAML을 만들었다. base/Plan10/main을 key 단위로 비교한 결과 변경 key
  교집합은 0개였고, 53개 main path와 107개 main component를 semantic block merge해 양쪽 계약을 보존했다.
- 결합 tree의 첫 full build는 Support S80 direct-order fixture 16건이 V51 표시 field `NOT NULL`을
  충족하지 않고 latest migration test 1건이 V49를 고정해 실패했다. 기존 공통 order display fixture와
  V51 inventory assertion으로 교정하고 집중/전체 회귀를 다시 통과했다.
- Plan 40의 첫 clean demo는 policy bootstrap 단계에서 V54 `payload_hash char(64)`와 JPA String의
  `varchar(64)` 기대가 달라 fail-closed했다. schema validation을 끄거나 migration을 고치지 않고
  `SqlTypes.CHAR`로 mapping contract를 일치시켰다. 성공 뒤에는 환경변수 nonce가 macOS process command에
  남지 않아 frontend stop이 보수적으로 신호를 거부하는 문제도 발견했고, Vite `--mode` 명령행 nonce와
  실제 launcher/stop 회귀 테스트로 소유권 증명을 복구했다.
- Plan 50 전체 build는 30초 뒤 자동 scheduler 읽기와 통합 fixture의 다중 table `TRUNCATE`가 반대 lock
  순서를 잡아 PostgreSQL deadlock을 일으켰다. test profile의 28개 initial delay와 Session cleanup을
  비활성 시점으로 옮기되 production 기본값과 명시적 worker `runOnce()` 검증은 유지했다. 재실행은
  57개 격리 context의 Modulith metadata/GC 때문에 11분 46초 걸렸지만 정상 종료했다.
- Plan 50 frontend 전체 typecheck/build는 Plan 80/90이 소유한 mutation 세 곳의 CSRF header가 아직 없어
  실패한다. read-only 고객 주문 화면 29 tests와 mobile browser layout/error-state는 통과했으며 실제
  account-backed data browser flow는 Plan 80 전이라 완료 증거로 주장하지 않는다.
- Plan 60 target `MerchantStoreList`만 `items` wrapper였지만 Plan 40 구현·계약 테스트·frontend는 모두
  top-level array였다. 이미 배포 기준이 된 Plan 40 동작을 보존하고 target/runtime를 배열로 교정했다.
- 주문보드 read index는 20,000-row local fixture에서 intended Index Only Scan을 만들었지만 1,000-row
  insert와 상태 전이 write sample은 각각 약 31% 느려졌다. read/write 결과를 함께 evidence에 남겼고
  production 성능 향상이나 SLA로 일반화하지 않는다.
- Plan 60 첫 full build는 이전 runtime OpenAPI inline-schema 기대와 Support timeline의 비결정적 timestamp
  fixture 때문에 2건 실패했다. 실제 `$ref` 계약과 DB 시간 check를 보존하는 방향으로 교정한 뒤 같은
  전체 build를 다시 통과시켰다.
- Plan 00 remote branch가 Stack A 완료 뒤 force-update되어 PR #57의 14개 문서·OpenAPI 경로가 충돌했다.
  Plan 10 쪽이 V50/V51 계약과 completion evidence의 strict superset이어서 이를 보존한 merge로 해결할 수
  있었지만, 자동으로 들어온 obsolete active Plan 10 파일은 별도로 제거해야 했다.
- PR #64의 새 CI `build`는 코드가 아니라 세션 운영 runbook의 EOF blank line 하나를 whitespace error로
  판정했다. 한 줄 제거 뒤 문서 검증을 다시 통과시키고 자식 Plan 30~60에 순차 전파했다.
- CodeQL은 Public과 Operations의 `csrf.disable()`을 모두 동일한 high 위험으로 표시했다. Public은
  기본 CSRF를 복원해 코드로 해소했지만, Operations는 Bearer header만 받고 Cookie·Session을 인증하지
  않는다는 Accepted 계약상 false positive다. 이 예외는 GitHub audit comment와 ADR-094 재검토 조건으로
  제한했으며, 다른 CSRF 예외로 일반화하지 않는다. 두 호출이 같은 configurer method에 있어 Public 제거 뒤
  GitHub가 남은 Operations result를 alert #2로 재연결했으므로, #3과 #2 모두 같은 audit comment로 닫혔다.
- CSRF regression을 실경로로 검증하려고 만든 top-level test controller가 global component scan에 들어가
  Runtime OpenAPI parity를 깨뜨렸다. nested test configuration으로 옮길 때 bean factory까지 남기면 duplicate
  handler가 되어 인증 context 자체가 실패했다. 최종적으로 nested controller를 configuration 안에서 한 번만
  등록해 test context에만 보이도록 제한했다.

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
| 2026-08-12 | Plan 00~60은 exact predecessor 기반의 정확히 일곱 Draft PR로 실행한다. Plan 00 base는 main, 이후 base는 직전 branch이며 combined release PR을 만들지 않는다 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md) |
| 2026-08-12 | Support 두 commit이 Plan 00의 ancestor이면 `origin/main` 통합 전 상태를 `SUPPORT_INTEGRATION_PENDING`으로 기록하고 Stack A를 중단하거나 restack하지 않는다 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md) |
| 2026-08-12 | Checkpoint 1 exact tree의 마지막 migration은 V42이며 Stack A가 Plan 10부터 Plan 60 최종 검증까지 단일 writer lease를 보유한다 | 이 ExecPlan `Progress` |
| 2026-08-12 | Plan 10은 V43 expand + bounded backfill + V44 contract로 배포 경계를 나누고 공개번호 route만 UUID를 숨긴다 | [Plan 10](../completed/productization-10-public-order-reference.md) |
| 2026-08-12 | Plan 10 뒤 Stack A를 동결하고 migration writer를 Support S70~S100에 양보한다. Plan 20은 readiness를 잃고 기존 V43/V44는 resume 시 재번호화한다 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md) |
| 2026-08-13 | Support V43~V49가 완료·release된 `origin/main`을 Plan 10에 merge하고 미적용 Plan 10을 V50/V51로 옮긴 뒤 전체 검증으로 Stack A를 재개한다 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md), [ADR-072](../../adr/ADR-072-execplan-unattended-execution-and-migration-lane.md) |
| 2026-08-13 | Plan 10 resume 전체 검증 통과와 같은 completion 변경에서 Plan 20 readiness를 true로 복원한다 | [Plan 10](../completed/productization-10-public-order-reference.md), [Plan 20](../completed/productization-20-authentication-foundation.md) |
| 2026-08-13 | Plan 20은 Spring Session 기본 `REQUIRES_NEW`를 `REQUIRED`로 바꿔 account lock transaction과 session rotation을 원자화하고 전체 검증 뒤 완료한다 | [Plan 20](../completed/productization-20-authentication-foundation.md), [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-13 | Plan 30 smoke는 승인 결제 조회까지, account-backed Merchant 전환·환불 기본 전체 smoke는 Plan 40 완료 gate로 분리 | [Plan 30](../completed/productization-30-customer-account-and-login.md), [Plan 40](../completed/productization-40-merchant-account-and-initial-password.md) |
| 2026-08-13 | Plan 40은 account 범위 `MERCHANT` Audit actor와 Operations-owned outbound port를 사용하고, clean 전체 smoke 통과 뒤에만 Plan 50을 실행 가능하게 함 | [Plan 40](../completed/productization-40-merchant-account-and-initial-password.md), [ADR-093](../../adr/ADR-093-merchant-credential-lifecycle.md) |
| 2026-08-14 | Plan 50 목록은 R1 read-only candidate → bounded atomic W1 expiry → R2 fixed-candidate projection이며 빈 ACTIVE page도 scan boundary cursor를 사용 | [Plan 50](../completed/productization-50-customer-order-read-model.md), [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-14 | Plan 60 전이는 client가 본 `expectedStatus`를 command에 포함하고 stale state 409와 불가능한 조합 422를 분리하며, 디자인의 3열 중 제조 중 열은 `ACCEPTED`와 `PREPARING`을 함께 표시 | [Plan 60](../completed/productization-60-store-order-board.md), [ADR-100](../../adr/ADR-100-store-order-board-read-model.md) |
| 2026-08-14 | 재작성된 Plan 00 base가 PR #57과 충돌하면 Plan 10의 V50/V51 계약을 보존한 history-preserving merge를 만들고, 수정된 부모 tip은 Plan 20~60에 순차 merge한다 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md), 이 ExecPlan `Progress` |
| 2026-08-14 | Public Chain은 기본 CSRF를 유지하고, Cookie·Session을 인증하지 않는 stateless Operations Bearer Resource Server의 CodeQL 예외만 감사 가능한 false positive dismiss로 제한한다 | [ADR-094](../../adr/ADR-094-browser-session-security.md), [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |

## Outcomes & Retrospective

- M1 Plan 10이 완료되어 주문 생성·조회 계약은 공개 주문번호, 매장·영업일 픽업번호와 불변 표시
  snapshot을 제공한다. Support에 넘겼던 migration-writer lease는 V49 release와 main 통합 뒤 Stack A가
  다시 획득했고, Plan 10 V50/V51 결합 tree의 964-test 전체 재검증을 통과해 Plan 20을 후보로 복원했다.
- M2 Plan 20이 완료되어 네 인증 Chain, PostgreSQL Session/CSRF, CurrentActor와 failure-visible Session
  lifecycle을 제공한다. 고객·점주 계정/로그인은 범위대로 Plan 30/40에 남아 있고 그 전 보호 경로의
  401 중간 단절을 유지한다.
- M3 Plan 30이 완료되어 CustomerAccount, 0원 PointAccount 원자 provisioning, 고객 Session 가입·로그인·
  logout과 승인 결제 조회까지의 demo checkpoint를 제공했다. 이 checkpoint에서 분리했던 점주
  계정/Session과 기본 전체 smoke는 M4 Plan 40에서 완료됐다.
- M4 Plan 40이 완료되어 운영자 account+membership 발급, account-backed Merchant Session, 최초
  비밀번호 변경 gate와 ACTIVE 매장 목록을 제공한다. 기본 전체 demo는 고객 승인 결제 조회에서 이어져
  Merchant 주문 완료·포인트 적립·부분/잔여 환불과 UNKNOWN 결제 회복까지 통과했다.
- M5 Plan 50이 완료되어 고객 주문 목록·상세가 Session owner, 공개 주문번호, immutable snapshot,
  signed keyset cursor와 서버 소유 `allowedActions`를 사용한다. V55 plan evidence와 1,078-test 전체
  regression이 통과했고 UUID 입력 고객 화면을 탭·기간 기반 목록으로 교체했다.
- M6 Plan 60이 완료되어 점주가 Session membership으로 매장을 선택하고 공개 주문번호 기반 3열 보드에서
  접수·제조·준비·픽업 완료를 처리한다. V56 query/write evidence, 동시 전이, ETag/304와 권한 상실의
  failure-visible UI가 전체 regression과 브라우저 검증을 통과했다.
- Stack A는 Plan 00~60 구현과 필수 검증을 완료했고 migration-writer lease를 해제했다. 이후 Plan 00
  remote base 재작성으로 생긴 PR #57 충돌과 PR #64 whitespace CI 실패를 history-preserving merge와 단일
  문서 포맷 수정으로 해소해, 일곱 open/non-Draft PR의 `main → 00 → 10 → 20 → 30 → 40 → 50` 조상 관계를
  복원했다. combined release PR, PR merge, rebase, force-push와 Plan 70+ 구현은 수행하지 않았다.
  프로그램 전체 M7~M10은 이 Goal 밖이며 시작하지 않는다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-12: Support deferred integration과 seven-PR Stack A topology를 반영.
- 2026-08-13: Plan 20 completion과 Plan 30 readiness를 actual validation evidence로 반영.
- 2026-08-13: Plan 30 completion, Plan 40 readiness와 customer/full smoke gate 분리를 반영.
- 2026-08-13: Plan 40 completion evidence와 Plan 50 readiness, 후속 dependency path를 반영.
- 2026-08-14: Plan 50 completion evidence와 Plan 60/70 readiness, 남은 Stack A 범위를 반영.
- 2026-08-14: Plan 60 implementation completion evidence, Plan 90 readiness와 남은 release topology gate를 반영.
- 2026-08-14: Plan 60 Draft PR, final seven-PR topology와 Stack A migration-writer lease 해제를 기록.
- 2026-08-14: Plan 00 base rewrite에 따른 PR #57 충돌 해소, PR #64 whitespace CI 수정과 Plan 20~60
  순차 기준선 보정을 actual GitHub 상태와 함께 기록.
- 2026-08-14: Public CSRF 기본 보호 복원, Operations bearer-only CodeQL false-positive dismissal과
  Plan 20~60 보안 기준선 전파의 실제 검증 결과를 기록.
