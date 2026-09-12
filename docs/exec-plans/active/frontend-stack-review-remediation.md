# 프론트엔드 업무 스택 리뷰 보완

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
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

#167의 전역 문의 정렬에 additive `V82_1` 인덱스를 추가한다. 기존 V82와 후속 V83–V86의
번호·checksum을 보존하고 Flyway 정렬상 V82 다음에 적용한다. 이 작업은 사용자가 지정한 기존
PR stack의 보완이며 새 독립 migration plan을 시작하지 않는다. 2026-09-12 현재 origin/main은
`a6199c6`/V81이고 stack의 마지막 migration은 V86이다. 활성 BeanFlow 작업은 이 보완 작업이며,
다른 worktree의 V33/V34 변경은 2026-08-08부터 남아 있는 변경으로 보존한다. 이 스택의
migration writer는 이 작업 하나로 직렬화하며 V82→V82.1 업그레이드와 최종 전체 stack의
V82→V82.1→V83–V86 순서를 fresh PostgreSQL Flyway에서 검증한다. #170의 서버 조정 준비 기록은
이 단일 lane을 이어 `V83_1`로 추가하며 기존 V83–V86 번호·checksum을 보존한다.

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
- [x] #160–#163 수정·검증·원격 반영 및 리뷰 16건 해결.
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
- Storybook Docs는 로컬 브라우저 실행 권한 오류 후 재실행해 93 entries/47 state surfaces가 통과했다.
- #160 및 #162 원격 CI 통과 후 해당 리뷰 9개를 답변·해결했다. #161의 CI Storybook 일시 실패는
  동일 코드의 후속 브랜치와 로컬 전체 Storybook 통과를 확인하고 실패 작업을 재실행했다.
- #164 원격 CI와 리뷰 해결은 대기 중이다.

### #165 로컬 검증

- 승인 요청 재배정 시 아직 PLANNED인 Resolution 실행자를 같은 Request→Case→Resolution 잠금 순서로 변경한다.
  원 계획 작성자와 승인안은 보존하며 시작한 해결 건은 도메인에서 재배정을 거부한다.
- 비용 책임 미확정은 플랫폼·매장 0/0으로 평가하며 임의 비용 귀속을 하지 않는다.
- Passed: PostgreSQL Resolution 14개, 프로필 변경 13개, Resolution 도메인 10개, Runtime parity 1개,
  frontend unit 243개, boundary 10개, copy 11개, 영향 Storybook 40개, Docs 98 entries/47 state surfaces,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs, Spotless.
- 재배정 회귀 테스트의 최초 배정 이력 누락을 보완한 후 다시 통과했다.
- #161 CI 재실행과 #163 CI가 통과해 #160–#163 리뷰 총 16개를 답변·해결했다.
- #165 원격 CI와 리뷰 해결은 대기 중이다.

### #166 로컬 검증

- 운영 조사 grant 전용 최소 승인안 조회를 제공해 광범위한 상담 조회 권한 없이 기존 검토 화면을 사용한다.
  원문·본인확인 세션·상담 내용은 응답하지 않으며, 결정 명령의 현재 권한·승인안 재검증은 유지한다.
- 긴급 열람 승인은 요청자의 현재 배정과 활성 대상 연결을 재검증한다. 화면의 대상·필드 변경은 확인 체크를 초기화한다.
- Passed: PostgreSQL 긴급 열람·프로필 정정·보상·운영 조사 47개, frontend unit 245개,
  boundary 10개, copy 11개, 영향 Storybook 30개, Docs 103 entries/47 state surfaces,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs, Spotless.
- 최초 컴파일의 nullable entity getter 사용과 Runtime OpenAPI 검사기의 참조 따옴표 형식을 보완했다.
  Runtime parity 1개 재실행도 Passed다.
- #164·#165 CI의 인증 만료 Story는 상태 갱신 중 입력을 조회하던 race를 보완해 원격에 반영했다.
  해당 Story의 로컬 재검증은 Passed, 원격 CI와 #164–#166 리뷰 해결은 대기 중이다.

### #167 로컬 검증

- 현재 답변 grant를 확인한 뒤 저장된 replay를 우선하여 담당자 교대 후에도 기존 공개 답변 결과를 돌려준다.
  새 답변은 현재 배정·Case 버전·상태를 검증한다. 목록은 페이지의 Case 상태를 한 번에 투영한다.
- 고객 명령의 복구 키는 현재 고객 세션으로 구분하고 운영 토큰을 보내지 않는다.
- Passed: 문의 통합 13개, 도메인 2개, 실행 계획/마이그레이션 1개, Runtime parity 1개,
  frontend unit 245개, boundary 10개, copy 11개, 문의·라우트 Storybook 19개와 병합된 상담 Story 2개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs, Spotless.
- 20,000개 문의(인수 10,000개)의 동일 fixture에서 첫·다음 페이지는 V82.1 적용 전 Seq Scan/Sort,
  적용 후 idx_support_inquiry_global_page의 Index Scan으로 확인했다. 실제 운영 지연 개선 수치로 일반화하지 않는다.
- 고객 세션 GET fixture가 없던 초기 Story 실패를 실제 /me 계약 fixture로 보완해 재검증했다.
- Storybook Docs 105 entries/47 state surfaces도 Passed다. 원격 CI와 리뷰 해결은 대기 중이다.

### #168 로컬 검증

- 이미지 GET와 메뉴 디렉터리는 검증용 접근 사유를 사용하며 Audit를 추가하지 않는 기존 계약을 명확히 했다.
  이미지 변경의 기존 감사 계약은 유지한다.
- 동명·동일 상태 메뉴의 구분 코드를 행·버튼 접근성 이름·선택된 편집기에 표시한다.
- 이미지 단독 grant 목적을 기존 최소 매장 목록에 연결하고 공통 이미지 편집기에서도 기존 편집 중 이동 제한을 유지한다.
- Passed: 이미지 조회 Controller 8개, 목적별 매장 관리 15개, Runtime parity 1개,
  frontend unit 245개, boundary 10개, copy 11개, 영향 Storybook 46개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs, Spotless.
- 최초 동명 메뉴 Story의 조회 대기를 보완했고, 이전 실패 결과가 남은 MCP 세션을 재시작해 46개 모두 통과를 확인했다.
- Storybook Docs 108 entries/47 state surfaces도 Passed다. 원격 CI와 리뷰 해결은 대기 중이다.

### #169 부모 반영 검증

- 직접 미해결 리뷰는 없으며 #168까지의 수정 이력을 부모 merge로 반영했다.
- Passed: frontend unit 245개, boundary 10개, copy 11개, 최근 매장·마이페이지·라우트 Storybook 11개,
  typecheck, check:design, build-storybook, build, test:sites 4개, verify-docs.
- Backend 구현 변경은 없고 이 slice의 별도 backend 테스트는 Not run이다. 원격 CI는 대기 중이다.

## Surprises & Discoveries

이미지 삭제는 후속 #168에서 코드가 보완돼 있으므로 #161 자체의 회귀와 함께 확인한다.
기존 GET materialization은 Accepted 정책 여부를 먼저 확인하며 범용 worker로 일괄 교체하지 않는다.

## Decision Log

- 2026-09-12: 기능별 기존 PR에 보완 커밋을 추가하고 부모 변경을 merge한다. 기존 원격 이력 보존 목적.

## Outcomes & Retrospective

진행 중. 검증과 원격 반영은 아직 완료되지 않았다.

## Revision Notes

- 2026-09-12: 원격 리뷰 기준 실행 범위와 검증 경계를 기록했다.

### #170 포인트 조정 재진입 복구 (2026-09-12)

- 기존 조정 API의 멱등 키로 서버 준비 ID를 사용한다. 준비·현재 복구·취소/결과 확인만 추가하고 실제 포인트 반영은 기존 트랜잭션에서 수행한다. 액터별 미확인 기록 하나, immutable 본문, 계정 잠금, 결과 원자 저장으로 새 키 재실행을 차단한다.
- 화면은 진입 시 서버 기록을 먼저 확인한다. 대기/응답 유실/권한 철회/취소 경합을 명시적으로 처리하며 브라우저 저장소에 고객·본문·키를 저장하지 않는다. 성공 또는 취소 후 새 입력은 초기화한다.
- Identity→Loyalty public API 방향을 보존하도록 Loyalty 조회 port를 Identity의 최소 마스킹 projection이 구현한다. 구조 검증에서 발견한 역방향 의존을 제거했다.
- Passed: PostgreSQL 조정 17 + 준비/재진입/경합/원자 rollback 8 + runtime API parity 1 + Modulith 1 = 27개. 프론트엔드 단위 245개, typecheck, 디자인 검사, Storybook/앱 빌드, Sites 4개, Docs smoke 110개, 관련 MCP Story 30개(a11y 포함), 문서/OpenAPI 검사. 초기 타입·Story fixture/대기·구조 오류를 수정한 뒤 통과했다.
- Not run: 실제 운영 DB 변경, 배포. #170 원격 CI와 리뷰 해결은 push 후 확인한다.
- #160–#168의 33개 원본 리뷰는 해당 head의 terminal CI 후 간결한 수정 답변과 함께 해결했다. #169는 CI 완료(변경 없는 backend job은 SKIPPED), 원본 리뷰 없음.

### #171 업무별 대상 선택과 정책 명령 오류 (2026-09-12)

- 공용 매장 picker를 목적별 최소 목록으로 전환했다. 소속 추가·점주 계정 관리·이의 조회·환불은 각 기존 업무 권한만 사용한다. 소속 추가는 STORE_MEMBERSHIP_WRITE 전용 화면과 정확 계정 projection으로 기존 목록 READ·credential 관리 grant 없이 완료한다.
- 조회와 보안 Audit는 같은 트랜잭션이며 불필요한 인증/타 매장 소속을 조회하거나 반환하지 않는다. 새 DDL/production dependency 없음.
- 정책 KEY_REUSED/MANUAL_REVIEW_REQUIRED는 확정 오류로 안내하고 잠금을 해제한다. 기존 불명 결과와 REQUEST_IN_PROGRESS는 같은 요청을 유지한다.
- Passed: PostgreSQL 소속 8 + 최소 매장 권한/커서 19 + runtime parity 1 = 28개. 프론트엔드 단위 245개, typecheck, 디자인 검사, Storybook/앱 빌드, Sites 4개, Docs smoke 111개, 관련 MCP 77개 및 #170 재시도 보완 포함 총 102개(a11y 포함), 문서/OpenAPI 검사.
- #170 CI의 응답 대기 전 버튼 클릭 race를 2a002f4에서 보완했다. 활성화 이후 클릭하며 원래 payload/key 검증은 유지한다. 새 CI 결과를 확인한 뒤 리뷰를 해결한다.
- Not run: 운영 DB 변경, 배포. #171 원격 CI와 리뷰 해결은 push 후 확인한다.

#172는 동일 직렬 migration lane에서 V83_2(두 복구 조회 감사 action 등록)만 추가한다. 앞선 V82_1/V83_1과 공개된 V82/V83은 수정하지 않으며 이후 V84–V86을 재번호화하지 않는다.

### #172 복구 대상·감사·확정 오류 (2026-09-12)

- 복구 건/제안 목록은 조회 목적·상태 필터·건수만 같은 트랜잭션의 감사 기록에 남긴다. 감사 저장 실패는 503이며 목록을 반환하지 않는다.
- 서버 canPropose는 PAYMENT_CANCELLATION_SETUP + OPEN + resolution 미지정에만 true다. 이력을 조회하더라도 선택할 수 없으며 주문에서 연결 진입한 경우에도 현재 건을 조회해 생성 가능 여부를 확인한다.
- 응답 유실 후 같은 요청에 반환된 ORDER_STATE_CONFLICT/REPROCESSING_NOT_SAFE 및 EXPIRED/STALE 확정 오류는 잠금을 해제하고 최신 상태를 조회한다. 권한 상실로 결과가 불명인 경우에는 기존 요청을 유지한다.
- Passed: PostgreSQL 복구 19 + runtime parity 1 = 20개, frontend unit 245개, boundary 10개, copy 11개, typecheck, check:design, build-storybook, build, Sites 4개, MCP Story 36개(a11y 포함), Docs smoke 111개, 문서/OpenAPI 검사.
- Not run: 운영 DB 변경, 배포. 원격 CI와 리뷰 해결은 push 후 확인한다.

### #173 상담 대상 표시·선택과 잠금 순서 (2026-09-12)

- SUBJECT_SEARCH와 주문의 추가 ORDER_READ 조건을 projection·감사·표시에 공유했다. 현재 권한 철회 후 주문번호/매장명이 노출되지 않는다.
- 주문 후보 조회는 모든 grant를 Case보다 먼저 잠근다. 실제 PostgreSQL에서 쓰기가 CASE_WRITE grant를 보유한 동안 후보 조회의 대기를 확인한 뒤 같은 상담에 노트를 추가하고, 두 요청이 deadlock 없이 끝남을 검증했다.
- AVAILABLE 표시가 없는 대상을 본인확인·긴급 열람·정정·연결 해제·주문 조치·보상에서 새로 선택할 수 없게 했다. 기존 정정 대상이 표시 불가이면 다른 대상으로 자동 전환하지 않는다.
- INTERNAL_REQUESTER 계약 예외와 항상 반환하는 OperatorActor.display를 문서/생성 타입에 반영했다.
- Passed: PostgreSQL 주문/상담 11 + 운영자 목록 6 + runtime parity 1 = 18개, frontend unit 245개, boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, 관련 MCP 123개 및 정정 대상 회귀 1개 = 124개(a11y 포함), Docs smoke 113개, 문서/OpenAPI 검사. 표시 metadata가 빠진 기존 테스트 fixture와 실제 HTTP/null 응답 기대값을 보완했다.
- #170은 terminal CI 후 원본 리뷰를 해결했다(누적 34개). #171 전체 CI에서 유효 나노초 시각이 감사 전화번호 검출에 걸리는 문제를 재현하여 부모 #170에 국소 수정했다. 감사 12 + 준비/조정 8 = 20개 및 문서 검사 통과 후 #171·#172로 전파한다.
- Not run: 운영 DB 변경, 배포. 최신 원격 CI와 남은 리뷰 해결은 push 후 확인한다.

### #174 긴급 열람 중 업무 탐색 잠금 (2026-09-12)

- 상위 잠금에 busy 외에 원문 표시와 불명 열람을 포함한다. 원문을 지운 후 탐색은 다시 열리며 불명 열람은 현재 화면에서 상태를 확인한다. 개인정보 저장이나 API는 바꾸지 않았다.
- 기존 인증 만료 회귀 Story도 ID 입력 대신 요청 목록 선택으로 진입하도록 통합했다.
- Passed: PostgreSQL 주문 요청 12 + 보상 18 + 프로필 18 + 긴급 열람 8 + runtime parity 1 = 57개, frontend unit 245개, boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, MCP 149개(a11y 포함), Docs smoke 114개, 문서/OpenAPI 검사.
- Not run: 운영 DB 변경, 배포. 원격 CI 통과 후 해당 리뷰를 해결한다.

### #175 매장 동의·운영 조사 탐색 통합 (2026-09-12)

- 직접 미해결 리뷰는 없으며 부모의 최소 운영 승인안 조회·역할별 픽업·표시 권한·명령 유지 기능을 동의/조사 목록 선택과 함께 통합했다.
- Passed: 주문 동의/탐색 PostgreSQL 14 + 운영 조사 7 + runtime parity 1 = 22개, frontend unit 245개, boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, MCP 84개(a11y 포함), Docs smoke 115개, 문서/OpenAPI 검사.
- Not run: 운영 DB 변경, 배포. 최신 원격 CI는 push 후 확인한다.

### #176 보상 사고 선택 통합 (2026-09-12)

- 사고 목록·등록에 부모의 명령 유지 범위를 적용하고 표시할 수 없는 주문은 보상 대상으로 선택하지 않도록 통합했다. 비용 책임 미확정 Story도 사고 목록 선택으로 진입하며 0/0 분담 검증을 유지한다.
- Passed: PostgreSQL 보상 22 + runtime parity 1 = 23개, frontend unit 245개, boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, MCP 46개(a11y 포함), Docs smoke 116개, 문서/OpenAPI 검사.
- Not run: 운영 DB 변경, 배포. 최신 원격 CI는 push 후 확인한다.

### #177 처리 중 방문 이력 이동과 비용 주체 선택 통합 (2026-09-12)

- 기존 data router의 useBlocker로 처리 중·결과 불명의 정책 변경/비용 주체 등록을 현재 URL·패널에 유지한다. 결과 확인 후에는 이전 이동 시도를 폐기하고 새 이동을 허용한다. 추가 저장소나 dependency 없이 기존 본문·멱등 키를 재사용한다.
- 비용 주체 Picker와 서버의 조정 준비/재진입 복구를 함께 보존했다. 공통 정책·플랫폼 등록 명령에도 업무별 journal scope를 적용했다.
- Passed: 비용 주체 PostgreSQL 6 + 조정 준비 8 + runtime parity 1 = 15개, frontend unit 247개(두 업무의 back/forward/다른 경로 이동·동일 요청 replay 포함), boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, MCP 84개(a11y 포함), Docs smoke 118개, 문서/OpenAPI 검사. 기존 Credit Story의 기대 비용 주체를 실제 선택 후보 UUID와 일치시켰다.
- #171·#172는 최신 CI 완료·성공 후 7개 리뷰에 답변하고 해결했다(누적 41개).
- Not run: 운영 DB 변경, 배포. #177 원격 CI 통과 후 리뷰를 해결한다.

### #178 업무 선택·불명 요청 유지 통합 (2026-09-12)

- 부모의 journal·actor 범위와 자식의 불명 결과 유지, 상담 생성/개인정보 열람 상위 잠금을 통합했다. 새 상담 생성에 고정 업무 scope를 적용하고 기존 단위 테스트의 운영자 fixture를 실제 명령 계약에 맞췄다.
- #177 방문 이력 단위 테스트는 저장 결과 표시뿐 아니라 실제 잠금 해제도 기다리도록 보완(a578d9b)했다.
- Passed: frontend unit 250개, boundary 10개, copy 11개, typecheck, check:design, Storybook/앱 빌드, Sites 4개, MCP 206개(a11y 포함), Docs smoke 119개, 문서/OpenAPI 검사.
- #173·#174도 최신 CI 완료·성공 후 6개 리뷰에 답변하고 해결했다(누적 47개).
- Not run: 이 슬라이스의 서버 테스트(서버 변경 없음), 운영 DB 변경, 배포. 최신 원격 CI는 push 후 확인한다.
