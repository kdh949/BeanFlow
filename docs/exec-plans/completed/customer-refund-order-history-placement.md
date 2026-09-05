# 고객 환불을 주문 내역에 배치하고 쿠폰 받기를 분리한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/selected-screen-plain-language-copy.md`
> **Completed-At:** `2026-09-05`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객이 환불을 별도 사후 처리 메뉴에서 찾지 않고 원 주문의 목록과 상세에서 확인하도록 정보 구조를
바꾼다. 쿠폰 받기는 주문 환불과 다른 목적이므로 독립 화면과 독립 Storybook story로 유지한다.

## Current State

- 고객 마이페이지는 `주문 내역`, `환불 내역`, `쿠폰 받기`를 각각 별도 링크로 보여 준다.
- `/app/refunds`와 `/app/coupon-claims`는 같은 `CustomerAftercarePage`의 서로 다른 tab을 연다.
- 주문 상세는 이미 `CustomerOrderDetail.paymentRecovery`를 사용해 고객 취소 환불 상태를 안전하게
  표시하지만, 주문 목록은 `VIEW_REFUND`를 고객에게 설명하지 않는다.
- Accepted ADR-031은 고객이 주문 화면 한 곳에서 취소와 환불 진행을 함께 보도록 결정했다.

## Definitions

- **환불 표시 대상 주문:** 서버가 `allowedActions`에 `VIEW_REFUND`를 반환한 주문이다.
- **환불 진행 요약:** `CustomerOrderDetail.paymentRecovery`가 제공하는 고객용 축약 상태와 확인 가능한
  금액이다.
- **기존 환불 경로:** 이미 저장됐거나 외부에서 들어올 수 있는 `/app/refunds` URL이다.

## Scope

### In Scope

- 마이페이지에서 독립 `환불 내역` 링크 제거
- 주문 목록에서 `VIEW_REFUND` 주문의 환불 확인 가능 여부 표시
- 주문 상세의 환불 진행 요약을 명시적인 `환불 내역` 영역으로 표현
- `/app/refunds`를 `/app/orders?status=PAST`로 이동
- 쿠폰 받기를 독립 컴포넌트, route와 Storybook story로 분리
- 관련 단위·Storybook 상호작용·접근성·타입·빌드 검증

### Non-goals

- 고객이 직접 부분 환불을 요청하는 기능
- 새 환불 목록 endpoint, DTO 필드 또는 서버 상태 추가
- 확인할 수 없는 품목별 환불 금액이나 성공 결과 추정
- 디자인 시스템 공개 API, CSS, Business Policy 또는 ADR 변경

## Business Rules and Invariants

- 환불 완료는 서버의 `paymentRecovery.state = SUCCEEDED`인 경우에만 표시한다.
- 서버가 금액을 주지 않으면 0원으로 추정하지 않는다.
- `PROCESSING`과 `REFUND_DELAYED`는 성공이나 실패로 단정하지 않는다.
- 목록에서 환불 대상 여부는 주문 상태를 재구성하지 않고 `VIEW_REFUND`만 사용한다.
- 부분 환불은 고객이 실행하지 않으며 매장 또는 운영자만 실행한다.

## Architecture and Transaction Boundaries

고객 presentation, router와 Storybook만 변경한다. 목록은 기존 `GET /me/orders`, 상세는 기존
`GET /me/orders/{orderReference}` 응답을 소비한다. 새 쓰기 transaction, Provider 호출, 멱등성 key와
reconciliation 흐름은 없다.

## Alternatives Considered

- 환불과 쿠폰 tab 유지: 서로 다른 사용자 목적을 한 화면에 묶고 ADR-031의 주문 중심 조회를 어겨 기각한다.
- 독립 환불 목록 유지: 별도 owner contract가 없고 가짜 fixture를 제품 구조로 오인시켜 기각한다.
- 주문 목록과 상세에 통합: 기존 서버 소유 `VIEW_REFUND`와 `paymentRecovery`만 소비하므로 채택한다.

## Failure Semantics

- 주문 목록 또는 상세 조회 실패는 기존 명시적 ErrorState로 유지한다.
- 환불 금액 부재는 0원이나 환불 없음으로 대체하지 않는다.
- 지연 상태는 담당자 확인 중이며 고객의 중복 요청이 필요 없다는 기존 안내를 유지한다.
- 쿠폰 받기 계약 부재는 독립 화면에서 준비 중 상태로 표시한다.

## Data and Migration

DB와 migration 변경 없음.

## API and Event Contracts

OpenAPI, Props의 서버 경계, route parameter, DTO와 event 계약은 변경하지 않는다. 기존 `/app/refunds`
route만 주문 내역으로 redirect하고 `/app/coupon-claims`는 쿠폰 전용 presentation을 사용한다.

## Milestones

1. 주문 목록·상세·쿠폰 Storybook assertion을 목표 정보 구조로 먼저 변경해 RED를 확인한다.
2. 쿠폰 전용 화면 분리, route와 마이페이지를 변경한다.
3. 주문 목록의 환불 표시와 주문 상세의 환불 내역 영역을 구현한다.
4. focused 및 전체 Storybook 검사와 frontend 검증을 실행한다.
5. 결과와 발견을 기록하고 계획을 completed로 이동한다.

## Required Tests

- 지난 주문 중 `VIEW_REFUND`가 있는 주문은 환불 확인 가능 문구와 상세 링크를 보여 준다.
- 일반 주문은 환불 문구를 표시하지 않는다.
- 주문 상세는 진행, 지연, 완료, 추가 현금 환불 없음과 조건부 금액을 정확히 표현한다.
- 쿠폰 받기 화면과 story에는 환불 tab·제목·원장이 없다.
- 마이페이지에는 독립 환불 링크가 없고 주문 내역과 쿠폰 받기 링크가 남는다.
- `/app/refunds`는 지난 주문으로 이동한다.

## Validation Commands

```bash
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run check:design
cd frontend && npm run check:product-copy
cd frontend && npm run build-storybook
cd frontend && npm run build
cd frontend && npm run test:storybook:docs
```

Storybook MCP에서 변경 때마다 관련 story를 `run-story-tests(a11y=true)`로 확인하고, 최종적으로 전체
story와 주문 목록·주문 상세·쿠폰 받기 대표 story를 미리보기로 확인한다.

## Observability

새 telemetry 없음. 고객이 보는 환불 상태는 기존 서버 projection과 주문 상세 polling을 사용한다.

## Documentation Updates

- 이 ExecPlan의 Progress, Surprises & Discoveries, Decision Log와 Outcomes를 실제 결과로 갱신한다.
- 이미 이 정보 구조를 확정한 ADR-031과 Business Policy는 변경하지 않는다.

## Progress

- [x] 2026-09-05: 정책, ADR-031, ADR-099, 고객 주문 계약과 Storybook 문서를 확인했다.
- [x] 2026-09-05: 목표 Storybook assertion을 구현보다 먼저 변경했다. 최초 RED 실행은 Storybook MCP
  tool timeout으로 결과를 받지 못했고, 서버 복구 뒤 구현 결과를 focused 검사로 확인했다.
- [x] 2026-09-05: 고객 route, 마이페이지, 주문 목록·상세와 쿠폰 전용 화면을 구현했다.
- [x] 2026-09-05: 대표 미리보기, focused 및 전체 Storybook 검사와 frontend 검증을 완료했다.

## Surprises & Discoveries

- 기존 주문 상세는 이미 `paymentRecovery`로 환불 진행을 안전하게 보여 주고 있었지만, 별도 사후 처리
  화면이 같은 관심사를 중복하고 있었다.
- 주문 목록 DTO에는 상세 환불 금액이 없고 `VIEW_REFUND`만 있으므로 목록은 확인 가능 여부만 표시하고
  금액·상태는 상세에서 보여 줘야 한다.
- 오래 실행된 Storybook 개발 서버에서 MCP test/change detection 연결이 멈췄다. 포트 6006 서버를
  재시작한 뒤 같은 focused 검사와 전체 검사가 정상 통과했다.
- router JSX에 `Navigate`를 직접 두면 route story coverage guard가 새 화면 컴포넌트로 분류했다.
  화면 없는 호환 경로이므로 route loader의 `redirect`로 옮겨 guard와 의도를 함께 만족시켰다.

## Decision Log

| 일자 | 결정 | 근거 |
| --- | --- | --- |
| 2026-09-05 | 환불은 주문 목록·상세에 통합하고 쿠폰 받기는 독립시킨다 | 사용자 정보 구조 요구, ADR-031 |
| 2026-09-05 | 목록은 `VIEW_REFUND`, 상세는 `paymentRecovery`만 사용한다 | ADR-099의 서버 소유 action과 unknown 금액 비추정 |
| 2026-09-05 | 기존 `/app/refunds`는 지난 주문으로 redirect한다 | 저장된 링크를 404로 만들지 않고 올바른 화면으로 유도 |
| 2026-09-05 | redirect는 JSX 화면이 아닌 route loader로 구현한다 | 화면 story 대상과 호환 URL 처리를 분리 |

## Outcomes & Retrospective

- 마이페이지의 독립 `환불 내역` 링크를 제거하고 `주문 내역`과 독립 `쿠폰 받기` 링크를 남겼다.
- 주문 목록 제목을 `주문 내역`으로 명확히 하고, 서버가 `VIEW_REFUND`를 준 지난 주문에만
  `환불 내역 확인`을 표시해 해당 주문 상세로 연결했다.
- 주문 상세의 `paymentRecovery`를 명시적인 `환불 내역` 영역으로 구성하고 진행 지연과 완료 story를
  각각 유지했다. 완료와 금액은 서버가 제공한 경우에만 표시한다.
- 기존 합친 `CustomerAftercarePage`와 환불 fixture를 제거하고 쿠폰 받기를
  `CustomerCouponClaimsPage`와 3개 독립 story로 분리했다.
- `/app/refunds`는 `/app/orders?status=PAST`로 redirect하고 `/app/coupon-claims`는 쿠폰 전용 화면을 연다.
- Storybook MCP focused 검사와 전체 232개 interaction/a11y story가 통과했다.
- frontend unit 178개, presentation boundary 10개, product copy 11개, typecheck, design boundary,
  Storybook/app build, Sites 4개와 Storybook Docs smoke 61개가 통과했다.
- 새 디자인 시스템 component, CSS, backend contract, migration, Business Policy와 ADR 변경은 없다.

## Revision Notes

- 2026-09-05: 최초 작성.
- 2026-09-05: 주문 중심 환불 정보 구조 구현과 전체 검증 완료 후 completed로 이동.
