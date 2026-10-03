# 매장 인증 실패에서 업무 경로로 복귀

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `—`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

UX 감사 OWN-01/OWN-02: 권한 거절 뒤 로그인으로 이동하고 원래 업무로 돌아갈 수 있게 한다. 로그인 거절과 의존성 장애를 구분하며 공개 문의 코드로 담당 운영자에게 문의할 수 있게 한다.

## Current State

main ec61729에서 MerchantSessionGate의 403 화면은 문구만 있고 동작이 없다. MerchantLoginPage는 모든 실패를 두 입력의 오류로 표시한다. return path는 /store 접두사만 검사해 유사 경로와 인증 화면 순환을 허용한다.

## Definitions

복귀 경로는 같은 사이트의 /store 또는 하위 업무 경로와 query/hash다. 문의 코드는 서버가 발급한 correlationId다.

## Scope

### In Scope

MerchantSessionGate, MerchantAuthPages, merchantSession의 경로 검증, 관련 stories와 회귀 테스트. OWN-01과 OWN-02를 각각 동작·테스트 단위로 커밋한다.

### Non-goals

계정 생성, 잠금 해제, 자가 비밀번호 복구, 인증 정책 변경, API/DB 변경, 다른 역할의 세션 삭제.

## Business Rules and Invariants

BR-34/35와 ADR-092/093 유지. 계정 존재 여부나 잠금 상태를 노출하지 않는다. 403은 자동 로그아웃이 아니며 503은 비로그인으로 간주하지 않는다. 인증 성공과 서버의 ACTIVE 확인 전 보호 화면을 열지 않는다.

## Architecture and Transaction Boundaries

프런트엔드 presentation 및 route 경계만 변경. 기존 서버 Session, MerchantAccount와 transaction은 유지. 기존 세션 generation과 CSRF 처리를 재사용한다.

## Alternatives Considered

자동 logout은 다른 역할 세션 손상 및 실패 은폐 위험으로 제외. 별도 인증 UI/오류 프레임워크 대신 ConsoleFrame, Button, TextField, FeedbackState 조합을 사용한다.

## Failure Semantics

403에는 명시적 로그인 이동. 503에는 재조회 유지. 로그인 AUTHENTICATION_FAILED만 두 입력을 invalid로 표시. 제한/통신 실패는 페이지 오류와 안전한 문의 코드로 표시. 입력값·서버 원문 오류를 새로 기록하지 않는다.

## Data and Migration

없음. 계정·주문 데이터 변경 없음.

## API and Event Contracts

변경 없음. 기존 오류 code/correlationId만 소비.

## Milestones

1. 권한 거절 → 로그인 → 초기 비밀번호 변경(필요 시) → 원래 업무 경로 복귀.
2. 인증 거절 / 횟수 제한 / 의존성 장애 표시 분리와 문의 코드.
3. Storybook, 단위 검사, 필수 빌드와 문서 검사 후 main 대상 독립 PR.

## Required Tests

권한 거절에서 링크와 query/hash 보존, 비인증·초기비밀번호·ACTIVE 경로, 외부/접두사 유사/인증 경로 거절. 실패별 aria-invalid와 문의 코드, 재시도. 기존 merchant session 경합/로그아웃 회귀 유지.

## Validation Commands

frontend: npm run typecheck; npm test; npm run check:design; npm run build-storybook; npm run test:storybook:docs; npm run build; npm run test:sites. Storybook MCP inventory/docs/instructions → changed/preview/tests(a11y). scripts/verify-docs.sh. Chrome 1024/1440 화면 확인.

## Observability

기존 correlationId 표시. 새 로그나 계정 식별정보 수집 없음.

## Documentation Updates

국소 결정은 minor-decisions에 기록. 공개 계약·인증 정책 변경이 없어 새 ADR 불필요.

## Progress

- [x] 원본 dirty 작업 트리를 보존하고 독립 브랜치 생성
- [x] BeanFlow Storybook 6011 MCP로 컴포넌트 계약 확인
- [x] OWN-01 구현: 회귀 23개, typecheck, Storybook 권한/장애 2개(a11y 포함) 통과
- [ ] OWN-02 구현 및 검증
- [ ] 필수 검증 및 독립 PR

## Surprises & Discoveries

기본 6006은 다른 프로젝트가 사용 중이므로 이 작업 트리의 BeanFlow를 6011에서 실행한다. API/DB migration lane에 진입하지 않는다.

## Decision Log

2026-10-03: 독립 PR 우선, 실제 의존 항목은 선행 PR 병합 뒤 진행. 이 slice는 선행 기능 의존성 없음. UI는 기존 디자인 시스템 REUSE/COMPOSE만 사용한다.

## Outcomes & Retrospective

진행 중. 운영 배포 검증은 Not run. 자동 병합/배포 범위 아님.

## Revision Notes

2026-10-03: 실행 계획 작성.
