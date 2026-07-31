# ADR-060: 고객 취소 구현의 MVP 범위와 비목표

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-029~059는 고객 취소의 상태, 환불, 자원·혜택 복원, 알림, 정산, 감사와 운영
복구를 확정했다. 구현 ExecPlan을 작성할 때 주변 lifecycle, 새 외부 Provider와
인프라까지 포함하면 이미 검토한 불변식보다 범위 확장이 더 큰 위험이 된다. 반대로
알림·scanner·2인 승인처럼 확정된 내구 보장을 “후속”으로 미루면 202와 failure
semantics가 약화된다.

## Decision

### In scope

- `PENDING_PAYMENT`와 acceptance deadline 전 `PAID`의 고객 소유 주문 전체 취소
- Order cancellation 원인·사유 모델과 DB 제약
- Tx C0/C1, target별 Audit와 취소 명령 멱등성
- OrderCompensation clean cutover, 여섯 step과 네 owner event consumer
- 선행 부분 환불 allocation 원장과 남은 현금 환불
- 기존 Payment/Notification adapter contract 위의 request·lookup·retry·manual
  review 구현
- BENEFIT_ONLY의 PAYMENT `NOT_REQUIRED`
- 픽업·재고·쿠폰·포인트 source-aware 복원과 trigger별 policy snapshot
- 보상 쿠폰 terms·비용 snapshot
- 접수 및 환불 성공·지연 알림
- 미완료 고객 취소 환불의 정산 제외 Audit
- setup integrity 즉시 탐지·scanner, 제한 복구와 2인 승인
- Ordering idempotency retention과 acceptance timeout durable work
- OpenAPI, 운영 runbook, migration, architecture/contract/concurrency/failure test

### Non-goals

- `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED` 이후 고객 직접 취소
- 운영자 또는 매장 구성원이 실행하는 고객 주문 취소와 수수료·책임 승인 workflow
- 고객의 품목 단위 부분 취소와 Order 원본 line 변경
- 새로운 PG 또는 Notification Provider 선정·계약·credential 온보딩, 실제 sandbox/
  production account 연동과 Provider별 SLA 협상
- 실제 매장 계좌 지급, PG 환불 수수료·세금·회계 전표와 채권 관리
- Kafka, Redis, Kubernetes, MSA 분리와 새 distributed lock
- unsafe setup issue의 자동 snapshot 재구성이나 애플리케이션 밖 직접 DB
  break-glass 절차
- legacy compensation data/publication migration과
  `LEGACY_COMPENSATION_INCOMPLETE` scanner; ADR-059 release gate가 pre-release 전제를
  확인하지 못하면 구현 배포를 중단하고 별도 scope를 만든다.
- 모든 Order lifecycle 상태 전이·멱등성·이벤트의 전면 리팩터링
- 고객 notification preference, 법적 채널·기한과 보상 전체 완료 알림
- 별도 Cancellation Aggregate, cancellation history sub-resource와 고객용 내부 step
  상세
- 실제 사용자·트래픽·성능 개선 주장; 구현 후 정의된 조건에서만 측정한다.

- Non-goal을 local fake, no-op, in-memory fallback으로 대체하지 않는다. 필요한 기존
  Provider 설정이 없으면 해당 production profile은 시작 실패한다.
- In-scope 내구 항목을 미구현 placeholder로 남긴 채 200/202 success를 반환하지
  않는다.

## Alternatives Considered

### 새 외부 Provider 연동까지 포함

- production end-to-end를 한 계획에서 다룰 수 있다.
- 사업자 계약, credential, SLA와 adapter별 실패 code가 제품 취소 구현을 막고
  repository 밖 결정까지 필요하다.

### 취소 core만 구현하고 운영·알림 후속 분리

- 초기 코드 변경이 작다.
- 202 시점 복구 가능성, setup 손상 탐지와 고객 통지가 문서 계약보다 약해진다.

## Rationale

현재 결정으로 완결된 customer-cancellation capability와 그 실패 복구는 함께
구현해야 한다. 외부 사업자·실제 지급·수락 후 책임 정책처럼 새 권한과 사업 결정을
요구하는 기능은 분리해야 ExecPlan이 저장소 안에서 검증 가능한 범위를 유지한다.

## Consequences

- 구현 ExecPlan은 큰 단일 capability지만 결정된 milestone로 분할해야 한다.
- 실제 Provider production readiness는 별도 launch gate/plan이 필요하다.
- release gate가 legacy 전제를 깨면 이번 clean-cutover 계획은 진행할 수 없다.

## Failure Scenarios

- Provider onboarding을 암묵적으로 포함하면 확인되지 않은 code/SLA를 정책으로
  추측한다.
- 운영 scanner와 승인 API를 후속으로 미루면 setup 손상을 감지·복구할 방법 없이
  고객에게 지연만 표시한다.
- ACCEPTED 이후 취소를 상태 guard 우회로 넣으면 제조 비용 정책 없이 환불한다.
- 새 인프라를 편의상 도입하면 장애·제거 비용이 문서화되지 않는다.

## Verification

- ExecPlan scope가 In scope 항목을 모두 milestone/test에 연결
- Non-goal endpoint/state/dependency 부재
- production profile의 fake/no-op fallback 부재
- legacy release gate 실패 시 배포 중단

## Required Tests

- scope traceability matrix의 policy→implementation→test 연결
- 비허용 상태·role의 명시적 거부
- production fake Provider startup failure
- Non-goal API/schema/enum 부재 정적 검사
- release gate nonzero fixture 차단

## Metrics

이 ADR은 runtime metric을 추가하지 않는다. 각 in-scope ADR의 metric을 사용한다.

- **Not measured:** 외부 Provider production readiness

## Revisit Conditions

수락 후 취소 비용 정책, 실제 Provider 계약·지급 또는 operator cancellation 정책이
Accepted 될 때 별도 Feature/ADR로 확장한다.

## Related Decisions

- BR-14
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-053](ADR-053-two-person-setup-repair-approval.md)
- [ADR-059](ADR-059-pre-release-compensation-clean-cutover.md)
