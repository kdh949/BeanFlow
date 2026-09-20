# 고객센터 직접 처리 구현

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `2026-09-18`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

권한 있는 현재 상담 담당자가 고객·매장 인증 협조나 타인 승인 없이 모든 기존 지원 업무를 수행한다.

## Current State

baseline origin/main 3c56694. 별도 feature/support-direct-processing worktree를 사용한다.
기존 checkout의 미커밋 변경은 보존한다. 기존 Support는 세션 FK와 승인 분리 제약을 가진다.

## Definitions

SUPPORT_DIRECT는 실제 상담원 결정이며 LEGACY는 과거 정책의 실제 인증·승인 기록이다.

## Scope

### In Scope

Support 주문/해결/보상/프로필/원문/긴급 열람, Ordering 실행 검사, 인증 Provider 종료,
OpenAPI·생성 타입·고객센터/매장 진입 화면, PostgreSQL migration과 테스트.

### Non-goals

새 처리 엔진, 기존 권한 확대, 금융/정산 불변식 변경, 배포/commit/push.

## Business Rules and Invariants

SP-23과 ADR-136을 따른다. 대상 일치, 현재 권한/배정/활성 상태, hash/version,
금융 한도·중복 방지·감사 commit·unknown 복구는 유지한다.

## Architecture and Transaction Boundaries

기존 Support Application Service와 owner API를 재사용한다. 요청과 실행 때 현재 객체 인가를 재검사한다.
원문은 예약/감사 transaction, 외부 복호화, 현재 권한 재검사/result transaction 뒤 반환한다.
외부 금융 Provider는 기존 claim/call/result/recovery 경계로 유지한다.

## Alternatives Considered

자동 인증/승인 기록과 별도 엔진은 사실성·중복 불변식 문제로 제외했다.

## Failure Semantics

기존 미실행 요청은 409 재작성. raw 응답 유실은 자동 재조회하지 않는다.
비용 미확정과 Provider unknown을 성공이나 임의 부담으로 바꾸지 않는다.

## Data and Migration

최신 main 마지막 migration V90 확인. 현재 작업 inventory에서 다른 활성 BeanFlow writer 없음.
이 작업이 migration writer lane을 소유하며 완료된 원격 통합 전 다른 schema writer를 시작하지 않는다.
새 migration으로 nullable verification FK, LEGACY/SUPPORT_DIRECT, 직접 대상 binding과 시각을 추가한다.
기존 기록/제약은 direct에 필요한 부분만 조건부 확장한다.

## API and Event Contracts

기존 URL 유지. verificationSessionId→subjectLinkId, 프로필은 subjectId 연결 해석.
새 정책 버전/빈 승인 단계/현재 실행 명령을 제공하고 legacy 멱등 응답을 읽을 수 있어야 한다.

## Milestones

1. 정책/ADR/ExecPlan 기록.
2. 직접 인가/DB/서버와 legacy 실행 경계.
3. OpenAPI와 타입, Storybook 선행 UI 구현.
4. 정상/거부/금융/원문/전환 검증 및 diff 검토.

## Required Tests

무세션/무승인 동일 담당자 직접 처리, 권한·배정·연결·주문 소유자·종료 상담 거부,
만료·동시 실행·중복/한도·제조 경쟁·unknown, legacy 데이터와 금융 복구,
감사/복호화/권한 회수 실패, UI raw 제거와 응답 유실.

## Validation Commands

- `./gradlew test --tests 'io.github.kdh949.beanflow.support.*' --tests 'io.github.kdh949.beanflow.architecture.*' --tests 'io.github.kdh949.beanflow.schema.*' --tests '*CustomerCancellation*' --tests '*SupportPickup*' --tests '*OwnerProfile*' --console=plain`
- `./gradlew spotlessCheck test --tests '*CustomerCancellationCommandIntegrationTest' --tests '*SupportActionRequestIntegrationTest' --tests '*SupportOrderChangeExecutionIntegrationTest' --tests '*PostAcceptanceResolutionIntegrationTest' --tests '*SupportProfileChangeIntegrationTest' --tests '*SupportCompensationIntegrationTest' --tests '*SupportArchitectureTest' --tests '*OpenApi*' --console=plain`
- `./gradlew test --tests '*SupportProfileChangeIntegrationTest' --console=plain` (최종 감사 문구 검증)
- `./gradlew spotlessCheck`; `scripts/verify-docs.sh`; `git diff --check`
- frontend: `npm run typecheck`; `npm test`; `npm run check:design`; `npm run build-storybook`;
  `npm run test:storybook:docs`; `npm run build`; `npm run test:sites`.
- Storybook MCP `list-all-documentation`, `get-documentation`, `get-storybook-story-instructions`,
  `get-changed-stories`, `preview-stories`, `run-story-tests(a11y=true)`.

## Observability

기존 감사와 workflow 상태에 직접 처리 근거·actor·시각을 남긴다. PII를 로그/감사에 기록하지 않는다.

## Documentation Updates

SP-23, ADR-136, ADR-082/084/085/086/087/106 변경 조항과 관련 support 정책/runbook.

## Progress

- [x] 2026-09-18 baseline/정책 범위 확인, 결정 기록.
- [x] 서버/DB/API 직접 인가, V91, v2 정책, 인증 쓰기 API 410 구현.
- [x] 기존 패턴의 직접 처리/정보 보기/과거 기록 UI 구현.
- [x] PostgreSQL/금융/전환/구조/API/UI 검증과 최종 diff 검토 완료.

## Surprises & Discoveries

원 checkout에 다른 작업이 있어 최신 main의 별도 worktree에서 진행한다.
동시 revision 작성은 requester lock의 transaction이 평가와 저장을 모두 감싸야 한다.
등록 이후 대상 연결이 해제된 해결안은 첫 실행에서 다시 거부해야 한다.
대상이 여러 명이면 주문/보상/프로필/일반 및 긴급 열람 모두 명시적으로 선택한다.
기존 인증 API 성공 응답을 요구하던 문서 검증 계약도 종료 응답 410으로 변경한다.

## Decision Log

2026-09-18: 기존 권한/배정 유지, 원문 한 번 클릭, 등록→실행 유지 확정. ADR-136에 기록.

## Outcomes & Retrospective

권한 있는 담당 상담원이 활성 대상 연결을 선택해 주문 취소/픽업 변경, 제조 이후 해결,
포인트/쿠폰 보상, R1~R4 프로필 정정/복구, 일반/긴급 원문 열람을 직접 처리한다.
주문·보상·고위험 프로필은 기존 요청 등록→실행을 유지한다. 실제 인증 세션과 승인자는
생성하지 않는다. 보상 비용 미확정과 금융 결과 불명은 계속 명시적인 미완료 상태로 남는다.

최신 결과 기준 검증은 다음과 같다. 후속 재실행은 변경된 클래스/화면만 대상으로 했다.

- 서버: 64개 클래스, 중복 제외 364개 테스트 통과. 최초 범위 검증 361개 중 이전 매장 동의
  정책을 기대하던 테스트 1개를 수정했다. 마지막 관련 109개와 감사 보완 후 프로필 19개가 통과했다.
- PostgreSQL: V90→V91의 과거 세션/승인 보존, 신규 v2 정책, direct FK/TTL 제약,
  이전 미실행 요청 409, 과거 응답 역직렬화, 완료 실행 재생, legacy 금융 복구 확인.
- 무세션 동일 담당자의 취소/픽업/환불 해결/포인트/쿠폰/전화번호/R4 재설정 확인.
  실행 전 권한 철회·대상 연결 해제·동시 revision·중복 환불/지급·만료 후 재작성 검증.
- 인증 Provider bean 없이 PostgreSQL 애플리케이션 컨텍스트 시작, 종료 API 410,
  과거 조회와 미확인 challenge 복구 확인.
- Spring Modulith, ArchUnit, runtime OpenAPI parity, 현재 DB metadata/invariant 검증 통과.
- 프런트 단위 37개 파일/257개 테스트와 presentation/product-copy 테스트 통과.
  타입·디자인 규칙·Storybook 및 서비스 빌드·Sites 4개 테스트 통과.
- MCP interaction/a11y: 변경 및 관련 181개 story의 최신 결과 모두 통과.
  한 번 클릭 원문 열람, 다중 대상 명시 선택, 권한 없음/만료/실패/응답 유실/화면 이탈을 포함한다.
- Storybook Docs: 122개 문서 항목, 상태별 문서 15개/화면 47개 통과.
  정적 문서의 notification-summary 요청 ENOENT 로그와 기존 chunk 크기 경고는 비실패 경고로 남았다.
- 문서: 18개 검증 테스트, OpenAPI semantic 109개 operation/102개 schema,
  target 258 paths/292 operations 및 runtime 248 paths/282 operations 검증 통과.
- 원 checkout의 사용자 변경을 보존하고 별도 worktree에서 diff를 검토했다.
  기존 migration은 수정하지 않았고 V91만 추가했다. 운영 원문/secret/불필요한 생성물은 추가하지 않았다.

전체 저장소의 비관련 테스트 전체 실행, 실제 운영 환경 시작, 실 Provider 호출과 배포는 **Not run**이다.
이미지 기반 visual regression은 **Not configured**이며 interaction/a11y와 Docs 검증을 대체하지 않는다.
위 검증은 로컬 결과이며 원격 CI와 구분한다. 운영 migration과 배포는 실행하지 않았다.
운영 반영은 cutover 문서의 쓰기 중단과
DB→서버→프런트 동시 전환 절차를 따른다.

## Revision Notes

2026-09-18 초기 작성 및 구현·검증 완료. 로컬 완료이며 운영 배포와 구분한다.
