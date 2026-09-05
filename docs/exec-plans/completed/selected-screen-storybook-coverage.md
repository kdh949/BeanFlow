# 선택된 고객·점주·운영·고객센터 화면을 Storybook 기준으로 완성한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-05`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객의 부분 환불·쿠폰 발급, 점주의 거래 카탈로그·운영 설정·분석·이의제기 상세,
운영팀의 장애·정산·감사·승인 업무, 고객센터의 후속 상담 업무를 현재 BeanFlow 디자인
시스템으로 탐색할 수 있게 한다. 각 화면과 핵심 상태는 canonical Storybook에서 직접
열 수 있어야 한다.

runtime OpenAPI가 없는 기능은 제품 route에서 성공 가능한 것처럼 가장하지 않는다. 화면은
Accepted 정책과 target OpenAPI의 정보 구조를 사용하되 명시적인 `계약 준비 중` 상태를 표시하고,
완전한 ready 상태는 Storybook fixture로만 검증한다. runtime 계약이 이미 있는 기능은 기존 typed
client와 실패 semantics를 재사용한다.

## Current State

- 고객 쿠폰함과 주문 상세는 있지만 고객용 부분 환불 원장과 쿠폰 claim endpoint는 없다.
- 점주 콘솔에는 주문·환불·정산·이의제기·지역만 있고 active 거래 카탈로그 plan은 아직 backend
  writer를 구현하지 않았다.
- target OpenAPI에는 운영 실패 큐·정산 대사·감사 조회가 있지만 runtime OpenAPI에는 없다.
- 고객센터 runtime OpenAPI에는 Case 협업, 주문 변경, 보상, 프로필 변경, break-glass endpoint가
  있으나 현재 UI는 검색·Case·verification·reveal·보상 요청 진입까지만 연결한다.
- `docs/exec-plans/active/customer-merchant-screen-contract-completion.md`는 사용자 소유 미추적 파일이며
  이 작업에서 수정하지 않는다.

## Definitions

- **ready story:** 서버 계약이 제공한다고 가정한 deterministic fixture로 완성된 정보 구조와 상호작용을
  검증하는 Storybook 상태다.
- **contract-pending route:** runtime endpoint가 없는 제품 route에서 빈 목록이나 가짜 성공 대신 명시적
  연결 대기 상태를 표시하는 화면이다.
- **follow-up work:** Support Case 생성 이후 담당자 협업, action 승인·실행, 사후 해결, 보상 실행,
  프로필 변경과 비상 접근 업무다.

## Scope

### In Scope

- 고객 환불 내역·쿠폰 발급 화면과 각각의 ready/empty/contract-pending story
- 점주 메뉴·가격, 재고, 영업시간·픽업, 매장 혜택, 매출 분석 workspace와 이의제기 상세 화면
- 운영 실패 큐·상세, 정산 대사, 감사 로그와 승인·라우팅·추적·쿠폰·캠페인·지급 workspace
- 고객센터 후속 업무 workspace
- 기존 Customer/Console shell navigation과 router 연결
- Storybook interaction/a11y, frontend unit/design/build/docs 검증

### Non-goals

- 새 backend endpoint, DB migration, Provider 호출, Aggregate 상태 전이 또는 target/runtime OpenAPI 전환
- runtime 계약이 없는 기능을 local fixture, fake success 또는 browser storage로 대체
- Accepted 금액·승인·권한·멱등성·실패 정책 변경
- 기존 사용자 미추적 ExecPlan 수정

## Business Rules and Invariants

- 부분 환불은 immutable line allocation을 표시하며 쿠폰 복원을 성공으로 추정하지 않는다.
- 쿠폰은 한 주문에 최대 하나이며 claim 성공은 server-issued issuance가 있어야만 표시한다.
- 점주 화면은 ACTIVE same-store membership과 server version이 write authority다.
- 운영 실패 큐는 Payment, Notification, Settlement owner state를 임의로 합치거나 누락을 0으로 바꾸지 않는다.
- 정산 대사는 `CONSISTENT | MISMATCH | INCOMPLETE`를 분리하고 조회가 repair를 실행하지 않는다.
- 감사·PII·break-glass read는 reason, permission, approval와 Audit 성공 없이는 값을 표시하지 않는다.

## Architecture and Transaction Boundaries

이번 변경은 frontend route, presentation composition과 Storybook fixture만 변경한다. 새 transaction은
없다. runtime API가 있는 mutation은 기존 `SubmissionIntent`, expected version과 typed client를 사용한다.
runtime API가 없는 product route는 command를 보내지 않고 contract-pending 상태를 렌더링한다.

## Alternatives Considered

- active backend ExecPlan 전체를 함께 구현: 정확한 end-to-end 결과를 주지만 migration-writer lease와 여러
  Context의 schema/API 작업이 필요해 이번 화면 중심 범위를 넘는다.
- route에 demo fixture 사용: 빠르지만 실제 성공으로 오인되므로 기각한다.
- Storybook에만 화면 추가: 안전하지만 사용자가 앱에서 화면 구조를 찾을 수 없으므로 route에는 명시적
  contract-pending 상태를 함께 둔다.

## Failure Semantics

- dependency failure와 contract absence는 empty state로 바꾸지 않는다.
- unknown/reconciling/manual-review 상태를 서로 다른 status copy로 보존한다.
- mutation을 자동 재시도하지 않는다.
- PII, 원본 proof, Provider key와 raw error는 fixture, URL, storage, story log에 넣지 않는다.

## Data and Migration

DB와 migration 변경 없음.

## API and Event Contracts

runtime API는 `openapi/beanflow-v1-runtime.yaml`, 미래 UI 정보 구조는 Accepted
`openapi/beanflow-v1.yaml`만 참조한다. 이 작업에서 두 계약을 수정하지 않는다.

## Milestones

1. 고객 환불·쿠폰 발급 story-first 화면과 route.
2. 점주 관리 workspace와 이의제기 상세 story-first 화면과 route.
3. 운영 work queue/control workspace와 실제 dashboard 정보 구조.
4. 고객센터 follow-up workspace.
5. 전체 Storybook MCP, unit, typecheck, design, build, docs 검증.

## Required Tests

- 각 workspace 탭의 heading, status, allowed/disabled action과 keyboard selection.
- loading, empty, contract-pending, ready, unknown/manual-review 상태.
- runtime 계약이 없는 route에서 mutation action이 비활성이고 가짜 데이터가 없는지 검증.
- Support 후속 업무에서 Case terminal/permission/approval 경계 표시.
- AppShell navigation과 router 접근 가능성.

## Validation Commands

```bash
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run check:design
cd frontend && npm run build-storybook
cd frontend && npm run build
cd frontend && npm run test:sites
cd frontend && npm run test:storybook:docs
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

Storybook MCP에서 변경 때마다 focused `run-story-tests(a11y=true)`, 마지막에 전체 run을 실행한다.

## Observability

새 telemetry 없음. contract-pending은 사용자에게 명시적으로 보이는 상태이며 성공 metric을 만들지 않는다.

## Documentation Updates

- 이 ExecPlan의 Progress, Surprises & Discoveries, Outcomes를 실제 결과로 갱신한다.
- 화면별 완료와 backend contract 미연결을 최종 보고에서 분리한다.

## Progress

- [x] 2026-09-05: 현재 route, Storybook MCP component/page documentation, target/runtime OpenAPI 차이를 조사했다.
- [x] 2026-09-05: 고객 부분 환불·쿠폰 발급 화면과 4개 stories를 추가했다.
- [x] 2026-09-05: 점주 관리 workspace·이의제기 상세와 10개 stories를 추가했다.
- [x] 2026-09-05: 운영 복구·제어 workspace의 13개 stories를 추가하고 dashboard 2개 상태를 갱신했다.
- [x] 2026-09-05: 고객센터 후속 업무 workspace와 7개 stories를 추가했다.
- [x] 2026-09-05: 역할별 navigation·route 연결과 전체 Storybook, unit, typecheck, design, build, Docs 검증을 완료했다.

## Surprises & Discoveries

- target OpenAPI의 운영 실패 큐·정산 대사·감사 endpoint는 runtime generated client에 아직 없다.
- Support 후속 업무 endpoint는 runtime에 이미 있으나 현재 화면 coverage가 좁다.
- Storybook Docs smoke는 최신 `storybook-static`이 필요하므로 정적 Storybook build 이후 실행해야 한다.

## Decision Log

| 일자 | 결정 | 근거 |
| --- | --- | --- |
| 2026-09-05 | 기존 primitive를 REUSE하고 화면별 composition만 추가 | `frontend/AGENTS.md`, Storybook MCP documentation |
| 2026-09-05 | runtime 계약 부재를 fixture success로 대체하지 않는다 | failure semantics와 Definition of Done |

## Outcomes & Retrospective

- 고객·점주·운영·고객센터에 선택된 정보 구조와 작업 화면을 canonical design-system 조합으로 추가했다.
- 새 화면 34개 story가 ready, empty, 실패/불명, contract-pending, interaction 상태를 검증한다.
- 제품 route는 backend 계약이 없는 기능을 임의 데이터나 성공 동작으로 대체하지 않고 준비 중 상태로 표시한다.
- 전체 Storybook interaction/a11y, 176개 frontend unit test, design/copy boundary, production build,
  Storybook static build와 61개 Docs smoke가 통과했다.
- backend endpoint, migration과 target/runtime OpenAPI 전환은 이 계획의 Non-goal로 남는다.

## Revision Notes

- 2026-09-05: 최초 작성.
- 2026-09-05: 모든 milestone과 검증 완료 후 completed로 이동.
