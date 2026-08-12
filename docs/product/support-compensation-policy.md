# Support Compensation Policy

> **Status:** Source separation, immutable versioning, initial v1 bands/rolling limits and exceptional Operations
> investigation are Accepted in ADR-086 and SP-21. The numeric values are Initial policy assumptions, not measured optima.

## Separation

`REFUND`, `BENEFIT_RESTORATION`, `LEDGER_CORRECTION`, `GOODWILL_COMPENSATION`을 분리한다. Goodwill은 PointAdjustment나 coupon restoration으로 위장하지 않으며 한 request는 `POINT` 또는 `COUPON` 하나만 발급한다.

## Immutable policy

CompensationPolicyVersion은 reason, benefit, 대상 상태, verification, actor/rolling limits, ratio, approval, cost responsibility, template/expiry를 snapshot한다. 발효된 version은 수정·삭제하거나 기존 request에 소급하지 않는다.

## Initial policy assumptions

- LOW: 3,000원 이하, 실결제 50% 이하, 관련 주문·첫 goodwill, 최근 30일 고객 합계 10,000원 이하 등. BASIC+Agent 후보.
- MEDIUM: 3,000원 초과~10,000원 이하 또는 반복/50~100%; Supervisor 승인.
- HIGH: 10,000원 초과~30,000원 이하, 실결제 초과, rolling 초과, Store 부담; ENHANCED+Specialist+Operations 조사.
- EXCEPTIONAL: 30,000원 초과, 관련 주문 없음, terminal 중복, 비용 미확정, 비정상 징후; Operations 조사 또는
  terminal duplicate denial이며 자동 발급하지 않는다.

위 숫자는 **Initial policy / Assumption**이며 측정된 optimum이 아니다.

## Initial v1 execution limits

실행 시점의 hard cap은 evaluation band와 별개로 최종 방어한다. 모든 window는 `issuedAt >= now - window`인
terminal consumption을 포함하는 실제 rolling window이며 경계 시각의 consumption을 포함한다.

| Scope | Window | Maximum |
|---|---:|---:|
| CUSTOMER | 30 days | 30,000 KRW |
| ORDER | 30 days | 30,000 KRW |
| INCIDENT | 30 days | 30,000 KRW and one terminal benefit |
| ACTOR | 1 day | 100,000 KRW |
| STORE | 1 day | 300,000 KRW |

Order나 Store가 없는 exceptional POINT request는 해당 scope를 생략하지만 CUSTOMER, INCIDENT와 ACTOR cap은
그대로 적용한다. Coupon은 order/store binding이 필수다. 동일 incident의 terminal benefit은 window가 지난 뒤에도
두 번째 발급하지 않는다.

Point는 PointLot과 append-only PointTransaction(`SUPPORT_COMPENSATION`)으로 발급한다. SHARED 책임은 Platform과
Store의 별도 funding Lot/transaction leg로 합계가 exact amount와 일치해야 한다. Coupon은 승인된 immutable fixed-KRW
template로만 발급하고 실제 future Order에서 사용·완료될 때 issuance의 immutable cost snapshot으로 Settlement에
반영한다. benefit 발급 시 SettlementItem/Adjustment를 만들지 않는다.

`PLATFORM`, `STORE`, `SHARED`, `UNDETERMINED`를 closed responsibility로 사용한다. STORE/SHARED는 동의·계약·정책
근거의 digest가 필수이고 SHARED bps 합은 10,000이다. `UNDETERMINED`는 Operations가 조사할 수 있지만 승인만으로
발급 가능 책임으로 바뀌지 않으며 새 exact request 없이는 실행할 수 없다. balance 직접 수정, 쿠폰 조건 자유 입력,
UNKNOWN 비용 주체의 자동 fallback을 금지한다. rolling scope row를 canonical 순서로 잠근 실행 transaction이
terminal incident key, owner issuance, consumption, Audit와 approval one-time consumption을 함께 commit한다.

LOW는 BASIC+agent, MEDIUM은 BASIC+Support Manager, HIGH와 EXCEPTIONAL은 ENHANCED+Operations investigation 뒤
distinct eligible agent가 실행한다. Operations reviewer는 benefit을 변경하거나 직접 발급하지 않는다. 알림은 발급
commit 뒤 별도 durable transaction에서 처리하고 실패를 `RETRY_SCHEDULED` 또는 `MANUAL_REVIEW`로 남기며 발급을
rollback하지 않는다.
