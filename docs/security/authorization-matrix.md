# Authorization Matrix

| Resource / Action | Customer | Store Owner | Store Staff | Platform Operator | Settlement Operator |
|---|---:|---:|---:|---:|---:|
| 내 주문 생성·조회 | Own | No | No | Read for support | No |
| 내 주문 외부 결제 승인 | Own order and own active PaymentMethod | No | No | No direct approval | No |
| 고객 주문 취소 | Own and allowed state | No | No | No direct cancellation | No |
| 취소 결과와 환불 진행 요약 조회 | Own | No | No | Read for support | No |
| 주문 보상 case step 상세 조회 | No | No | No | Explicit permission | No |
| 매장 주문 보상 진행 축약 조회 | No | Owned store | Assigned store | Read for support | No |
| 가까운 매장 검색 | Yes | Yes | Yes | Yes | Yes |
| 매장 메뉴 조회 | Yes | Yes | Yes | Yes | Yes |
| 매장 메뉴 변경 | No | Owned store | Assigned store if permitted | Controlled | No |
| 주문 수락·제조 상태 | No | Owned store | Assigned store | Support only | No |
| 내 포인트 조회 | Own | No | No | Active explicit `POINT_ACCOUNT_READ` grant + reason | No |
| 감사형 포인트 조정 | No | No | No | Active explicit `POINT_ADJUSTMENT` grant + reason + evidence | No |
| 부분 환불 | No | Owned store with policy | Permission required | Approved operation | Read only |
| 매장 정산 조회 | No | Owned store | No by default | Yes | Yes |
| 이의제기 생성 | No | Owned store | No | No | No |
| 이의제기 판정 | No | No | No | No public endpoint | No public endpoint |
| 재처리 | No | No | No | Explicit permission + reason | Settlement scope only |
| 누락 Refund 복구 제안 | No | No | No | Explicit permission + reason | No |
| 누락 Refund 복구 승인·거절 | No | No | No | 제안자와 다른 활성 operator + reason | No |
| terminal 고객 취소 Refund LOOKUP 재개 | No | No | No | Active `CUSTOMER_CANCELLATION_REFUND_RECONCILE` grant + reason | No |
| 권한 변경 | No | Limited | No | Audited | No |
| 만료 혜택 복원 정책 조회·변경 | No | No | No | Active `EXPIRED_BENEFIT_POLICY_READ`/`WRITE` grant + reason | No |
| 일반 포인트 적립 정책 조회·변경 | No | No | No | Active `POINT_ACCRUAL_POLICY_READ`/`WRITE` grant + reason | No |

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

매장 주문 명령은 JWT 역할과 Identity의 현재 `ACTIVE` membership을 모두 요구한다.
role과 membership role이 일치하지 않거나 membership이 `REVOKED`이면 `403`이다.

정산 Batch/Item 조회와 이의제기 접수도 JWT `STORE_OWNER`와 Identity의 현재 `ACTIVE OWNER`
membership을 함께 요구한다. `STORE_STAFF`, revoked owner와 다른 매장 owner는 조회·접수할 수
없다. 이의제기 판정은 현재 내부 Application Service/worker만 존재하고 공개 운영 endpoint나
JWT permission surface가 없다. 향후 운영 판정 API를 만들 때는 전용 permission, actor Audit와
결정 사유 계약을 먼저 확정한다.

고객 주문 리소스는 존재하지 않으면 `404`, 다른 고객 소유이면 `403`을 반환한다. 조회와
취소가 같은 코드를 사용하며 operation에 따라 갈리지 않는다(ADR-030). 고객 취소
endpoint는 `CUSTOMER` 역할만 허용하고 매장·운영자 role의 호출은 `403`이다.

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
