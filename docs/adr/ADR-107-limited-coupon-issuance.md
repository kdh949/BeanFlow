# ADR-107: 한정 쿠폰의 원자적 발급과 잔여 수량 표현

- **Status:** Superseded by [ADR-120](ADR-120-operations-managed-limited-coupon-campaign.md)
- **Date:** 2026-08-12
- **Implementation owner:** [Design and capability contract](../exec-plans/completed/productization-00-design-capability-contract.md)

## Context

디자인의 고객 `4b 쿠폰·프로모션`은 "선착순 한정 · 총 3,500매 · 1인 1장 · 소진 시 발급 중단"을
보여주고, 운영자 `3a 쿠폰 발급 모니터`는 초과 발급이 없었는지를 감시한다.

현재 저장소에는 이 모델이 **없다.**

- `promotion_campaign`에는 할인 방식, 최소 결제 금액, 최대 할인액, 대상 메뉴만 있다.
  총 발급 한도, 1인 한도, 발급 기간 컬럼이 없다.
- `promotion_coupon_issuance`는 이미 고객별로 존재하는 쿠폰(`AVAILABLE`/`RESERVED`/`USED`)이다.
  고객이 발급을 요청하는 경로가 아니라 사전 부여된 결과다.

즉 "쿠폰 받기"는 신규 capability다. 동시에 수천 건이 몰릴 때 한도를 넘겨 발급하지 않는 것이
이 기능의 유일한 어려운 부분이다.

## Decision

### 스키마

`promotion_campaign`에 발급 한도를 추가하고, 발급 수량은 별도 카운터 행이 소유한다.

```sql
ALTER TABLE promotion_campaign
    ADD COLUMN issuance_total_quota   integer,
    ADD COLUMN issuance_per_customer  integer,
    ADD COLUMN issuance_starts_at     timestamptz,
    ADD COLUMN issuance_ends_at       timestamptz,
    ADD COLUMN issued_coupon_expires_at timestamptz;

CREATE TABLE promotion_campaign_issuance_counter (
    campaign_id   uuid    PRIMARY KEY REFERENCES promotion_campaign(id),
    issued_count  integer NOT NULL CHECK (issued_count >= 0)
);

CREATE TABLE promotion_limited_coupon_claim (
    campaign_id uuid NOT NULL REFERENCES promotion_campaign(id),
    customer_id uuid NOT NULL,
    issuance_id uuid NOT NULL UNIQUE,
    claimed_at timestamptz NOT NULL,
    PRIMARY KEY (campaign_id, customer_id),
    FOREIGN KEY (issuance_id) REFERENCES promotion_coupon_issuance(id)
      DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE promotion_limited_coupon_claim_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    http_status integer NOT NULL,
    response_body text NOT NULL,
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key)
);
```

- 다섯 컬럼이 모두 `NULL`이면 한정 발급 캠페인이 아니다. 아니면 모두 non-null이어야 하며
  `issuance_total_quota > 0`, `issuance_per_customer = 1`,
  `issuance_starts_at < issuance_ends_at <= issued_coupon_expires_at` CHECK를 둔다. 기존 사전 발급
  캠페인의 동작은 바뀌지 않는다.
- 1인 한도는 P0/P1 범위에서 `1`만 지원한다. Unique Index가 이를 DB 제약으로 강제한다.
  `issuance_per_customer > 1`은 이 ADR의 범위 밖이며 도입 시 별도 결정이 필요하다.
- 일반 `promotion_coupon_issuance(campaign_id, customer_id)`에는 Unique Index를 추가하지 않는다.
  기존 복원 정책은 같은 campaign·customer에 compensation issuance를 만들 수 있기 때문이다. 1인 1회
  claim은 한정 발급 전용 `promotion_limited_coupon_claim`이 소유한다.

### 발급 경로

Campaign과 counter row를 잠그고 claim·카운터·쿠폰·멱등 응답을 하나의 로컬 transaction에서 처리한다.

```sql
UPDATE promotion_campaign_issuance_counter
   SET issued_count = issued_count + 1
 WHERE campaign_id = :campaignId
   AND issued_count < :totalQuota
RETURNING issued_count;
```

- 요청은 Customer Session의 actor와 `Idempotency-Key`를 사용한다. command fingerprint는
  `(actorId, campaignId)`이며 같은 key·다른 fingerprint는 409, 같은 fingerprint의 terminal 결과는
  status·body를 그대로 재생한다. 외부 호출이 없고 기존 Campaign/counter root lock이 경쟁을
  직렬화하므로 ADR-064의 명령 transaction 모델을 사용하며 `PROCESSING` 상태를 두지 않는다.
- operation은 `LIMITED_COUPON_CLAIM_V1`이고 idempotency key는 trim된 8~128자, control character
  금지다. command row는 BR-26의 90일 보존과 Promotion-owned bounded cleanup worker를 사용한다.
- 먼저 `promotion_limited_coupon_claim`을 `ON CONFLICT DO NOTHING RETURNING`으로 삽입한다. 영향 행이
  0이면 이미 claim한 고객이므로 카운터를 건드리지 않고 409다. Unique 예외를 잡아 성공 흐름을
  계속하지 않는다.
- 다음으로 counter UPDATE를 실행한다. 영향 행이 0이면 소진이며 transaction을 rollback해 claim도
  남기지 않는다. 성공하면 같은 transaction에서 claim에 미리 넣은 issuance ID로
  `promotion_coupon_issuance(AVAILABLE)`를 만들고 `coupon_expires_at`은 campaign의
  `issued_coupon_expires_at` snapshot을 사용한다.
- `SELECT ... MAX/COUNT` 후 증가하는 방식은 금지한다. 동시 요청에서 한도를 넘긴다.
- claim·counter·issuance·terminal replay 중 하나라도 저장 실패면 전체 rollback과 503이다. PostgreSQL
  transaction rollback이 counter 증가를 되돌리므로 별도 보정이나 감소 명령을 만들지 않는다.

### 카운터의 의미

**카운터는 발급 기준으로 고정한다.** 주문 취소, 쿠폰 만료, 미사용 어떤 경우에도 감소하지 않는다.

- `issued_count`는 "지금까지 발급된 총 매수"이고 `issuance_total_quota`는 "발급할 수 있는 총 매수"다.
- 따라서 총 한도는 **예산 상한**이다. 실제 사용량이 아니다.
- 감소 경로가 없으므로 동시성 모델이 단조 증가 하나로 끝난다. 반납 경합, 이중 반납, 반납 후
  재발급 경합을 다루지 않아도 된다.
- 취소·환불 시의 쿠폰 자체 복원은 기존 정책을 그대로 따른다
  ([ADR-028](ADR-028-expired-benefit-restoration-policy.md),
  [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)). 복원된 쿠폰은 같은
  `promotion_coupon_issuance` 행이며 새 발급이 아니므로 카운터에 영향을 주지 않는다.

### 잔여 수량 표현

- 고객 화면의 잔여 수량은 `issuance_total_quota - issued_count`의 **조회 시점 값**이다.
  실시간 정확성을 보장하지 않는다.
- 응답에 이 값이 근사임을 표현한다. 잔여 수량이 0보다 커도 발급이 실패할 수 있다.
- 발급 성공 여부는 발급 요청의 결과로만 확정된다. 잔여 수량 조회를 발급 가능 판정으로 쓰지 않는다.
- 소진 후 캠페인은 고객 목록에서 발급 대상으로 노출하지 않는다. 이미 발급받은 고객의 쿠폰은
  그대로 유지한다.

### API 계약

```http
POST /api/v1/campaigns/{campaignId}/coupon-issuances
Idempotency-Key: <opaque key>
```

- request body에 `customerId`를 받지 않는다. Session actor를 사용한다.
- 한도 증액·기간 변경은 이 claim API의 범위가 아니다. 운영자 `3a` 화면을 활성화하려면 append-only
  campaign revision, 권한, 감사와 동시 증액 계약을 소유한 별도 ExecPlan/ADR이 먼저 필요하다.

### 실패 표현

| 상황 | 응답 |
|---|---|
| 소진 | `409 CAMPAIGN_QUOTA_EXHAUSTED` |
| 이미 발급받음 | `409 COUPON_ALREADY_ISSUED` |
| 발급 기간 밖 | `422 CAMPAIGN_NOT_ISSUABLE` |
| 카운터·쿠폰 저장 실패 | `503` |

- 화면 문구는 부드럽게 표현하되(`한정 수량이 모두 나갔어요`), API는 명시적 실패 코드를 반환한다.
  실패를 200으로 감싸지 않는다.

## Alternatives Considered

### 1. 사전 발급만 유지하고 고객 발급 화면을 만들지 않음

- 장점: 구현이 없다.
- 단점: 디자인의 선착순 프로모션과 운영자 발급 모니터가 성립하지 않는다. 캠페인 운영의 핵심 수단이 빠진다.

### 2. `SELECT COUNT(*)` 후 삽입

- 장점: 카운터 테이블이 없다.
- 단점: 동시 요청에서 한도를 넘긴다. 한도 초과 발급은 곧 예산 초과이고, 발급된 쿠폰을 회수할 방법이 없다.

### 3. 잔여 수량을 실시간 정확값으로 보장

- 장점: 화면과 결과가 항상 일치한다.
- 단점: 매 조회가 카운터 행을 읽어야 하고, 그래도 경합 순간에는 화면과 결과가 어긋난다.
  정확성을 보장할 수 없는 값에 비용을 쓰게 된다.

### 4. 카운터를 사용 기준으로 복원

- 장점: 미사용 쿠폰이 예산을 잠그지 않아 프로모션 효율이 높다.
- 단점: 감소 경로가 생겨 이중 반납·반납 후 재발급 경합·복원 순서를 모두 다뤄야 한다.
  총 한도의 의미가 "발급 상한"에서 "동시 보유 상한"으로 바뀌어 예산 통제와 어긋난다.

### 5. 대기열을 두고 소진 시 순번 부여

- 장점: 사용자 경험이 부드럽다.
- 단점: 대기열 자체가 새 인프라와 실패 모드다. 디자인도 "초과 처리 즉시 차단 · 대기열 없음"이다.

## Rationale

한도가 있는 발급에서 유일하게 타협할 수 없는 것은 **한도를 넘지 않는 것**이다. 잔여 수량 표시는
편의이고, 그 편의를 위해 정확성을 보장하려 하면 비용만 늘고 경합 순간에는 어차피 틀린다.

그래서 정확성은 발급 경로 한 곳(원자적 UPDATE)에 몰아넣고, 표시는 근사임을 명시한다.
정확성은 Campaign/counter lock 아래의 한 transaction에 집중하고, 표시 값은 그 transaction의
성공을 예측하는 권한 증명으로 사용하지 않는다.

카운터를 발급 기준으로 고정하는 선택도 같은 이유다. 감소 경로가 없으면 동시성 모델이 단조 증가
하나로 끝나고, 검증해야 할 경합이 사라진다.

## Consequences

- `promotion_campaign`에 컬럼 5개, 카운터·claim·terminal idempotency 테이블이 추가된다.
- 미사용·취소된 쿠폰이 총 한도를 계속 점유한다. 프로모션 효율이 낮아질 수 있고, 운영자는 한도를
  증액 기능이 별도 권한·감사 계약으로 구현된 뒤에만 운영자가 대응할 수 있다. 그 전에는 디자인
  `운영자 3a`의 "한도 500매 추가" 버튼을 노출하지 않는다.
- 캠페인당 카운터 행 하나에서 발급이 직렬화된다. 단일 캠페인의 동시 발급 처리량 상한이 이 행의
  잠금 대기로 결정된다. 측정 대상이다.
- 잔여 수량이 0보다 큰데 발급이 실패하는 경우가 정상 동작이다. 화면이 이를 오류로 표시하지 않아야 한다.
- 1인 2장 이상 캠페인은 이 결정으로 지원되지 않는다.

## Verification

- 총 한도 N에 대해 동시 발급 요청 2N건을 보내 정확히 N건만 성공하는지 PostgreSQL Testcontainers로 검증한다.
- 같은 고객의 동시 중복 요청에서 한 장만 발급되고 카운터가 1만 증가하는지 검증한다.
- 기존 compensation issuance가 같은 campaign·customer에 새 row를 만들 수 있는지 회귀 검증한다.
- 같은 `Idempotency-Key` 재시도가 두 장을 만들지 않는지 검증한다.
- 소진 후 발급 요청이 `409`이고 쿠폰 행이 생기지 않는지 검증한다.
- 발급 기간 밖 요청이 `422`인지 고정 `Clock`으로 검증한다.
- 발급 쿠폰의 만료 시각이 campaign `issued_coupon_expires_at`과 정확히 일치하는지 검증한다.
- 주문 취소로 쿠폰이 복원돼도 `issued_count`가 감소하지 않는지 검증한다.
- 쿠폰 만료 후에도 `issued_count`가 감소하지 않는지 검증한다.
- 한정 발급 컬럼이 `NULL`인 기존 캠페인의 동작이 바뀌지 않는지 회귀 검증한다.
- 다섯 한정 발급 컬럼의 all-null/all-nonnull과 기간·한도 CHECK를 검증한다.
- 잔여 수량 응답이 근사임을 나타내는 계약을 계약 테스트로 고정한다.
- 카운터 행 잠금 대기 시간을 부하 조건에서 측정한다.

## Metrics

- 캠페인별 발급 성공·소진 차단·중복 차단 수
- 카운터 행 잠금 대기 시간 p95
- 발급 요청 p50·p95
- 발급 대비 실제 사용률(예산 효율)
- 한도 추가 부여 횟수

## Revisit Conditions

- 단일 캠페인의 동시 발급에서 카운터 잠금 대기가 실제로 병목이 될 때
- 1인 2장 이상 발급이 실제 요구가 될 때
- 미사용 쿠폰의 예산 점유가 프로모션 운영에 실질적 문제가 된다고 측정될 때
- 발급 대기열 또는 사전 예약형 프로모션이 필요할 때

## Related Decisions

- [ADR-024](ADR-024-coupon-calculation-model.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-097](ADR-097-store-pickup-number.md)
