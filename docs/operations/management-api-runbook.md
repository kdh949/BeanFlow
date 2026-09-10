# 관리 API 운영 절차

API 추가가 운영 grant 발급이나 배포를 수행하지는 않는다. 기존 운영 권한 발급 절차에서 목적별 grant를
명시적으로 발급해야 한다. 원장 replay도 현재 grant 또는 ACTIVE membership을 다시 확인한다.

## 정산 이의제기

1. `GET /api/v1/operations/settlement-disputes?storeId={storeId}`로 매장별 이의를 조회한다.
   `SETTLEMENT_DISPUTE_READ`가 필요하며 state, signed cursor, limit(1~100)을 사용할 수 있다.
2. 상세 `GET /api/v1/operations/settlement-disputes/{disputeId}`에서 state, version, 근거와 pendingDecision을 확인한다.
3. FILED이면 `POST .../{disputeId}/reviews`에 `Idempotency-Key`와 `{expectedVersion, reason}`을 보내 검토를 시작한다.
   자동 review worker가 이미 전환했다면 최신 version을 다시 읽는다.
4. `POST .../{disputeId}/decisions`에 `{outcome: ACCEPTED|REJECTED, expectedVersion, reason}`을 보낸다.
   검토·판정에는 `SETTLEMENT_DISPUTE_DECIDE`가 필요하다. 조정액은 접수된 원본 금액을 사용한다.
5. 503 또는 응답 유실이면 **같은 키와 같은 payload**로 재시도한다. 의도 저장 후 실패하면 상세는
   UNDER_REVIEW와 pendingDecision을 반환한다. 다른 판정·철회는 409이며 DB에서 intent를 지우지 않는다.
   다른 권한 있는 운영자도 최신 version으로 같은 pending 판정을 접수해 재개할 수 있다.
   실제 판정 Audit는 최초 intent actor/reason/time을 보존하고 새 재개 요청 Audit는 재개 actor를 기록한다.
6. 점주는 `GET /api/v1/stores/{storeId}/disputes/{disputeId}`로 상세를 확인하고 UNDER_REVIEW에서만
   `POST .../{disputeId}/withdrawals`를 요청한다. OWNER membership, Merchant Session, CSRF,
   Idempotency-Key와 `{expectedVersion, reason}`이 필요하다.

승인은 동일 `dispute:{id}:accepted` source의 SettlementAdjustment를 재사용한다. accepted intent가 먼저
commit되므로 Adjustment 이후 event/Audit 실패가 반대 판정으로 바뀌지 않는다. 성공은 response 원장과
판정·Audit·event publication 저장까지 commit된 상태다. listener의 후속 성공은 별도 event 복구 상태로 확인한다.

완료 command response는 90일 후 최대 100행씩 삭제하며 pending command는 삭제하지 않는다.
판정 intent와 감사 이력은 command cleanup과 함께 삭제하지 않는다. 원래 이의 접수·재이의·보류액 정책은 유지한다.

## 매장 개설과 식별 정보

1. `STORE_IDENTITY_READ`로 `GET /api/v1/operations/store-regions?query=...`에서 확인된 법정동 코드를 조회한다.
   빈 query는 전체 어휘를 순회한다. 지역과 매장 목록은 actor/filter에 묶인 signed cursor와 limit 1~100을 사용한다.
2. `STORE_IDENTITY_WRITE`로 `POST /api/v1/operations/stores`에 Idempotency-Key와
   `{name, latitude, longitude, regionCode, reason}`을 보낸다. 좌표 범위와 유한수, 지역 존재를 검증한다.
3. 응답의 acceptingOrders와 pickupEnabled는 false다. 실제 계약·메뉴·픽업·소속과 운영 설정을 별도로 구성한다.
   이름·좌표·지역 검색어는 개설 트랜잭션에서 함께 저장하며 기본 계약이나 슬롯은 생성하지 않는다.
4. `GET /api/v1/operations/stores?query=...` 또는 `GET /api/v1/operations/stores/{storeId}/identity`로 확인한다.
5. 이름·좌표 변경은 `PUT /api/v1/operations/stores/{storeId}/identity`에
   `{name, latitude, longitude, expectedVersion, reason}`을 보낸다. 여기의 version은 식별 정보 전용 버전이다.
   이미지·주문 정책 변경과 독립이며 지역 코드는 기존 점주 지역 지정 경로가 관리한다.

매장 root와 profile을 잠근 뒤 변경하므로 카탈로그/주문 및 지역 writer와 직렬화된다. 검색어 또는 Audit 저장이
실패하면 profile·매장·응답 원장도 rollback한다. 동일 키/payload는 최초 응답을 재생하고, 다른 payload 또는
오래된 identity version은 409다. 완료 응답 원장은 90일 후 최대 100행씩 정리한다.

매장 개설/식별 정보 감사에는 전후 identity version과 profile digest를 기록한다. 실제 좌표와 지역 코드, 이름을
Audit에 복사하지 않으며 권한이 필요한 매장 상세에서 조회한다. 사용자 입력 사유에도 기존 raw PII 금지 규칙이 적용된다.

## 픽업 슬롯

- 같은 매장의 ACTIVE OWNER/STAFF는 `GET /api/v1/stores/{storeId}/pickup-slot-management?from=...&to=...`로
  해당 구간 `[from, to)`에 시작하는 슬롯을 조회한다. limit 1~100, actor/store/interval-bound signed cursor를 사용한다.
- `POST /api/v1/stores/{storeId}/pickup-slot-management`에 `{startsAt, endsAt, capacity, reason}`과
  Idempotency-Key, Merchant Session/CSRF를 보내 슬롯을 만든다. 시각은 microsecond 정밀도로 정규화하고
  미래 시작·유효한 종료·0 이상 정원을 요구한다. 서로 다른 명령으로 만든 슬롯은 독립된 정원을 가진다.
- `GET .../{slotId}`에서 실제 예약/확정 수량과 version을 확인한다. `PUT .../{slotId}`는 생성 필드와
  expectedVersion을 받는다. 현재 사용량보다 작은 정원, 사용 중인 슬롯의 시간 이동, 이미 시작한 슬롯의
  변경을 거절한다. 미래의 미사용 슬롯은 capacity 0으로 닫을 수 있다.
- 예약과 관리는 동일 PickupSlot row lock을 사용한다. 예약이 먼저 commit하면 version 또는 사용량 guard가
  관리를 거절한다. 정원 0 변경이 먼저 commit하면 신규 예약이 PICKUP_SLOT_FULL로 실패한다.
- 동일 key/payload는 최초 응답을 재생하며 replay 전 현재 membership을 다시 확인한다. 예약/확정 수량은
  관리 요청에서 받지 않는다. Audit/response 실패는 변경을 rollback하며 response 원장은 90일 후 bounded cleanup한다.

## 수수료 계약 버전

1. `STORE_SETTLEMENT_TERMS_READ`로 `GET /api/v1/operations/stores/{storeId}/settlement-terms`를 조회한다.
   revision은 기존 데이터를 포함한 해당 매장 불변 계약 수다. actor/store-bound signed cursor와 limit 1~100을 사용한다.
2. `GET .../{termsVersionId}`로 계약 근거 reference, bps 수수료율과 적용 구간을 확인한다.
3. `STORE_SETTLEMENT_TERMS_WRITE`로 `POST .../settlement-terms`에 Idempotency-Key와
   `{sourceReference, feeRateBps, effectiveFrom, effectiveTo, expectedRevision, reason}`을 보낸다.
   수수료는 0~10000 bps, 시작은 미래이며 종료는 시작 이후 또는 null이다. 시각은 microsecond로 정규화한다.
4. 기존 계약은 수정/삭제하지 않는다. 반개구간이 겹치거나 sourceReference가 중복되거나 revision이 오래되면 409다.
   종료일이 없는 기존 계약과 겹치는 새 계약도 등록할 수 없다. 강제 종료·과거 계약 정정은 별도 정책 결정 대상이다.
5. 오류나 응답 유실은 같은 키/payload로 재시도한다. 현재 grant 확인 뒤 최초 응답을 재생한다.
   완료 응답은 90일 후 최대 100행씩 정리한다. 계약과 과거 주문 snapshot은 이 정리에서 삭제하지 않는다.

계약 writer는 Store 배타 잠금으로 최종 주문 quote와 직렬화한다. 기존 주문의 수수료 snapshot은 바뀌지 않는다.
계약이 없는 구간에는 기존 `SETTLEMENT_INPUT_UNAVAILABLE` 정책을 유지하며 기본 수수료를 채우지 않는다.
Audit에는 revision, feeRateBps, 근거 reference digest를 기록하며 원문 근거 reference를 복사하지 않는다.

## 기존 계정의 매장 소속

- `STORE_MEMBERSHIP_READ`로 `GET /api/v1/operations/stores/{storeId}/memberships`와 `GET .../{accountId}`를
  조회한다. 목록은 ACTIVE/REVOKED를 함께 보여 주며 actor/store-bound signed cursor와 limit 1~100을 사용한다.
- 추가 대상은 기존 MerchantAccount UUID다. 계정 발급 응답 또는 별도 `MERCHANT_CREDENTIAL_MANAGE` 권한의
  기존 계정 조회 경로에서 확인한다. 이 API는 계정을 생성하거나 password/credential 상태를 바꾸지 않는다.
- `STORE_MEMBERSHIP_WRITE`로 `POST .../memberships`에 Idempotency-Key와 `{accountId, role, reason}`을 보낸다.
  role은 OWNER/STAFF이며 최초 상태는 ACTIVE다. 이미 소속이 있으면 REVOKED라도 409다.
- `PUT .../{accountId}`는 `{role, status, expectedVersion, reason}`을 받는다. ACTIVE에서 역할을 바꾸거나
  기존 role을 유지하고 REVOKED로 철회할 수 있다. 재활성화는 ACTIVE와 원하는 role을 명시한다.
  같은 상태/역할로 변화 없는 새 요청과 오래된 version은 409다. 마지막 OWNER를 자동 승계하지 않는다.
- 회원권 부여는 만료/초기 비밀번호를 활성화하지 않는다. 실제 매장 접근은 기존 credential 정책을 함께 적용한다.
- 카탈로그·픽업·이의 철회는 소속 shared lock을 보유하므로 해당 요청이 끝난 뒤 역할 변경/철회가 commit된다.
  기존 읽기 요청은 이미 시작한 응답을 마칠 수 있으며 철회 후 새 접근은 거절된다.
- 현재 운영 grant 확인 뒤 같은 key/payload를 replay한다. 다른 payload는 409이고 Audit 실패는 소속과 원장을
  rollback한다. 완료 응답은 90일 후 최대 100행씩 정리하며 소속 자체를 삭제하지 않는다.

## 일반 알림 수동 복구

1. `NOTIFICATION_RECOVERY_READ`로 `GET /api/v1/operations/notification-delivery-recoveries`의 Case 이력을 조회한다.
   목록의 targetId는 Delivery ID다. actor/kind-bound signed cursor와 limit 1~100을 사용한다.
2. `GET .../{deliveryId}`에서 원본 state/version, 누적 attemptCount/attemptLimit과 recoveryCase.version을 확인한다.
   payload·수신자·Provider 키는 HTTP 응답에 노출하지 않는다.
3. `NOTIFICATION_RECOVERY_RETRY`로 `POST .../{deliveryId}/retries`에 Idempotency-Key와
   `{expectedVersion, expectedCaseVersion, reason}`을 보낸다. 원본과 Case가 MANUAL_REVIEW여야 한다.
4. 202는 동일 Delivery의 추가 시도 한 번이 내구 저장되었다는 뜻이다. Case는 RUNNING이며 발송 성공이 아니다.
   최초 자동 한도 4와 누적 이력은 유지하고 attemptLimit만 현재 attemptCount+1로 늘린다.
5. 기존 워커가 같은 provider idempotency key와 payload로 실행한다. ACK 또는 정책에 따른 marketing skip은
   Case RESOLVED, 재실패/claim lease 소진은 MANUAL_REVIEW다. 새 수신자나 payload로 복제하지 않는다.
6. 재실패 후에는 상세의 최신 두 version으로 새 키를 사용해 다시 요청한다. 응답 유실은 같은 키/payload로
   재시도하며 현재 grant를 다시 확인한다. 오래된 version, 다른 payload 또는 실행 중 요청은 409다.

HTTP 트랜잭션은 Provider를 호출하지 않는다. 예산 변경·Case RUNNING·Audit·응답 원장을 함께 commit하며
어느 저장이든 실패하면 전부 rollback한다. 완료 접수 응답은 90일 후 최대 100행씩 정리한다. 실제 실행 상태는
원본 Delivery와 Case에 남는다. UNKNOWN/claim 진행 상태를 운영 API로 강제 성공 처리하지 않는다.

## 이벤트 publication 수동 복구

1. `EVENT_PUBLICATION_RECOVERY_READ`로 `GET /api/v1/operations/event-publication-recoveries`를 조회한다.
   targetId가 원본 publication ID이며 actor/kind-bound signed cursor와 limit 1~100을 사용한다.
2. `GET .../{publicationId}`에서 원본 eventType/listenerId, 실제 status/attemptCount/completedAt와 Case를 확인한다.
   원본 serialized payload는 HTTP로 제공하지 않는다. recoverable은 현재 실패·Case·listener 조건의 판정이다.
3. 장애 원인을 해결한 뒤 `EVENT_PUBLICATION_RECOVERY_RETRY`로 `POST .../{publicationId}/retries`에
   Idempotency-Key와 `{expectedCaseVersion, reason}`을 보낸다. 202와 RUNNING은 접수 상태다.
4. 등록된 AFTER_COMMIT listener의 원본 event type/ID만 허용한다. 예약 Analytics 및 미등록/미매핑 target,
   완료·실행 중인 원본과 stale Case version은 409다. 기존 보상 target-to-step mapping을 우회하지 않는다.
5. 원본 payload/listener와 누적 completion attempts를 초기화하지 않는다. 원장의 baseline attempts와
   일치하는 한 건만 기존 registry에 전달한다. registry의 원자적 claim이 누적 횟수를 늘리면 같은 요청은
   추가 후보가 되지 않는다. 원장 없는 수동 검토 건은 자동 복구에서 계속 제외한다.
6. 기존 worker가 실제 listener를 실행하고 결과 대사 worker가 Case를 갱신한다. 완료일 확인은 RESOLVED,
   한 번 시도 후 명시적 FAILED는 MANUAL_REVIEW다. PROCESSING/RESUBMITTED 등 결과 불명은 RUNNING으로 남긴다.
   불명 결과를 임의로 FAILED/COMPLETED로 SQL 변경하지 않는다. 원본 상태를 확인하고 해당 owner 장애를 조치한다.
7. 같은 키/payload replay는 최초 RUNNING 응답을 반환한다. 현재 결과는 상세에서 읽는다. 재실패 후에는
   최신 Case version과 새 키로 다시 요청한다. 하나의 실패 결과 대사 오류는 다른 결과 대사를 막지 않고,
   batch 처리 후 실패를 다시 보고해 다음 tick에서 재시도한다.

publication 완료는 해당 listener 처리 완료일 뿐 알림 발송·환불·지급 전체 성공을 뜻하지 않는다. 별도 Delivery,
Refund 또는 보상 step이 MANUAL_REVIEW이면 해당 owner의 관리 경로와 실제 상태를 함께 확인한다. 원본 거래의
성공한 외부 작업을 다시 생성하지 않는다. 접수는 원장·Case·Audit가 원자적으로 저장되며 HTTP에서 listener를
실행하지 않는다. 결과 대사는 실행 중인 건을 batch limit 전에 제외하므로 불명 건이 알려진 결과를 막지 않는다.
완료 원장은 completedAt 기준 90일 후 최대 100행씩 정리하며 RUNNING 원장은 삭제하지 않는다.
