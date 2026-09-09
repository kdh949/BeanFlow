# 콘솔 리뷰의 실패·조회·타임라인 계약 보완

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

환불 실패의 후속 동작과 주문 품목·상담 이력 표시를 실제 서버 계약에 맞춘다.

## Current State

#144는 대부분의 실행 오류를 응답 유실처럼 표시한다. #145의 품목 누락은 공통 요약 검사에서
이미 거절하지만 상세 경계의 직접 검증과 DB 회귀 테스트가 없다. #146은 서버가 생성하는
`type:state` summary를 그대로 출력하고 Story는 사람이 읽는 summary를 사용한다.

## Definitions

응답 유실은 환불 POST의 transport failure다. 서버가 반환한 오류 및 요청 전 CSRF 준비 실패와 구별한다.
타임라인 문구는 서버의 typed type/state를 제품 표시 계층에서 변환한 값이다.

## Scope

### In Scope

#144 오류 분기·회귀 Story, #145 상세 품목 검사·PostgreSQL 통합 테스트,
#146 타임라인 변환·실제 응답 fixture·테스트. 부모 수정은 후속 branch로 merge한다.

### Non-goals

새 금융 정책, DB migration, 외부 호출/트랜잭션 변경, API 확장, PR 병합과 배포.

## Business Rules and Invariants

BR-25/38, ADR-100/108 및 error catalog를 유지한다. 서버가 금액·권한·환불 상태를 결정한다.
응답 유실과 동일 명령 처리 중에는 body/key를 보존한다. 수량 오류는 새 preview와 재선택을 요구한다.
빈 품목은 503이며 타임라인의 UNKNOWN을 성공·실패로 추정하지 않는다.

## Architecture and Transaction Boundaries

frontend presentation와 Ordering 상세 projection만 변경한다. Aggregate, DB transaction,
Provider 호출과 멱등성 저장 경계는 유지한다. 새 production dependency 없음.

## Alternatives Considered

모든 오류를 UNKNOWN으로 표현하면 서버 결정을 왜곡한다. raw summary/message 노출은 내부 코드를
사용자에게 전가한다. 기존 오류 catalog와 type/state를 UI에서 명시적으로 변환한다.

## Failure Semantics

400/403/404 등 거절은 같은 key replay로 유도하지 않는다. 422 수량 부족은 선택을 비우고 새
preview를 조회하며 자동 축소 환불하지 않는다. 503은 서비스 오류로 표시하고 새 preview 확인을
요구한다. `IDEMPOTENCY_REQUEST_IN_PROGRESS`는 응답 유실과 구분해 같은 명령의 처리 상태를 확인한다.

## Data and Migration

없음. 통합 테스트 안에서만 주문 line을 제거한다.

## API and Event Contracts

공개 계약 변경 없음. 상세 `lines`의 minItems: 1과 기존 SupportTimeline type/state/summary 유지.

## Milestones

1. #144 수정·검증·push.
2. #144 수정 merge 후 #145 수정·검증·push.
3. #145 수정 merge 후 #146 수정·전체 검증·push와 원격 CI 확인.

## Required Tests

환불 400/403/404/422/503, 응답 유실의 동일 body/key, 처리 중 응답, preview 재검증.
실제 DB 품목 누락의 503. 서버 형식 타임라인의 도메인별 번역·불명/미지원 상태 표시.

## Validation Commands

frontend typecheck, npm test, check:design, build-storybook, test:storybook:docs, build, test:sites.
Storybook MCP 문서·changed stories·preview·run-story-tests(a11y=true).
StoreOrderBoardIntegrationTest, RuntimeOpenApiParityTest, spotlessCheck, assemble, verify-docs.

## Observability

기존 correlation ID 보존. 새 telemetry 없음. 로컬 fixture와 실제 운영 검증을 구별한다.

## Documentation Updates

이 계획에 PR별 검증을 기록하고 마지막 PR에서 completed로 이동한다. 기존 ADR을 적용하므로 새 ADR 없음.

## Progress

- [x] 세 PR의 authoritative reviewThreads와 현재 branch 확인.
- [x] 격리 worktree 및 Storybook MCP prerequisite 확인.
- [x] #144 수정·로컬 검증·push. 원격 CI에서 수량 변경 Story의 완료 대기 보완 필요.
- [ ] #145 수정·검증·push.
- [ ] #146 수정·전체 검증·push.

## Surprises & Discoveries

#145의 기존 itemSummary가 이미 빈 품목을 거절한다. 명시적인 상세 guard와 실제 DB 테스트로 보강한다.

## Decision Log

- 2026-09-09: 기존 승인된 정책과 API 의미를 적용하며 각 소유 PR에 수정 후 순서대로 merge한다.

## Outcomes & Retrospective

#144: typecheck, npm test (unit 185개, presentation 10개, product-copy 11개), check:design,
문서/OpenAPI 검사와 Storybook MCP 환불 오류·멱등 재시도 10개 및 a11y 통과.
전체 Storybook/제품 빌드와 전체 회귀는 마지막 PR에서 실행한다. 운영 환경 검증: Not run.

#145: 실제 DB에서 품목을 삭제한 테스트는 기존 guard에서도 503으로 통과했다. 상세 경계 guard를
추가한 뒤 보드 통합 10개, 정책·ETag 5개, Runtime OpenAPI parity 1개, spotlessCheck와 assemble 통과.
고립 fixture에서 불변 포인트 적립 unit은 TRUNCATE로 정리하며 production 제약을 완화하지 않는다.

## Revision Notes

- 2026-09-09: 리뷰 범위·불변식·검증 계획 기록.
