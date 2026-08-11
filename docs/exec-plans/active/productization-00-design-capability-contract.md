# 디자인 48화면과 신규 필수 운영 화면의 Backend 계약을 정합화한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

원본 48개 화면을 그대로 구현하면 대부분이 UUID 입력창이 되거나, 실제로 일어나지 않는 동작을
표시하고 점주 계정 발급 화면은 아예 빠진다.
이 plan은 코드를 쓰기 전에 **화면별 Backend capability, 우선순위, 충돌 해소를 확정**한다.

결과물은 이후 productization 10~100의 10개 plan이 무엇을 만들고 무엇을 만들지 않을지의 기준이 된다.

## Current State

- [Design to Capability Map](../../product/design-to-capability-map.md) 초안이 있다. 48화면의 소유
  Context, 필요 API, 우선순위, 상태 계약이 기록됐다.
- [Design Contract Conflicts](../../product/design-contract-conflicts.md) 초안에 17개 충돌(C-1~C-17)과
  판정이 기록됐다.
- productization ADR-092~105와 ADR-107~110 총 18건이 작성됐다. ADR-106은 별도 Support S40 결정이다.
- P0 구현을 막던 식별자·인증·기간·권한·부분 환불·최근 매장·PointAccount·실패 큐 결정은
  2026-08-12에 모두 확정됐다. 점주 PIN step-up과 점주 정책 변경 범위 2건만 해당 P1 시작 전
  결정으로 명시 이월했다.
- `actors-and-goals.md`, `non-goals.md`, `authorization-matrix.md`는 Session actor, 공개 식별자와 P0
  read grant를 반영했다.
- `openapi/beanflow-v1.yaml`(target)에는 인증·목록 operation이 없다.
- Business Policy는 이 검토에서 인증·Session·알림·환불·조회·점주 credential 정책을 추가해 `BR-01`~`BR-46`이며
  `scripts/verify-docs.sh`가 이 범위를 정확히 검증한다.

## Definitions

- **Capability:** 화면이 요구하는 Backend 동작 단위다. endpoint와 1:1이 아니다.
- **Conflict:** 화면이 표현하는 동작이 Accepted ADR·Business Policy·실패 의미론과 어긋나는 경우다.
- **P0:** 고객이 로그인해 주문하고, 점주가 처리하고, 운영자가 실패를 복구하는 최소 제품 범위다.
- **Contract status:** target OpenAPI의 `x-beanflow-contract-status` 값이다.

## Scope

### In Scope

- Capability Map과 Conflicts 문서의 확정(초안 → Accepted)
- ADR-092~ADR-105와 ADR-107~ADR-110의 `Proposed` → `Accepted` 전환
- `actors-and-goals.md`, `non-goals.md`, `authorization-matrix.md` 갱신
- P0 신규 operation의 target OpenAPI 반영(request/response/error/security schema 포함)
- P0 24화면 각각이 정확히 하나 이상의 구현 ExecPlan에 연결되는 coverage matrix 작성. owner가 없는
  P0 capability가 있으면 이 plan을 완료하지 않음
- 인증·세션·잠금 관련 Business Policy 항목(`BR-34` 이후) 추가와 `scripts/verify-docs.sh`의
  BR 범위 갱신
- 디자인 화면 수정 지시 목록 작성(어떤 화면을 어떻게 바꿀지)

### Non-goals

- Controller, Entity, migration 구현
- runtime OpenAPI 갱신(구현 plan이 수행한다)
- P1 화면의 상세 계약 확정
- 프론트엔드 코드 변경

## Business Rules and Invariants

- 화면과 Accepted 결정이 충돌하면 화면을 바꾼다. 화면을 유지하려면 결정을 먼저 바꾼다.
- target OpenAPI에 operation이 있다는 사실이 구현 완료를 뜻하지 않는다.
- 측정하지 않은 수치를 화면·문서에 쓰지 않는다.
- 실제로 하지 않는 일을 UI가 표시하지 않는다(저장 결제수단 결제, 실제 지급, 자동 PG 전환).

## Architecture and Transaction Boundaries

이 plan은 런타임 동작을 만들지 않는다. 트랜잭션 경계 변경이 없다.

target OpenAPI에 추가할 P0 operation 그룹은 다음과 같다.

```text
인증        POST /auth/customer/registrations
            POST /auth/customer/sessions
            GET  /auth/customer/csrf
            POST /auth/merchant/sessions
            POST /auth/merchant/password-changes
            GET  /auth/merchant/csrf
            GET  /auth/operations/config
            DELETE /auth/customer/sessions/current
            DELETE /auth/merchant/sessions/current
            GET  /me
            GET  /merchant/me
            GET  /operations/me

고객 조회   GET /me/orders
            GET /me/orders/{orderReference}
            POST /me/orders/{orderReference}/cancellations
            POST /me/orders/{orderReference}/reorders
            GET /me/points
            GET /me/point-transactions

매장 탐색   GET /stores/search
            GET /stores/nearby
            GET/PUT/DELETE /me/favorite-stores[/{storeId}]
            GET /me/recent-stores
            GET /me/store-recommendations

점주        GET  /merchant/me/stores
            GET  /stores/{storeId}/orders
            GET  /stores/{storeId}/orders/{orderReference}
            POST /stores/{storeId}/orders/{orderReference}/transitions
            POST /stores/{storeId}/orders/{orderReference}/refund-previews
            POST /stores/{storeId}/orders/{orderReference}/refunds
            GET  /stores/{storeId}/disputes

운영 조회   GET /operations/failure-queues/summary
            GET /operations/failure-queues/{queueType}
            GET /operations/failure-queues/{queueType}/{workReference}
            GET /operations/failure-search
            GET /operations/settlement-batches[/{batchId}]
            GET /operations/settlement-batches/{batchId}/items
            GET /operations/settlement-batches/{batchId}/reconciliation
            GET /operations/audit-records[/{auditRecordId}]

점주 발급   GET  /operations/merchant-accounts?loginId=
            POST /operations/merchant-accounts
            POST /operations/merchant-accounts/{merchantAccountId}/temporary-password-resets
            POST /operations/merchant-accounts/{merchantAccountId}/lock-releases
```

## Alternatives Considered

### 1. 문서 없이 바로 구현

- 장점: 즉시 시작할 수 있다.
- 단점: 충돌이 구현 중에 드러난다. 이미 만든 화면과 API를 되돌리는 비용이 문서 작성 비용보다 크다.

### 2. 48화면 전부의 상세 계약을 지금 확정

- 장점: 이후 결정이 없다.
- 단점: P1 화면의 요구는 P0 사용 결과에 따라 바뀐다. 지금 확정하면 대부분 다시 쓴다.

### 채택

P0 24화면은 상세 계약까지, P1은 우선순위와 소유 Context까지만 확정한다.

## Failure Semantics

- 충돌 판정이 결정되지 않은 화면은 구현 대상으로 넘기지 않는다. `미결 항목`으로 남긴다.
- ADR을 `Accepted`로 바꾸기 전에 구현 plan을 `Implementation-Ready=true`로 만들지 않는다.
- Business Policy 번호를 추가하면 `scripts/verify-docs.sh`의 기대 범위를 같은 변경에서 갱신한다.
  갱신하지 않으면 문서 검증이 실패하며, 이 실패를 무시하지 않는다.

## Data and Migration

없다. `Writes-Migration: false`다.

## API and Event Contracts

- target OpenAPI에 위 P0 operation을 추가하고 `x-beanflow-contract-status`를 `TARGET`으로 표기한다.
- runtime OpenAPI는 변경하지 않는다.
- 이벤트 계약은 변경하지 않는다.
- 오류 코드는 [Error Catalog](../../api/error-catalog.md)에 신규 항목을 추가한다. 인증 실패,
  계정 잠금, 최초 비밀번호 미변경, 주문번호 미존재, stale 환불 preview, 미확정 환불, 운영 조회
  기간·cursor 오류, 임시 비밀번호 비재생 replay를 각각 구분한다.

## Milestones

1. Capability Map과 Conflicts 문서 확정. 남은 미결 2건의 결정 또는 P1 이월 확정.
2. ADR-092~ADR-105와 ADR-107~ADR-110을 `Accepted`로 전환하고 `docs/adr/README.md` 갱신.
3. `actors-and-goals.md`에 계정 주체와 인증 방식 반영.
4. `non-goals.md`에 전화번호 OTP(P1), 실제 지급·KYC, PG 자동 전환, 환불 계좌 추가.
5. `authorization-matrix.md`에 Session actor와 신규 endpoint 행 추가.
6. Business Policy `BR-34`~`BR-46`와 `scripts/verify-docs.sh` 범위 갱신.
7. target OpenAPI에 P0 operation 추가.
8. 디자인 화면 수정 지시 목록 작성.
9. P0 24화면의 구현 owner ExecPlan coverage를 검증하고 누락 plan을 생성한다.

## Required Tests

- `bash scripts/verify-docs.sh`가 통과한다(ADR 인덱스 정합성, 마크다운 링크, BR 범위,
  ExecPlan metadata, OpenAPI 유효성).
- target OpenAPI가 `openapi_spec_validator`를 통과한다.
- 문서 내부 링크가 모두 해석된다.
- 기존 `RuntimeOpenApiParityTest`가 회귀 없이 통과한다(runtime spec을 바꾸지 않았음을 확인).

## Validation Commands

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/ci/requirements-docs.txt
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
./gradlew test --tests '*RuntimeOpenApiParityTest'
git diff --check
git diff --cached --check
```

## Observability

이 plan은 런타임 지표를 만들지 않는다. 대신 다음을 문서로 남긴다.

- P0/P1/Non-goal 화면 수와 근거
- 충돌 18건의 판정과 영향 화면
- 미결 항목과 결정 기한

## Documentation Updates

- `docs/product/design-to-capability-map.md`
- `docs/product/design-contract-conflicts.md`
- `docs/product/actors-and-goals.md`
- `docs/product/non-goals.md`
- `docs/product/business-policy-decisions.md`
- `docs/security/authorization-matrix.md`
- `docs/api/error-catalog.md`
- `docs/adr/README.md`와 ADR-092~ADR-105, ADR-107~ADR-110
- `docs/decisions/minor-decisions.md`
- `openapi/beanflow-v1.yaml`
- `scripts/verify-docs.sh`(BR 범위)

## Progress

- 2026-08-11: Capability Map, Conflicts 문서와 ADR 14건 초안 작성.
- 2026-08-12: 미해결 4건 결정. ADR-107 추가, ADR-099·ADR-104 갱신, MD-2026-012 기록,
  충돌 C-14~C-17 추가.
- 2026-08-12: P0 화면 owner를 Plan 70~100까지 확장하고 부분 환불, pickup 영업일, 운영 read grant,
  최근 매장, Operations PKCE, PointAccount provisioning, typed failure queue와 운영 조회 기간을 확정.
- 2026-08-12: 운영 웹의 점주 계정+최초 membership 원자 발급과 임시 비밀번호 1회 표시를 추가해
  충돌 C-18을 해소하고 P0를 24화면으로 확장.

## Surprises & Discoveries

- `scripts/verify-docs.sh`가 Business Policy ID를 연속 범위로 **정확히** 검증한다. 정책을
  추가하려면 검증 스크립트를 같은 변경에서 고쳐야 한다.
- 디자인 `5d 빈 상태 세트`는 라우트가 아니라 문구·톤 비교 시트다. 화면 수 48 중 1은 구현 대상이 아니다.

## Decision Log

| 일자 | 결정 | 근거 |
|---|---|---|
| 2026-08-12 | 원본 48화면에 점주 발급 운영 화면 1개 추가, P0 24 / P1 20 / Non-goal 4 / 화면 아님 1 | Capability Map D절 |
| 2026-08-12 | 충돌 18건 중 화면 수정 5, 계약 확장 7, 범위 표기 6 | Conflicts 문서 |
| 2026-08-12 | 주문 내역은 기본 30일 + 기간 필터, 과거 상한 없음 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-12 | 쿠폰 카운터는 발급 기준 고정. 총 한도는 예산 상한이다 | [ADR-107](../../adr/ADR-107-limited-coupon-issuance.md) |
| 2026-08-12 | 점주 매출 지표는 Analytics가 단독 소유 | [MD-2026-012](../../decisions/minor-decisions.md) |
| 2026-08-12 | 알림 분류는 `orderId` 유무로 판정, 매장 알림은 전부 거래성 | [ADR-104](../../adr/ADR-104-notification-inbox.md) |
| 2026-08-12 | P0 완료 범위는 24화면 전체이며 productization-60은 중간 통합 지점이다 | [Capability Map](../../product/design-to-capability-map.md) |
| 2026-08-12 | 현재 actor 조회와 logout은 Customer·Merchant·Operations Chain별 경로로 분리한다 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-12 | 부분 환불은 같은 매장 OWNER·STAFF 모두 preview/실행하고 내부 UUID·금액을 받지 않는다 | [BR-38](../../product/business-policy-decisions.md), [ADR-108](../../adr/ADR-108-merchant-partial-refund-preview.md) |
| 2026-08-12 | pickup 영업일은 snapshot한 slot 시작 시각의 Asia/Seoul 날짜다 | [ADR-097](../../adr/ADR-097-store-pickup-number.md) |
| 2026-08-12 | Operations SPA는 PKCE S256, 실패·정산·감사 조회는 서로 다른 read grant를 사용한다 | [BR-39](../../product/business-policy-decisions.md), [BR-41](../../product/business-policy-decisions.md) |
| 2026-08-12 | PointAccount는 가입 transaction에서 만들고 failure queue는 source-owned typed Projection을 사용한다 | [ADR-109](../../adr/ADR-109-customer-point-account-provisioning.md), [ADR-110](../../adr/ADR-110-federated-operations-failure-queues.md) |
| 2026-08-12 | 감사·정산 운영 목록은 기본 30일, 요청 최대 90일이다 | [BR-44](../../product/business-policy-decisions.md), [BR-45](../../product/business-policy-decisions.md) |
| 2026-08-12 | 운영 콘솔이 점주 계정+최초 membership을 원자 발급하고 임시 비밀번호는 최초 응답에서 1회만 표시 | [BR-46](../../product/business-policy-decisions.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-11: 최초 작성.
