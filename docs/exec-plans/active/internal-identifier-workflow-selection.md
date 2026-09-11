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
6. 신규/기존 사고 선택과 기존 duplicate-benefit 경계, 비용 주체 및 불투명 참조 선택의 출처 명시.
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
- S5 사고·비용 참조: 실제 사고 선택/등록과 명명된 비용·등록 참조 선택.
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
- [ ] S3 상담 대상·담당자.
- [ ] S4 요청 재개·점주 동의.
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
S3a 담당자 디렉터리와 다섯 배정/필터 화면을 구현했다. 실제 PostgreSQL에서 새 디렉터리 5개, 기존 상담/인증/구조/계약 20개와 HTTP 인증 9개 테스트가 통과했다. frontend typecheck, unit 233개, presentation 10개, copy 11개와 design 검증이 통과했다. S3b 상담 대상, S4~S5와 전체 마무리 검증은 아직 수행하지 않았다.

## Revision Notes

- 2026-09-11: 최초 작성.
- 2026-09-11: S1 매장/소속 선택, 현재 표시 projection, 정책 대상 잠금과 검증 결과 기록.
