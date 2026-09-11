# 프론트엔드 업무 스택 리뷰 보완

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR #160–#184의 미해결 리뷰를 현재 계약과 대조하여 실제 결함을 수정하고, 검증된 커밋을 해당
PR에 반영한다. 기능별 커밋과 기존 부모 PR 관계를 유지하며 리뷰에는 결과와 검증만 기록한다.

## Current State

2026-09-12 수집 기준 미해결 리뷰는 48건이다. #125의 기존 리뷰는 모두 해결 상태다.
#160부터 각 원격 head에서 수정하고 부모의 보완을 자식에 merge한다. 공유 이력은 재작성하지 않는다.
기존 작업 디렉터리의 진단 파일은 보존하고 별도 worktree에서 작업한다.

## Definitions

- 미확정 요청: 서버 처리 결과를 브라우저가 확인하지 못한 요청이다. 확정 실패와 구분한다.
- 멱등 키: 동일 actor·operation·내용의 재요청을 원래 결과에 연결하는 키다.
- 완료: 해당 수정의 로컬 검증·원격 CI·리뷰 해결 확인까지 마친 상태다. merge나 배포를 뜻하지 않는다.

## Scope

### In Scope

- #160: READY 결제 재개 제한, 기기 시계 영향 제거, 조회 재시도·홈·목록 갱신 보존 (6건).
- #161: 이미지 204 삭제, 편집 보존, 새 편집의 저장 안내 초기화 (4건).
- #162: 매장 선택 권한, 정책 오류 코드와 scope 표시 (3건).
- #163: 재이의 금액, 복구 제안 시계와 대상별 입력 초기화 (3건).
- #164: 역할별 픽업 조회, workflow 조회 의미, 명령 재진입, 인증 만료, 연결 해제, 정책 버전 (7건).
- #165: 미실행 Resolution 재배정과 미결정 보상 분담률 (2건).
- #166: 최소 조사 권한, 긴급 열람의 현재 배정·대상, 필드별 확인 (3건).
- #167: 공개 답변 replay, 문의 목록 일괄 조회와 전역 정렬 비용 (3건).
- #168: 이미지 조회 감사 계약과 동명 메뉴 구분 (2건).
- #170: 포인트 조정의 재진입 복구 (1건).
- #171: 업무별 최소 매장·계정 탐색, terminal 멱등 오류 (3건).
- #172: 복구 목록 감사, terminal replay, OPEN Case 생성 제한 (4건).
- #173: 대상 표시 권한, 잠금 순서, 구분 불가능한 대상 선택, OpenAPI 일치 (5건).
- #174: 원문·미확정 열람 중 탐색 잠금 (1건).
- #177: 브라우저 history로 미확정 요청 패널이 제거되는 경로 (1건).

### Non-goals

새로운 메시지 브로커, 범용 업무 엔진, 디자인 시스템 교체, PR merge와 배포.

## Business Rules and Invariants

BR-03/25/33의 서버 시간·멱등·결제 상태, 기존 독립 grant, 현재 actor와 대상 binding을 유지한다.
결과 불명을 성공·확정 실패로 추정하지 않는다. 개인정보 원문을 브라우저 저장소에 추가하지 않는다.
새 명령은 현재 대상·버전·권한을 검사하며 replay는 해당 endpoint의 저장 결과 계약을 따른다.

## Architecture and Transaction Boundaries

조회는 기존 owner projection을 재사용한다. Application Service가 권한·감사·도메인 쓰기의
local transaction을 조정한다. Provider 호출을 새 DB transaction 안으로 옮기지 않는다.
동시성 변경은 grant와 Aggregate 잠금 순서를 실제 쓰기 경로에 맞춰 검증한다.

## Alternatives Considered

공유 브랜치 rebase 대신 부모 수정 merge를 사용한다. 새로운 공통 상태 엔진 대신 화면별
이동 잠금과 기존 멱등 원장의 좁은 복구를 우선한다. 조회 성능은 일괄 projection을 우선한다.

## Failure Semantics

401/403/404는 무한 자동 재시도하지 않는다. 네트워크·429·5xx는 명시적 재시도 대상으로
분리한다. 실패한 조회는 오래된 값을 성공처럼 표시하지 않는다. 권한 상실과 terminal replay를
구분하며 진행 중 요청의 key·내용은 상태 전환으로 잃지 않게 한다.

## Data and Migration

첫 단계는 스키마 변경이 없다. 이후 영속 복구 또는 인덱스가 필요하면 기존 모델의 재사용을
먼저 검토하고, DDL 전에 ADR-072와 migration writer 상태를 확인해 이 절과 metadata를 갱신한다.

## API and Event Contracts

변경되는 응답·오류·grant·감사 의미를 target/runtime OpenAPI와 authorization matrix에 함께 기록한다.
현재 결정을 바꾸는 경우 관련 ADR을 먼저 개정한다. 새로운 event 도입은 기본 대안이 아니다.

## Milestones

1. #160–#163 고객·매장·운영 기본 업무 수정.
2. #164–#170 상담·문의·포인트의 인증과 재시도 경계 수정.
3. #171–#177 선택·감사·동시성·이동 경계 수정.
4. #178–#184까지 부모 변경 반영, 전체 검증과 authoritative reviewThreads 재확인.

## Required Tests

서버 결제 상태·만료 replay, clock skew, permanent 조회 오류, 페이지 보존, dirty 편집,
독립 grant 조합, 재배정·unlink 이후 거부, 응답 유실 후 동일 키 재확인, terminal replay,
실제 PostgreSQL 동시성·감사 rollback, Storybook interaction과 접근성을 검증한다.

## Validation Commands

`./gradlew test --tests <affected integration tests>`, `./gradlew check`, `scripts/verify-docs.sh`.
Frontend의 `typecheck`, `test`, `check:design`, `build-storybook`, `build`, `test:sites`와
Storybook MCP의 변경 story·preview·focused/full tests를 실행한다. 각 결과는 Progress에 구분한다.

## Observability

기존 stable 오류·Audit·명령 상태를 사용한다. 실제 실행하지 않은 검증·성능을 성공으로 기록하지 않는다.

## Documentation Updates

관련 ADR, Business Policy, authorization matrix, OpenAPI, 이 ExecPlan의 진행·결과를 갱신한다.

## Progress

- [x] 원격 열린 PR과 미해결 reviewThreads 48건 수집 및 부모/head 고정.
- [x] 별도 worktree와 Storybook MCP 준비, 컴포넌트 문서 조회.
- [ ] #160–#163 수정·검증·원격 반영.
- [ ] #164–#170 수정·검증·원격 반영.
- [ ] #171–#177 수정·검증·원격 반영.
- [ ] #178–#184 부모 반영과 전체 검증.
- [ ] 리뷰 답변·해결 및 남은 미해결 수 확인.

### #160 로컬 검증

- 결제 준비 전후 서버 eligibility 검증, 기기 시계의 결제·이미지 hard gate 제거.
- 재시도 가능한 조회 실패만 자동 재시도하고, 숨겨진 화면에서는 요청을 중지한다.
- 주문 목록은 펼친 페이지 수만큼 최신 cursor로 다시 읽고 갱신 중 카드를 유지한다.
- Passed: PostgreSQL OneTimeCheckoutIntegrationTest 19개, frontend unit 227개,
  presentation boundary 10개, product copy 11개, 영향 Storybook 53개, Docs 70개 entry/47 state surface,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs.
- Docker가 중지돼 첫 DB 실행은 환경 오류였으며 Docker 시작 후 재실행했다.
- 원격 CI와 reviewThreads 해결은 대기 중이다.

### #161 로컬 검증

- 내용·이미지·픽업 편집 및 저장 중 업무 탭과 매장·메뉴 전환을 제한하고 명시적 취소 경로를 제공한다.
- 이미지 DELETE 204를 성공 처리하며 표시 정보의 새 편집은 이전 저장 안내를 지운다.
- Passed: frontend unit 229개, presentation boundary 10개, product copy 11개,
  영향 Storybook 52개(초기 포커스 실패 수정 후 해당 상태 재실행 포함), typecheck, check:design,
  build-storybook, build, test:sites 4개. 원격 CI와 리뷰 해결은 대기 중이다.

### #162 로컬 검증

- 실제 ORDER_STATE_CONFLICT와 현재 정책 재조회 안내, GLOBAL/STORE 이력 라벨을 일치시킨다.
- 업무별 최소 매장 목록은 목적별 단독 grant로 이름·ID만 반환한다. 전체 식별정보 권한은 유지한다.
- Passed: PostgreSQL 매장 관리 14개, Runtime OpenAPI parity 1개, 인증 경로 3개,
  frontend unit 231개, presentation boundary 10개, product copy 11개, 영향 Storybook 34개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs.
- 권한 철회 fixture에 revoked_at이 빠진 초기 테스트 실패를 수정해 재검증했다.
- 원격 CI와 리뷰 해결은 대기 중이다.

### #163 로컬 검증

- 재접수 폼은 이전 청구 금액의 부호를 보존하며, 복구 판정은 서버 state와 현재 actor를 따른다.
- 다른 제안 조회는 이전 판정 사유·생성 안내를 초기화한다.
- Passed: frontend unit 231개, presentation boundary 10개, product copy 11개, 영향 Storybook 18개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs.
- Backend 동작 변경은 없으며 이 slice의 backend 재실행은 Not run이다. 원격 CI와 리뷰 해결은 대기 중이다.

### #164 로컬 검증

- 상담원과 매장 액터가 각자의 권한·대상 범위로 픽업 후보를 조회하도록 API와 화면을 연결한다.
- 기존 S60 GET 상태 보정은 상태·버전·감사의 일회 전이를 유지하고 반복 조회 무변경을 검증한다.
- 상담 명령은 전송 전 actor·업무·입력 해시와 키만 기록해 같은 탭 재진입 때 동일 키를 사용한다.
- 만료 challenge 재발급, 삭제된 연결의 자동 재선택 방지, 현재 동의 정책 버전 검증을 추가한다.
- Passed: PostgreSQL 상담 승인·매장 주문 변경 16개, Runtime OpenAPI parity 1개,
  frontend unit 241개, presentation boundary 10개, product copy 11개, 전체 Storybook 505개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs.
- Storybook Docs는 로컬 브라우저 실행 권한 오류 후 재실행 중이다.
- #160 및 #162 원격 CI 통과 후 해당 리뷰 9개를 답변·해결했다. #161의 CI Storybook 일시 실패는
  동일 코드의 후속 브랜치와 로컬 전체 Storybook 통과를 확인하고 실패 작업을 재실행했다.
- #164 원격 CI와 리뷰 해결은 대기 중이다.

## Surprises & Discoveries

이미지 삭제는 후속 #168에서 코드가 보완돼 있으므로 #161 자체의 회귀와 함께 확인한다.
기존 GET materialization은 Accepted 정책 여부를 먼저 확인하며 범용 worker로 일괄 교체하지 않는다.

## Decision Log

- 2026-09-12: 기능별 기존 PR에 보완 커밋을 추가하고 부모 변경을 merge한다. 기존 원격 이력 보존 목적.

## Outcomes & Retrospective

진행 중. 검증과 원격 반영은 아직 완료되지 않았다.

## Revision Notes

- 2026-09-12: 원격 리뷰 기준 실행 범위와 검증 경계를 기록했다.
