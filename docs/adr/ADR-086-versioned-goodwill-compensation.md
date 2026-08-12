# ADR-086: versioned risk compensation과 goodwill source 분리

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

기존 refund, benefit restoration과 audited PointAdjustment는 고객 불편 goodwill과 목적·승인·비용 책임이 다르다.

## Decision

REFUND, BENEFIT_RESTORATION, LEDGER_CORRECTION, GOODWILL_COMPENSATION source를 분리한다. Support compensation은 immutable PolicyVersion, exact request, one POINT-or-COUPON benefit, rolling buckets, duplicate terminal key와 cost responsibility snapshot을 가진다. Point는 new Lot/append-only transaction, Coupon은 approved immutable template를 사용한다. Unknown responsibility는 Store/Platform fallback하지 않는다. HIGH/EXCEPTIONAL은 Operations investigation 후 agent execution이다.

### S90 initial runtime amendment (2026-08-12)

Initial immutable policy version은 3,000/10,000/30,000원 LOW/MEDIUM/HIGH 경계와 다음 execution hard cap을
snapshot한다: CUSTOMER 30일 30,000원, ORDER 30일 30,000원, INCIDENT 30일 30,000원과 lifetime terminal 1회,
ACTOR 1일 100,000원, STORE 1일 300,000원. 이는 측정 optimum이 아닌 SP-21 initial assumption이다. 변경은 기존
version을 수정하지 않고 새 version과 head CAS로만 적용한다.

LOW는 BASIC+agent, MEDIUM은 BASIC+Support Manager, HIGH와 EXCEPTIONAL은 ENHANCED+Operations investigation
route다. Operations는 exact request를 APPROVE/DENY/RETURN/ESCALATE하고 benefit을 수정·발급하지 않는다. 기존 S60
`SupportActionRequest` revision과 required Operations callback을 `GOODWILL_COMPENSATION` typed target에 재사용한다.
승인자는 executor가 될 수 없으며 execution은 current assignment, permission, verification, exact revision/policy/request
version을 다시 검사한다.

Cost responsibility는 `PLATFORM | STORE | SHARED | UNDETERMINED`다. STORE/SHARED는 closed evidence basis와 digest가
필수이고 SHARED bps 합은 10,000이다. `UNDETERMINED`는 조사는 가능하지만 executable cost owner가 아니므로 Operations
approval을 Platform/Store fallback으로 해석하지 않는다. exact responsibility의 새 request 없이는 발급하지 않는다.

Rolling scope guard는 CUSTOMER→ORDER→INCIDENT→ACTOR→STORE canonical 순서로 잠그고 실제 rolling window의 immutable
terminal consumption을 합산한다. execution transaction은 limit recheck, incident terminal unique, owner-local issuance,
Support terminal result, S60 one-time consumption과 Audit를 함께 commit한다. Support는 owner table을 직접 쓰지 않고
Loyalty/Promotion public Application API를 호출하며 외부 Provider 호출은 없다.

POINT는 `SUPPORT_COMPENSATION` PointTransaction과 새 PointLot으로 발급한다. SHARED는 Platform/Store 별도 funding
Lot/transaction leg를 만들고 합계가 request amount와 일치한다. COUPON은 Promotion-owned immutable fixed-KRW template로
발급하며 자유 조건을 받지 않는다. issuance cost snapshot은 future redemption Order의 existing settlement-input 경계가
사용하고 issuance 시점에는 SettlementItem/Adjustment를 만들지 않는다. 알림은 발급 commit 뒤 별도 durable transaction이며
실패가 benefit을 rollback하지 않는다.

## Alternatives Considered

- PointAdjustment/restoration 재사용: audit/source 의미가 잘못되어 기각.
- mutable current table로 재계산: 과거 request가 변해 기각.
- agent 자유 쿠폰/비용 입력: 오남용·정산 불일치로 기각.

## Rationale

금전적 부수효과, 정책 version과 비용 책임을 재현 가능하게 한다.

## Consequences

Initial amounts(3k/10k/30k, 30-day 10k)은 측정 optimum이 아닌 policy assumptions다. buckets/owner APIs/schema가 필요하다.

## Verification

Band boundaries, version immutability, duplicate/rolling concurrency, Lot/issuance/Audit atomicity, unknown cost and notification failure.

## Metrics

Band/benefit/cost responsibility별 evaluated/issued, duplicate/limit conflict. 개인 식별 label 금지.

## Revisit Conditions

실제 distribution, abuse, cost or resolution outcomes가 초기 policy 재조정을 요구할 때.

## Related Decisions

ADR-011, ADR-028, ADR-041~043, ADR-049, ADR-066.
