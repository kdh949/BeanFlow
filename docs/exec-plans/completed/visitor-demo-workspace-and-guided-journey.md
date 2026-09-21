# 방문자별 주문 처리 체험

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `2026-09-15`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture
회원가입 없이 전용 고객·점주·매장으로 주문 접수부터 픽업까지 체험한다. 실제 주문 화면과 API를 사용하며 진행 안내는 서버 상태를 따른다.

## Current State
착수 시 고객/점주 Session, 주문 보드, 혜택 전액 사용 승인, Toss 테스트 결제, 3분 접수 제한이 존재했다. 이번 변경으로 방문자별 발급과 서버 상태 기반 안내를 추가했다. 기존 local-demo 공유 seed를 방문자 계정으로 재사용하지 않는다.

## Definitions
체험 공간(workspace)은 한 브라우저에 귀속된 전용 고객·점주·매장과 30분 접근 기한이다. 기본 체험은 전용 포인트로 전액 결제된 샘플 주문이며 직접 주문은 기존 메뉴/장바구니/Toss 테스트 결제를 사용한다.

## Scope
### In Scope
전용 계정과 매장 생성, HttpOnly Session 발급, 고정 30분 만료/종료, 동시 발급 제한, 멱등 요청, 새 샘플 주문, `/demo` 및 기존 화면에 안내 구성, Storybook과 API/브라우저 검증.
### Non-goals
실 서비스 배포, 운영 계정 발급, 실결제, 기존 주문 상태/접수 시간 변경, 거래 원장 삭제, 개인정보 수집, 기존 미커밋 변경의 통합 또는 커밋.

## Business Rules and Invariants
BR-58과 ADR-132를 따른다. 각 방문자는 별도 계정/매장을 가진다. 다른 매장 주문 생성 및 만료된 계정 인증을 서버에서 차단한다. 일반 로그인 Session을 덮어쓰지 않는다. 종료/만료 후 거래 증거는 보존한다. timeout 주문은 되살리지 않는다.

## Architecture and Transaction Boundaries
Demo 모듈은 발급/수명주기만 소유하고 각 owner API로 Identity, Merchant, Loyalty, Ordering을 조합한다. 발급은 같은 PostgreSQL transaction에서 계정/매장/영업시간/포인트/감사/Session/workspace를 commit한다. 샘플 생성은 workspace lock 아래 기존 즉시 quote/OrderCreationWorkflow/benefit-only 승인 경계를 사용한다. 외부 PG 호출은 포함하지 않는다. 주문 상태 변경은 기존 API를 유지한다.

## Alternatives Considered
공유 계정은 방문자 간 주문 간섭 때문에 제외. 브라우저 시뮬레이션은 실제 상태 확인 목적에 맞지 않는다. DB를 매 방문자마다 생성하는 대신 전용 account/store와 서버 소유권 검사로 격리한다.

## Failure Semantics
발급 실패는 rollback 및 명시적 오류다. 응답 유실은 동일 브라우저 cookie와 Idempotency-Key로 복구한다. 외부 결제 UNKNOWN은 기존 복구 경로를 유지한다. 만료/종료는 확정 전이를 보존하고 새 공간 시작을 안내한다. 활성 공간 및 누적 발급 상한 초과는 429이며 자동 성공/공유 계정 fallback이 없다.

## Data and Migration
V93만 이 작업이 소유한다. 최신 main의 V92 결제 멱등성 인덱스 뒤에서 실행되도록 미적용 demo migration을 재번호했다. Demo workspace와 명령 원장, Identity의 제한된 체험 계정 scope를 추가한다. 기존 account/order row를 변환하지 않는다.

## API and Event Contracts
`/api/v1/demo/config`, `/csrf`, `/session`, `/sessions`, `/session/resume`, `/session/orders`, `/session/order`와 종료 API. 별도 CSRF와 opaque HttpOnly 브라우저 cookie를 사용한다. 인증 Session 식별자는 JSON에 노출하지 않는다. 외부 event는 기존 주문/결제 event만 사용한다.

## Milestones
1. 정책/ADR 및 Figma/Storybook 계약 확인
2. 발급/격리/만료/멱등 backend와 migration
3. 단계별 Storybook과 frontend 구성
4. 실제 route 연결과 직접 주문 안내
5. 관련 자동검증/브라우저/문서

## Required Tests
서로 다른 방문자 account/store/order 격리, 만료 경계 및 종료, 동일 key replay/동시 발급, 전체 rollback, 일반 Session 보존, 다른 매장 주문 거부, profile guard. 안내는 서버 성공 전 진행하지 않으며 오류/시간초과/접기/역할전환/직접 주문 상태를 검증한다.

## Validation Commands
관련 Gradle unit/integration/modularity/format 검사; frontend typecheck, npm test, check:design, build-storybook, Storybook MCP run-story-tests, build, test:sites, 실제 browser 390/1440 확인.

## Observability
발급/종료/샘플 결과별 counter와 시스템 감사 기록. cookie/session/password/raw IP를 기록하지 않는다.

## Documentation Updates
BR-58, ADR-132, 전용 runbook, frontend Storybook docs.

## Progress
- [x] Figma entry와 기존 source/Storybook 계약 확인
- [x] 방문자별 공간 구현 범위 확정
- [x] Backend 구현/검증
- [x] UI Storybook 및 route 구현/검증
- [x] 최종 검증/문서 정리

## Surprises & Discoveries
일반 메뉴/영업시간/정산 정책 변경 API는 운영 인가를 필요로 한다. 체험 초기화는 운영자를 사칭하지 않고 신규 체험 자원만 생성하는 owner port와 SYSTEM 감사를 사용한다. 기본 주문은 픽업 슬롯 없는 IMMEDIATE BENEFIT_ONLY 경로로 만든다.

## Decision Log
- 2026-09-15: 방문자별 계정·매장 격리 및 서버 발급/만료를 채택. 기본 주문은 혜택 전액 사용, 직접 주문은 테스트 PG 사용.

## Outcomes & Retrospective
`/demo`와 11개 독립 상태, 실제 주문 화면 안내, 방문자별 발급/격리/30분 만료를 구현했다. V93 및 전용 CSRF/Session 변경을 함께 구현했다. 일반 로그인, 실제 주문 상태 전환과 거래 증거 보존은 기존 경계를 유지한다.

Backend 17개, frontend unit 250개, 전체 Storybook 792개 테스트 실행이 통과했다. 영향 범위 134개 스토리는 MCP 상호작용/접근성 결과도 모두 수신했다. 전체 MCP 호출은 테스트 통과 후 JSON 직렬화 중 heap OOM으로 응답 수신에 실패했다. 타입/디자인/제품·Storybook 빌드/사이트 smoke/변경 Kotlin 포맷 검사를 통과했다. 1440px 및 390px에서 실제 제품 페이지의 안내 배치와 모바일 접기를 확인했다.

[검증 기록](../../quality/visitor-demo-validation-2026-09-15.md)과 [실행 문서](../../operations/visitor-demo-runbook.md)에 재현 경로와 한계를 남겼다. 기본값은 비활성이고, 배포·운영 DB 적용·외부 Toss 및 알림 성공 검증은 수행하지 않았다.

## Revision Notes
- 2026-09-15: 최초 실행 계획.
- 2026-09-15: 구현과 로컬 검증 완료. 완료 경로로 이동하고 테스트 도구 응답 한계를 기록.

- 2026-09-15: 최신 main `0ea0055`에서 데모 전용 PR 브랜치 분리. 원래 작업 폴더와 별도 성능/복구 변경을 보존하고 V87 적용 순서 제약을 명시.
- 2026-09-21: 당시 최신 main을 병합하고 샘플·직접 주문을 IMMEDIATE로 통일했다. 미적용 V88은 당시 마지막 V91 다음 V92로 재번호하고 보안 체인 조건, 만료 실패 격리와 주문 추적 재시도를 보강했다.
- 2026-09-21: 리뷰 후 비활성 주문의 Identity 조회를 제거하고, 당시 V92에 discovery 비노출 표식과 일반 탐색 필터를 추가했다. 발급 활성 조건과 기존 workspace 만료 정리를 분리하고 데모 오류에 correlation ID를 복원했다.
- 2026-09-21: PR #198이 결제 멱등성 인덱스를 V92로 병합한 최신 main을 통합하고, 아직 적용되지 않은 demo migration을 V93으로 재번호했다.
