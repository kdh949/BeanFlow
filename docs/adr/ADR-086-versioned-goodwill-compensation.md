# ADR-086: versioned risk compensation과 goodwill source 분리

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

기존 refund, benefit restoration과 audited PointAdjustment는 고객 불편 goodwill과 목적·승인·비용 책임이 다르다.

## Decision

REFUND, BENEFIT_RESTORATION, LEDGER_CORRECTION, GOODWILL_COMPENSATION source를 분리한다. Support compensation은 immutable PolicyVersion, exact request, one POINT-or-COUPON benefit, rolling buckets, duplicate terminal key와 cost responsibility snapshot을 가진다. Point는 new Lot/append-only transaction, Coupon은 approved immutable template를 사용한다. Unknown responsibility는 Store/Platform fallback하지 않는다. HIGH/EXCEPTIONAL은 Operations investigation 후 agent execution이다.

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
