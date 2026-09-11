# ADR-127: 고객 선택으로 운영 포인트 계정 연결

- **Status:** Accepted
- **Date:** 2026-09-11
- **Implementation owner:** [고객 선택 포인트 업무](../exec-plans/active/customer-point-selection.md)

## Context

운영 포인트 화면은 내부 PointAccount UUID를 직접 입력해야 한다. CustomerAccount와 PointAccount는
BR-42의 customer ID로 이미 연결되지만 운영 조회의 진입에는 이 관계를 사용하지 않는다.
상담용 연락처 검색의 Support profile은 가입 CustomerAccount와 별개이므로 이를 추론해 연결하지 않는다.

## Decision

- Identity가 `POST /operations/customer-searches`를 소유한다. BR-34의 canonical login ID를 정확
  검색하며 결과는 0~1개다. CUSTOMER_ACCOUNT_SEARCH active grant와 POINT_ACCOUNT_INVESTIGATION
  reason code를 요구한다. 고객 ID와 가린 login ID/display name만 반환하며 password/상태/연락처는 제외한다.
- 검색은 DTO projection으로 읽는다. grant 확인·검색·CUSTOMER_ACCOUNT_SEARCHED 감사 append는
  하나의 짧은 local transaction이다. 원본 검색어/표시 이름은 audit/log/trace/error에 기록하지 않는다.
  감사에는 고유 search ID와 결과 개수만 남긴다. no-store 응답이며 query parameter는 받지 않는다.
- Loyalty가 `GET /operations/customers/{customerId}/point-account`를 소유한다. POINT_ACCOUNT_READ
  active grant와 고정 조회 사유를 확인한 뒤 기존 customer ID index로 계정을 찾는다. 응답은
  customerId/accountId이며 POINT_ACCOUNT_RESOLVED 감사와 같은 transaction에서 완료한다.
  누락은 POINT_ACCOUNT_INTEGRITY_FAILURE 503으로 처리하며 계정 생성/0원 추정은 하지 않는다.
- 기존 account ID 기준 잔액·원장·조정 API 및 ADR-066의 유일한 조정 명령 경로를 유지한다.
  고객 검색 grant는 포인트 읽기/조정 권한을 부여하지 않는다. 각 기존 API는 current grant를 재검증한다.
- 브라우저는 검색 결과와 선택 고객/연결 account ID를 컴포넌트 메모리에만 보관한다.
  localStorage/sessionStorage/URL/cache persistence에 보관하지 않는다. 고객 전환·검색 재실행·화면
  이탈 시 이전 대상의 상태를 비운다. 이전 요청 응답은 새 대상에 반영하지 않는다.
- 결과 불명 조정은 대상·내용·동일 Idempotency-Key를 보존하고 재확인한다. 미확인 명령을 둔 채
  고객/금액을 바꾸거나 새로운 key로 자동 재제출하지 않는다. 성공 후 current balance/history를 재조회한다.
- SearchField/Button/기존 상태·조정 패턴을 조합한다. 내부 ID 입력을 고객 검색/확인/선택으로 바꾼다.

## Manual sequential migration scope

이번 명시적 후속 요청은 자동 Goal 실행이 아니다. 검증 완료된 #169 e8fde1e를 정확한 base로 하는
단일 후속 PR만 만든다. 2026-09-11 main/origin/main a6199c6는 V81까지이며 이 base는 #167의 V82를
포함한다. 열린 PR #125에는 migration이 없고 #160~169의 DDL writer는 완료된 #167뿐임을 확인했다.
기존 native inquiry lane을 직렬 후속 V83의 permission/audit 어휘 확장까지 이어받는다. 다른 writer와
동시에 쓰지 않으며 이 후속 PR merge까지 lane을 유지한다. 기존 migration/parent branch를 수정하거나
번호를 예약·재배치하지 않는다. 이는 ADR-072의 일반 자동 실행 규칙을 변경하지 않는 이 요청 한정
수동 직렬 handoff다. 다른 writer 또는 predecessor head 변경을 발견하면 inventory를 다시 확인한다.

## Alternatives Considered

- UUID 입력 유지: 실제 운영자가 대상을 찾을 수 없다.
- 브라우저 영구 고객 목록: 현재 권한/고객 선택과 오래된 상태가 섞인다.
- 고객 기준 조정 API 추가: 기존 ADR-066 단일 명령 계약과 멱등 경계를 불필요하게 늘린다.
- Support 연락처 검색 재사용: 가입 계정이 아닌 다른 데이터 소유권/인가 목적을 혼동한다.

## Rationale

기존 조회/원장/조정 불변식을 재사용하고 Identity와 Loyalty의 데이터 소유권을 유지한다.
고객 검색의 개인정보 범위를 별도 권한과 최소 projection으로 제한한다.

## Consequences

고객 login ID를 알아야 하며 이름 부분 검색은 제공하지 않는다. 검색·계정 연결에 감사 쓰기가 추가된다.
V83은 새 permission을 자동 부여하지 않는다. 담당자에게 명시적으로 검색 권한을 부여해야 한다.

## Verification

정확 검색/정규화/마스킹, 권한 교차·철회, 감사 실패 rollback, 누락 계정 503, 실제 검색→연결→
조회→조정, 고객 전환/늦은 응답/응답 유실/재시도, storage 미사용, OpenAPI parity/Modulith/Storybook.

## Metrics

검색·연결 감사의 action과 HTTP 상태로 성공/실패를 추적한다. 고객 ID·검색어를 metric tag에 넣지 않는다.

## Revisit Conditions

이름 부분 검색, 고객 수명주기 변경, 포인트 계정 다중화, 별도 고객 조사 권한 체계가 필요할 때.

## Related Decisions

- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-109](ADR-109-customer-point-account-provisioning.md)
