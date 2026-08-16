# ADR-108: 점주 부분 환불 preview와 공개 품목 식별 계약

- **Status:** Accepted
- **Date:** 2026-08-12
- **Implementation owner:** [Merchant financial workflows](../exec-plans/completed/productization-90-merchant-financial-workflows.md)

## Context

기존 `POST /api/v1/payments/{paymentId}/refunds`는 `paymentId`와 각 `orderLineId` UUID를 요청으로
받는다. 환불 계산·Provider 실행·포인트 복원·정산 조정은 구현돼 있지만 점주가 화면에서 금액을
확인한 뒤 실행할 수 있는 preview가 없고, UUID를 사람이 찾아 넣어야 한다.

OrderLine에는 주문 안에서 0부터 연속이고 immutable한 `lineSequence`와 금액·메뉴 스냅샷이 이미 있다.
새 공개 ID 컬럼을 추가하지 않고 이 순서를 `orderReference` 범위 안의 품목 식별자로 사용할 수 있다.

preview 뒤 다른 직원의 환불이 먼저 성공할 수 있으므로 preview 금액을 그대로 승인 금액으로 신뢰하면
누적 환불 상한과 unit consumption이 경쟁한다.

## Decision

### Legacy UUID endpoint authentication split (2026-08-13)

Plan 20의 actor-exclusive Chain 전환에서 기존 `POST /payments/{paymentId}/refunds`는 Merchant Session
전용으로 유지하고 `PLATFORM_OPERATOR` branch는
`POST /operations/payments/{paymentId}/refunds`로 분리한다. 두 URI는 같은 preparation, provider
execution, idempotency source와 결과 원장을 사용한다. URI를 idempotency scope에 추가하거나 운영자
호출에 새 Provider key를 만들지 않는다. 이 legacy 분리는 Plan 90의 공개
`storeId + orderReference + lineSequence` 계약을 대체하지 않으며 새 점주 화면은 계속 아래 public
contract만 사용한다.

### Public contract

```http
POST /api/v1/stores/{storeId}/orders/{orderReference}/refund-previews
POST /api/v1/stores/{storeId}/orders/{orderReference}/refunds
```

- 두 endpoint는 BR-38에 따라 현재 `ACTIVE OWNER | STAFF` membership과 Order의 store 일치를 요구한다.
- 요청 품목은 `{ lineSequence, quantity }`를 사용한다. `paymentId`, `orderLineId`, 환불 금액, 포인트
  복원액과 쿠폰 귀속액을 받지 않는다.
- 실행 request의 line selection은 1건 이상이며 sequence가 중복되면 400이다. 전체 잔여 환불도 모든
  line의 잔여 수량을 명시한다. legacy endpoint의 omitted-lines full refund 의미를 새 UI 계약으로
  가져오지 않는다.
- **Amendment (2026-08-17):** preview request의 `lines`는 선택적이다. 생략은 "아직 아무 품목도 고르지
  않음"이며 전체 환불이 아니다. 주문보드 상세가 품목 목록을 주지 않으므로 preview가 환불 가능한
  품목과 잔여 수량의 유일한 source이고, 응답은 언제나 환불 가능한 모든 line을 담는다. 고르지 않은
  line은 `selectedQuantity`와 금액이 0이다.

### Preview

preview는 현재 승인액, 성공 환불, 미확정 환불, unit consumption, OrderLine allocation과 활성
부분환불 포인트 복원 policy version을 읽어 다음을 계산한다.

```text
RefundPreview
  orderReference
  lines[]                      # 환불 가능한 모든 line. 고르지 않은 line은 selectedQuantity 0
    lineSequence
    menuName
    selectedQuantity
    remainingQuantity
    grossAttributionKrw
    couponAttributionKrw
    pointsRestorationKrw
    cashRefundKrw
  totals
  previewVersion
```

- `previewVersion`은 Order aggregate version, Payment version·승인액, 성공·미확정 Refund watermark,
  line별 남은 unit과 활성 복원 policy version을 canonicalize한 SHA-256 소문자 hex다.
- preview는 DB row나 Refund를 만들지 않고 Provider·Loyalty write·Audit을 호출하지 않는다.
- previewVersion은 보안 token이나 권한 증명이 아니다. membership은 실행 시 다시 확인한다.

### Execute and TOCTOU

실행 body는 같은 line selection, `previewVersion`과 trim 뒤 1..500자 reason을 보낸다. Header에는
8..128자의 `Idempotency-Key`가 필수다.

실행 transaction은 기존 Payment와 Order refund snapshot lock 순서를 사용해 membership, line,
remaining unit, 누적 성공액·미확정 결과와 policy version을 다시 읽고 previewVersion을 재계산한다.
다르면 Refund를 만들기 전에 `409 REFUND_PREVIEW_STALE`로 실패한다. 같으면 lineSequence를 내부
orderLineId로 변환한 뒤 기존 `PartialRefundPreparationTransaction`을 호출한다.

Provider 호출은 기존처럼 준비 transaction commit 뒤 실행하고 결과를 별도 transaction에 저장한다.
timeout·응답 유실이면 기존 Refund를 `UNKNOWN`/reconciliation으로 수렴시키며 새 Refund나 다른
Provider key를 만들지 않는다.

미확정 Refund가 하나라도 있어 남은 승인액을 확정할 수 없으면 preview와 새 실행을
`409 REFUND_OUTCOME_UNRESOLVED`로 막는다. 기존 reconciliation만 계속한다.

## Alternatives Considered

### 기존 UUID endpoint를 화면에서 그대로 호출

구현은 작지만 사람이 기술 식별자를 다뤄야 하고 다른 주문 line UUID를 섞는 오류가 계속 가능하다.

### OrderLine용 새 임의 공개 ID 컬럼

전역 추측 저항성이 필요하지 않은 주문 내부 품목에 migration·backfill·Unique 제약을 추가한다.
이미 immutable하고 order-scoped unique인 lineSequence보다 이점이 없다.

### Preview 결과 금액을 execute body로 전송

프론트엔드 조작과 stale 금액을 서버가 다시 검증해야 하므로 중복 계약만 늘어난다. 서버 계산 값을
입력으로 받지 않는다.

### Preview row를 DB에 저장·예약

TOCTOU를 줄일 수 있지만 preview 취소·만료 worker와 잠금 수명이 새 운영 대상이 된다. 실행 시 lock
아래 재계산만으로 기존 불변식을 지킬 수 있어 도입하지 않는다.

## Rationale

`orderReference + lineSequence`는 사람이 선택한 주문 품목을 안정적으로 표현하면서 내부 UUID를
노출하지 않는다. preview를 편의 Projection으로 두고 실행 시 기존 거래 lock 아래 전부 재계산하면,
새 금융 source of truth 없이 경쟁과 위변조를 차단할 수 있다.

## Consequences

- 새 migration 없이 Query/Facade와 API 계약이 추가된다.
- preview가 품목 catalog 역할을 겸하므로 화면은 별도 조회 endpoint 없이 시작할 수 있다. 대신 preview
  응답 크기가 주문 line 수에 비례한다.
- STAFF도 금액 상한 없이 실행 가능하므로 BR-38의 actor·membership·사유 Audit과 운영 지표가 중요하다.
- preview와 실행 사이 선행 환불은 정상적인 409가 되며 UI는 재조회·재선택을 안내해야 한다.
- legacy UUID endpoint는 전환 동안 유지하되 새 점주 화면에서는 호출하지 않는다.

## Verification

- legacy Merchant/Operations URI가 각각 Session/JWT만 허용하고 상대 actor 인증은 403이며, 같은
  idempotency key와 payload를 두 URI에서 재사용해도 Provider 부수효과가 1회 이하인지 검증한다.
- OWNER·STAFF same-store 성공과 다른 매장·revoked·role mismatch 403.
- sequence 중복·다른 주문 범위·0/초과 수량과 lineSequence→UUID 변환 정확성.
- preview가 Refund·Audit·Provider·Loyalty write를 만들지 않는지 검증.
- 두 직원이 같은 unit을 preview한 뒤 한 명만 실행하고 다른 요청은 stale 409인지 PostgreSQL로 검증.
- previewVersion이 성공 Refund, Payment/Order version과 policy version 변화에 따라 달라지는지 검증.
- 미확정 Refund가 있을 때 새 Provider 호출 0회와 명시적 unresolved conflict.
- 같은 Idempotency-Key replay와 다른 payload 재사용의 Provider 부수효과 1회 이하.
- cash·points·coupon attribution과 누적 승인액·Settlement tie-out 회귀 검증.

## Metrics

- preview 성공·stale·unresolved·not-refundable 결과 수
- OWNER/STAFF별 실행 수와 금액 분포(고객·주문·직원 ID는 tag 금지)
- preview→실행 전환 시간과 stale 비율
- Provider `UNKNOWN`과 reconciliation 해소 시간

## Revisit Conditions

- STAFF 환불 오조작·분쟁이 관측되어 금액 상한·step-up·점주 승인이 필요할 때
- preview를 장시간 보존하거나 견적 승인 증거로 사용해야 할 때
- 묶음 상품·세금·외화로 lineSequence/unit 배분 계약이 바뀔 때

## Related Decisions

- [BR-38 매장 부분 환불 실행 권한](../product/business-policy-decisions.md)
- [ADR-014 정수 KRW 배분과 품목 부분 환불](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-027 매장 membership 인가](ADR-027-store-membership-authorization.md)
- [ADR-061 Refund 요청·확정 금액](ADR-061-refund-requested-and-confirmed-amounts.md)
- [ADR-063 부분 환불 포인트 복원](ADR-063-partial-refund-expired-point-restoration.md)
