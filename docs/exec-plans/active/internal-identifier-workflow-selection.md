# 내부 식별자 입력 없이 운영과 상담 업무 이어가기

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

운영자와 상담원이 내부 UUID를 미리 찾거나 서로 전달하지 않고, 실제 고객·매장·주문·담당자와
업무 요청을 검색하고 선택하여 기존 명령을 수행한다. ID 자체를 없애는 것이 아니라 화면이 연결을 소유한다.

## Current State

기준은 #170 `feature/customer-point-selection`의 `716f9421c19bce255cd26cacc7f1a6dac095a61a`다.
두 커밋은 고객 login ID 정확 검색, 마스킹된 후보 선택, 고객→PointAccount 연결과 기존 포인트 명령을
구현했고 최신 head CI가 모두 통과했다. 해당 기능은 재구현하지 않는다.
선행 감사는 #169에서 ID 직접 입력 28개(로그인 ID 제외), 21개 컴포넌트를 확인했다. #170은 이 중
PointAccount 입력을 해결했다. 비용 주체 입력과 다른 운영·상담 업무는 남아 있다.
S1은 `feature/operations-target-selection`의 #171이며 S2는 그 head를 잇는
`feature/order-recovery-selection`이다. 기존 힙 덤프는 작업 범위 밖이다.

## Definitions

- 선택 대상: 서버가 현재 권한으로 반환한 업무 객체와 사람이 구분할 수 있는 표시 정보.
- 요청 목록: 기존 영속 요청의 실제 상태를 읽는 탐색 수단. 실행 권한을 부여하는 목록이 아니다.
- 사고: 동일 사고에 대한 lifetime terminal benefit 한 번이라는 기존 보상 제한의 단위.
- 불투명 참조: 외부/비용 책임 주체를 연결하는 값. DB UUID 및 실제 계좌번호·비밀키와 구분한다.

## Scope

### In Scope

1. 기존 매장 이름 검색을 운영 환불·이의제기·점주 발급·포인트 정책에 재사용.
2. 공개 주문번호로 주문 보상/후속 처리 대상을 확인하고 내부 주문 ID 자동 연결.
3. 현재 담당자 선택, 상담 대상 검색/연결, ID 중심 선택 항목의 의미 있는 표시.
4. 기존 본인확인/열람/주문 변경/보상/정정/긴급 요청 목록 및 별도 승인 재개.
5. 점주 요청 목록과 동의 목록을 상담 요청에 연결하고 수동 ID 전달 제거.
6. 신규/기존 사고 선택과 기존 duplicate-benefit 경계, 비용 주체 선택 및 외부 업무 코드의 출처 명시.
7. 관련 계약·권한·감사·테스트·Storybook·수직 커밋과 PR.

### Non-goals

#170 고객 검색/PointAccount 연결 재구현, 금융 한도/승인 규칙 완화, 임의 ID/이름 추정,
실거래·운영 데이터 변경, merge/deploy, 새 production dependency.

## Business Rules and Invariants

BR-25/26/30/38/39/46/54/56/57, SP-17~21과 ADR-066/069/084/085/086/106/127/128을 따른다.
선택 후보는 현재 권한을 다시 확인하고 실제 명령은 기존 owner service의 검증을 유지한다.
연결 대상·명령 payload·멱등 키는 불명 결과 동안 고정한다. PII는 허용된 최소 마스킹이며 영구 브라우저
저장을 추가하지 않는다. 검색 또는 감사 실패를 정상 empty/0/이름 미확인 fallback으로 처리하지 않는다.

## Architecture and Transaction Boundaries

프론트엔드는 기존 canonical field/button/selection/state를 조합한다. 신규 목록은 owner-local
Query Repository/DTO Projection과 public port를 사용한다. Controller는 Service만 호출한다.
목록/표시 조회가 쓰기 Aggregate의 그래프를 확장하지 않는다. 감사/권한 검사는 같은 local transaction,
외부 통신은 긴 DB transaction 안에 넣지 않는다. 기존 명령의 lock order/version/승인 consumption을 유지한다.

## Alternatives Considered

ID 설명문만 보강하면 운영자가 ID를 찾는 일이 남는다. UUID를 브라우저에 저장하면 오래된 대상/권한이
섞인다. 기존 목록을 재사용하고 부족한 최소 조회만 소유 모듈에 추가한다. 등록되지 않은 담당자 이름이나
비용 주체를 UUID에서 유추하지 않는다.

## Failure Semantics

400 입력/커서 오류, 401 미인증, 403 현재 권한 부족, 404 실제 미존재, 409 현재 상태/버전/연결 충돌,
503 저장소·감사 실패를 구분한다. 검색 재실행/선택 변경의 늦은 응답은 버린다. 명령 실패와 재조회 실패를
구분하고 pending/unknown 상태에서 대상 변경이나 새 key 생성으로 재실행하지 않는다.

## Data and Migration

기준은 V83까지다. ADR-128의 수동 직렬 lane을 사용하며 새 migration은 해당 수직 슬라이스가 실제로
필요할 때만 생성한다. 기존 V82/V83 및 다른 checkout을 수정하지 않는다. main은 V81, #167은 V82,
#170은 V83이고 열려 있는 다른 PR #125에는 migration이 없음을 확인했다. 새 writer 출현 시 재확인한다.

## API and Event Contracts

기존 `/operations/stores`, 고객/점주 공개 주문번호, Support owner 조회와 typed 명령을 우선한다.
새 API는 목록/선택/현재 권한 또는 필요한 사고 등록에 한정한다. 구체 계약은 각 milestone 전에
owner source 및 ADR과 함께 확정한다. Target/runtime OpenAPI와 생성 TypeScript를 같은 슬라이스에 반영한다.
기존 이벤트와 금융 명령 계약을 우회하지 않는다.

## Milestones

- S1 매장 선택: 공유 picker, 환불/이의제기/계정 발급/정책 진입과 각 story.
- S2 주문·복구 선택: 공개 주문번호 조회, 복구 제안 목록/검토와 기존 실행 연결.
- S3 상담 대상·담당자: 현재 담당자 디렉터리/적격 선택, 대상 검색/표시/연결.
- S4 요청 재개·점주 동의: Case별 요청, 승인 대기와 store-scoped 요청/동의 목록.
- S5 사고·비용 참조: 실제 사고 선택/등록, 명명된 비용 주체 선택과 외부 코드 입력 안내.
- S6 전체 재감사, 관련 테스트·빌드·문서, PR별 최신 head CI 및 미해결 항목 0 확인.

각 milestone은 업무별 수직 커밋을 만들고, 의존하는 다음 PR은 검증된 직전 head를 base로 한다.

## Required Tests

목록→대상 선택→실제 명령→재조회, empty/loading/권한/실패/긴 문구/모바일/키보드,
검색/페이지 전환/늦은 응답, 불명 명령 대상 잠금, 교차 actor/store/Case cursor,
현재 grant 철회/별도 승인, API runtime parity, PostgreSQL rollback·중복·동시성, Modulith.

## Validation Commands

frontend: `npm run typecheck`, `npm test`, `npm run check:design`, `npm run build`, `npm run test:sites`,
`npm run build-storybook`, `npm run test:storybook:docs`, 실제 dev MCP `run-story-tests(a11y=true)`.
backend: JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-26.jdk/Contents/Home ./gradlew
'-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g' spotlessCheck test (해당 테스트 필터부터).
`scripts/verify-docs.sh`, `git diff --check`, PR head checks.

## Observability

기존 HTTP error/correlation과 owner Audit을 유지한다. 새 민감 조회는 PII-free 감사로 목적/결과를 남긴다.
고객/담당자 이름, 검색 원문, ID를 metric label에 넣지 않는다.

## Documentation Updates

BR-57/ADR-128 및 관련 owner ADR amendment, OpenAPI와 본 계획, 최종 ID 입력 대응표.

## Progress

- [x] #170 두 커밋·clean worktree·최신 CI 및 제외 범위 확인.
- [x] 기준 head에서 별도 후속 브랜치 생성. 기존 힙 덤프 보존.
- [x] 이전 28개 ID 입력 목록과 실제 route/자동 전달 대안 확인.
- [x] S1 매장 선택 구현: 4개 ID 입구를 이름 선택으로 전환하고 정책·소속 목록 표시 정보를 owner 조회로 연결.
- [x] 소속 추가의 수동 account ID 경로 제거 및 정책 응답 유실 시 대상/payload/key 고정.
- [x] S1 PR #171 발행. CI에서 드러난 정책 HTTP fixture의 매장명 누락을 d444e63으로 보완하고 관련 테스트 통과.
- [x] S1 d444e63 최신 head CI 전부 통과.
- [x] S2 공개 주문번호 조회, 복구 대상/제안 목록, 기존 명령 연결과 불명 대상 잠금 구현.
- [x] S2 최종 빌드/Docs/MCP 응답 확인.
- [x] S2 PR #172 발행 (11c9c6c), #171 d444e63을 base로 유지.
- [x] S2 11c9c6c 최신 head CI 전부 통과.
- [x] S3 상담 대상·담당자 구현과 관련 backend/frontend/문서 검증.
- [x] S3 전체 MCP 632개 정상 응답 통과, PR #173 발행.
- [x] S3 PR #173 3a1dbe1 최신 head CI 전부 통과.
- [x] S4a 기존 업무 요청/Case 목록 재개 구현, 관련 backend/frontend/문서 검증 통과.
- [x] S4b 점주 동의·운영 조사 선택 및 관련 검증.
- [ ] S5 사고·비용 참조.
- [ ] S6 전체 검증과 PR 최신 head CI.

## Surprises & Discoveries

운영 담당자는 현재 UUID와 grant만 저장되며 이름 디렉터리는 없다. 비용/외부 참조도 사람이 선택할
표시 이름과 등록 출처가 없는 경우가 있어 label 교체만으로 완료할 수 없다. 해당 단계에서 실제 출처를
먼저 정의한다. Support profile과 가입 CustomerAccount는 추론 연결하지 않는다.

## Decision Log

- 2026-09-11: #170은 제외하고 나머지 ID 입력 업무를 수정/구현하는 명시적 후속 요청.
- 2026-09-11: #170 head를 보존하는 직렬 후속 PR들로 진행. 불명/인가/금융 정책 유지.

## Outcomes & Retrospective

S1 구현 완료, PR #171 최신 head CI 전부 통과. backend 4개 클래스의 기존 19개 테스트와 추가한 이름 변경/누락 경로
1개 테스트가 통과했다. frontend typecheck, unit 233개, presentation 10개, copy 11개, design,
product build, Sites 4개, Storybook build, docs/OpenAPI 검증이 통과했다. 전체 dev MCP 613개 중
612개가 통과했고 제거한 ID field를 기대한 부모 story 1개를 수정하여 focused 재검증을 통과했다.
별도 static Docs Chromium 검증도 111개 entry, 15개 상태 문서, 47개 상태 surface에서 통과했다. #170 고객 검색/PointAccount 파일은 수정하지 않았다.
S2는 공개 주문번호 조회와 복구 대상/제안 목록을 구현했다. PaymentSetupRepairIntegrationTest 17개,
OperatorCompensationControllerTest 2개, RuntimeOpenApiParityTest 1개, ModularityTests 1개가 통과했다.
권한 철회 fixture에 revoked_at을 누락한 초기 테스트 실패는 DB 제약에 맞게 수정하고 재검증했다.
frontend typecheck, unit 233개, presentation 10개, copy 11개, design과 제품/Sites/Storybook build가 통과했다.
전체 MCP 실행의 616개 interaction/a11y 테스트가 통과했고 도구 응답도 정상 반환되었다.
첫 전체 실행은 결과 직렬화 중 Node heap 부족으로 중단되어 dev server를 8 GiB heap으로 재시작했다.
정적 Docs의 제거된 ID 입력 표식을 주문번호로 갱신했다. 최신 빌드의 111개 문서,
15개 상태 문서, 47개 상태 surface와 제품 build/Sites 4개 검증이 통과했다.
S3는 서명된 조직 로그인 이름의 표시 모델과 목적별 현재 grant 후보를 구현한다. V84는 담당자 표시 조회 모델만 추가한다.
S3a 담당자 디렉터리와 다섯 배정/필터 화면을 구현했다. 실제 PostgreSQL에서 새 디렉터리 5개, 기존 상담/인증/구조/계약 20개와 HTTP 인증 9개 테스트가 통과했다. frontend typecheck, unit 233개, presentation 10개, copy 11개와 design 검증이 통과했다. S3b는 정확 검색 후보/공개 주문번호 선택과 owner의 마스킹 표시 정보를 접수/대상 연결 및 5개 업무 선택 항목에 연결했다.
반복 조회의 Audit unique source 충돌을 별도 조회 source로 수정했다. 최종 관련 backend 32개가 통과했고,
frontend unit 233개, presentation 10개, copy 11개, design, 제품/Sites/Storybook build와 docs/OpenAPI가 통과했다.
정적 Docs 113개 entry, 15개 상태 문서, 47개 surface가 통과했다. focused MCP 9개가 정상 응답으로 통과했다.
전체 MCP runner 632개는 통과했지만 결과 전달 중 Node 8 GiB heap 부족으로 응답 연결이 끊어졌다.
서버를 복구하고 공식 로컬 사용 통계 제외 옵션으로 재실행하여 전체 632개가 정상 MCP 응답으로 통과했다. 제품 설정/검증 기준은 변경하지 않았다. PR #173은 #172를 base로 두고 최신 CI를 확인한다.
S4는 기존 inspection 권한을 재사용한 bounded 업무 요청 탐색을 구현한다. 개별 조회 transaction을 유지하고 원문 열람/명령을 실행하지 않는다. S4~S5와 전체 마무리 검증은 진행 중이다.

## Revision Notes

- 2026-09-11: 최초 작성.
- 2026-09-11: S1 매장/소속 선택, 현재 표시 projection, 정책 대상 잠금과 검증 결과 기록.

S4a는 6종의 기존 업무 요청 목록과 Case 분류/접수 시각 표시를 구현했다. 관련 73개 backend 테스트 중 72개가 통과했고, 검증 fixture에 실제 VERIFIED challenge 두 개를 보완하여 나머지 본인확인 조회 테스트도 통과했다. 종료된 Case와 연결 해제된 열람 요청은 재개 후보에서 제외하며 최신 경로를 재검증한다. frontend unit 233개, presentation 10개, copy 11개, design, 제품/Sites 4개/Storybook build, 문서·OpenAPI 검증이 통과했다. 정적 Docs 114개 entry/15개 상태 문서/47개 surface가 통과했다. 전체 MCP runner 646개는 통과했으며 전체 결과 직렬화 중 Node heap 부족으로 도구 연결이 종료되어, 서버를 복구하고 모든 story를 작은 묶음으로 나누어 정상 응답을 확인한다. 검증 대상과 a11y 기준은 줄이지 않는다.

S4a 최신 본인확인/열람 디렉터리 20개 테스트가 통과했고, 종료 시각 제약에 맞춘 fixture를 포함한 주문/본인확인 11개 재실행도 통과했다. 개인정보·증명 lifetime 단위 테스트는 기존 보안 검증을 유지한 채 선택 목록에 맞게 갱신했다. MCP 전체 646개 runner 통과와 focused 정상 응답을 확인했으며 전체 결과 전달의 메모리 문제는 별도 검증 환경 한계로 남기고 최종 단계에서 묶음 재실행한다.

S4b는 SupportOrderDiscovery와 OperationsSupportInvestigationDirectory, owner 공개 projection, StoreSupportOrderChangeWorkspace/SupportOrderActionWorkspace/OperationsSupportInvestigationPage 및 OpenAPI를 변경한다. 후보→상세→기존 명령, 교차 매장/actor cursor, 만료·회수·소비·재배정·승인안 변경, 불명 대상 잠금을 검증한다. 직접 ID 설명만 바꾸는 대안 대신 명시적 조회를 추가하며 부작용은 bounded 추가 DB 조회다. ADR-128에 경계와 실패 정책을 기록했다.

S4a 전체 646개는 10개 순차 배치의 정상 MCP 응답으로 모두 통과하여 결과 전달 한계를 해소했다. PR #174를 발행했다. S4b 관련 PostgreSQL/API/구조 22개 테스트가 통과했다. 빈 후보 목록의 owner batch 사전조건, 만료 fixture의 시각 제약, runtime path reference 형식을 검증에서 보완했다. 정적 Docs 115개 entry/15개 상태 문서/47개 surface도 통과했다. 전체 MCP는 부모 매장 관리 story의 옛 ID 입력 기대를 새 목록으로 수정하고 후속 배치를 검증한다.

S4b 전체 657개 정식 story의 interaction/a11y가 10개 순차 배치에서 정상 MCP 응답으로 모두 통과했다. 부모 매장 관리 story의 제거된 ID 입력 기대도 목록 상태로 갱신했다. 전체 frontend typecheck, unit 233개, presentation 10개, copy 11개, design, 제품 build/Sites 4개/Storybook build, docs/OpenAPI가 통과했다. 현행 API는 232 paths/265 operations다.

S4b e66c87d를 #175로 발행했다. S5a는 SupportCompensationIncidentDirectory/Registry,
V85, 기존 보상 평가 binding, SupportCompensationIncidentPicker/Workspace와 계약을 변경한다.
Controller→Application Service→Support 소유 등록/조회 모델, 현재 권한·Case/session lock과
기존 advisory idempotency lock을 사용한다. 외부 호출이나 금융 쓰기는 추가하지 않는다.
새 UUID만 매번 생성하는 대안은 같은 사고 재검토를 잃으므로 기존 사고 목록과 영속 등록을 선택한다.
일반 field/button/checkbox/state는 REUSE, 사고 선택·등록은 COMPOSE, 기존 보상 화면은 EXTEND다.
관련 route는 /support/follow-up이다. 입력·불명 명령 동안 주문/본인확인/사고 선택을 잠근다.
테스트는 등록 replay/다른 payload/감사 rollback/권한·검증·연결 철회/교차 cursor/기지급 사고와
기존 한도·승인, UI 등록→평가/기존 선택/empty/loading/error/unknown/키보드/a11y를 다룬다.
2026-09-11 열린 PR inventory에 새 독립 migration writer가 없고 현재 직렬 head는 V84다.

S5의 외부 정산·배달업체 참조는 ADR-087의 불투명 업무 코드로 DB ID와 다르다. 초기 계획의
일반 등록 참조 catalog를 구현하면 승인 전 원문을 저장하지 않는 경계와 provider credential
storage 비범위에 충돌한다. 따라서 해당 코드는 입력 출처·형식·금지 원문을 명시하고 기존 R3
해시/재입력/승인·owner 암호화 정책을 유지한다. 이 세 항목을 DB ID 누락으로 집계하지 않는다.

S5a는 사고 등록·기존 사고 선택 및 등록된 사고의 고객/주문 재검증을 구현했다. V85는 불변 사고와
멱등 등록 identity 및 새 Audit action 분류를 추가한다. 초기 검증에서 누락된 감사 action FK 등록을
발견해 같은 미게시 migration에 보완했으며 최종 실제 PostgreSQL 보상 22개, runtime parity 1개,
Modulith 1개가 통과했다. 동시 동일 등록의 같은 응답·사고/Audit 1개, 다른 payload 충돌, legacy
사고 유지, 기지급 사고의 추가 지급 거부, 교차 주문·커서 및 철회, 감사 실패 전체 rollback을 검증했다.
frontend typecheck, unit 233개, presentation 10개, copy 11개, design, 제품/Sites 4개/Storybook build,
정식 MCP 전체 664개, 정적 Docs 116개 entry/15개 상태 문서/47개 surface가 통과했다.
PR #174 최신 head CI가 전부 통과했다. #175 CI는 진행 중이다. main은 a6199c6/V81로 변동 없음을
원격과 로컬에서 재확인했다. S5b 비용 주체 선택 및 S6 최종 감사가 남아 있다.

S5a 59773c3를 #176으로 발행했다. S5b는 Operations PointCostIssuerDirectory/PlatformCostOwner 등록,
V86, PointCostIssuerPicker/PlatformCostOwnerWorkspace, 전역·매장 정책 및 PointAccount의 비용 입력만
변경한다. #170 고객 검색/계정 연결은 유지한다. 플랫폼 이름 입력은 비용 주체 등록 업무이며 사용자에게
DB ID를 요구하지 않는다. REUSE: 기존 field/button/notice/tabs; COMPOSE: 비용 선택과 등록;
EXTEND: 세 소비 화면 및 /ops/policies의 비용 주체 메뉴. 새 dependency/token은 추가하지 않는다.
플랫폼 등록과 감사는 짧은 local transaction, Merchant 표시 조회는 public port로 읽는다. 금융 명령의
기존 멱등/버전/비용 snapshot은 유지한다. 전역 정책 변경의 불명 결과에서도 대상·내용·key를 고정한다.
명부 없이 이름을 추정하는 대안과 외부/비용 참조를 임의 플랫폼 기본값으로 바꾸는 대안은 제외한다.
새 source와 legacy 유지 차이를 ADR-066에 먼저 기록했다. 새 DDL은 동일 수동 직렬 lane의 V86이다.
검증은 이름/실제 owner 매핑, 목적별 권한·교차 cursor·archived 브랜드, 중복 등록·다른 payload·Audit
rollback, 세 화면의 실제 요청 body, unknown 잠금, 기존 고객 선택 회귀와 전체 프론트/문서/API/구조다.

S5b는 이름 기반 매장·브랜드·플랫폼 비용 주체 선택과 불변 플랫폼 이름 등록을 구현했다.
목적별 현재 권한, cursor 결합, archived 브랜드, 이름 정규화 중복·동시 요청, 멱등 replay와
Audit 실패 rollback을 실제 PostgreSQL에서 검증했다. 신규 통합 6개, runtime parity 1개,
Modulith 1개와 Spotless가 통과했다. 초기 새 테스트의 JsonNode map 호출을 배열 순회로 고쳤다.
frontend typecheck, unit 233개, presentation 10개, copy 11개, design, 제품/Sites 4개/Storybook build,
정식 MCP 전체 676개와 정적 Docs 118개 entry/15개 상태 문서/47개 surface, 문서·OpenAPI가 통과했다.
각 MCP 배치의 요청 story ID가 정상 통과 응답에 모두 포함되는지도 대조했다. 초기 focused 도구가
직전 결과를 반환해 해당 결과는 근거에서 제외하고 전체 배치로 재검증했다. #175 CI는 전부 통과했다.
#176 backend 전체는 통과했으나 동의 갱신 중 버튼을 너무 일찍 찾는 부모 story를 587df6d에서
수정했다. S6에서 공통 명령의 불명 결과 보존, 부모 선택 잠금과 외부 업무 코드 설명을 마무리한다.

S5b 4b3355a를 #177로 발행했다. S6는 useSupportCommand와 SupportWorkspace/DataAccess,
StorePointPolicyDirectory/OperationsPolicyPage의 부모 선택 잠금, ProfileFields 업무 코드 안내를
변경한다. REUSE: 기존 field/button/notice; EXTEND: 상위 workspace busy 전달과 재시도 보존.
원문 열람은 재실행하지 않으며 현재 원문 삭제·만료 정책을 유지한다. 최초 불명 후 403/409 응답은
최초 요청의 rollback 증거가 아니므로 기존 key/payload를 유지한다. 데이터 소유권·transaction·
API/DB 변경 없이 화면 상태만 보완한다. 대상 변경을 허용하는 대안은 미확정 요청을 잃으므로 제외한다.
의미 있는 hook 회귀와 부모 화면 interaction/a11y, 기존 전체 스토리·문서 검증을 수행한다.

S6 코드와 입력 재감사를 완료했다. 공통 명령의 최초 불명 상태 보존, 상담 접수/연결의 같은 요청 재시도,
열람·보상·본인확인과 상위 상담 선택 잠금, 매장 정책 변경 중 상위 정책 탭 잠금을 검증했다.
외부 업무 코드 3개에 발급처와 형식 설명을 연결했다. 관련 hook 회귀 3개를 포함한 unit 236개,
presentation 10개, copy 11개, typecheck, design, 제품/Sites 4개/Storybook build가 통과했다.
전체 683개 정식 story의 interaction/a11y가 요청 ID와 정상 MCP 통과 응답 대조까지 통과했고,
정적 Docs 119개 entry/15개 상태 문서/47개 surface가 통과했다. 102개 production TSX의 입력 선언
230개와 동적 profile fields를 재대조했다. 남은 ID 입력 라벨은 로그인 이름 3개다. 업무별 28개 대조는
`docs/testing/internal-identifier-workflow-coverage.md`에 기록했다. 원격 각 PR 최신 head CI는 확인 중이다.

추가 시각 검증에서 비용 주체·외부 업무 코드·상담 열람 대기 화면을 각각 320/768/1440px로 확인했다.
9개 화면 모두 document 가로 넘침 0, 입력·버튼·label·legend·안내문 최소 14px였다. 다만 320px
비용 주체 직접 주소 진입에서 선택된 세 번째 탭의 이름 일부가 수평 목록 밖에 가려졌다. 공통 Tabs의
TabList를 EXTEND하여 활성 탭의 가로 위치만 보정한다. 새 스타일·토큰·dependency·API는 없으며
수동 키보드 활성화와 disabled 상태를 유지한다. 긴 목록 초기 선택 story와 좁은 실제 소비 화면으로
검증한다. 페이지 전체를 스크롤하는 대안은 피하고 탭 목록의 scrollLeft만 조정한다.

탭 가림을 긴 목록 story의 실제 bounding box 실패로 재현했다. 수정 후 기존 수동/자동 키보드 선택을
포함한 focused 5개와 전체 MCP 684개, typecheck, unit 236개/presentation 10개/copy 11개,
design, 제품/Sites 4개/Storybook build가 통과했다. 9개 Chromium viewport에서 가로 넘침 0,
선택 탭 가림 0, control·안내문 최소 14px를 확인했다. 정적 Docs 재검증은 진행 중이다.
#176(587df6d)과 #177(4b3355a)의 최신 head CI가 모두 통과했다.
