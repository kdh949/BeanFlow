# 문의 입력 오류와 읽기 개선

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

CUS-05/06/10: 정상 날짜가 포함된 문의 접수, 안전한 필드 오류, 긴 제목 및 도움말 읽기 개선.

## Current State

하이픈 숫자 정규식이 ISO 날짜를 거부하고 boundary는 필드 맥락을 잃는다. 제목은 단일행 버튼 링크, help card는 내부 padding이 없다.

## Definitions

ISO 날짜는 엄격한 YYYY-MM-DD와 LocalDate 검증을 모두 통과한 값.

## Scope

### In Scope

Support content, inquiry application boundary, shared failure details, error mapper, 고객/support 문의 UI와 도움말.

### Non-goals

새 개인정보 검사 서비스, 외부 발송, 데이터 보정, 스키마 변경.

## Business Rules and Invariants

BR-55/ADR-126 명확화 선행. 입력/민감값 반사 금지, 100/2000 제한, 고객 소유권 및 담당자 권한/멱등성 유지.

## Architecture and Transaction Boundaries

Support Aggregate 및 기존 transaction 유지. 검증은 lock/write 전에 수행. Shared API는 중립적 FailureDetail을 제공하고 web handler가 기존 ErrorDetail로 매핑.

## Alternatives Considered

모든 하이픈 숫자 허용/클라이언트 전용 검사는 거절. 국소 날짜 예외와 기존 DTO details 사용. Field/Button/Link REUSE; 기존 management-card COMPOSE.

## Failure Semantics

400은 입력 유지 및 해당 필드 안내. transport/5xx의 불명 결과와 같은 요청 재확인은 유지.

## Data and Migration

DDL/기존 데이터 변경 없음.

## API and Event Contracts

기존 ErrorResponse.details 사용. raw 입력 없는 title/content와 INVALID_VALUE.

## Milestones

1. 정책/테스트와 stories
2. 날짜 및 필드 오류 수직 슬라이스
3. 제목/도움말 각 커밋
4. 검증/독립 PR

## Required Tests

날짜 윤년/오류, 날짜+PII, 숫자 연속, inquiry replay와 400 details/DB 미기록, frontend 필드 수정 후 오류 숨김, unknown 재시도, 긴 제목320/390/1024.

## Validation Commands

Gradle focused domain/integration/architecture + ktlint, frontend required checks와 MCP, docs/OpenAPI.

## Observability

기존 correlation/audit 유지, raw 값 로그 없음.

## Documentation Updates

BR-55, ADR-126, 이 계획.

## Progress

- [x] 분석/MCP
- [ ] 구현/검증
- [ ] PR

## Surprises & Discoveries

기존 #201은 같은 error handler의 logging을 수정하므로 통합 시 양쪽 동작 보존. 의존 관계는 없음.

## Decision Log

2026-10-03: 기존 승인 계획의 국소 수정, 독립 main PR.

## Outcomes & Retrospective

Pending.

## Revision Notes

2026-10-03: 실행 시작.
