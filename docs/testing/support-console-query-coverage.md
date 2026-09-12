# 고객센터 조회 기능 선별 구현 대조표

## 비교 기준

- 기존 콘솔 제안: PR #125 `c2efc8feaf7b7ff4441c74a08c2f5c0d50d0b9c1`.
- 선별 구현 시작점: PR #180 `d2213c1f9405491ba99cc3395b1a8ca4ee009a2e`.
- 현재 상담/주문/보상/정보 정정 명령, 공용 ConsoleFrame, 내장 고객 문의와 #170 고객 포인트 선택을 유지한다.
- 아래는 endpoint 이름의 일치가 아닌 실제 조회·선택·후속 처리 동작의 비교다.

## 원래 9개 GET 계약의 처리

| #125 조회 | 현재 처리 | 근거 |
| --- | --- | --- |
| `/support/case-queue/summary` | S1 추가. 현재 담당자의 접수·진행·대기만 집계 | `SupportCaseQueueController`, `SupportCaseQueryRepository.summary`, `SupportCaseQueueSummary` |
| `/support/case-queue` | 기존 `/support/cases` 확장. 분류·우선순위·내 담당·상태·담당자와 cursor | `SupportCaseApplicationService.list`, `SupportCaseDirectoryPage` |
| `/support/cases/{caseId}/overview` | 기존 Case 상세·대상 표시·업무 링크와 S2 연결 주문 탭으로 조합 | `SupportCaseManagementPage`, `SupportSubjectSelection`, `SupportLinkedOrderSummary` |
| `/support/orders/{orderId}/overview` | S2 추가. 현재 담당 상담/활성 ORDER 링크/주문 조회 grant 전후 재검증 | `SupportLinkedOrderOverviewService`, `OrderingSupportTimelineQueryService.findOrderOverviews` |
| `/support/approval-tasks` | S3 추가. 다섯 종류의 기존 객체 권한과 현재 검토 판정을 사용 | `SupportApprovalDirectoryService`, `SupportApprovalInboxPage` |
| `/support/approval-tasks/{taskType}/{resourceId}` | 현재 종류별 상세로 연결. 승인·실행 화면을 중복하지 않는다 | `/support/data-access/:grantId`, `/support/break-glass/:requestId`, `/support/action-requests/:requestId`, `/support/compensations/:compensationRequestId`, `/support/profile-changes/:profileChangeId` |
| `/support/approval-tasks/{taskType}/{resourceId}/timeline` | S3 `/support/approval-tasks/{kind}/{requestId}/history`로 구현. 해당 요청의 결정만 actor-bound cursor로 조회 | `SupportApprovalDirectoryQuery.history`, `ApprovalHistory` |
| `/support/compensations` | #174의 `/support/work-items?kind=COMPENSATION` 및 기존 상세로 충족 | `SupportWorkPicker`, `SupportCompensationWorkspace` |
| `/support/profile-changes` | #174의 `/support/work-items?kind=PROFILE_CHANGE` 및 기존 상세로 충족 | `SupportWorkPicker`, `SupportProfileChangeWorkspace` |

## 가져오지 않은 중복과 의미 변경

- #125의 별도 Shell, 라우트 대체, 버튼/상태 표현, 대상 프로필 중복 조회를 가져오지 않는다. 현재 canonical 컴포넌트·토큰과 masked 표시 계약을 유지한다.
- 상담 목록은 기존 안정적인 `openedAt + caseId` 정렬을 유지한다. 최신 활동 정렬과 주대상 프로필을 매 행마다 다시 조합하는 병렬 목록은 추가하지 않는다.
- 기존 #125의 진행 중 집계는 CLOSED만 제외하지만 S1은 도메인의 terminal RESOLVED도 제외한다.
- 주문 `paidAt`에서 결제 상태를 추측하는 표현을 가져오지 않는다. 주문 당시 품목·금액과 현재 주문 상태를 표시하고 결제·환불 진행은 기존 이력으로 연결한다.
- 승인함의 주문 변경 링크는 주문 ID가 아니라 실제 action request ID를 전달한다. 이력도 해당 요청의 정확한 approval request에 연결하므로 다른 주문 업무나 revision을 혼합하지 않는다.
- 승인 목록 조회는 명령 성공 보장이 아니다. 기존 workflow의 만료/권한 철회 수렴과 감사 기록은 유지한다. 새 승인·지급·정정 명령은 없다.
- 전체 요청도 기존 객체 권한 범위를 넓히지 않는다. 담당/연결/Case 상태 또는 grant가 바뀌어 기존 상세를 볼 수 없으면 이력 조회도 거절된다.

## 검증 결과

S1: backend 14 tests; 관련 MCP 11 stories; static Docs 120 entries. PR #181 `fb092f6` 원격 required CI 통과.
S2: backend 15 tests; 관련 MCP 19 stories; static Docs 121 entries. PR #182 `561eb06` 원격 required CI 통과.
S3: backend 7 suites 70 tests 통과. 최초 3개 fixture/기대값 실패를 보완하고 해당 suites 29 tests를 재실행해 통과했다. PR #183 `aff73c2` 원격 required CI 통과.

공통 frontend unit 236/presentation 10/product-copy 11, typecheck/design/product/Storybook build/Sites, 문서/OpenAPI 검증을 실행했다. 최종 등록 707개 Storybook story를 정확한 ID별로 대조해 MCP test/a11y 통과를 확인했다. Static Docs 122 entries/15 stateful docs/47 state surfaces 통과. 세 슬라이스의 주요 화면을 320/768/1440px로 검사한 15개 viewport에서 문서 가로 넘침이 없고 검사한 control/label/p 최소 14px였다. 단계별 결과는 각 구현 시점에 해당한다.

실서비스 배포·live 인증 E2E·운영 DB·원문 reveal·실제 금융 명령은 Not run. 구조/API/Storybook 검증을 운영 완료로 해석하지 않는다. 자동 visual regression baseline은 Not configured.

## PR 단위와 의존 관계

| 단위 | PR | 구현 커밋 | 직전 기준 |
| --- | --- | --- | --- |
| 내 상담 현황·목록 필터 | [#181](https://github.com/kdh949/BeanFlow/pull/181) | `fb092f6c9cf369127207a17ca756d72d70d7f618` | #180 `d2213c1` |
| 연결 주문 품목·금액 | [#182](https://github.com/kdh949/BeanFlow/pull/182) | `561eb06c80fea92aca00a94ff4e70a52c43e4267` | #181 `fb092f6` |
| 통합 승인함·결정 이력 | [#183](https://github.com/kdh949/BeanFlow/pull/183) | `aff73c2261a0d36c90c93a50a311e1f867dfdcf7` | #182 `561eb06` |

세 구현 커밋은 직전 단위의 자손이며 각 PR은 해당 직전 브랜치를 기준으로 비교한다. 마지막 검증 문서는 #183 위 문서 전용 변경으로 유지한다.

세 구현 PR에서 preflight/frontend/backend-build/test 6개/build의 10개 required checks가 모두 SUCCESS이며 로컬·원격 구현 head가 일치함을 확인했다.

- [PR #181 CI 결과](https://github.com/kdh949/BeanFlow/actions/runs/34599184041/job/103267465301): `fb092f6c9cf369127207a17ca756d72d70d7f618`.
- [PR #182 CI 결과](https://github.com/kdh949/BeanFlow/actions/runs/34600021456/job/103270367831): `561eb06c80fea92aca00a94ff4e70a52c43e4267`.
- [PR #183 CI 결과](https://github.com/kdh949/BeanFlow/actions/runs/34602086609/job/103277381463): `aff73c2261a0d36c90c93a50a311e1f867dfdcf7`.
