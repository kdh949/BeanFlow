# Support Compensation Policy

> **Status:** Source separation, immutable versioning and exceptional Operations investigation are Accepted in ADR-086;
> exact class/type names and numeric bands remain Initial policy/DRAFT implementation inputs.

## Separation

`REFUND`, `BENEFIT_RESTORATION`, `LEDGER_CORRECTION`, `GOODWILL_COMPENSATION`을 분리한다. Goodwill은 PointAdjustment나 coupon restoration으로 위장하지 않으며 한 request는 `POINT` 또는 `COUPON` 하나만 발급한다.

## Immutable policy

CompensationPolicyVersion은 reason, benefit, 대상 상태, verification, actor/rolling limits, ratio, approval, cost responsibility, template/expiry를 snapshot한다. 발효된 version은 수정·삭제하거나 기존 request에 소급하지 않는다.

## Initial policy assumptions

- LOW: 3,000원 이하, 실결제 50% 이하, 관련 주문·첫 goodwill, 최근 30일 고객 합계 10,000원 이하 등. BASIC+Agent 후보.
- MEDIUM: 3,000원 초과~10,000원 이하 또는 반복/50~100%; Supervisor 승인.
- HIGH: 10,000원 초과~30,000원 이하, 실결제 초과, rolling 초과, Store 부담; ENHANCED+Specialist+Operations 조사.
- EXCEPTIONAL: 30,000원 초과, 관련 사건 없음, terminal 중복, 비용 미확정, 비정상 징후; DENIED 또는 MANUAL_REVIEW.

위 숫자는 **Initial policy / Assumption**이며 측정된 optimum이 아니다.

Point는 PointLot과 append-only PointTransaction(`SUPPORT_COMPENSATION`)으로, Coupon은 승인된 불변 template로 발급한다. balance 직접 수정, 쿠폰 조건 자유 입력, UNKNOWN 비용 주체의 자동 fallback을 금지한다. rolling bucket은 실행 transaction에서 lock/conditional update로 최종 방어한다.
