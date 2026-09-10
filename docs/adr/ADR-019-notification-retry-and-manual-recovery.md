# ADR-019: 알림 재시도와 수동 복구

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-27은 알림 최초 시도 후 1분, 5분, 30분 간격의 세 추가 재시도와 네 번째 실패 후
`MANUAL_REVIEW`를 정한다. Provider ACK 유실은 중복 발송 위험을 만든다.

## Decision

- NotificationDelivery를 event, recipient, channel 범위로 한 번 생성한다.
- Provider 호출은 원본 Order 트랜잭션 밖에서 수행한다.
- 시도 횟수, nextAttemptAt, last failure와 provider idempotency reference를 영속화한다.
- 네 번째 실패 후 자동 재시도를 중단하고 `MANUAL_REVIEW`로 전환한다.
- 운영자 재처리는 동일 delivery idempotency key, 명시적 사유와 AuditRecord를 사용한다.
- 알림 실패는 Order 상태를 롤백하거나 발송 성공으로 기록하지 않는다.

## Alternatives Considered

- 요청 thread에서 동기 발송
- 무한 retry
- bounded persistent retry와 manual recovery

## Rationale

원본 거래와 알림 가용성을 분리하면서 실패와 중복 위험을 운영자가 복구할 수 있게 한다.

## Consequences

- retry scheduler와 backlog 관측이 필요하다.
- Provider가 idempotency를 지원하지 않으면 ACK 유실의 중복 가능성을 운영 상태로
  노출해야 한다.

## Verification

- 1분·5분·30분 Clock schedule
- timeout 후 Provider 성공/ACK 유실
- 네 번째 실패 후 추가 자동 시도 없음
- 동일 delivery의 수동 재처리

## Metrics

- **Target:** retry backlog, attempt count와 manual-review count를 관측
- **Not measured:** Provider latency, 실패율과 rate limit

## Revisit Conditions

Provider SLA, 비용, rate limit 또는 다채널 우선순위가 확정될 때

## Related Decisions

- BR-27, BR-30
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-010](ADR-010-initial-event-publication.md)

## 2026-09-10 명시적 운영 복구 API

ADR-124의 API는 MANUAL_REVIEW인 동일 Delivery의 attemptLimit을 누적 attemptCount+1로 설정하여 추가 시도
한 번만 예약한다. 최초 자동 시도 한도 4는 유지한다. provider idempotency key·payload·시도 이력은 보존하며
재실패/lease 소진은 MANUAL_REVIEW로 돌아간다. 성공 또는 정책에 따른 marketing skip만 Case를 RESOLVED로
전환한다. HTTP는 외부 Provider를 호출하지 않고 RUNNING Case와 접수 응답만 저장한다.
