# 상담 현황과 연결 주문, 통합 승인함 조회 완성

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/internal-identifier-workflow-selection.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

상담원이 내 업무량과 연결 주문을 확인하고, 관리자가 여러 종류의 승인 요청을 한곳에서 찾아 기존 처리 화면으로 이동한다.

## Current State

기준은 PR #180의 `d2213c1`이다. PR #125의 `c2efc8f`가 제안한 9개 GET 계약을 현재 구현과 대조했다.
현재 상담 목록·상세, 본인확인, 주문 변경, 보상·정보 정정 요청 선택, 승인 단계·상담 이력, 내장 고객 문의는 구현돼 있다.
미구현은 내 상담 현황·분류/우선순위 필터, 연결 주문의 품목/금액 조회, 여러 종류를 모으는 승인함이다.
기존 업무별 상세와 명령은 새 조회의 도착점으로 유지한다. PR #125의 셸·라우트 대체와 중복 요청 목록 API는 가져오지 않는다.

## Definitions

- 상담 현황: 현재 로그인한 담당자의 OPEN/IN_PROGRESS/WAITING 건수. RESOLVED/CLOSED는 진행 중이 아니다.
- 연결 주문: 상담에 현재 활성 ORDER 링크가 있는 주문. 공개 주문번호·매장명·주문 당시 품목·금액으로 표시한다.
- 승인함: 권한으로 볼 수 있는 요청을 모으는 조회 진입점. 목록 행은 승인 또는 실행 권한을 부여하지 않는다.

## Scope

### In Scope

1. 기존 상담 목록 필터 확장과 내 현황 API/UI.
2. Ordering 공개 DTO를 통한 연결 주문 요약 API/UI.
3. 기존 업무 조회/권한 경계를 재사용하는 통합 승인함, 승인 결정 이력과 기존 상세 링크.

### Non-goals

기존 #170 고객 포인트 선택 변경, 별도 셸, 거래 명령/승인 정책 변경, 개인정보 추가 수집, 배포와 PR 병합.

## Business Rules and Invariants

ADR-081/082/084/090/128과 SP-01~22를 유지한다. 서버가 권한과 대상 연결의 권위다. 개인정보 원문을 URL·로그·영속 브라우저 저장소에 넣지 않는다. 반환된 내부 식별자는 링크 전달에만 사용한다.

## Architecture and Transaction Boundaries

Controller→Application Service→소유 Query Repository/공개 포트. Support가 타 Context 테이블을 읽지 않는다.
권한 grant 확인은 기존 잠금과 호환되는 짧은 일반 transaction을 사용한다. 페이지 전체에 걸친 외부 호출이나 쓰기 aggregate 확장이 없다.

## Alternatives Considered

PR #125 전체 병합은 현행 셸·권한·상태 처리와 중복되어 기각한다. 새 요청 목록을 중복 구현하는 대신 #174의 목록과 현재 상세를 재사용한다. 필터·현황·주문 요약만 필요한 공개 계약을 확장한다.

## Failure Semantics

권한 실패와 의존성 오류를 빈 목록·0건·주문 없음으로 바꾸지 않는다. 승인함 조회와 실제 명령 사이의 상태 변화는 기존 상세/명령에서 재검증한다. 결제 성공을 주문 상태로 추측하지 않는다.

## Data and Migration

마이그레이션과 새 production dependency 없음. 기존 Support/Ordering 읽기 모델만 사용한다.

## API and Event Contracts

Runtime 및 target OpenAPI를 동기화하고 frontend schema를 생성한다. 기존 목록의 optional 필터 추가는 호환성을 유지한다. 새로운 event와 command는 없다.

## Milestones

- S1: 내 상담 현황과 분류/우선순위 필터. #180 위 독립 수직 슬라이스 PR.
- S2: 연결 주문 요약. S1 위 수직 슬라이스 PR.
- S3: 통합 승인함. S2 위 수직 슬라이스 PR.

## Required Tests

권한 거절·actor 격리·terminal 상태 제외·필터/cursor 결합, 주문 링크/권한/품목·금액 투영, 승인함 visibility/중복·후속 링크/오류, Runtime parity와 Modulith 구조.
Storybook loading/empty/error/permission/success/filter/page/deep link 및 a11y. 기존 통합 화면을 보존하는 회귀 검증.

## Validation Commands

`./gradlew spotlessCheck test --tests <affected integration tests> --tests '*RuntimeOpenApiParityTest' --tests '*ModularityTests'`, `scripts/verify-docs.sh`, `scripts/verify-docs.sh` 내 OpenAPI YAML/semantic 검사.
frontend typecheck, test, check:design, build, test:sites, build-storybook, test:storybook:docs와 MCP run-story-tests.

## Observability

기존 API 오류와 correlation ID를 사용한다. 새 원문 로그·개인정보 telemetry 없음. 성능 향상을 주장하지 않는다.

## Documentation Updates

ADR-129와 현재 문서에 선택 경계·검증 결과를 기록한다. 완료 시 completed로 이동하고 참조를 갱신한다.

## Progress

- [x] #125와 #180의 조회 계약/업무 화면 대조 및 canonical Storybook MCP 확인.
- [x] S1 구현: 집계·필터·API 및 14 backend tests, frontend unit 236/presentation 10/copy 11, typecheck/design/build/Sites/Storybook build, 11 focused MCP tests 통과. PR #181 `fb092f6` 게시 완료.
- [x] S2 구현·검증: backend 15 tests, frontend unit 236/presentation 10/copy 11, typecheck/design/build/Sites/Storybook build, 관련 MCP 19개 통과. PR 게시 진행 중.
- [ ] S3 구현·검증·PR.
- [ ] 최종 CI와 stack head 검증, 문서 완료 처리.

## Surprises & Discoveries

#125의 active 집계는 CLOSED만 제외하므로 RESOLVED까지 포함한다. 현재 domain terminal 정의에 맞춰 OPEN/IN_PROGRESS/WAITING만 집계한다.
#174가 보상·정보 정정의 기존 요청 목록을 이미 구현했다. 중복 GET을 추가하지 않는다.
기존 상담 timeline은 Case/접촉/대상 기록이며 승인 결정 전체를 포함하지 않는다. S3는 기존 승인 step 및 grant/break-glass decision의 요청별 bounded 이력을 추가한다.

## Decision Log

- 2026-09-11: 조회 기능만 현재 공용 화면에 조합한다. 사용자 승인 범위 내 구현/PR 게시이며 병합·배포는 포함하지 않는다.

## Outcomes & Retrospective

S1: RESOLVED/CLOSED 제외, 다른 담당자 제외, 필터와 cursor 결합 검증 통과. 320/768/1440px 두 화면에서 문서 가로 넘침 없음, 검사한 control/label/p 최소 14px. 최초 Story selector 중복과 종료 fixture의 시각 제약을 바로잡고 재검증했다. Static Docs 120 entries/15 stateful docs/47 state surfaces 통과. S2: 담당자·활성 Case·활성 주문 링크를 조회 전후 검사하며 Ordering 품목/금액만 반환한다. backend 15 tests와 관련 MCP 19개, Static Docs 121 entries/15 stateful/47 surfaces 통과. 320/768/1440px에서 가로 넘침·선택 탭 잘림 없음, 검사한 control/label/p 최소 14px. 최초 새 포트에 대한 기존 테스트 더블의 메서드 누락을 보완 후 재검증했다. S3 미구현.

## Revision Notes

- 2026-09-11: 현재 구현과 비교한 세 슬라이스로 시작.
