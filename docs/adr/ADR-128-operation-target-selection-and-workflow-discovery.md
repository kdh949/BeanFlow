# ADR-128: 운영 대상 선택과 상담 요청 탐색

- **Status:** Accepted
- **Date:** 2026-09-11
- **Implementation owner:** [내부 ID 입력 제거](../exec-plans/active/internal-identifier-workflow-selection.md)

## Context

관리 API를 호출하는 화면이 있어도 내부 ID를 사람이 먼저 구해 입력해야 하면 업무가 완결되지 않는다.
#170은 고객 선택 포인트 업무를 해결했다. 매장/주문/담당자/요청/사고의 탐색은 별도 보완이 필요하다.

## Decision

- BR-57에 따라 업무 대상을 실제 검색 결과 또는 현재 권한으로 조회한 목록에서 선택한다.
- 기존 매장 이름 검색과 명령 API를 재사용한다. 공개 주문번호는 Ordering이 내부 주문에 연결한다.
- 목록은 owner-local DTO projection이다. 다른 Aggregate graph 확장이나 무인가 cross-table 검색을 추가하지 않는다.
- 요청 목록은 발견 수단이며 상세/승인/실행은 현재 권한·담당자·대상·버전·본인확인과 기존 분리 승인을 재검증한다.
- 검색 실패는 empty, 과거 결과 또는 임의 표시 이름으로 바꾸지 않는다. 선택은 route memory에 두고
  변경/화면 이탈 때 제거한다. 미확인 금융 명령은 대상·payload·동일 key를 유지한다.
- ID-only 입구는 목록/선택으로 교체한다. 기존 상세 공유 주소는 유지한다. 생성 직후에는 서버 결과의
  식별자를 내부적으로 연결한다. 신규 사고 식별자를 화면 재렌더링마다 생성하여 중복 지급 제한을 우회하지 않는다.
- 담당자 표시 정보와 비용/외부 참조는 확인된 출처를 사용한다. 실제 자료 없는 별칭/기본 주체를 생성하지 않는다.
- 각 기능의 구체 조회/등록 계약은 해당 owner ADR과 함께 이 문서에 추가하며 contract-first로 검증한다.

### Store and membership labels

매장 이름은 Merchant의 현재 공개 매장 프로필에서, 점주·직원 표시 이름과 로그인 아이디는 Identity의
현재 MerchantAccount에서 읽는다. 목록의 현재 grant와 감사 경계 안에서 최소 표시 정보만 조회하며
클라이언트의 행별 HTTP 요청 대신 owner port의 일괄 조회를 사용한다. 매장 정책 목록에는 `scopeName`,
계정 조회의 소속에는 `storeName`, 소속 조회에는 `accountDisplayName`/`accountLoginId`를 추가한다.
이 필드는 조회용이며 기존 명령의 멱등 응답·감사 snapshot에 표시 이름을 소급 저장하지 않는다.
등록된 표시 정보가 사라진 경우 조회 실패로 드러내며 UUID 별칭이나 이전 이름으로 대체하지 않는다.
소속 추가는 기존 로그인 아이디 정확 조회로 통일하고 수동 account UUID 경로를 제거한다.

### Order and repair discovery

`GET /operations/order-compensations/{orderReference}`는 `ORDER_COMPENSATION_READ`와 기존 조회 사유를
확인한 뒤 Ordering이 공개 주문번호를 정규화·정확 조회한다. 기존 보상 조사와 감사 transaction 안에서
주문 표시 정보와 후속 처리 상태를 반환한다. 내부 UUID 경로는 호환성을 위해 유지한다. Operations가 소유하는
outbound query port를 Ordering이 구현하여 기존 모듈 의존 방향을 유지한다.
주문번호·주문 시점 매장명·주문 상태·생성 시각을 표시하고 고객/Provider 정보는 제외한다.
권한 있는 주문 조회의 내부 주문/매장 ID는 명령 연결용으로만 전달하며 화면의 입력이나 이름으로 쓰지 않는다.

`GET /operations/payment-setup-recovery-cases`와 `GET /operations/reprocessing-repair-proposals`는
`PAYMENT_CANCELLATION_SETUP_REPAIR`가 있는 운영자가 대상과 현재 상태를 선택하기 위한 목록이다.
커서는 actor, 상태 필터와 선택한 Case에 바인딩한다. owner-local 복구 조회와 Ordering의 일괄 표시
projection을 사용하며 상세·제안·판정은 기존 grant와 safe guard를 다시 검증한다. 목록 조회는 만료 처리나
복구를 실행하지 않는다. 유효기간이 지난 대기 항목은 현재 시각 기준 만료 안내와 함께 실행을 막는다.
제안자/판정자의 이름은 담당자 디렉터리 단계에서 연결하고 이 단계에서는 본인 여부와 역할을 구분한다.
불명 명령 응답에서는 대상·payload·동일 key를 고정하고 입력을 바꿔 새 요청을 만들지 않는다.

### Operator display and assignment discovery

Operations는 인증된 `/operations/me` 요청에서 서명 검증을 마친 JWT의 `preferred_username`과 `iat`만
관측하여 operator display read model을 갱신한다. 이름을 요청 body로 받거나 UUID에서 생성하지 않는다.
이름은 조직 로그인 식별명이며 현재 권한이나 실제 법적 이름의 증거로 사용하지 않는다. 최근 관측 시각을
함께 반환하며 더 오래된 `iat`는 새 관측 값을 덮어쓰지 않는다. claim이 없던 기존 계정은 `MISSING_PROFILE`로
구분한다. 이 상태는 저장소 장애와 다르며 후보 수/표시 상태를 명시한다. 저장소 오류를 미등록으로 숨기지 않는다.

담당자 목록은 현재 `SUPPORT_CASE_READ` 또는 `SUPPORT_CASE_ASSIGN` 권한을 요구한다. 선택 목적에 따라
Case 쓰기 및 주문/보상/정정 실행에 필요한 실제 grant를 모두 가진 계정을 조회하며, 실행 시 owner 명령이
승인자 분리·현재 Case/요청/버전과 권한을 다시 검증한다. 표시 이름이 준비되지 않은 계정을 임의 이름으로
선택시키지 않고 해당 조직 계정의 로그인이 필요함을 알린다. 현재 Case 담당자 표시는 등록 여부를 명시한다.
상담 이력 필터 목적은 과거 Case 쓰기 권한이 철회된 담당자도 선택할 수 있다. 이 목적은 배정에 사용하지 않는다.
목록 커서는 요청 actor·목적·검색 조건에 바인딩한다. 이름 검색 원문과 표시 이름은 Audit/로그/metric에 남기지 않는다.
로그인 표시 갱신은 짧은 local transaction이며 외부 인증 서버 API를 호출하지 않는다.

Bundled Keycloak realm에 access-token `preferred_username` mapper를 명시하며 external Keycloak도 동일
mapper를 설정한다. BeanFlow가 Keycloak 관리자 credential을 받거나 인증 서버의 사용자 원장을 복제하지 않는다.
조회 모델은 실제 로그인 계정의 최신 관측 정보이며, 아직 로그인하지 않은 조직 계정 전체를 열거하는 API가 아니다.

### Support subject selection and labels

상담 접수/대상 연결은 기존 `POST /support/searches`의 전화번호·이메일 정확 검색과 마스킹 후보를
조합한다. 고객 계정과 보호 프로필의 식별자를 추론하여 연결하지 않는다. CUSTOMER/STORE/RIDER 후보는
해당 owner의 실제 보호 프로필 식별자이며 RIDER는 기존 DELIVERY 연결에 해당하는 외부 배달원이다.
점주/매장 직원 문의는 기존 접수와 동일하게 선택한 매장을 요청자 참조로 사용한다. 내부 운영자는 조직
로그인 계정을 선택한다. 접수 전용 INTERNAL_REQUESTER 목록은 CASE_WRITE 권한으로 현재 활성 grant가
하나 이상 있는 관측 계정을 조회하며 담당자 배정 후보와 구분한다. 제3자/시스템/미확인 요청자의 업무 참조는 DB ID가 아닌 기존 비식별 접수 설명이며 유지한다.

담당 상담의 주문 연결 후보는 공개 주문번호의 정확 조회로 찾는다. 현재 Case 읽기/쓰기, 현재 담당자,
활성 Case, `SUPPORT_ORDER_READ`와 `SUPPORT_SUBJECT_SEARCH`를 확인한다. 기존 검색 rate guard를
공유하며 조회 결과와 PII-free 감사는 같은 local transaction이다. 주문 후보는 공개 주문번호, 주문 시점
매장명과 상태만 반환한다. 후보 선택은 주문 실행 권한이나 고객 관계의 증명이 아니며 기존 명령 검증을 유지한다.

Case 상세의 연결 대상 표시에는 owner별 일괄 projection을 사용한다. 보호 프로필의 마스킹 이름은
현재 `SUPPORT_SUBJECT_SEARCH`, 주문 표시에는 `SUPPORT_ORDER_READ`를 추가로 확인하며 허용되지 않으면
`REQUIRES_PERMISSION`을 반환한다. 허용된 표시 조회는 감사와 같은 transaction이다. 실제 등록 정보가 없으면
`MISSING_PROFILE`, 저장소/감사/잘못된 마스킹은 조회 실패로 구분한다. UUID를 이름으로 대신 표시하지 않는다.
본인확인, 개인정보 열람, 정정과 금전 실행은 표시 정보를 권한 근거로 사용하지 않는다.

### Existing support work discovery

`GET /support/work-items`는 업무 종류와 선택적인 Case 범위로 기존 영속 요청을 찾는다. Support 소유
테이블의 bounded ID page를 읽은 뒤 각 업무의 기존 inspect/get 권한·상태 검사를 재사용한다. 별도 SQL로
개별 객체의 실행/승인 권한을 재정의하지 않는다. 본인확인/승인 조회의 기존 만료·재배정 상태 갱신은 그대로
수행할 수 있지만 challenge 발급, 원문 열람, 승인 결정, 금전 실행 또는 외부 발송은 수행하지 않는다.

여러 요청의 lock을 한 transaction에 쌓지 않는다. 후보 조회와 각 기존 inspection은 별도의 짧은 local
transaction이며 마지막에 목록 권한을 재검증한다. 따라서 목록은 atomic snapshot이 아니며 실제 선택 후
다시 상세를 읽는다. 기존 inspection이 객체 가시성에 대해 반환한 ACCESS_DENIED는 명시적인 행 권한
필터로 적용한다. 기본 목록 권한 철회는 403으로 실패하며, 다른 예외/저장소 실패를 빈 목록으로 바꾸지 않는다.
권한 필터로 빈 page에도 nextCursor가 있으면 다음 조회 구간을 표시한다. 커서는 actor·종류·Case에 묶는다.
종료된 Case의 본인확인/열람 요청과 연결 해제된 열람 대상은 재개 후보에서 제외한다. 이력은 Case 이력에서 확인한다.
목록에는 업무 목적, 현재 조회 상태, 생성/만료 시각과 Case 분류/접수 시각만 반환하며 원문 개인정보,
인증 증명, payload/evidence digest, 비밀 또는 실행 자격을 반환하지 않는다. 기존 상세 공유 주소는 유지한다.

### Store consent and operations investigation discovery

매장의 동의 대기 목록은 현재 매장 주문 관리 권한을 확인하고, Support의 유효한 직접 주문 변경 요청을
bounded page로 읽어 Ordering의 batch projection으로 해당 매장의 ACCEPTED 주문과 현재 버전을 확인한다.
공개 주문번호·변경 종류·요청/만료 시각으로 선택한 뒤 기존 상세를 재조회한다. Support는 Ordering 테이블을
직접 join하지 않는다. 후보 필터로 빈 page라도 다음 cursor를 유지한다.

상담 실행자는 현재 요청 workflow에서 EXECUTE가 허용될 때만 해당 매장·업무·승인안에 유효한 동의/위임을
조회한다. 만료·철회·사용 횟수·승인자 분리·정확한 revision/digest/targetVersion·활성 위임 정책을 검사한다.
선택은 사용 횟수를 소비하지 않는다. 실제 실행은 기존 lock 및 consume 검사를 다시 수행한다. 동의 후 매장이
내부 ID를 복사해 상담원에게 전달할 필요가 없으며 기존 idempotency snapshot은 변경하지 않는다.

운영 검토 목록은 OPERATIONS_SUPPORT_INVESTIGATION 및 SUPPORT_CASE_READ를 확인한다. Operations 소유
조사 기록과 Support가 제공하는 최소 분류/Case 접수 시각 projection을 조합하며 현재 revision과 다른 조사나
만료된 OPEN 조사는 대기 목록에서 제외한다. 커서는 actor와 필터에 바인딩한다. 목록에 payload/evidence
hash나 개인정보를 노출하지 않는다. 선택 후 기존 현재 승인안/조사 조회 및 별도 검토자 검증을 유지한다.
새 목록은 같은 짧은 local transaction에서 grant와 owner projection을 읽으며 외부 Provider를 호출하지 않는다.
새 schema 또는 production dependency는 필요하지 않다.

### Manual sequential migration scope

이번 작업은 #170의 구현 제외를 명시한 수동 후속 요청이다. 정확한 기준은 #170
`716f9421c19bce255cd26cacc7f1a6dac095a61a`이며 최신 CI 통과를 확인했다. main/origin/main은 V81,
직렬 부모 #167은 V82, #170은 V83이고 열린 다른 PR #125에는 DDL이 없다. 각 writer의 구현이 끝난
동일 frontend 후속 stack에서 ADR-127 lane을 이어받는다. 하나의 milestone만 migration을 작성하고
검증된 head 뒤에 다음 milestone을 시작한다. 기존 migration/다른 checkout/공유 history는 수정하지 않는다.
이 요청의 최종 PR merge까지 동일 lane을 유지하며 다른 writer가 나타나면 inventory를 재검증한다.
이는 자동 실행의 main-first 규칙이나 unrelated schema writer 병렬 허용을 변경하지 않는다.

## Alternatives Considered

ID 설명문은 대상을 찾는 업무를 남긴다. 브라우저 영구 ID 목록은 stale 권한/대상 위험이 있다.
범용 DB 탐색기는 소유권·개인정보 범위를 과도하게 넓힌다. 업무별 최소 목록/선택이 기존 경계를 보존한다.

## Consequences

목록과 표시 projection이 추가되며 권한/감사 실패도 명시적으로 처리해야 한다. 기존 금전적 쓰기 경로,
비용 snapshot, 사고별 terminal unique와 순차 승인은 변하지 않는다.

## Verification

선택부터 명령 결과까지 Storybook interaction/a11y, 현재 권한과 교차 객체/커서, late response와
불명 명령, 실제 PostgreSQL 계약/rollback/중복/동시성, runtime parity와 Modulith를 검증한다.

## Revisit Conditions

외부 인사/참조 관리 연동, 데이터 대량 탐색, 새로운 개인정보 조회 범위 또는 금융 정책 변경이 필요할 때.

### 업무별 선택 권한 보완 (2026-09-12)

공용 매장 선택은 `/operations/store-targets`의 목적별 최소 id/name 응답을 사용한다. 소속 추가는 STORE_MEMBERSHIP_WRITE, 계정 관리는 MERCHANT_CREDENTIAL_MANAGE, 이의 조회는 SETTLEMENT_DISPUTE_READ, 포인트 정책 조회는 POINT_ACCRUAL_POLICY_READ를 사용한다. 환불은 기존 PLATFORM_OPERATOR 역할만으로 허용되는 업무이므로 REFUND 목적도 같은 역할 경계를 유지한다. 각 후속 명령은 기존 권한을 재검증한다.
소속 추가의 `/operations/stores/{storeId}/memberships/account-target`는 Identity가 STORE_MEMBERSHIP_WRITE + 존재하는 매장 + 고정 조회 목적을 확인해 정확한 로그인 아이디의 accountId/loginId/displayName만 반환한다. 기존 MERCHANT_ACCOUNT_READ 보안 감사 action을 재사용하고 purpose/storeId를 기록하며 인증 상태·비밀번호·타 매장 소속은 읽거나 반환하지 않는다. 기존 소속 목록 조회 grant 없이도 추가 전용 화면에서 이 경로를 사용할 수 있다. 조회와 Audit는 하나의 짧은 DB transaction이며 외부 호출과 새 dependency/DDL은 없다.
정책 변경의 KEY_REUSED와 MANUAL_REVIEW_REQUIRED는 확정 오류 안내로 종료하고 동일 키 자동 재시도 대상으로 보지 않는다. 네트워크·408·5xx·REQUEST_IN_PROGRESS 및 그 후 결과를 증명하지 못하는 권한 오류는 기존 요청을 유지한다.

### 복구 큐 조회 감사와 생성 가능 상태 (2026-09-12)

복구 Case/제안 목록은 PAYMENT_CANCELLATION_SETUP_REPAIR grant와 같은 짧은 transaction에서 각각 PAYMENT_SETUP_RECOVERY_CASES_READ / PAYMENT_SETUP_REPAIR_PROPOSALS_READ 금융 감사 action을 남긴다. 목적은 해당 큐 업무의 고정 PAYMENT_SETUP_RECOVERY_REVIEW이며 필터 상태와 결과 건수만 저장한다. 고객/주문/Provider 식별값과 검색 원문은 감사 payload에 넣지 않는다. 감사 저장 실패는 503이고 응답을 반환하지 않는다. 새 외부 호출이나 실행 엔진은 없다.
Case 목록의 canPropose는 PAYMENT_CANCELLATION_SETUP + OPEN + resolution 없음으로 서버가 계산한다. caseId 필터는 주문 상세에서 연결된 건도 같은 기준으로 읽게 하며 cursor scope에 포함한다. 종료/처리 중 Case를 조회할 수는 있지만 제안 생성 대상으로 선택하지 않는다. 실제 명령에서 최신 상태를 다시 검증한다.
복구 EXPIRED/STALE의 terminal 409 replay, 결과 조회 예약의 ORDER_STATE_CONFLICT/REPROCESSING_NOT_SAFE는 미확정 잠금을 해제하고 상세/목록을 갱신한다. replay 전에 실패하는 권한 철회는 이전 미확정 상태를 보존한다.

### 상담 연결 표시와 후보 조회 보완

- 연결 대상 표시는 SUBJECT_SEARCH와 주문의 추가 ORDER_READ를 한 predicate로 projection·감사·응답에 적용한다. 표시가 AVAILABLE이 아니면 본인확인·정정·긴급 열람·연결 해제·주문 조치의 새 대상을 선택하지 못하며, 권한 또는 원 소유자의 표시 프로필 보완을 안내한다.
- 주문 후보 조회는 CASE_READ → CASE_WRITE → ORDER_READ → SUBJECT_SEARCH grant를 획득한 뒤 담당 활성 Case를 잠근다. 기존 Case 쓰기의 grant → Case 순서와 역전되지 않는다. 조회 결과와 개인정보 비포함 감사 기록은 동일 트랜잭션이다.
- INTERNAL_REQUESTER의 호출자 CASE_WRITE 및 후보의 임의 ACTIVE grant 예외를 계약에 명시한다. 현재 운영자의 display는 누락 대신 MISSING_PROFILE을 포함해 항상 반환한다.
