# 앱에서 고객 문의를 접수하고 상담원의 공개 답변 확인하기

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객은 도움말에서 본인의 문의를 접수하고 답변/실제 처리 상태를 확인한다. 상담원은 접수함에서
인수하여 기존 SupportCase 업무와 공개 답변을 함께 처리한다.

## Current State

고객 도움말은 안내문만 제공한다. Case는 현재 담당자가 필수이며 내부 메모만 저장한다.
기존 고객 인증/Ordering 공개 주문 reference/Support 권한/Case/Audit/디자인 시스템을 재사용한다.

## Definitions

Inquiry는 담당자 없는 고객 접수 Aggregate, public message는 고객에게 공개하는 본문,
Case는 기존 상담원 업무 Aggregate다. 공개 답변은 결제·환불 실행 결과가 아니다.

## Scope

### In Scope

고객 문의 목록/접수/상세/추가 메시지, 직원 접수함/인수/공개 답변, 소유 주문 연결과 상태 투영.

### Non-goals

익명 문의, 외부 전달, 첨부파일, 자동 배정, 기존 거래/Case 상태 정책 변경.

## Business Rules and Invariants

BR-55/ADR-126을 따른다. 고객 소유권·한 문의당 한 Case·실제 담당자·공개/내부 분리,
민감 값 거부·terminal 쓰기 차단·멱등과 Audit 원자성을 보호한다.

## Architecture and Transaction Boundaries

Support Controller → application service → Inquiry repository/Case service/Ordering public port.
접수, 인수, 메시지 각각 local transaction. 연결된 쓰기는 Case → Inquiry lock 순서다.
인수 전 Inquiry lock 아래 새 Case만 만들며 기존 Case lock을 역순 획득하지 않는다.

## Alternatives Considered

외부 문의 링크, 임시 담당자, 내부 Note 공개 대신 별도 접수/공개 모델을 선택한다.

## Failure Semantics

소유권/권한 실패, 버전 충돌, terminal 차단, idempotency conflict를 명시한다.
Audit/DB 실패는 transaction rollback. 응답 유실은 동일 key 재확인하며 성공을 추정하지 않는다.

## Data and Migration

V82에 Inquiry, 공개 Message, metadata-only command replay를 추가한다. 적용 migration 수정 없음.
2026-09-11 migration-writer lease: 현재 task가 유일한 writer. 작업/열린 PR inventory에서 다른
DDL writer 없음. branch 생성 후 origin/main a6199c6의 마지막 V81 확인. 이 수동 요청의 직렬
PR stack은 feature/frontend-support-profile-access fed14d7을 parent로 사용한다. 자동 plan
branch 선택 예외를 만들지 않는다. 이 PR merge까지 lease 유지, 다른 DDL 작업은 시작하지 않는다.

## API and Event Contracts

`/me/support-inquiries` 목록/접수, `/{inquiryId}` 상세, `/{inquiryId}/messages` 추가.
`/support/inquiries` 접수함, `/{inquiryId}` 상세, `/{inquiryId}/claims` 인수,
`/{inquiryId}/messages` 공개 답변. 새 외부 event 없음. OpenAPI와 generated TS 동기화.

## Milestones

1. 정책·계약·영속 모델과 고객 접수 화면.
2. 상담 인수/공개 답변과 고객 추가 문의, 상태/권한/오류 story.
3. 전체 검증·별도 PR·CI 및 최종 coverage 기록.

## Required Tests

PostgreSQL ownership, public isolation, idempotency, concurrent claim, terminal/reassignment,
Audit rollback, order ownership; domain content; runtime contract and architecture.
고객·상담 story loading/empty/error/denied/long/mobile/unknown/stale/terminal.

## Validation Commands

`./gradlew spotlessCheck test` (관련 test filter 먼저), `scripts/verify-docs.sh`.
frontend typecheck/test/check:design/build/build-storybook/test:sites/test:storybook:docs,
실제 BeanFlow MCP preview/get-changed-stories/run-story-tests(a11y=true).

## Observability

PII-free Audit과 기존 HTTP 오류/correlation ID. 답변 본문은 로그/감사 summary 제외.

## Documentation Updates

BR-55, ADR-126, API surface, OpenAPI, frontend workflow plan과 이 계획 결과를 갱신한다.

## Progress

- [x] 정책과 migration inventory 확인.
- [x] 구현과 focused validation. Inquiry integration 11개, 실제 고객 cookie/CSRF 1개, Ordering 소유권 포함 10개, domain 2개, Support architecture 3개, Modulith 1개, runtime parity 1개 Passed.
- [x] 전체 frontend/contract/architecture 검증. 565개 실제 MCP interaction/a11y, 233개 unit 및 boundary/copy 21개, typecheck/design/build/static Storybook/105개 Docs/47개 상태/sites 4개, 문서 18개 Passed.
- [ ] vertical commits, PR, terminal CI.

## Surprises & Discoveries

ktlint 자동 정렬이 긴 중첩 테스트 호출을 한 번에 안정화하지 못했다. 동일 1.8.0 CLI의 포맷을 반영하고 repository spotlessApply/spotlessCheck를 다시 통과시켰다. formatter 설정과 production dependency는 변경하지 않았다.

기존 Case의 담당자 NOT NULL 때문에 문의 접수와 상담원 인수를 별도로 모델링한다.

## Decision Log

2026-09-11: 내장 고객지원 요구를 BR-55/ADR-126에 기록했다. 기존 디자인 컴포넌트를 COMPOSE한다.

## Outcomes & Retrospective

고객 접수와 상담원 인수/공개 답변을 구현했다. 390px 긴 문의 화면에서 가로 넘침 없고 본문 15px 토큰을 확인했다. 최종 spotless와 문의/기존 상담/Runtime parity 회귀 검증까지 통과했다. PR/원격 CI를 확인 중이다.

## Revision Notes

2026-09-11 초기 작성.
