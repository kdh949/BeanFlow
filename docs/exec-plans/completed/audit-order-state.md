# 장바구니 편집과 주문 상태 일치

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

CUS-04/07: 편집 중인 장바구니와 오래된 견적의 제출을 막고 종료 주문의 미래 준비 안내를 제거한다.

## Current State

main ec61729에서 옵션 편집은 주문/수량/쿠폰/포인트와 동시에 가능하며 견적에 입력 식별이 없다. OrderPreparationEstimate는 terminal 상태를 구분하지 않는다.

## Definitions

입력 key는 서버 editableOrderInput의 JSON 값이며 cart revision은 기기 내 장바구니 변경 식별자다.

## Scope

### In Scope

CustomerCommercePages, CustomerTransactionPages, 관련 stories와 회귀 테스트.

### Non-goals

서버 주문/결제/멱등성 계약, 다른 탭 동기화, 새 상태 관리 라이브러리.

## Business Rules and Invariants

BR-49/ADR-116 비예약 견적 및 서버 fingerprint 재확인 유지. stale 견적은 명시 확인 후 재제출한다. UNKNOWN 결제 결과를 실패로 단정하지 않는다.

## Architecture and Transaction Boundaries

고객 장바구니 컴포넌트의 로컬 상태/ref만 변경한다. 서버 Aggregate/DB 트랜잭션은 변경 없음.

## Alternatives Considered

전역 state machine/새 dependency 대신 기존 상태와 입력 key 및 동기적 제출 잠금 사용. Button/QuantityStepper/PointUseField REUSE, 안내 COMPOSE.

## Failure Semantics

입력 변경 뒤 이전 견적/응답은 사용할 수 없다. CSRF 준비 중 revision이 바뀌면 POST를 보내지 않고 새 견적을 요청한다. 제출 실패의 기존 correlation과 멱등성 유지.

## Data and Migration

없음.

## API and Event Contracts

없음.

## Milestones

1. stories로 편집 잠금과 종료 상태 명세
2. 견적 입력 연결과 제출 경쟁 방지
3. 종료 상태 메시지 수정
4. 검증 및 독립 PR

## Required Tests

편집 잠금/닫기/적용 후 새 견적, 중복 클릭, CSRF 지연 중 입력 변경, stale 명시 확인, terminal 안내. 기존 결제 SDK 호출과 장바구니 보존.

## Validation Commands

frontend typecheck/test/check:design/build-storybook/build/test:sites, Storybook MCP focused tests, verify-docs, staged diff check.

## Observability

기존 오류 안내 사용. 추가 로그/개인정보 없음.

## Documentation Updates

국소 행동 명확화, 기존 정책 변경 없음. 이 ExecPlan에 근거/결과 기록.

## Progress

- [x] MCP 문서와 원인 확인
- [x] 장바구니 구현/검증
- [x] 종료 주문 안내와 최종 검증
- [x] 독립 PR 제출 준비

## Surprises & Discoveries

전체 Storybook MCP 집계는 이전 독립 PR들에서 runner 성공 뒤 heap OOM이 발생했다. 변경 범위 focused 결과와 CI 결과를 구분한다.

## Decision Log

2026-10-03: 기존 입력/컴포넌트 재사용, API/DDL 변경 없음. 독립 main PR.

## Outcomes & Retrospective

Passed: typecheck, npm test (38 files / 271 unit tests; presentation 10; product copy 11), check:design, build-storybook, build, Sites 4, docs/OpenAPI validation. Final focused MCP 4 stories passed; preceding focused run also covered point/removal and Toss checkout. Chrome 390px/320px confirmed cart edit controls disabled and responsive wrapping.

Added six cart race tests and eight order-state cases. Reviewer-found late stale response overwrite was fixed; known terminal stale responses cannot replace a newer quote. Successful order responses are still processed. Existing READY pickup-window display remains. Same-tab revision protection only; cross-tab storage synchronization is not claimed.

Full aggregate MCP run: Not run here after the reproducible heap failure on earlier PRs; changed stories were tested through MCP and remote full frontend CI remains Pending. Production/payment-provider verification: Not run. Existing bundle size warning remains. Visual baseline: Not configured.

## Revision Notes

2026-10-03: 실행 시작.
