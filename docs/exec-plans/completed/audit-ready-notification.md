# 준비 완료 알림의 주문 연결

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

CUS-08: 준비 완료 알림에서 매장/메뉴/공개 주문 번호를 확인하고 주문 상세로 이동한다.

## Current State

OrderReadyV1에는 표시 snapshot이 없고 inbox는 generic copy/NONE target이다. UNKNOWN replay allowlist는 V1만 지원한다.

## Definitions

V2는 새 READY 이벤트 버전. snapshot은 주문 생성 당시 표시 정보이며 현재 catalog가 아니다.

## Scope

### In Scope

Ordering 발행, eventing V2, Notification 수신/본문/target, 수동 replay allowlist, 알림 UI.

### Non-goals

과거 알림 보정/재발송, 새 DDL/API, live Ordering 조회, Provider 계약 변경.

## Business Rules and Invariants

ADR-104 snapshot, V1 호환; event/recipient/channel unique, inbox/delivery 원자성, 90일 보존, 고객 소유권.

## Architecture and Transaction Boundaries

READY 주문 transaction에서 V2 발행. Notification 짧은 로컬 transaction으로 inbox/delivery 생성. 외부 발송은 commit 뒤 기존 worker. Aggregate 관계 변경 없음.

## Alternatives Considered

V1 payload 수정 또는 Notification live lookup은 금지. 새 V2와 typed command copy/target만 확장. UI Button/Link와 기존 행/토큰 재사용.

## Failure Semantics

snapshot 오류는 실패. 기존 V1 copy 변경 금지. UNKNOWN은 검증된 exact V2 listener만 재실행하며 ACK fencing/동시 unique 유지.

## Data and Migration

없음. 기존 body/target 컬럼 사용.

## API and Event Contracts

OrderReadyV2 공개번호/매장명/대표메뉴명/추가항목수, payloadVersion=2. V1 listener signature 유지.

## Milestones

1. 정책/계약
2. V2 producer→consumer 및 replay 테스트
3. UI story→동선/읽지않음 표현
4. 독립 PR

## Required Tests

V2만 발행, snapshot/target 저장·V1 replay, 중복/동시/충돌, UNKNOWN commit-ACK/동시 owner, rollback, 고객 응답 비노출, UI legacy/marketing 분기.

## Validation Commands

Gradle focused integration/domain/architecture/contract + spotless, frontend required checks/MCP, docs.

## Observability

기존 delivery/publication/inbox metric 유지. provider 성공으로 위장하지 않는다.

## Documentation Updates

ADR104/125, failure semantics, event catalog, 이 계획.

## Progress

- [x] 분석
- [x] 구현/검증
- [ ] 독립 PR 제출 (구현 완료, 병합/배포 제외)

## Surprises & Discoveries

V1 copy를 변경하면 replay snapshot 비교가 실패한다. 새 V2는 복구 allowlist도 함께 지원해야 한다.

## Decision Log

2026-10-03: independent main PR, 새 이벤트 버전만 발행하고 V1 보존.

## Outcomes & Retrospective

Passed: Gradle focused PostgreSQL integration/Modulith 46 tests (inbox 7, notification recovery 5, publication recovery 16, store lifecycle 17, Modularity 1), spotless; frontend typecheck, unit 257 tests/37 files plus presentation/copy checks, design check, Storybook build, product build, Sites 4 tests; focused Storybook MCP 3 stories (interaction/a11y); docs verification.

Chrome visual check: Blocked. 기존 Chrome 연결이 사라져 390/320px 실제 화면 확인을 완료하지 못했다. 연결 복구 후 `pages-customer-notifications--ready-and-legacy`를 재확인한다. 시각 회귀 baseline: Not configured. 전체 Storybook MCP aggregate: Not run (동일 세션의 다른 PR에서 집계 응답 hang/OOM 재현; focused MCP와 CI 전체 suite를 구분한다). 원격 CI/배포: 이 문서 완료 시점에는 Not run.

새 READY는 V2 snapshot과 ORDER target을 생성한다. V1 listener/copy는 유지하고 과거 NONE 알림만 주문 내역 안내를 제공한다. 실제 provider 발송 성공이나 배포를 주장하지 않는다.

## Revision Notes

2026-10-03: 실행 시작.
