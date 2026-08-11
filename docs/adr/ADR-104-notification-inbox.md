# ADR-104: 고객 알림함과 거래·마케팅 수신 설정

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Design and capability contract](../exec-plans/active/productization-00-design-capability-contract.md)

## Context

Notification Context는 발송, 재시도, 수동 복구를 갖췄다
([ADR-019](ADR-019-notification-retry-and-manual-recovery.md), `notification_delivery`). 그러나
고객이 자기 알림을 다시 볼 수 있는 경로가 없다.

운영자 화면(`운영자 1b`)에는 "푸시 토큰 만료로 전달 실패했으나 앱 내 알림함에는 남아 있고 고객이
열지 않았다"는 상태가 나온다. 즉 디자인은 **발송 채널과 알림함을 별개**로 다룬다. 현재 구현에는
알림함이 없다.

고객 알림 설정(`고객 4e`)은 준비 완료·슬롯 임박 같은 거래 알림은 끌 수 없고 마케팅 알림만 끌 수
있다고 표현한다. 이 분류 기준이 정의돼 있지 않다.

## Decision

### 알림함과 전달 채널의 분리

```text
NotificationDelivery   외부 채널 전달 시도와 결과 (기존)
NotificationInboxItem  고객이 앱에서 보는 알림 레코드 (신규)
```

- 알림함 항목은 **채널 전달 성공 여부와 독립적으로** 생성된다. 푸시가 실패해도 알림함에는 남는다.
- 알림함 항목의 상태는 `읽지 않음`/`읽음`이다. 채널 전달 상태(`RETRY_SCHEDULED`, `FAILED`)를
  고객 알림함에 노출하지 않는다.
- 반대로 알림함 생성 성공을 채널 전달 성공으로 표시하지 않는다. 두 상태를 하나로 합치지 않는다
  ([ADR-009](ADR-009-explicit-failure-semantics.md)).
- Notification이 source event를 소비하는 짧은 로컬 transaction에서 필요한 `NotificationInboxItem`,
  `NotificationDelivery`와 persistent publication을 함께 생성한다. 외부 Provider 호출은 기존 delivery
  worker가 commit 뒤 수행한다. Inbox 또는 Delivery 저장 하나라도 실패하면 둘 다 rollback하고 event를
  retry한다. 한쪽만 성공으로 남기지 않는다.

### 분류

| 분류 | 정의 | 수신 거부 |
|---|---|---|
| `TRANSACTIONAL` | 진행 중 거래의 상태 변화. 주문 수락·거절, 준비 완료, 취소·환불 결과, 슬롯 임박 | 불가 |
| `MARKETING` | 거래와 무관한 프로모션·캠페인 안내 | 가능 |

### 분류 판정 규칙

분류는 판단이 아니라 **데이터로 판정**한다.

```text
recipientType = STORE                    → TRANSACTIONAL (예외 없음)
recipientType = CUSTOMER  AND orderId 있음 → TRANSACTIONAL
recipientType = CUSTOMER  AND orderId 없음 → MARKETING
```

- 현재 6개 템플릿(`STORE_ACCEPTANCE_WARNING`, `ORDER_REJECTED`, `ORDER_READY`,
  `ORDER_CANCELLATION_ACCEPTED`, `CUSTOMER_CANCELLATION_REFUND_SUCCEEDED`,
  `CUSTOMER_CANCELLATION_REFUND_DELAYED`)은 모두 `orderId`를 가지므로 전부 `TRANSACTIONAL`로
  자동 분류된다. 기존 동작이 바뀌지 않는다.
- 현재 `notification_delivery.order_id`는 `NOT NULL`이므로 여섯 템플릿에 대한 위 판정은 실제 스키마와
  일치한다. `orderId` 없는 고객 마케팅이나 매장 정산 알림을 도입하는 migration에서 이 컬럼을 nullable로
  바꾸고 recipient별 분류 CHECK와 계약 테스트를 함께 추가한다. nullable 변경만 먼저 하지 않는다.
- 포인트 만료 임박, 쿠폰 발급 안내, 프로모션 시작 알림은 `orderId`가 없으므로 `MARKETING`이다.
- **매장 대상 알림은 예외 없이 `TRANSACTIONAL`이다.** 정산 완료, 이의제기 판정처럼 `orderId`가
  없는 매장 알림도 마찬가지다. 금전이 움직이는 사실을 점주가 끄지 못하게 한다.
- 수신 설정은 고객 전용 개념이다. 매장 계정에는 수신 거부 설정을 제공하지 않는다.
- 분류는 알림 생성 시점에 결정되고 이후 바뀌지 않는다.
- `MARKETING` 기본값은 **수신 거부**다. 옵트인한 고객에게만 발송한다.
- `MARKETING`은 생성 시점에 opt-in을 확인해 opt-out 고객에게 새 InboxItem과 Delivery를 만들지 않는다.
  외부 Provider claim 직전에도 다시 확인해 생성 후 opt-out한 고객에게 발송하지 않는다. 이미 생성된
  알림함 항목은 소급 삭제하지 않는다. preference 조회 실패는 기본 opt-in/out으로 추정하지 않고 event
  처리 또는 delivery를 retry한다.
- 이 규칙으로도 애매한 알림이 생기면 `MARKETING`으로 둔다. 거래 진행에 필수인지가 최종 판단 기준이다.

### API

```http
GET   /api/v1/me/notifications
PATCH /api/v1/me/notifications/{notificationId}
GET   /api/v1/me/notification-preferences
PUT   /api/v1/me/notification-preferences
```

- 목록은 Cursor Pagination을 사용한다([ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)).
- `PATCH`는 읽음 처리만 허용한다. 알림 본문을 수정할 수 없다.
- `PATCH` body는 `{ "read": true }`만 허용하고 최초·반복 요청 모두 204다. `false`, 알 수 없는 필드와
  빈 body는 400이며 unread 되돌리기는 P0/P1 범위 밖이다.
- 수신 설정 `PUT` body는 `{ "marketingOptIn": boolean }`의 전체 교체이며 CustomerAccount당 한 row를
  upsert한다. 설정 row가 없으면 `false`로 투영한다.
- 알림함 항목은 고객 소유다. 다른 고객의 알림은 404다.
- 알림 본문에 결제 식별자, 카드 정보, 내부 오류 코드를 넣지 않는다.

### 스키마와 조회 키

```sql
CREATE TABLE notification_inbox_item (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    logical_source varchar(240) NOT NULL,
    order_id uuid,
    classification varchar(32) NOT NULL,
    template varchar(80) NOT NULL,
    title varchar(120) NOT NULL,
    body varchar(500) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_reference varchar(12),
    read_at timestamptz,
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (customer_id, logical_source),
    CHECK ((target_type = 'NONE' AND target_reference IS NULL) OR
           (target_type = 'ORDER' AND target_reference IS NOT NULL))
);
CREATE INDEX ix_notification_inbox_customer_recent
    ON notification_inbox_item (customer_id, created_at DESC, id DESC);
CREATE INDEX ix_notification_inbox_retention
    ON notification_inbox_item (retention_expires_at, id);

CREATE TABLE notification_customer_preference (
    customer_id uuid PRIMARY KEY,
    marketing_opt_in boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);
```

- 주문 target의 `targetReference`는 ADR-096 `publicReference` snapshot이다. UUID를 deep link나 API
  응답에 넣지 않는다. Plan 10 이후 새 notification event version이 `orderId`와 `publicReference`를
  함께 전달하고 Notification이 현재 Ordering table을 조회해 추론하지 않는다.
- 목록 정렬·cursor tuple은 `(created_at DESC, id DESC)`이고 customer scope와 filter를 ADR-070 방식으로
  서명한다. InboxItem은 DTO Projection으로 읽고 Delivery를 조인하지 않는다.

### 보존

InboxItem은 [BR-37](../product/business-policy-decisions.md)에 따라 분류와 무관하게 생성 후 90일
보존한다. 읽음·preference·Delivery 상태는 `retention_expires_at`을 바꾸지 않는다. Notification-owned
worker가 기본 1시간마다 최대 100개를 `(retention_expires_at, id)` 순서로 삭제한다. Delivery 운영
데이터 보존과 고객 Inbox 보존을 같은 값으로 추론하지 않는다.

## Alternatives Considered

### 1. `notification_delivery`를 그대로 고객에게 노출

- 장점: 새 테이블이 없다.
- 단점: 전달 시도 레코드는 채널·재시도·Provider 오류를 담는 운영 데이터다. 고객에게 재시도 횟수와
  실패 코드가 노출되고, 채널이 여러 개면 같은 알림이 중복으로 보인다.

### 2. 알림함을 만들지 않고 주문 상세로 대체

- 장점: 구현이 없다.
- 단점: 푸시가 실패하면 고객이 상태 변화를 알 방법이 없다. 운영자 화면의 "앱 내 알림함에는 남아
  있다"는 전제가 성립하지 않는다.

### 3. 모든 알림을 끌 수 있게 허용

- 장점: 설정이 단순하다.
- 단점: 준비 완료를 못 받은 고객이 픽업하지 않으면 매장 손실과 분쟁이 발생한다. 거래 알림은
  서비스 제공의 일부다.

### 4. 세 분류(`TRANSACTIONAL` / `ACCOUNT` / `MARKETING`)

- 장점: 포인트 만료처럼 금전 가치가 걸린 알림을 기본 켜짐이되 끌 수 있게 둘 수 있어 사용자 통제권이 크다.
- 단점: 판정이 다시 사람의 판단이 된다. `ACCOUNT`와 `MARKETING`의 경계 논쟁이 알림이 늘 때마다
  반복되고, 설정 화면과 발송 평가가 함께 복잡해진다.

### 5. 수신자 구분 없이 `orderId` 규칙만 적용

- 장점: 규칙이 하나다.
- 단점: `orderId`가 없는 정산·이의제기 알림이 매장에서 `MARKETING`이 되어 점주가 끌 수 있게 된다.
  금전 알림 누락은 곧 분쟁이다.

## Rationale

외부 발송은 실패할 수 있고 알림함 저장도 DB 장애로 실패할 수 있다. 상태와 외부 부수효과를 분리하고
로컬 durable work를 원자적으로 만들면 알림함을 채널 장애와 독립적으로 재시도할 수 있다. 이는 [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)의
재시도·수동 복구와 상충하지 않고 보완한다.

## Consequences

- 새 테이블과 목록 API가 추가된다. 알림 생성 경로에 쓰기가 하나 늘어난다.
- InboxItem과 Delivery/persistent publication은 한 로컬 transaction이다. Provider 호출만 worker로
  분리한다. 로컬 저장 실패는 source event retry로 수렴한다.
- 수신 설정 평가가 발송 경로에 추가된다.
- 분류 기준이 애매한 알림이 나올 때마다 판단이 필요하다. 판단 결과를 Business Policy에 누적한다.

## Verification

- 채널 전달이 실패해도 알림함 항목이 존재하는지 검증한다.
- 현재 6개 템플릿이 모두 `TRANSACTIONAL`로 분류되는지 검증한다.
- `orderId`가 없는 고객 알림이 `MARKETING`으로, `orderId`가 없는 매장 알림이 `TRANSACTIONAL`로
  분류되는지 검증한다.
- `MARKETING` 기본값이 수신 거부이고 옵트인 전에는 발송되지 않는지 검증한다.
- `MARKETING` 수신 거부 중에는 새 InboxItem·Delivery가 모두 생성되지 않고, 생성 뒤 수신 거부하면
  Provider 호출은 없으며 기존 InboxItem은 남는지 검증한다.
- InboxItem·Delivery·publication 중 하나의 저장 실패가 전체 rollback과 event retry인지 검증한다.
- `TRANSACTIONAL` 알림은 수신 거부 설정과 무관하게 발송되는지 검증한다.
- 매장 계정에 수신 거부 설정 endpoint가 노출되지 않는지 검증한다.
- 다른 고객의 알림 조회·읽음 처리가 404인지 검증한다.
- 알림 본문에 결제 식별자와 내부 오류 코드가 없는지 계약 테스트로 검증한다.
- Cursor 다음 페이지가 누락·중복 없이 이어지는지 검증한다.
- `{read:true}` 반복이 204이고 `false`·빈 body·알 수 없는 필드가 400인지 검증한다.
- 주문 알림 target이 publicReference만 노출하고 UUID를 포함하지 않는지 검증한다.
- 생성 후 90일 경계와 읽음·preference·Delivery retry 뒤 retention 불변, 최대 100개 bounded cleanup을
  검증한다.

## Metrics

- 알림함 생성 수와 채널 전달 성공 수의 차이
- 읽음 처리 비율과 읽기까지의 시간
- `MARKETING` 수신 거부 비율
- 알림 목록 조회 p95

## Revisit Conditions

- 알림 채널이 늘어 채널별 설정이 필요할 때
- 알림함 항목 수가 커져 보존·아카이빙 전략이 필요할 때
- 분류 경계 판단이 반복적으로 어려워질 때

## Related Decisions

- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-089](ADR-089-purpose-based-retention-legal-hold-and-deletion.md)
