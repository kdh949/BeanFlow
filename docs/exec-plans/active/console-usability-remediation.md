# 콘솔 인증·가독성·업무 의미와 조회 흐름 수정

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

점주·운영자·상담원이 자신의 인증 상태를 명확히 알고, 작은 화면에서도 주문·금액과 동작을 읽고,
화면의 안내와 실제 가능한 업무가 일치하도록 만든다. 외부 결과가 불명확한 환불은 완료처럼 표현하지 않는다.

## Current State

2026-09-08 현재 main `7d61f91`의 frontend는 감사한 체크아웃과 동일하다. 인증 전 로그아웃 노출,
모바일 로그아웃 누락, 8~11px 업무 글자, Field/Tabs의 레이아웃 충돌, 여러 도메인에 대한 동일 상태 번역,
준비 중 페이지로의 잘못된 유도와 미연결 조회가 확인됐다. 원본 작업 트리의 다른 변경은 제외한다.

## Definitions

- 전체 콘솔: 인증 상태·탐색·계정 동작을 포함한 ConsoleShell과 해당 업무 페이지.
- 준비 중: runtime 계약이나 해당 화면 연결이 아직 제공되지 않는 기능. 장애·빈 목록·성공과 구별한다.
- 결과 불명: UNKNOWN/RECONCILING 등 서버가 환불을 확정하지 않은 상태. 새 환불 실행의 근거가 아니다.

## Scope

### In Scope

1. 인증별 메뉴, 계정 표시, 모든 화면 폭의 로그아웃, 멤버십 조회 실패의 명시적 표현.
2. 공통 글꼴 및 주문·환불 가독성, 환불/계정/탭/상담 동작/목록의 반응형 배치.
3. 도메인별 상태명, 업무 행동 문구, 환불 결과 표현과 후속 동작.
4. 주문 정보·현재 매장 설정 등 기존 runtime 계약의 조회 연결과 미제공 기능의 정확한 안내.
5. 전체 shell 및 장문 한국어·320/390px·실패/인증/금전 상태 Storybook 검증.

### Non-goals

새 재고·배송·분석 모델, 새로운 금액/승인 정책, 인증 방식 교체, 권한 완화, 가짜 runtime 데이터,
관측되지 않은 거래 성공, migration, merge와 deployment는 범위가 아니다.

## Business Rules and Invariants

BR-25, BR-38, BR-39, BR-41 및 ADR-092/100/108을 유지한다. 서버만 금액·권한·허용 동작을 결정한다.
미확정 환불은 ADR-108의 REFUND_OUTCOME_UNRESOLVED를 보존한다. 네트워크 실패를 권한 없음이나
빈 목록으로 대체하지 않는다. 본인확인/접근 승인의 개인정보 경계를 바꾸지 않는다.

## Architecture and Transaction Boundaries

frontend presentation·auth gate·API client 소비와 Ordering의 상세 조회 Projection/DTO를 변경한다. Aggregate, DB transaction,
Provider 호출·멱등성 저장 경계는 변경하지 않는다. 비동기 조회는 이전 요청의 응답이 새로운 선택을 덮지 않게 한다.

## Alternatives Considered

- 페이지별 CSS 덮어쓰기 추가: 현재 충돌을 누적하므로 선택하지 않는다. 기존 공통 컴포넌트 계약과 배치를 수정한다.
- 모든 준비 중 기능에 완성 fixture 연결: 실제 기능처럼 보이는 오류이므로 금지한다.
- 거대한 단일 PR: 원인과 검증 범위가 다르므로 네 개의 선형 stack으로 분리한다.

## Failure Semantics

인증 확인/미인증/의존성 오류/권한 부족을 구별한다. 멤버십 읽기 실패는 재시도 가능한 오류다.
환불의 진행 중·성공·실패·수동 확인은 문구와 시각 표현이 일치한다. 재조회 실패 시 이전 preview로 실행하지 않는다.

## Data and Migration

DB schema와 migration 없음. 주문 보드 item의 선택적 lines 필드를 상세 응답에서 제공하고 typed runtime client를 생성한다.

## API and Event Contracts

`openapi/beanflow-v1-runtime.yaml`에 존재하는 API만 연결한다. target-only 경로와 미완성 목록 projection은
실제 기능처럼 노출하지 않는다. 계약 변경이 필요한 추가 발견은 별도 근거와 승인된 정책을 먼저 확인한다.

## Milestones

1. `feature/console-auth-and-layout` → main: 인증·계정·가독성·반응형, story 회귀 방어.
2. `feature/console-domain-feedback` → 1번 branch: 상태·문구·환불 후속 동작과 상담 정보 위계.
3. `feature/console-order-detail-contract` → 2번 branch: 상세 조회에 주문 당시 품목·옵션 snapshot 추가.
4. `feature/console-workflow-availability` → 3번 branch: 기존 조회 연결과 제공 범위/진입점 정합성.

각 branch는 직전 검증 commit에서 시작한다. 후속 PR은 직전 branch만 base로 한다. 최종 PR에 이 계획의
완료 이동과 항목별 결과를 기록한다. 새 파일 변경이 PR을 과도하게 키우면 같은 원칙으로 더 분리할 수 있다.

## Required Tests

- 미인증에 로그아웃 없음, 인증된 모바일에 로그아웃 존재, 실패한 로그아웃의 오류와 세션 보존.
- 멤버십 오류와 권한 없음 구분, 초기 비밀번호 상태의 업무 메뉴 제한.
- 320/390px 폼·탭·동작 행의 겹침/overflow와 1440px 가독성.
- 동일 ACTIVE/ACCEPTED 코드의 계정·접근 승인·이의제기 문맥별 문구.
- UNKNOWN/FAILED/성공 후 환불 동작, preview 실패/경합/응답 유실 처리.
- 조회 실패·재시도·선택 변경의 오래된 응답 차단, 미제공 경로 안내.

## Validation Commands

frontend: `npm run typecheck`, `npm test`, `npm run check:design`, `npm run check:product-copy`,
`npm run build-storybook`, `npm run test:storybook:docs`, `npm run build`, `npm run test:sites`.
live Storybook MCP: documentation → story instructions → changed stories → preview → run-story-tests(a11y=true).
문서: `scripts/verify-docs.sh`. 각 slice의 focused 검사 후 최종 전체 회귀와 브라우저 확인을 수행한다.

## Observability

새 telemetry 없음. 기존 오류 코드/문의 코드와 실패 UI를 보존한다. 실제 backend/배포 결과와 Storybook
fixture를 구별하여 보고한다.

## Documentation Updates

이 계획, 디자인 시스템 관련 지침, 제품 문구/표현의 국소 결정 및 항목별 검증 결과를 갱신한다.
새 사업 정책을 만들지 않는다. Accepted 결정의 UI 적용은 해당 기존 ADR을 참조한다.

## Progress

- [x] 감사 결과 및 최신 main 비교, 독립 작업 트리 준비.
- [x] BeanFlow 6009 MCP inventory와 story 작성 지침 조회.
- [x] 인증·레이아웃 slice 구현과 로컬 검증. 첫 PR 작성.
- [x] 업무 의미·환불 slice 구현/검증/PR.
- [x] 주문 상세 조회 품목·옵션 계약 구현 및 검증.
- [ ] 조회·제공 범위 slice 구현/검증/PR.
- [ ] 전체 회귀, 시각 검증, stack ancestry와 원격 CI 확인.

## Surprises & Discoveries

- ADR-108은 미확정 환불의 새 preview/실행을 409로 막는다. 화면에서도 그 상태를 명시하고
  재요청을 항상 안전하다고 설명하는 문구를 제거해야 한다.
- 주요 업무 story가 전체 shell을 생략하여 공통 글꼴·계정 UI 문제를 발견하지 못한다.

## Decision Log

- 2026-09-08: 승인된 감사 수정과 PR 요청에 따라 독립 worktree 및 선형 stack 사용.
- 2026-09-08: 기존 디자인 시스템을 재사용·조합하고 준비 중 기능은 계약 기반으로만 연결.

## Outcomes & Retrospective

첫 slice: typecheck, unit 185개, presentation 10개, product-copy 11개, 디자인 검사,
Storybook build, Docs smoke(66 docs/47 states), 제품 build, Sites 4개, verify-docs 통과.
MCP 전체 267개 중 266개 통과 후 실패 fixture 1개를 수정하고 해당 story 재실행 통과.
320/390px 환불, 320px 계정·상담과 390px 계정 프레임에서 가로 overflow 없음 확인.
운영자의 실제 OIDC 로그인/배포 환경: Not run. 세션/화면 검증은 단위 테스트와 명시적 Storybook fixture다.

## Revision Notes

- 2026-09-08: 최초 실행 계획.

- 두 번째 slice: 도메인별 상태명, 환불 결과/요청 보존과 금액 재조회 실패, 상담 정보 위계 수정.
  typecheck, unit 185개, presentation 10개, product-copy 11개, 디자인 검사 통과.
  Storybook MCP 전체 274개 및 a11y 통과. Storybook/제품 build, Docs smoke 66 docs/47 states,
  Sites 4개 통과. 320px 상담·390px 환불 결과 화면에서 가로 overflow와 13px 미만 주요 제어 글자 없음.

- 2026-09-09: 공개 주문번호 상세 API도 요약만 반환하므로 읽기 전용 품목·옵션 확장을 별도 세 번째 PR로 분리.
  ADR-004의 저장된 메뉴·옵션·수량만 사용한다. 고객/결제/내부 식별자는 추가하지 않으며 인가·전이·멱등성 경계는 유지한다.
  후보 파일은 StoreOrderBoardContracts/QueryRepository/Projector, OpenAPI, schema.d.ts, 기존 통합 테스트다.
  기본 보드 polling에 옵션을 추가하는 대안은 사용하지 않는다. 상세 응답 크기만 품목 수만큼 증가한다.
  계약/문서 검사, PostgreSQL 권한·스냅샷·목록 미포함 테스트와 전체 frontend 타입 검사를 실행한다.

- 세 번째 slice: PostgreSQL 보드 통합 9개, 정책·ETag 5개, Runtime OpenAPI parity 1개 통과.
  frontend typecheck 및 문서/OpenAPI semantic 검사 통과. 상세만 옵션을 읽고 polling은 기존 응답을 보존한다.
