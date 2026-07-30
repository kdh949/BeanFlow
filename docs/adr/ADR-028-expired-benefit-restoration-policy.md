# ADR-028: 버전형 만료 혜택 복원 정책

- **Status:** Accepted
- **Date:** 2026-07-30

## Context

결제 후 매장 거절 시 사용 쿠폰과 포인트를 복원해야 한다. 그러나 짧은 수락 대기 중
원 혜택이 만료되면 원 만료시각을 유지한 복원은 고객에게 실질적인 가치를 돌려주지
못한다. 이 동작과 보상 유효기간은 운영 중 변경 가능하되 진행 중 거절 결과가 설정
변경에 따라 달라져서는 안 된다.

## Decision

- Operations가 append-only `ExpiredBenefitRestorationPolicyVersion`과 현재 head를
  소유한다.
- 기본 mode는 `COMPENSATE_WITH_NEW_ISSUANCE`, 유효기간은 30일이다.
- `PLATFORM_OPERATOR` 전용 API로 현재 정책을 조회하고 새 version을 생성한다.
- 변경 명령에는 Idempotency-Key, expected current version, 변경 사유가 필요하다.
- 변경은 과거 case를 수정하지 않고 다음 거절부터 적용한다.
- 거절 transaction이 version, mode, validity days를 `OrderRejectedV1`과
  compensation case에 snapshot한다. 모든 consumer는 현재 head가 아니라 snapshot을
  사용한다.
- `COMPENSATE_WITH_NEW_ISSUANCE`에서는 거절 시각에 만료된 쿠폰은 같은 Campaign의
  보상 CouponIssuance, 포인트는 원 allocation별 보상 PointLot으로 발급한다.
- `PRESERVE_ORIGINAL_EXPIRY`에서는 원 복원 시도를 원장에 남기되 이미 만료된 가치를
  사용 가능 잔액으로 만들지 않는다.

## Alternatives Considered

### 고정 30일

- 구현은 단순하지만 운영 정책 변경에 배포가 필요하고 변경 이력을 설명하기 어렵다.

### consumer가 처리 시점의 현재 정책 조회

- event payload는 작지만 재시도 시점에 따라 결과가 바뀌어 멱등성과 감사 가능성이
  깨진다.

## Rationale

정책을 거절 사실에 snapshot하면 설정 변경, 지연 전달과 재시작 뒤에도 동일한 결과를
재현할 수 있다. append-only version은 누가 어떤 이유로 미래 거절 동작을 바꿨는지
보존한다.

## Consequences

- 정책 head 변경과 거절은 DB 잠금으로 선형화한다.
- 보상 issuance/lot은 원 발급 reference와 source event를 보존한다.
- 정책 API와 거절 case에 version을 노출한다.

## Verification

- expected version 충돌과 동일 idempotency key 재생
- 정책 동시 변경 중 단일 새 head
- 변경 직전·직후 거절이 서로 다른 snapshot 사용
- 지연·중복 consumer가 snapshot으로 같은 disposition 생성

## Metrics

- `beanflow.operations.benefit_policy.change.count{mode,outcome}`
- `beanflow.order.rejection.expired_benefit.count{benefit,mode,disposition}`

## Revisit Conditions

보상 가치에 별도 상한, Campaign 종료 후 대체 가치 또는 고객별 예외 승인이 필요할 때

## Related Decisions

- BR-06
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-024](ADR-024-coupon-calculation-model.md)
