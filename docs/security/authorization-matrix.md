# Authorization Matrix

| Resource / Action | Customer | Store Owner | Store Staff | Platform Operator | Settlement Operator |
|---|---:|---:|---:|---:|---:|
| 고객 가입·로그인 | Self | No | No | No | No |
| 점주 로그인·비밀번호 변경 | No | Self | Self | No | No |
| 점주 계정 발급·exact 조회·초기화·잠금 해제 | No | No | No | Active `MERCHANT_CREDENTIAL_MANAGE` grant + reason + idempotency + Audit | No |
| 고객 현재 Session 조회·로그아웃 (`/me`, `/auth/customer/sessions/current`) | Own | No | No | No | No |
| 점주 현재 Session 조회·로그아웃 (`/merchant/me`, `/auth/merchant/sessions/current`) | No | Own | Own | No | No |
| 운영자 현재 actor 조회 (`/operations/me`) | No | No | No | Own JWT | Own JWT |
| 접근 가능 매장 목록 (`/merchant/me/stores`) | No | ACTIVE membership만 | ACTIVE membership만 | No | No |
| 내 주문 목록·상세 (주문번호) | Own | No | No | Read for support | No |
| 매장 주문보드 목록 | No | Owned store | Assigned store | No | No |
| 매장 주문 상태 전이 (주문번호) | No | Owned store | Assigned store | No | No |
| 내 주문 생성·조회 | Own | No | No | Read for support | No |
| 빠른 재주문 | Own terminal source only | No | No | No direct reorder | No |
| 내 결제수단 등록·목록 | Own only | No | No | No public support endpoint | No |
| 내 결제수단 default 지정·폐기 | Own active/lifecycle-allowed method | No | No | No direct command | No |
| 내 주문 외부 결제 승인 | Own order and own active PaymentMethod | No | No | No direct approval | No |
| 고객 주문 취소 | Own and allowed state | No | No | No direct cancellation | No |
| 취소 결과와 환불 진행 요약 조회 | Own | No | No | Read for support | No |
| 주문 보상 case step 상세 조회 | No | No | No | Explicit permission | No |
| 매장 주문 보상 진행 축약 조회 | No | Owned store | Assigned store | Read for support | No |
| 가까운 매장 검색 (`/stores/nearby`) | Customer Session | No | No | No | No |
| 매장 메뉴 조회 (`/stores/{storeId}/menus`) | Customer Session | No | No | No | No |
| 매장 픽업 슬롯 조회 (`/stores/{storeId}/pickup-slots`) | Customer Session | No | No | No | No |
| 매장 메뉴 변경 | No | Owned store | Assigned store if permitted | Controlled | No |
| 주문 수락·제조 상태 | No | Owned store | Assigned store | Support only | No |
| 내 포인트 조회 | Own | No | No | Active explicit `POINT_ACCOUNT_READ` grant + reason | No |
| 감사형 포인트 조정 | No | No | No | Active explicit `POINT_ADJUSTMENT` grant + reason + evidence | No |
| 부분 환불 preview·실행 | No | ACTIVE owned-store membership + reason + idempotency | ACTIVE assigned-store membership + reason + idempotency | Approved operation | Read only |
| 매장 정산 조회 | No | Owned store | No by default | Yes | Yes |
| 이의제기 생성 | No | Owned store | No | No | No |
| 이의제기 판정 | No | No | No | No public endpoint | No public endpoint |
| 운영 실패 작업 목록·상세 | No | No | No | Active `REPROCESSING_CASE_READ` grant | No |
| 운영 정산 대사 목록·상세 | No | No | No | Active `SETTLEMENT_RECONCILIATION_READ` grant | No |
| 감사 로그 목록·상세 | No | No | No | Active `AUDIT_RECORD_READ` grant + access reason + audited read | No |
| 재처리 | No | No | No | Explicit permission + reason | Settlement scope only |
| 누락 Refund 복구 제안 | No | No | No | Explicit permission + reason | No |
| 누락 Refund 복구 승인·거절 | No | No | No | 제안자와 다른 활성 operator + reason | No |
| terminal 고객 취소 Refund LOOKUP 재개 | No | No | No | Active `CUSTOMER_CANCELLATION_REFUND_RECONCILE` grant + reason | No |
| 권한 변경 | No | Limited | No | Audited | No |
| 만료 혜택 복원 정책 조회·변경 | No | No | No | Active `EXPIRED_BENEFIT_POLICY_READ`/`WRITE` grant + reason | No |
| 일반 포인트 적립 정책 조회·변경 | No | No | No | Active `POINT_ACCRUAL_POLICY_READ`/`WRITE` grant + reason | No |

## Authentication chains

인증 방식은 사용자 유형별로 다르다([ADR-092](../adr/ADR-092-hybrid-authentication.md)).

| Chain | 경로 | 인증 | CSRF |
|---|---|---|---|
| Public | `/actuator/health`, `/api/v1/payment-config`, `/api/v1/auth/operations/config` | 없음 | 해당 없음 |
| Operations | `/api/v1/operations/**`, `/api/v1/support/**` | Keycloak Bearer JWT | 적용하지 않음 |
| Merchant | `/api/v1/auth/merchant/**`, `/api/v1/merchant/**`, 매장 범위 경로 | Session Cookie | 적용 |
| Customer | 나머지 `/api/v1/**` | Session Cookie | 적용 |

- 요청은 정확히 하나의 Chain에 속한다. 경로가 겹치거나 미배정이면 기동을 실패시킨다.
- Chain을 명시하지 않은 새 endpoint는 구조 검증을 실패시킨다. Customer Chain을 암묵적
  기본값으로 사용하지 않는다.
- Cookie는 `HttpOnly`, `Secure`, `SameSite=Lax`이며 고객은 `BEANFLOW_CUSTOMER_SESSION`, 점주는
  `BEANFLOW_MERCHANT_SESSION`으로 이름을 분리한다([MD-2026-013](../decisions/minor-decisions.md)).
- CSRF token은 고객 `BEANFLOW_CUSTOMER_XSRF`, 점주 `BEANFLOW_MERCHANT_XSRF` Cookie로 분리하고
  `X-BEANFLOW-CSRF` header에 복사한다. actor별 token 발급 endpoint와 다른 Chain의 token은 수용하지 않는다.
  token 발급 GET은 body 없는 204이며 해당 actor의 XSRF Cookie만 발급한다.
- 현재 actor 조회와 logout도 Chain별 경로로 분리한다. 하나의 `/me`에서 여러 Cookie와 JWT를
  동시에 해석하지 않는다.
- 로그인 시 Session ID를 회전한다. 비밀번호 변경·로그아웃 시 해당 계정 Session을 폐기한다.
- Session에는 actor 식별자, 인증 시각과 로그인 시점 `credentialVersion`만 둔다. 계정 상태와 현재
  version, 권한과 매장 membership은 캐시하지 않고 매 요청 다시 조회한다([ADR-094](../adr/ADR-094-browser-session-security.md),
  [ADR-095](../adr/ADR-095-unified-current-actor.md)).
- Session 저장소 조회 실패는 익명 요청으로 강등하지 않고 `503`이다.

## Merchant account state gate

점주 계정이 `INITIAL_PASSWORD`이면 비밀번호 변경과 `/merchant/me` 외 모든 매장 API가 `403`이다
([ADR-093](../adr/ADR-093-merchant-credential-lifecycle.md)). 이 판정은 Merchant Chain 인가 규칙과
Application Service 양쪽에서 수행한다. 한 곳만 두면 새 endpoint 추가 시 누락된다.

계정 상태가 `ACTIVE`라고 매장 접근 권한이 생기지 않는다. 매장 접근은 계속
`StoreMembership`이 소유한다([ADR-027](../adr/ADR-027-store-membership-authorization.md)).

## Public order reference is not an authorization token

공개 주문번호(`BF-XXXX-XXXX`)와 픽업번호는 표시·조회 편의를 위한 식별자이며 권한 증명이 아니다
([ADR-096](../adr/ADR-096-public-order-reference.md)).

- 고객 경로는 주문번호와 함께 Session actor의 소유권을 검증한다. 소유자가 아니면 `403`,
  존재하지 않으면 `404`다.
- 매장 경로는 주문번호와 함께 `StoreMembership`과 주문의 `storeId` 일치를 검증한다.
- 공개번호 고객 취소와 매장 전이도 같은 predicate로 내부 UUID를 해석한 뒤 기존 Aggregate 명령을
  실행한다. 신규 응답과 멱등 replay 변환 결과에는 내부 `orderId`를 포함하지 않는다.
- 형식 검증은 대문자 canonicalization 뒤 수행한다. 존재 확인은 403/404 구분에만 사용하며 공개번호
  단독 조회 결과를 반환하지 않는다.
- 픽업번호는 매장·영업일 안에서만 유일하므로 조회 키로 사용하지 않는다.

## Enforcement layers

- Security FilterChain: 인증 객체 구성
- Method Security: 역할 기반 진입점
- Application Service: 객체 소유권·매장 membership·Operations explicit permission grant
- Aggregate: 상태와 비즈니스 권한에 독립적인 불변식
- Audit: 금액·권한·수동 재처리

인가 실패를 리소스가 없다는 것과 혼동하지 않도록 API 노출 정책을 별도로 정한다.

## Explicit operator permission

`OperatorPermissionGrant`는 Operations가 소유하는 permission source of truth다. JWT `roles`는
인증된 actor의 coarse role gate일 뿐, `permissions` claim·role·in-memory cache는 active grant
부재 또는 조회 장애의 fallback이 아니다. privileged Application Service는 actor의
`PLATFORM_OPERATOR` role과 active grant를 같은 local transaction에서 확인한다. revoked/missing
grant는 403, grant/Audit persistence failure는 503이다.

P0 운영 조회는 BR-39에 따라 `REPROCESSING_CASE_READ`, `SETTLEMENT_RECONCILIATION_READ`,
`AUDIT_RECORD_READ`를 각각 사용한다. 세 grant는 서로 대체하지 않으며 조회 grant는 재처리·조정 같은
명령 권한을 포함하지 않는다. 감사 로그 조회만 `X-Access-Reason`과 같은 transaction의 접근 Audit을
추가로 요구한다. 다른 두 조회도 grant 장애를 빈 목록이나 role-only 허용으로 바꾸지 않는다.

S10은 기존 9개와 Support/Operations/Privacy permission을 closed vocabulary로 등록했고 V42까지 현재
43개 값이다. productization-20은 `MERCHANT_CREDENTIAL_MANAGE`를 추가하고 productization-100은
`REPROCESSING_CASE_READ`, `SETTLEMENT_RECONCILIATION_READ`, `AUDIT_RECORD_READ`를 추가해 P0 목표를
47개로 만든다. 새 값은 persistent grant/revoke/regrant와 동일한 lock/Audit 경계를 사용하지만,
role bundle이나 default grant로 배포되지 않는다. S20은 `SUPPORT_CASE_READ`, `SUPPORT_CASE_WRITE`, `SUPPORT_CASE_ASSIGN`를
active grant와 Case assignment/version 조건으로 사용한다. S30은 `SUPPORT_SUBJECT_SEARCH`를 Tx1/rate guard와
Vault 호출 뒤 Tx2에서 모두 확인한다. S40은 verification, reveal request/approval, BASIC/SENSITIVE reveal,
break-glass request와 distinct privacy review permission을 owning transaction에서 확인한다. Action, Delivery와
LegalHold 값은 owning endpoint가 생기기 전까지 dormant foundation이며 capability release를 뜻하지 않는다.

주문 보상 case step 상세 GET은 active `ORDER_COMPENSATION_READ` grant와
`X-Access-Reason` header를 요구하고 target Case access Audit와 조회를 한 local
transaction에 묶는다. 다른 policy·point permission이나 `PLATFORM_OPERATOR` role만으로
통과시키지 않는다.

정책 GET은 `EXPIRED_BENEFIT_POLICY_READ`와 `X-Access-Reason` header를, PATCH는
`EXPIRED_BENEFIT_POLICY_WRITE`와 request body reason을 요구한다. GET reason은 trim 뒤 1..200자,
control character 금지이며 current policy heads와 access Audit이 함께 저장된 경우에만 200이다.
일반 적립 policy current/history GET과 version write도 별도 `POINT_ACCRUAL_POLICY_READ`/`WRITE`
grant를 사용하며 같은 read reason, write idempotency·body reason과 Audit commit gate를 적용한다.
모든 `/operations/policies/ordinary-point-accrual/**` endpoint는 JWT의 coarse
`PLATFORM_OPERATOR` role도 요구한다. READ grant는 current/head/history GET에만, WRITE grant는
GLOBAL/STORE append-only PATCH에만 유효하며 서로 대체하지 않는다. Store policy endpoint는 권한
확인 뒤 Merchant의 authoritative Store 존재 boundary를 사용하고, 조회 실패를 존재하지 않음이나
GLOBAL fallback으로 바꾸지 않는다.
포인트 조정은 `POINT_ADJUSTMENT` grant와 body reason/evidence를 요구한다. 상세는 ADR-069를 따른다.
누락 Refund 복구의 제안과 결정은 모두 `PAYMENT_CANCELLATION_SETUP_REPAIR` grant를
요구한다. 결정자는 제안자와 다른 actor여야 하며, role이나 다른 permission은 이 grant를
대체하지 않는다. request body는 사유와 결정만 허용하고 금융 필드는 거부한다.
terminal 고객 취소 Refund 재조회는 별도 `CUSTOMER_CANCELLATION_REFUND_RECONCILE`
grant를 요구한다. 단일 운영자는 body reason과 `Idempotency-Key`만 제출할 수 있고,
Application Service는 완전한 고객 취소 원천을 다시 검증한 뒤 기존 Provider key의 LOOKUP
한 번만 예약한다. 다른 repair/read grant나 `PLATFORM_OPERATOR` role만으로 통과하지 않으며,
금액·Provider 결과·금융 식별자를 입력하거나 새 REQUEST를 보내는 권한은 부여하지 않는다.
고객 자신의 point-account/ledger read는 reason 없이 허용하지만, Platform Operator support read는
`POINT_ACCOUNT_READ` grant, `X-Access-Reason`과 target access Audit을 요구한다. customer request에서
header는 optional이고 operator branch에서만 required다. operator branch는 grant 확인, projection과
`POINT_ACCOUNT_READ` Audit을 하나의 local transaction에서 commit해야만 200을 반환한다. missing/revoked
grant는 403이고 grant/Audit persistence failure는 503이며 role 또는 다른 permission으로 대체하지 않는다.

first grant와 grant/revoke/regrant는 HTTP role로 실행하지 않는다. offline bootstrap은 controlled
deployment job의 단기 OIDC workload identity를 required issuer·audience·allowed subject로
검증한 Verified Release Principal만 허용한다. raw workload token은 read-only mounted file에서
읽고 저장·로그하지 않으며, 누락·불일치·만료·검증 key 실패는 non-zero terminal failure와
무변경으로 끝난다. static bootstrap secret, application JWT와 Platform Operator role은
bootstrap authorization fallback이 아니다.
bootstrap command는 새 grant HTTP API를 만들지 않으며, verified identity 뒤 grant state/version과
Audit를 한 transaction으로 저장한다. 자유 입력 reason은 command validation에만 사용하고 DB/Audit에
복제하지 않으며, Audit는 표준 lifecycle reason과 immutable evidence reference만 보존한다. 운영 절차와
terminal exit contract는 [operator permission bootstrap runbook](../operations/operator-permission-bootstrap-runbook.md)을
따른다.

매장 주문 명령은 Merchant Session의 `MerchantActor` 유형과 Identity의 현재 `ACTIVE` membership을
요구한다. Session에는 role을 캐시하지 않으며 요청 시점의 membership role이 세부 권한 source다.
membership이 `REVOKED`이거나 operation이 허용하지 않는 role이면 `403`이다.

부분 환불 preview와 실행도 같은 객체 수준 인가를 적용한다(BR-38). `OWNER`와 `STAFF` 모두 자신이
`ACTIVE` membership을 가진 매장의 주문에 실행할 수 있지만, 다른 매장이나 허용되지 않은 membership role은
`403`이다. 실행은 사유와 `Idempotency-Key`가 필수이며 `paymentId`·`orderLineId` UUID를 사용자 입력으로
받지 않는다. P0에서는 STAFF 금액 상한이나 점주 사전 승인을 암묵적으로 추가하지 않는다.

정산 Batch/Item 조회와 이의제기 접수는 MerchantActor와 Identity의 현재 `ACTIVE OWNER`
membership을 요구한다. `STAFF`, revoked owner와 다른 매장 owner는 조회·접수할 수
없다. 이의제기 판정은 현재 내부 Application Service/worker만 존재하고 공개 운영 endpoint나
JWT permission surface가 없다. 향후 운영 판정 API를 만들 때는 전용 permission, actor Audit와
결정 사유 계약을 먼저 확정한다.

고객 주문 리소스는 존재하지 않으면 `404`, 다른 고객 소유이면 `403`을 반환한다. 조회와
취소가 같은 코드를 사용하며 operation에 따라 갈리지 않는다(ADR-030). 고객 취소
endpoint는 `CUSTOMER` 역할만 허용하고 매장·운영자 role의 호출은 `403`이다.

PaymentMethod lifecycle endpoint도 `CUSTOMER` 역할과 authenticated actor의 owner ID를 모두
요구한다. 목록 query는 owner predicate를 Repository/Query Repository에 포함하고, 등록은 owner를
request body에서 받지 않는다. default·폐기 target이 없으면 404, 다른 고객 소유이면 403이며
소유권을 확인하기 전에 alias·brand·last4·상태·default 여부를 노출하지 않는다. provider customer
reference, token, authKey/hash와 표시 metadata는 인가 근거가 아니고 owner ID를 대체하지 않는다.
legacy local/test provider row는 lifecycle resource scope 밖이라 owner에게도 목록에서 제외하고 target
command는 404다.

결제 승인 Tx1은 Order owner와 같은 customer의 `ACTIVE` PaymentMethod를 row lock 아래 검증한다.
Tx1이 먼저 commit한 Payment는 내부 immutable request snapshot으로 계속 수렴하지만, 이 snapshot은
새로운 PaymentMethod 접근 권한이나 lifecycle command 권한을 만들지 않는다. deactivation Tx D1이
먼저 commit하면 뒤 승인 준비는 Payment와 snapshot을 만들지 않고
`PAYMENT_METHOD_STATE_CONFLICT`로 실패한다.

Platform Operator에게 PaymentMethod 공개 목록·default·폐기 권한을 암묵적으로 부여하지 않는다.
운영 조사와 manual-review 해소 command가 필요하면 별도 permission, 최소 projection, 접근 사유와
Audit 계약을 먼저 결정한다. 현재 role, 다른 Operations grant와 DB 조회 장애를 support fallback으로
사용하지 않는다.

빠른 재주문도 같은 고객 Order 소유권 정책을 사용한다. `CUSTOMER`만 호출할 수 있고
source Order가 없으면 404, 다른 고객 소유이면 403이다. 소유권을 확인하기 전에 source
line·가격·혜택·결제·환불·정산 정보를 노출하지 않는다. 소유권 확인 뒤에도 source의
개인정보나 과거 결제수단을 응답에 복제하지 않으며 새 Order와 공개 가격 비교만 반환한다.

취소 보상의 step 상태, 시도 횟수와 내부 오류 코드는 운영자 전용이다. 매장에는
`trigger`, case 상태와 갱신 시각만 담은 축약 보상 요약을 반환하고 step 배열,
시도 횟수, 내부 오류 코드, case 식별자와 정책 version은 제외한다. 고객에게는
축약한 환불 진행 상태만 반환한다. 내부 재시도·불명 상태는 `PROCESSING`, 자동 처리
소진은 `PROCESSING + REFUND_DELAYED`로 표현하고 실제 실패 code와
`MANUAL_REVIEW`는 노출하지 않는다. 고객이 입력한 자유 서술 취소 상세는 어떤
역할에게도 API로 노출하지 않는다.

Order 표현은 역할별로 분리한다. 고객용 `Order`는 `cancelledAt`,
`cancellationCause`와 `cancellationReasonCode`를 노출한다. 매장용 `StoreOrder`는
`cancelledAt`과 `cancellationCause`만 노출하고 `cancellationReasonCode`와
`paymentRecovery`는 제외한다. 매장은 취소가 고객 요청인지 결제 거절인지 구분할 수
있지만 고객이 신고한 사유와 환불 진행은 보지 않는다(ADR-030, ADR-031).

감사형 포인트 조정은 고객·매장·정산 역할에 노출하지 않는다. 활성
`PLATFORM_OPERATOR`라도 active explicit `POINT_ADJUSTMENT` grant, non-blank reason,
evidence reference와 Idempotency-Key가 없으면 실행할 수 없다. issuer와 만료는
양수 adjustment의 immutable 비용·가치 snapshot이며 actor나 customer에서 추론하지
않는다(ADR-066).

## Support planning matrix

Support role은 coarse bundle일 뿐이다. 모든 Support request는 active persistent permission,
Case state/assignment, Subject/object relation, purpose-bound verification, field grant 또는 exact
approval revision과 owner state/version을 결합한다. unknown 조합은 DENIED다. R3 requester,
Support Manager, Operations reviewer와 executor separation을 서버와 DB 제약으로 검증한다.

정확한 capability 표와 negative fixture 계획은 [Support role matrix](support-role-permission-matrix.md),
[object authorization](support-object-level-authorization.md),
[planned test strategy](../testing/support-test-strategy.md)를 따른다.

### S90 goodwill compensation

| Operation | Persistent permissions | Object and separation checks |
|---|---|---|
| evaluate/create | `SUPPORT_CASE_READ`, `SUPPORT_COMPENSATION_REQUEST` | active assigned Case, linked customer/order, exact action-bound verification; HIGH/EXCEPTIONAL requires ENHANCED |
| read | `SUPPORT_CASE_READ` | Case-scoped visibility; customer PII/evidence/cost evidence excluded |
| execute | `SUPPORT_CASE_READ`, `SUPPORT_COMPENSATION_EXECUTE` | assigned executor, exact request/payload/target/policy version, manager/Operations approval; reviewer cannot execute |
| notification retry | `SUPPORT_CASE_READ`, `SUPPORT_COMPENSATION_EXECUTE` | terminal benefit already exists and Support state is `NOTIFICATION_RETRY`; no benefit input or reissue |

JWT role이나 UI evaluation은 위 grant를 대체하지 않는다. 권한 row는 caller transaction에서 잠그므로 revoke와
실행이 직렬화된다. Operations reviewer는 exact request를 반환할 뿐 Point/Coupon을 발급하지 않는다.
