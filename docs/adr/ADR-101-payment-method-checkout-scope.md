# ADR-101: 일회성 결제창과 저장 결제수단의 범위 분리

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Design and capability contract](../exec-plans/completed/productization-00-design-capability-contract.md)

## Context

저장소에는 두 가지가 동시에 존재한다.

1. PaymentMethod lifecycle: 등록·목록·기본 지정·폐기
   ([ADR-079](ADR-079-payment-method-token-management.md), `payment_method` 계열 테이블)
2. Toss V2 일회성 결제창 기반 승인
   ([ADR-080](ADR-080-toss-v2-one-time-payment-window.md))

`toss-sandbox-runtime` profile에서 PaymentMethod 등록·폐기는 명시적으로 `Misconfigured`이고,
고객 checkout은 PaymentMethod를 조회하지 않는다. 즉 **저장된 결제수단은 실제 승인에 쓰이지 않는다.**

디자인의 `고객 2a 결제`는 저장된 카드를 골라 결제하는 흐름을 보여준다. 이 화면을 그대로 구현하면
UI가 실제로 일어나지 않는 일을 표시하게 된다. 이는 기능 부족이 아니라 **거짓 동작**이다.

## Decision

Checkout의 승인 원천은 **서버가 준비한 일회성 Payment Window 하나뿐**이다.

### P0 범위

- 결제 화면은 결제수단을 선택하지 않는다. "토스 결제로 진행" 단일 진입점만 둔다.
- `POST /orders/{orderId}/payment-attempts`가 준비한 일회성 attempt만 승인 원천이다.
- PaymentMethod 목록은 결제 화면에서 조회하지 않는다.
- 저장 결제수단 관리 화면(`고객 2c`)은 유지하되, 그 화면에서 결제를 시작할 수 없다.
  화면에 "이 수단은 향후 원클릭 결제용이며 현재 결제에는 사용되지 않는다"는 사실을 표시한다.
- 환불은 원 결제수단으로만 환급한다. 별도 환불 계좌를 등록하지 않는다.

### P1 조건

저장 결제수단을 승인 원천으로 쓰려면 다음이 **모두** 충족돼야 한다.

1. Billing Key 또는 BrandPay 계약과 sandbox 검증
2. 정기·비대면 승인의 실패·재시도·reconciliation 계약
3. 저장 수단 폐기와 진행 중 결제의 경계 정의
   ([ADR-079](ADR-079-payment-method-token-management.md)의 Tx D1 규칙 확장)
4. 원클릭 결제의 멱등성 모델([ADR-064](ADR-064-risk-based-idempotency-model-selection.md) 적용)

이 네 가지 없이 UI만 연결하지 않는다.

### 문서 표기 규칙

- OpenAPI, README, 화면 어디에서도 "저장된 카드로 결제"라고 쓰지 않는다.
- PaymentMethod endpoint의 설명에 "현재 checkout 승인에 사용되지 않음"을 명시한다.

## Alternatives Considered

### 1. 저장 결제수단을 P0에서 승인에 연결

- 장점: 디자인을 그대로 구현할 수 있다.
- 단점: Billing Key 계약이 없다. 계약 없이 연결하려면 저장된 토큰을 일회성 창의 입력으로 쓰는
  변칙이 필요하고, 이는 Provider 계약 위반이거나 동작하지 않는다.

### 2. PaymentMethod 기능을 전부 제거

- 장점: 혼동이 사라진다.
- 단점: 이미 완성된 tokenization·lifecycle·감사 구현을 버리게 된다. P1 원클릭 결제의 기반이며
  보존 가치가 크다.

### 3. 결제 화면에 저장 수단을 표시만 하고 선택 시 일회성 창을 띄움

- 장점: 디자인과 유사해 보인다.
- 단점: 사용자가 선택한 카드와 실제 승인 카드가 다를 수 있다. 가장 나쁜 형태의 거짓 동작이다.

## Rationale

UI는 시스템이 실제로 하는 일을 말해야 한다. 결제는 특히 그렇다. 사용자가 "저장된 카드로 결제"를
눌렀는데 다른 카드로 승인되면, 이는 UX 문제가 아니라 신뢰와 분쟁의 문제다.

두 기능을 모두 살리되 경계를 명확히 하는 편이, 하나를 지우거나 억지로 연결하는 것보다 낫다.

## Consequences

- 디자인 `고객 2a`를 수정해야 한다. 결제수단 선택 영역이 사라진다.
- `고객 2c`는 P1로 내려간다. P0 라우트에서 제외한다.
- PaymentMethod 관련 기존 테스트와 계약은 그대로 유지된다.
- 원클릭 결제 요구가 생기면 이 ADR을 superseding하는 새 ADR이 필요하다.

## Verification

- Checkout 경로가 PaymentMethod 조회 없이 완결되는지 계약 테스트로 검증한다.
- `toss-sandbox-runtime` profile에서 PaymentMethod 등록·폐기가 `Misconfigured`로 실패하는지
  기존 테스트가 유지되는지 확인한다.
- 결제 화면 라우트에서 PaymentMethod API 호출이 발생하지 않는지 프론트엔드 테스트로 검증한다.
- OpenAPI 설명 문구에 결제수단 승인 표현이 없는지 문서 검증에 포함한다.

## Metrics

- 일회성 결제창 준비·승인·실패 수
- PaymentMethod 등록·폐기 수(승인과 무관한 관리 지표)

## Revisit Conditions

- Billing Key 또는 BrandPay 계약이 확정될 때
- 정기 결제 또는 자동 충전 요구가 생길 때
- 결제 이탈률이 측정되어 결제 단계 축소의 근거가 생길 때

## Related Decisions

- [ADR-079](ADR-079-payment-method-token-management.md)
- [ADR-080](ADR-080-toss-v2-one-time-payment-window.md)
- [ADR-021](ADR-021-payment-method-tokenization.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [Design Contract Conflicts C-2, C-11](../product/design-contract-conflicts.md)
