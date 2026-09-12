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

### 조정 준비와 브라우저 재진입 복구 (2026-09-12)

실제 포인트 반영은 기존 `POST /operations/point-accounts/{accountId}/adjustments`만 수행한다.
Loyalty의 조정 준비 기록은 실행 전 대상·정규화된 입력·서버 발급 멱등 키를 보관한다.
`POST /operations/point-adjustment-preparations`는 포인트를 변경하지 않으며,
`GET /operations/point-adjustment-preparations/current`로 현재 액터의 미확인 기록 하나를 복구한다.
새 financial Aggregate나 별도 금전 실행 엔진을 도입하지 않는다.

- DB는 액터당 미확인 준비 기록 하나만 허용한다. 현재 기록과 다른 새 조정 키는 거부한다.
  PREPARED의 동일 키·내용만 실행하며 APPLIED와 최초 결과는 원장·Audit·멱등 원장과 함께 commit한다.
- 고객의 가린 이름은 Identity 소유 projection으로 읽고, 본문·키·고객 식별자를 브라우저 저장소에 넣지 않는다.
  복구 조회는 POINT_ACCOUNT_READ와 고객 표시의 CUSTOMER_ACCOUNT_SEARCH를 확인한다.
  준비는 이 권한들과 POINT_ADJUSTMENT를 요구하고 실제 실행은 기존 POINT_ADJUSTMENT를 다시 확인한다.
- Account→관련 grant→준비 기록 잠금 순서를 지킨다. 같은 액터의 다른 계정 준비는 grant와 부분 UNIQUE로 직렬화한다.
  조회에서는 Account를 잠그지 않는다. 저장된 APPLIED 결과의 replay는 새 credit 만료 검증보다 앞선다.
- `DELETE /operations/point-adjustment-preparations/{preparationId}`는 expectedState를 확인해
  미실행 준비를 CANCELLED로 닫거나 이미 확인한 APPLIED 결과를 확인 완료한다. 실행과 취소는
  같은 Account 잠금으로 직렬화하며 취소한 키의 늦은 실행은 거부한다.
- 통신 결과 불명이어도 복구 기록을 먼저 조회한다. 저장 확인 실패를 빈 결과로 취급하지 않는다.
  APPLIED 결과를 명시적으로 확인하거나 PREPARED를 취소하기 전에는 새 조정을 시작하지 않는다.
- 미확인 기록은 결과 확인까지 보존한다. 닫힌 준비 기록은 기존 조정 명령의 90일 보존 정책과
  정리 작업을 재사용한다. 원문은 로그·Audit에 넣지 않고 조회/준비/닫힘의 비식별 이력을 기록한다.

고객 표시 조회는 Loyalty가 필요한 `PointAdjustmentCustomerLabelQuery` public port를 정의하고 Identity adapter가 자기 테이블의 마스킹 projection을 제공한다. ADR-109의 Identity→Loyalty 방향을 유지하며 Loyalty는 Identity API/Repository에 의존하지 않는다. 새 Context나 cross-Aggregate association을 만들지 않는다.
