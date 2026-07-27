# ADR-001: Modular Monolith로 시작

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

주문, 결제, 혜택, 정산과 알림은 경계가 다르지만 초기부터 물리적으로 분리하면 네트워크 실패와 운영 복잡도가 제품 검증보다 앞선다.

## Decision

Spring Modulith 기반 단일 배포 단위를 사용하고 모듈 공개 API, 이벤트와 구조 테스트로 논리적 경계를 강제한다.

## Alternatives Considered

- 단일 계층형 모놀리스
- 초기 마이크로서비스
- 모듈러 모놀리스

## Rationale

로컬 트랜잭션과 빠른 변경의 장점을 유지하면서 향후 분리 가능한 경계를 검증할 수 있다.

## Consequences

- 분산 장애 일부를 초기에는 경험하지 않는다.
- 한 프로세스 장애가 전체에 영향을 줄 수 있다.
- 내부 패키지 접근을 테스트로 엄격히 막아야 한다.

## Verification

- Spring Modulith verify
- ArchUnit 순환·내부 접근 테스트
- 모듈 의존성 문서

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

독립 배포, 확장 또는 장애 격리가 실제 요구가 될 때

## Related Decisions

ADR-002, ADR-010
