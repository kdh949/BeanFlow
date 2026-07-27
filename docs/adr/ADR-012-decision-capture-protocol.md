# ADR-012: 질문 기반 결정 기록 절차

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

개발 중 질문 답변이 코드에만 반영되면 근거가 사라지고 다음 세션에서 같은 결정을 반복한다.

## Decision

제품 수치는 Business Policy, 구조적 결정은 ADR, 국소적 선택은 Minor Decision에 구현 전에 기록한다. 대화 전문은 저장하지 않는다.

## Alternatives Considered

- 채팅 기록에만 의존
- 모든 결정을 ADR로 기록
- 영향도에 따라 세 단계로 분류

## Rationale

중요한 결정의 추적성을 확보하면서 기록 과잉을 피한다.

## Consequences

- 문서 갱신 비용이 추가된다.
- 분류 기준을 일관되게 적용해야 한다.

## Verification

- PR checklist
- review에서 behavior change와 decision record 비교

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

결정 기록이 과도하거나 누락되는 반복 패턴이 관찰될 때

## Related Decisions

docs/decisions/README.md
