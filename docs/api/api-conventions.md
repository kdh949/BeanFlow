# API Conventions

## Resource style

공개 API 계약은 역할별로 분리한다.

- `openapi/beanflow-v1-runtime.yaml`: 현재 source의 public controller mapping과 이를
  뒷받침하는 계약 테스트가 존재하는 request/response shape의 원본이다. 실제 non-local
  배포 증거를 뜻하지 않는다.
- `openapi/beanflow-v1.yaml`: Accepted ADR과 Active ExecPlan이 지향하는 pre-release target
  계약이다. 이 파일의 operation 존재만으로 현재 배포됐다고 판단하지 않는다.

두 파일은 `/docs`에서 Scalar로 렌더링된다. `processResources`가 `openapi/`를
`classpath:/openapi/`로 복사한다. `beanflow-v1-runtime.yaml`의 `./beanflow-v1.yaml#/...`
상대 참조는 브라우저에서 별도 HTTP 리소스 두 개를 오가며 resolve해야 해서 Scalar의 클라이언트
측 bundler가 안정적으로 처리하지 못하므로, `BundledOpenApiSpecProvider`가 앱 기동 시 두 파일을
SnakeYAML로 읽어 `components`를 병합하고 cross-file `$ref`를 local `#/...` 참조로 재작성한
단일 문서를 만들고, `DocumentationSpecController`가 이를 `GET /docs/spec/openapi.yaml`로
서빙한다. 이 복사본/병합본은 문서 표시 전용이며 계약 테스트는 여전히 저장소 루트의 `openapi/`
경로를 원본으로 읽는다. 문서 페이지는 매 요청마다 그 시점에 기동 중인 애플리케이션이 만든
최신 병합 스펙을 fetch하므로 별도 문서 빌드 없이 배포와 함께 최신화된다.

target operation은 Controller mapping과 계약·보안·실패 테스트가 함께 존재할 때 runtime
spec에 반영한다. Controller를 추가·제거하거나 shape를 바꾸는 변경은 runtime spec과 계약
테스트를 같은 변경에서 갱신한다. 두 spec 모두 `x-beanflow-contract-status`와
`x-beanflow-contract-date`를 가져야 한다. target schema를 외부 참조하는 runtime path item과
component는 문서 검증이 참조 존재를 확인한다. Runtime operation inventory는 별도 수동 목록이
아니라 `RuntimeOpenApiParityTest`가 Spring `RequestMappingHandlerMapping`과 양방향 비교한다.

## Authentication chains and CSRF

모든 `/api/v1` mapping은 중앙 registry에서 정확히 하나의 Chain에 명시적으로 배정한다. 새 mapping이
미배정이거나 두 Chain에 겹치면 애플리케이션 기동과 구조 테스트가 실패한다. 나머지를 Customer로
간주하는 default 분류는 없다.

| Chain | 인증 | 경로 |
|---|---|---|
| Public | 없음 | health, payment config, Operations OIDC config 예약 경로, `/docs/**`(Scalar API 문서) |
| Operations | Bearer JWT | `/operations/**`, `/support/**` |
| Merchant | PostgreSQL Session | `/auth/merchant/**`, `/merchant/**`, 매장 주문·정산 경로 |
| Customer | PostgreSQL Session | 명시적으로 등록된 나머지 고객 `/api/v1` 경로 |

Customer와 Merchant Session Cookie는 각각 `BEANFLOW_CUSTOMER_SESSION`,
`BEANFLOW_MERCHANT_SESSION`이며 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`다. unsafe 요청은
`GET /auth/customer/csrf` 또는 `GET /auth/merchant/csrf`가 발급한 actor별 XSRF Cookie 값을
`X-BEANFLOW-CSRF` header로 보내야 한다. 다른 actor의 Cookie·CSRF token이나 Operations Bearer를
브라우저 Session Chain에 보내면 fallback하지 않고 403이다. 인증 부재·무효 Session은 401, Session
저장소나 현재 계정 조회 장애는 503이다.

### Browser client contract

브라우저는 actor별로 서로 다른 API client를 쓴다. 하나의 client가 경로를 보고 인증 방식을
추론하지 않는다.

| Client | 인증 | 규칙 |
|---|---|---|
| `customerApi` | Session Cookie | `credentials: same-origin`. `Authorization` header를 붙이지 않고 unsafe method는 `X-BEANFLOW-CSRF`가 없으면 전송 전에 차단한다 |
| `merchantApi`, `operationsApi` | Bearer | 콘솔 전용. customer 경로에 사용하지 않는다 |

- `BEANFLOW_CUSTOMER_XSRF`는 JS로 읽는 CSRF Cookie이며 인증 정보가 아니다. Session Cookie는
  `HttpOnly`라 JS가 읽지 않는다. client는 CSRF token을 저장하지 않고 매 unsafe 요청에서 Cookie를
  읽어 header로 보낸다.
- 고객 화면은 customer ID, PointAccount ID, Order UUID를 입력 form이나 request body로 받지 않는다.
  API 응답이나 Provider callback으로 받은 opaque ID는 그대로 다시 호출에 쓸 수 있다.
- 브라우저 저장소는 schema version이 붙은 client cart(`beanflow.customer.cart.v1`)와 미해결 submit
  intent만 보관한다. Session, access token, password, provider key와 point/account UUID를 저장하지
  않는다. 로그아웃은 이 customer 상태만 지우고 다른 actor의 인증 상태는 건드리지 않는다.

`POST /settlement-items/{itemId}/disputes`는 Settlement Item 경로를 사용하지만 Dispute Context가
소유하는 resource다. handler는 Settlement internal repository를 직접 읽지 않고 confirmed Item
public view를 통해 검증하며, accepted decision은 Settlement public Adjustment command로 넘긴다.

`GET /merchant/me/stores`는 현재 `ACTIVE` membership을 top-level 배열로 반환한다. 빈 배열은
정상적인 “접근 가능한 매장 없음”이고, Identity 조회 실패는 빈 배열로 대체하지 않고 503이다.

## Status codes

### Support staged surface

The 80 Support/Delivery/LegalHold operations are kept in
[`support-api-surface.md`](support-api-surface.md). S20–S100 implement 74 Support/Operations operations in both
canonical OpenAPI files; S110 and later inventory rows remain `DRAFT`. An owning Stage adds endpoint-specific request, response/page, error and
security schemas to target OpenAPI only when its model is implementable. Runtime OpenAPI still requires a matching
Controller, contract, authorization and failure tests.

Exact phone/email input uses a POST body. Sensitive successful responses carry
`Cache-Control: no-store`; errors must not echo or retain raw PII. State-changing commands require
`Idempotency-Key`, same-key/changed-payload is 409, and execution re-evaluates current policy/version.
An endpoint may reuse ADR-070 only after its Stage records the exact typed filters and stable sort tuple. S20 records
the Case-list tuple in ADR-070; later Support cursor contracts remain unaccepted.

- `200 OK`: 조회 또는 동기 처리 결과
- `201 Created`: 새 리소스 생성
- `202 Accepted`: 비동기 처리 접수이며 완료가 아님
- `204 No Content`: 성공했으나 body 없음
- `400 Bad Request`: 형식·validation 오류
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 역할 또는 객체 수준 인가 실패
- `404 Not Found`: 접근 가능한 범위에서 리소스 없음
- `409 Conflict`: 상태 전이, 멱등 키 payload, 중복 자원 충돌
- `422 Unprocessable Entity`: 형식은 유효하지만 도메인 규칙 위반을 분리할 필요가 있을 때
- `503 Service Unavailable`: 필수 의존성 일시 장애
- 외부 결과 불명은 API 계약에 명시된 pending/unknown 표현 사용

## Store order board conditional reads and actions

`GET /api/v1/stores/{storeId}/orders`는 해당 매장의 모든 실행 상태
`PAID`, `ACCEPTED`, `PREPARING`, `READY`를 픽업 영업일별로 그룹화한다. `PAID`는 API lane
`PENDING_ACCEPTANCE`로 표현하며 새 Domain 상태가 아니다. 고객 개인정보, 내부 Order UUID와 결제
식별자는 응답하지 않는다.

- 200 응답은 정렬된 bounded `StoreOrderBoard`의 canonical **의미 Projection** SHA-256에서 만든 weak
  `ETag` (`W/"{sha256}"`)를 포함한다. 의미 Projection에는 groups, card fields, phase와 overflow의
  lane·count를 넣고 issuance·expiry를 가진 opaque overflow `nextCursor`는 넣지 않는다.
- `If-None-Match`는 쉼표로 구분한 tag, weak tag와 `*`를 처리한다. 현재 tag와 약하게 일치하면 304와 빈
  body를 반환한다. 304도 membership 확인과 Projection 조회·hash 계산을 수행하지만, 보드의 의미상 내용이
  같다는 뜻일 뿐 `nextCursor`의 TTL·유효성·response byte 동등성을 보장하거나 연장하지 않는다.
- `GET /api/v1/stores/{storeId}/orders/overflow`의 cursor가 만료·변조·scope 불일치로
  `400 INVALID_REQUEST`이면 client는 local queue와 board ETag를 버리고 unconditional main board snapshot을
  정확히 한 번 조회한다. 새 cursor로 queue를 자동 재시도하거나 overflow를 3초 polling에 넣지 않고,
  사용자의 다음 queue 열기 동작을 기다린다.
- `PAID`의 `OPEN`, `WARNING`, `TIMEOUT_PENDING` phase가 canonical Projection에 포함되므로 DB 변경이
  없어도 2분·3분 경계에서 tag가 바뀐다. hash 또는 Projection 실패를 full 200이나 빈 보드로
  대체하지 않고 503으로 반환한다.
- 상세와 전이는 UUID가 아닌 canonical `orderReference`를 사용한다. 다른 매장 reference는 403,
  접근 가능한 범위에 없는 reference는 404다.
- 전이 body는 `{action, expectedStatus, reason?}`다. action과 예상 출발 상태 조합 자체가 불가능하면
  `422 ORDER_ACTION_NOT_ALLOWED`, row lock 뒤 실제 상태가 달라졌으면
  `409 ORDER_STATE_CONFLICT`다. 같은 idempotency key의 exact replay는 이 비교보다 먼저 최초 응답을
  재생한다.
- 일반 전이는 200, `REJECT`는 Order 거절 commit 뒤 보상 진행을 분리해 202일 수 있다. 202는 환불·
  자원·혜택·알림 보상 완료가 아니다.

## PaymentMethod lifecycle

- `GET /api/v1/payment-methods`는 `CUSTOMER` 자신의 `ACTIVE`와 deactivation pending method만
  반환한다. terminal tombstone은 숨기고 내부 `MANUAL_REVIEW`는 공개 상태
  `DEACTIVATION_PENDING`과 선택적 `DEACTIVATION_DELAYED` notice로 축약한다.
- 목록은 common HMAC cursor, default 20·maximum 100과
  `(isDefault DESC, createdAt DESC, paymentMethodId DESC)`를 사용한다. customer scope는 cursor에
  서명되고 매 요청 인가를 다시 수행한다. default·상태 변경 사이의 snapshot은 보장하지 않는다.
- `POST /api/v1/payment-methods` request는 `authKey`, `displayAlias`만 허용하고 unknown field를
  거부한다. provider는 `TOSS_PAYMENTS`로 고정하며 PAN, CVC, expiry, 생년월일, 카드 비밀번호와
  내부 provider reference를 어떤 공개 schema에도 넣지 않는다.
- 등록 `201`은 새 PaymentMethod commit, `200`은 exact ACTIVE binding 수렴이 확인된 경우뿐이다. 결과 불명·
  수동 조사 중은 `202 PaymentMethodRegistration`, 명시적 무부수효과 거절은 422, Provider 설정·
  인증 결함은 503이다. 202를 등록 성공으로 해석하지 않는다.
- `DELETE /api/v1/payment-methods/{paymentMethodId}`는 Tx D1 commit부터 신규 결제를 막는다.
  Provider detach와 Tx D2가 확인되면 204, 그 전에는 `202 PaymentMethodDeactivation`이다. 202를
  Provider token 폐기 성공으로 해석하지 않는다.
- `PUT /api/v1/payment-methods/{paymentMethodId}/default`는 body가 없고 ACTIVE owner method만
  허용한다. default는 표시 선호이며 결제 승인에서 누락된 `paymentMethodId`를 보충하지 않는다.
- 공개 PaymentMethod는 ID, fixed provider, alias, brand, last4, default, 축약 상태와 시각만
  포함한다. token, provider customer reference, authKey/hash, claim, attempt와 raw Provider
  code/message는 응답하지 않는다.
- 네 lifecycle operation은 Controller와 계약·인가 테스트가 존재하므로 target과
  `openapi/beanflow-v1-runtime.yaml`에 모두 포함한다. 이 runtime 표시는 non-local 배포 증거가 아니다.

## Loyalty ledger projection

- Plan 13 V17/owner transaction이 `recoveryPendingKrw`, `ACCRUAL`과 `RECOVERY` storage contract를
  구현했다. Plan 14 read API는 이 값을 그대로 projection하며 0이나 ledger 합으로 대체하지 않는다.
- 고객 화면은 `GET /me/points`와 `GET /me/point-transactions`를 사용한다. 두 endpoint는 customer ID로
  PointAccount를 찾아 기존 Query Service를 호출하고 내부 `accountId`를 응답하지 않는다. 대응하는
  PointAccount가 없으면 0원 DTO, lazy-create 또는 404가 아니라 `503
  POINT_ACCOUNT_INTEGRITY_FAILURE`다. `GET /me/points`의 `expiring`은 미만료이면서 available 잔액이
  남은 PointLot의 `(expiresAt, amountKrw)` 최소 projection이며 조회 실패를 생략하지 않는다.
- `GET /point-accounts/{accountId}`와 `/transactions`는 account UUID를 이미 아는 운영 support 경로로
  유지한다. Customer Session은 두 경로에서도 자기 소유권만 사용한다.
  운영자 조회는 `GET /operations/point-accounts/{accountId}`와 `/transactions`로 분리하고 Bearer JWT,
  active `POINT_ACCOUNT_READ`, required `X-Access-Reason`과 접근 Audit을 요구한다. 한 URI에서 두 인증
  방식을 판별하지 않는다.
- `GET /point-accounts/{accountId}`의 `recoveryPendingKrw`는 음수 잔액이 아니라
  Loyalty `PointRecoveryPending(PENDING)` remaining 합계다.
- `GET /point-accounts/{accountId}/transactions`의 `amountKrw`는 DB에 저장한 양수
  magnitude가 아니라 공개 잔액 signed effect다. `RECOVERY`는 환불 적립 포인트의 실제
  차감이므로 음수이고, 미회수 부족액 자체는 transaction으로 표시하지 않는다.
- `RESTORE_SKIPPED_EXPIRED`는 정책 적용은 성공했지만 가용 잔액을 늘리지 않으므로
  공개 amount가 0이다. client는 type과 signed amount를 조합해 PointAccount summary를
  추측하지 않는다.
- `ADJUSTMENT`는 `balance_effect`가 CREDIT이면 양수, DEBIT이면 음수로 반환하는
  감사형 운영 correction이다. 공개 amount 부호를 DB의 양수 magnitude와 혼동하지
  않는다.

## Audited point adjustment

`POST /operations/point-accounts/{accountId}/adjustments`는 active `PLATFORM_OPERATOR`의
Operations-backed explicit `POINT_ADJUSTMENT` grant, Idempotency-Key, nonzero signed amount, non-blank
reason과 evidence를
요구한다. 양수 amount에는 issuer와 미래 `expiresAt`이 필수이고 음수 amount에는 두
필드가 있으면 안 된다. 성공 `201`은 Account summary와 실제 생성·차감 transaction 목록을
반환하며 replay 표시를 추가하지 않는다. debit 가용 Lot 부족은 409이고 부분 차감·0원
성공·PointRecoveryPending 생성으로 대체하지 않는다.

## Order creation response

`POST /api/v1/orders`의 201 body는 `{order, payment?}` 형태의 상태별 생성 결과다.

- 외부 결제가 필요한 variant는 `order.state=PENDING_PAYMENT`,
  `reservationExpiresAt` 필수, `payment` 필드 없음이다.
- payable 0인 variant는 `order.state=PAID`, `payableKrw=0`,
  active `reservationExpiresAt` 없음, `payment.type=BENEFIT_ONLY`,
  `payment.approvalState=APPROVED`, `approvedAmountKrw=0`이 필수다.
- 두 variant 모두 Order와 필요한 예약·Payment가 commit된 뒤에만 201을 반환한다.
- 같은 주문 생성 idempotency key/payload replay도 저장된 최초 201 envelope를
  그대로 반환한다.

## Fast reorder

`POST /api/v1/orders/{sourceOrderId}/reorders`는 별도 draft나 Reorder Aggregate를
만들지 않고 현재 조건으로 새 Order를 즉시 생성한다.

- 호출자는 `CUSTOMER`이며 source Order의 소유 고객이어야 한다. source가 없으면 404,
  다른 고객 소유이면 403이다.
- source는 `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED` 중 하나여야 한다.
  `PENDING_PAYMENT`, `PAID`, `ACCEPTED`, `PREPARING`, `READY`는
  `409 REORDER_SOURCE_STATE_INVALID`다.
- 서버는 source line의 `menuId`, 정규화된 `optionIds`, `quantity`만 입력으로 재사용한다.
  과거 이름·가격·혜택·결제·환불·pickup slot·예약·정산 snapshot은 복사하지 않는다.
- request body는 새 `pickupSlotId`와 `pointsToUseKrw`를 필수로, 새로 적용할
  `couponIssuanceId`를 선택적으로 받는다. 결제수단은 받지 않으며 1원 이상 결제는 생성된
  Order의 기존 payment-confirmation 명령으로 별도 승인한다.
- Merchant 가격·판매 가능성·MenuConfiguration, Fulfillment slot, Inventory 재고,
  Promotion coupon, Loyalty point를 기존 주문 생성 transaction에서 모두 다시 검증·예약한다.
  하나라도 사용할 수 없으면 `409 REORDER_ITEMS_UNAVAILABLE` 또는 기존 owner conflict로
  전체 실패하며 부분 Order를 만들거나 품목을 자동 삭제하지 않는다.
- legacy source line에 검증된 정규화 option ID snapshot이 없으면 옵션명이나 현재 Merchant
  상태로 추론하지 않고 `SOURCE_OPTION_SELECTION_UNAVAILABLE`로 해당 line을 실패시킨다.
- 201 body는 기존 주문 생성의 상태별 `order`/`payment?` 의미와 required
  `priceComparison`을 함께 반환한다. 가격 비교는 source/current의 혜택 적용 전 subtotal과
  가격이 바뀐 line만 포함하며 signed difference는 `current - source`다. coupon·point·payment
  차이는 가격 변경에 포함하지 않는다.
- 동일 key/payload replay는 최초 201 또는 확정 실패 status/body를 그대로 반환하며
  `replayed` 표시를 추가하지 않는다.

## Human-facing order identifiers

Order UUIDs remain internal aggregate/FK/event identifiers. Human-facing customer and merchant routes use the
canonical public reference `BF-XXXX-XXXX`, whose alphabet is
`23456789ABCDEFGHJKMNPQRSTUVWXYZ`. Input is uppercased before strict format validation; whitespace and ambiguous
characters are rejected rather than guessed.

- Customer read/cancel: `GET /api/v1/me/orders/{orderReference}` and
  `POST /api/v1/me/orders/{orderReference}/cancellations`.
- Store read/transition: `GET /api/v1/stores/{storeId}/orders/{orderReference}` and
  `POST /api/v1/stores/{storeId}/orders/{orderReference}/transitions`.
- A public reference is not authorization. Customer lookup includes `customerId`; store lookup includes `storeId`
  and requires current active membership. An existing reference outside that scope returns 403 and a missing
  reference returns 404.
- New public-reference responses omit internal `orderId`. Existing UUID routes remain during compatibility migration
  and keep their existing UUID response fields.
- `pickupNumber` is `A-` plus the unpadded positive per-store/per-Seoul-business-date sequence. It is display-only and
  never a lookup key.
- Store name and pickup window fields are immutable order snapshots. Reads do not replace them with current Merchant
  or Fulfillment values.

The Plan 10 runtime customer/store detail representations intentionally retain the existing rich Order fields minus
`orderId`. Plans 50 and 60 replace those transitional shapes with the dedicated customer read model and store board
contracts; Plan 10 does not implement those later projections.

## Customer order cancellation

`POST /api/v1/orders/{orderId}/cancellations`의 성공 표현은 취소 시점 Order 상태에
따라 두 갈래다.

- `PENDING_PAYMENT` 취소는 슬롯·재고·쿠폰·포인트 해제와 Order 전이가 모두 commit된
  뒤 `200 OK`를 반환한다. `paymentRecovery.state`는 `NOT_REQUIRED`다.
- `PAID` 취소는 `202 Accepted`를 반환한다. Order `CANCELLED`가 확정됐다는 뜻이며
  환불·자원 복원·알림 성공을 뜻하지 않는다.
- 별도 Cancellation Aggregate가 없으므로 `201 Created`와 `cancellationId`를 사용하지
  않고 `orderId`로 식별한다.
- request body는 필수이며 `reasonCode`가 필수, `detail`이 선택이다. `detail`은
  저장만 하고 어떤 API 응답, event payload, 감사 기록, Provider 요청과 로그에도
  포함하지 않는다.
- 취소 이후 환불 진행은 새 endpoint 없이 `GET /api/v1/orders/{orderId}`의
  `paymentRecovery`로 조회한다. 이 요약은 Refund aggregate에서만 파생하며 보상 case의
  PAYMENT step 상태에서 파생하지 않는다.
- `paymentRecovery.state`는 이번 고객 취소 source의 Refund 한 건만 원천으로 삼고
  다른 Refund나 보상 step을 합성하지 않는다. 내부
  `PROCESSING`·`RETRY_SCHEDULED`·`UNKNOWN`·`RECONCILING`은 고객
  `PROCESSING`, 내부 `FAILED`·`MANUAL_REVIEW`는 고객
  `PROCESSING + noticeCode: REFUND_DELAYED`로 투영한다. 고객 응답에는 attempt,
  실패 code와 수동 검토 여부를 포함하지 않는다.
- 취소 요청 현금액이 0일 때만 `NOT_REQUIRED`이고, 양수인데 Refund 또는 recovery
  snapshot이 없으면 내부 `SETUP_INCOMPLETE`다. 고객에게는
  `PROCESSING + noticeCode: REFUND_DELAYED`로 투영하고 운영자에게만 누락 원천을
  노출한다.
- `NOT_REQUIRED`는 요청액이 0이라는 뜻일 뿐 네 금액이 모두 0이라는 뜻이 아니다.
  선행 환불이 승인액을 전부 반환해 요청액이 0이 된 미수락 `PAID` 취소는
  `NOT_REQUIRED`이면서 `approvedAmountKrw`와
  `succeededRefundAmountBeforeCancellationKrw`가 양수다.
- `BENEFIT_ONLY` 취소는 snapshot과 네 금액이 모두 0이고 Refund가 없으며
  `state = NOT_REQUIRED`, `noticeCode` 부재다. 나머지 비동기 보상 때문에 `PAID`
  취소 응답은 계속 `202`다.
- `PENDING_PAYMENT` 취소는 외부 승인과 recovery snapshot 자체가 없으므로
  `state = NOT_REQUIRED`이면서 네 금액을 0으로 표현하지 않고 함께 생략한다. 네
  금액이 모두 0인 것은 `BENEFIT_ONLY` 취소뿐이다.
- 정상 setup의 `paymentRecovery`는 `approvedAmountKrw`,
  `succeededRefundAmountBeforeCancellationKrw`,
  `cancellationRequestedRefundAmountKrw`, `remainingRefundableAmountKrw`를 필수로
  반환한다. 앞의 세 값은 취소 Tx C1 snapshot이고, 마지막 값은 조회 시점 승인액에서
  `SUCCEEDED` Refund 성공액만 차감한 현재 실제 잔액이다. snapshot 손상으로 검증할 수
  없는 금액은 0이나 현재값으로 추정하지 않고 생략한다.
- 네 금액은 all-or-nothing이다. `REQUESTED`, `SUCCEEDED`와 notice 없는
  `PROCESSING`은 네 금액을 모두 반환하고, recovery snapshot이 없는
  `PENDING_PAYMENT` 취소와 setup 손상의 `PROCESSING + REFUND_DELAYED` 투영만 네
  금액을 함께 생략할 수 있다.
- 취소 POST의 `paymentRecovery`는 commit 시점 snapshot이고 멱등 재생에서도 그대로다.
  최신 state와 `remainingRefundableAmountKrw`는 Order GET으로 조회한다.
- 보상 step 상세는 운영자 전용 `GET /api/v1/operations/orders/{orderId}/compensation`
  에서만 조회한다. 이 endpoint는 여섯 step, `attemptCount`, `lastErrorCode`,
  `caseId`와 두 benefit policy version을 담은 `CompensationSummary`를
  `OperatorCompensationView`로 감싸 setup issue와 ReprocessingCase reference를
  추가한다.
- 매장 응답 `StoreOrderResult.compensationRecovery`는 `trigger`, case `state`와
  `updatedAt`만 담은 축약 `StoreCompensationSummary`다. 매장은 거절과 고객 취소를
  구분하고 보상이 진행 중인지 확인하되 step 배열, 시도 횟수, 내부 오류 code, case
  식별자와 정책 version은 보지 않는다(ADR-030, ADR-033).
- 취소 사실은 취소 POST 응답뿐 아니라 Order 표현에도 노출하며 역할별 projection을
  분리한다. 고객용 `Order`는 `rejectedAt`·`rejectionReason`과 대칭으로
  `cancelledAt`, `cancellationCause`(`CUSTOMER_REQUEST`, `PAYMENT_DECLINED`)와
  `cancellationReasonCode`를 optional로 반환하고 `CANCELLED`가 아니면 세 필드가
  모두 부재다. 매장용 `StoreOrder`는 `Order`에서 `cancellationReasonCode`와
  `paymentRecovery`를 제외한 projection이며 `StoreOrderResult.order`가 이를
  참조한다. 자유 입력 `detail`은 두 projection 모두에서 계속 부재다.
- 비허용 상태는 `409 ORDER_STATE_CONFLICT`, lease 만료는 `409 RESERVATION_EXPIRED`
  이며 자원 해제 실패는 `503 DEPENDENCY_UNAVAILABLE`다.
- 존재하지 않는 주문은 `404`, 타 고객 주문은 `403`으로 조회와 같은 코드를 사용한다.

## Idempotency

- 주문 생성, 빠른 재주문, 주문 취소, 결제 승인, 결제수단 등록·default 지정·폐기, 환불,
  매장 주문 상태 전이, 감사형 포인트 조정,
  이의제기와 운영자 재처리는 `Idempotency-Key`를 요구한다.
- scope: actorId + operation + key
- 같은 key + 같은 payload: 기존 결과
- 같은 key + 다른 payload: `409`
- business response와 header에는 `replayed` indicator를 넣지 않는다. terminal
  command는 저장된 최초 status/body를 그대로 반환하고 replay 여부는 내부
  IdempotencyRecord, metric과 structured log에서만 관측한다.
- 외부 결과가 non-terminal `UNKNOWN`인 Payment 승인·환불은 새 Provider 호출 없이
  현재 durable representation을 반환하는 예외다. 이 경우에도 replay indicator는
  없다.
- PaymentMethod 등록과 폐기의 same-key replay도 새 Provider 호출 없이 최초 terminal response
  또는 현재 202 representation을 반환한다. 등록 unknown은 일회성 authKey를 다시 보내지 않고,
  폐기 unknown은 DELETE를 다시 보내지 않는다. default command의 terminal 200 replay는 현재
  default를 다시 변경하지 않는다.
- 처리 중인 키는 부작용을 다시 실행하지 않는다. Payment처럼 현재 durable
  representation 계약이 있는 명령만 그 representation을 반환하고, 주문 생성은 아래의
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`, 고객 취소는 Order row lock 직렬화 규칙을
  따른다.
- 주문 생성은 같은 key/payload의 `COMPLETED` 또는 `FAILED` record에 저장된 최초
  HTTP status와 body를 그대로 재생한다. 따라서 성공 replay도 최초 `201 Created`를
  유지하고 확정 실패 replay도 최초 4xx/503을 유지한다.
- 주문 생성 record가 아직 `PROCESSING`이면
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환하며 202나 성공
  representation으로 바꾸지 않는다. 새 실행을 원하면 기존 결과가 확정된 뒤 계약에
  따라 새 key를 사용한다.
- 주문 생성 record가 `MANUAL_REVIEW`이면 자동 처리는 이미 중단됐다.
  `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 반환하고 `Retry-After`를 넣지 않으며 같은
  key로 owner 작업을 다시 실행하지 않는다. 공식 운영자 해결 command가 생기기 전에는
  terminal body를 추정하거나 DB row를 직접 변경하지 않는다.
- 빠른 재주문은 주문 생성과 분리된 operation `REORDER_ORDER_V1`을 사용하되 같은
  사전등록 모델을 쓴다. canonical payload는 `sourceOrderId`, `pickupSlotId`,
  `couponIssuanceId`(null 포함), `pointsToUseKrw`이고 source line은 immutable source
  snapshot이므로 hash에 중복 직렬화하지 않는다. 같은 key를 다른 source 또는 request에
  사용하면 `409 IDEMPOTENCY_KEY_REUSED`, 같은 key/payload가 `PROCESSING`이면
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`다. `MANUAL_REVIEW`이면
  `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`이고 `Retry-After`는 없다.
- 특정 Aggregate를 대상으로 하는 명령은 대상 식별자를 canonical payload에 포함한다.
  고객 취소와 매장 주문 상태 전이 모두 `orderId`를 포함하므로 같은 key를 다른 주문에
  재사용하면 `409 IDEMPOTENCY_KEY_REUSED`이며 다른 주문의 응답을 재생하지 않는다.
- 감사형 포인트 조정은 `accountId`, signed amount, issuer/expiry, reason과 evidence를
  canonical payload에 포함한다. PointAccount lock 아래의 명령 transaction이므로
  `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용하지 않고, 같은 key·payload는 최초 `201`
  body를 재생한다.
- 고객 주문 취소는 `PROCESSING` 사전등록 없이 명령 트랜잭션 하나에서 Order 잠금, 멱등
  레코드 조회, 취소와 최초 응답 저장을 함께 커밋한다. 같은 key·같은 payload 재요청은
  저장된 최초 `200` 또는 `202` body를 그대로 반환하며 `replayed` 같은 표시 필드를
  추가하지 않는다. 재생 body의 `paymentRecovery`는 취소 시점 snapshot이므로 최신 진행은
  `GET /api/v1/orders/{orderId}`로 조회한다.
- 취소의 canonical payload는 `orderId`, `reasonCode`, 정규화한 `detail`이다. 같은 key를
  다른 주문에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`이며 첫 주문의 응답을 재생하지
  않는다. 다른 key로 이미 취소된 주문을 다시 취소하면
  `409 ORDER_STATE_CONFLICT`다.
- 취소는 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용하지 않는다. 동시 같은 key 요청은
  Order row lock으로 직렬화되며, 잠금 대기가 요청 timeout을 넘기면
  `503 DEPENDENCY_UNAVAILABLE`이다. 롤백된 취소는 멱등 레코드를 남기지 않으므로 확정
  실패를 재생하지 않고 같은 key 재시도가 재실행된다.
- Payment 결과가 `UNKNOWN` 또는 `RECONCILING`이면 `202 Accepted`와 Payment
  representation을 반환한다. 같은 key/payload 재시도는 reconciliation이 진행 중인
  동안 202, 승인 확정 후 200을 반환할 수 있지만 새 승인 요청을 만들지 않는다.
- `PAYMENT_RESULT_UNKNOWN`은 확정 실패를 뜻하는 409 error가 아니다. 409는 상태 전이
  충돌 또는 같은 key의 다른 payload에만 사용한다.

## Error envelope

```json
{
  "code": "PAYMENT_RESULT_UNKNOWN",
  "message": "결제 결과를 확인 중입니다.",
  "correlationId": "string",
  "details": []
}
```

- 내부 예외명, SQL, secret과 stack trace를 노출하지 않는다.
- client가 재시도 가능한지 error catalog에 정의한다.
- 실패를 빈 성공 응답으로 바꾸지 않는다.

## Authentication and authorization

- 초기 계약은 Bearer token 인증을 전제로 한다.
- 401은 인증 실패, 403은 역할 또는 객체 수준 인가 실패다.
- 존재 여부 노출을 제한해야 하는 리소스는 인가 정책에 따라 404를 사용할 수 있지만,
  같은 operation 안에서 일관되게 적용한다.
- Store Owner와 Staff 요청은 `storeId` 소유권 또는 membership을 Application Service가
  검증한다.
- privileged Platform Operator request는 role 외에 Operations-owned active explicit grant를
  Application Service에서 검증한다. grant lookup/Audit dependency failure는 role/JWT claim
  fallback이 아니라 `503 DEPENDENCY_UNAVAILABLE`이고, active grant 부재는 403이다.

주문 생성의 menu ID가 존재하지 않거나 option ID가 해당 menu에 속하지 않거나
정규화한 option 집합에 대응하는 MenuConfiguration이 없으면
`400 INVALID_REQUEST`다. 존재하는 MenuConfiguration이 현재 판매 불가하면
`409 MENU_CONFIGURATION_NOT_AVAILABLE`이며 재고 부족과 구분한다.

## Dates and money

- API 시각은 ISO-8601 offset 또는 UTC instant
- 제품 기준 timezone은 `Asia/Seoul`
- KRW 금액은 정수 원
- float/double로 금액을 표현하지 않는다.

## Pagination

- 목록은 안정적인 cursor를 우선 검토한다.
- common cursor는 ADR-070의 `v1.<key-id>.<payload>.<signature>` HMAC-SHA-256 format을
  사용한다. endpoint, canonical filter hash, stable sort tuple과 endpoint별 최대 24시간 expiry를 signature에
  bind한다. S20 Case list expiry는 15분이다.
- v1 payload는 whitespace-free UTF-8 JSON이며 property 순서는 `endpoint`, `filterHash`, `sort`,
  `issuedAt`, `expiresAt`로 고정한다. 추가 property와 `null`은 허용하지 않고, `sort`는 순서를 보존하는
  string array, 두 시각은 JSON integer epoch second, UUID sort value는 lowercase canonical UUID다.
  payload/signature는 padding 없는 Base64URL이며 `now >= expiresAt`은 만료다.
- 매장 거리 검색 cursor는 `(distanceMicrometers, storeId)`를 사용하고 response의
  `distanceMeters`는 display-only floored integer다. Point ledger는
  `(occurredAt DESC, transactionId DESC)`, 정산 Batch는 `(settlementDate DESC,
  settlementBatchId DESC)`, Batch Item은 `(completedAt ASC, settlementItemId ASC)`를 사용한다.
- Nearby `radiusMeters`는 `1..10000`이다. PostGIS raw range predicate, micrometer sort/cursor
  tuple, response meter conversion과 latitude/longitude decimal normalization은 ADR-070이 canonical이다.
- 정렬 기준과 tie-breaker를 문서화한다.
- cursor는 내부 값을 직접 수정할 수 없는 opaque string으로 전달한다. malformed, signature/
  version/expiry/filter scope mismatch는 query 실행 전 `400 INVALID_REQUEST`다.
- request와 `nextCursor`의 public maximum length는 `2048`이다.
- cursor HMAC secret/active key configuration은 required startup dependency다. key rotation은
  이전 verifier key를 최대 24시간 유지한다. key ring은 duplicate ID를 검출하는 list이고 padding 없는
  Base64URL decode 뒤 secret은 최소 32 bytes여야 한다. malformed key, empty ring, unknown active key,
  short secret과 fallback secret은 startup failure다.
- 공개 test-vector 전용 key material은 deterministic test source에만 둘 수 있고 production/local runtime
  configuration, 실제 deployment environment variable 이름, log, test output 또는 운영 fallback에 쓸 수 없다.
- common `limit`은 optional이며 default 20, minimum 1, maximum 100이다.
- 응답은 `nextCursor`가 있을 때만 다음 page가 있음을 뜻한다.
- 매장 카탈로그 조회는 cursor 대신 자체 경계를 쓴다. 픽업 슬롯은 `starts_at`이 지금부터 7일
  이내인 것만, 메뉴는 한 매장당 메뉴 1,000개·옵션 5,000개까지다. 두 상한 모두 잘라서 반환하지
  않는다. 메뉴 상한을 넘으면 `503 DEPENDENCY_UNAVAILABLE`로 실패한다. ADR-076이 canonical이다.
- 일반 포인트 적립 정책의 GLOBAL/STORE history와 STORE head 목록도 같은 signed cursor를 사용한다.
  version history는 `policyVersionId DESC`, STORE head는 `(policyVersionId DESC, storeId DESC)`이며
  cursor는 endpoint, GLOBAL/STORE scope와 optional state filter에 bind된다.
- S20 Case list는 `(openedAt DESC, caseId DESC)`이며 cursor를 endpoint, optional state와 optional assignee ID에
  bind한다. response에는 interaction/note collection을 포함하지 않는다.

## Ordinary point accrual policy operations

- 모든 조회는 `PLATFORM_OPERATOR`, active `POINT_ACCRUAL_POLICY_READ`, 정규화된
  `X-Access-Reason`과 같은 transaction에서 commit된 Audit가 필요하다.
- GLOBAL 및 STORE 변경은 active `POINT_ACCRUAL_POLICY_WRITE`와 `Idempotency-Key`를 요구한다.
  GLOBAL은 현재 expected version이 필수이고, 최초 STORE version은 expected version을 생략한다.
- STORE `INHERIT_GLOBAL` body에는 policy value를 넣지 않는다. 이 version은 history를 보존하면서
  이후 주문만 current GLOBAL을 선택하게 한다.
- 이 operation들은 Controller와 계약 테스트가 있으므로 target과
  `openapi/beanflow-v1-runtime.yaml`에 모두 존재한다. 이 사실은 non-local deployment를
  주장하지 않는다.

## Payment and asynchronous recovery

- `202 Accepted`는 승인 또는 환불 성공을 뜻하지 않는다.
- Payment approval 상태는 `APPROVED`, `UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW`를 구분한다.
- `PaymentConfirmation.recovery`는 상태와 시각만 가진
  `PaymentApprovalRecoverySummary`를 사용한다. 고객 취소 환불의 notice와 금액
  allocation을 이 schema에 넣지 않는다.
- 내부 Refund 상태는 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`,
  `FAILED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`를 구분한다. 고객 취소
  `CancellationRefundRecoverySummary`는 위 customer projection을 적용한다.
- Order `REJECTED` 또는 `CANCELLED`와 Payment refund 성공은 같은 상태가 아니다.
- 5분 reservation lease가 Payment `UNKNOWN`보다 먼저 끝나면 Order는 `EXPIRED`를
  유지하며, 202 representation은 뒤늦은 승인에 대한 void/refund recovery 상태를
  명시한다.

## Expired benefit restoration policy

- 정책 resource key는 `trigger × benefitType`이다.
- base GET은 다섯 현재 head를 안정적으로 trigger, benefit type 순서로 반환한다. 이 호출은
  `EXPIRED_BENEFIT_POLICY_READ` active grant와 required `X-Access-Reason`을 요구하고,
  reason은 trim 뒤 1..200자·control character 금지다. response와 access Audit은 같은 local
  transaction에서 함께 저장된 경우에만 반환한다.
- keyed PATCH는 path의 trigger·benefit type 한 head만 새 append-only version으로
  갱신한다. `EXPIRED_BENEFIT_POLICY_WRITE` active grant, `Idempotency-Key`,
  `expectedPolicyVersionId`, mode, validity days와 reason이 필수다.
- version row는 수정·삭제하지 않고 과거 Case는 저장한 COUPON·POINTS version FK를
  계속 사용한다.

## Reservation expiry materialization

- `now >= reservationExpiresAt`인 `PENDING_PAYMENT` Order의 조회와 결제 명령은
  응답 전에 동일한 idempotent expiry transaction을 실행한다.
- 조회는 만료와 네 자원 해제가 모두 commit된 뒤 `EXPIRED` Order를 반환한다.
- 결제 명령은 만료 commit 뒤 `409 RESERVATION_EXPIRED`를 반환한다.
- expiry transaction이 실패하면 stale `PENDING_PAYMENT`나 부분 해제를 정상
  response로 반환하지 않고 `503 DEPENDENCY_UNAVAILABLE`를 반환한다.
- GET이 일으키는 write는 clock에 의해 이미 due가 된 상태 전이를 materialize하는
  범위로 한정하며 반복 조회가 추가 release를 만들지 않는다.

## Versioning

초기 URI version은 `/api/v1`을 사용한다. 호환되지 않는 변경은 별도 version 또는 명시적 migration을 요구한다.
