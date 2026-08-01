# Authorization Matrix

| Resource / Action | Customer | Store Owner | Store Staff | Platform Operator | Settlement Operator |
|---|---:|---:|---:|---:|---:|
| 내 주문 생성·조회 | Own | No | No | Read for support | No |
| 내 주문 외부 결제 승인 | Own order and own active PaymentMethod | No | No | No direct approval | No |
| 고객 주문 취소 | Own and allowed state | No | No | Approved operation — 후속 Feature, 현재 미구현 | No |
| 취소 결과와 환불 진행 요약 조회 | Own | No | No | Read for support | No |
| 주문 보상 case step 상세 조회 | No | No | No | Explicit permission | No |
| 매장 주문 보상 진행 축약 조회 | No | Owned store | Assigned store | Read for support | No |
| 매장 메뉴 조회 | Yes | Yes | Yes | Yes | Yes |
| 매장 메뉴 변경 | No | Owned store | Assigned store if permitted | Controlled | No |
| 주문 수락·제조 상태 | No | Owned store | Assigned store | Support only | No |
| 내 포인트 조회 | Own | No | No | Active explicit `POINT_ACCOUNT_READ` grant + reason | No |
| 감사형 포인트 조정 | No | No | No | Active explicit `POINT_ADJUSTMENT` grant + reason + evidence | No |
| 부분 환불 | No | Owned store with policy | Permission required | Approved operation | Read only |
| 매장 정산 조회 | No | Owned store | No by default | Yes | Yes |
| 이의제기 생성 | No | Owned store | No | No | No |
| 이의제기 판정 | No | No | No | No by default | Explicit permission |
| 재처리 | No | No | No | Explicit permission + reason | Settlement scope only |
| 누락 Refund 복구 제안 | No | No | No | Explicit permission + reason | No |
| 누락 Refund 복구 승인·거절 | No | No | No | 제안자와 다른 활성 operator + reason | No |
| 권한 변경 | No | Limited | No | Audited | No |
| 만료 혜택 복원 정책 조회·변경 | No | No | No | Active `EXPIRED_BENEFIT_POLICY_READ`/`WRITE` grant + reason | No |

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

정책 GET은 `EXPIRED_BENEFIT_POLICY_READ`와 `X-Access-Reason` header를, PATCH는
`EXPIRED_BENEFIT_POLICY_WRITE`와 request body reason을 요구한다. GET reason은 trim 뒤 1..200자,
control character 금지이며 current policy heads와 access Audit이 함께 저장된 경우에만 200이다.
포인트 조정은 `POINT_ADJUSTMENT` grant와 body reason/evidence를 요구한다. 상세는 ADR-069를 따른다.
고객 자신의 point-account/ledger read는 reason 없이 허용하지만, Platform Operator support read는
`POINT_ACCOUNT_READ` grant, `X-Access-Reason`과 target access Audit을 요구한다. customer request에서
header는 optional이고 operator branch에서만 required다.

매장 주문 명령은 JWT 역할과 Identity의 현재 `ACTIVE` membership을 모두 요구한다.
role과 membership role이 일치하지 않거나 membership이 `REVOKED`이면 `403`이다.

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
