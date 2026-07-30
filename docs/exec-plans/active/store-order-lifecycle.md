# 결제 완료 주문의 매장 처리 생명주기 완성

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

결제 완료 Order가 `PAID`에 머무르지 않고 매장 수락, 제조, 준비 완료, 인도 완료까지
전이할 수 있게 한다. 매장이 3분 안에 응답하지 않거나 명시적으로 거절하면 Order는
`REJECTED`가 되고, 환불·재고·슬롯·쿠폰·포인트·고객 알림은 원본 전이와 분리된
멱등 보상으로 완료한다. 보상 실패는 성공으로 숨기지 않고 조회 가능한 상태와 운영
case로 남긴다.

## Current State

- 구현된 Order 상태는 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `CANCELLED`뿐이다.
- 외부 결제 승인과 `BENEFIT_ONLY` 주문은 예약을 확정하고 Order를 `PAID`로 만든다.
- 확정된 슬롯·재고·쿠폰·포인트를 매장 거절로 복원하는 공개 API가 없다.
- 일반 Refund, Identity membership, NotificationDelivery와 영속 event publication이
  아직 없다.
- OpenAPI의 store-order 전이는 거절 보상 진행 상태와 무관하게 `200`만 정의한다.

## Definitions

- **Acceptance deadline:** 결제 완료 시각부터 3분이 되는 시각. 이 시각부터 수락은
  허용하지 않는다.
- **Warning deadline:** 결제 완료 시각부터 2분이 되는 시각.
- **Rejection compensation:** Order `REJECTED` 이후 각 owner Context가 환불과 자원
  복원을 수행하는 후속 작업.
- **Expired benefit policy:** 거절 시 원 쿠폰 또는 PointLot이 이미 만료된 경우 새
  보상 혜택을 발급할지 결정하는 운영 정책.
- **Persistent publication:** 원본 transaction과 함께 저장되어 재시작 후에도 listener
  delivery를 복구할 수 있는 event publication.

## Scope

### In Scope

- Store membership 기반 객체 수준 인가
- Order 수락·제조·준비·완료·거절 상태와 시각
- Store order 조회·상태 전이 API와 명령 멱등성
- 2분 경고와 정확한 3분 자동 거절
- Spring Modulith JPA Event Publication Registry
- 거절 보상 case와 owner별 진행 상태
- 확정 슬롯·재고와 사용 쿠폰·포인트 복원
- 매장 거절용 전액 Refund와 reconciliation
- store warning, rejection, ready NotificationDelivery
- 동적 만료 혜택 정책 조회·변경 API

### Non-goals

- 고객 취소와 수락 후 매장 취소
- 공개 부분 환불 API
- 실제 PG·알림 Provider 계약
- 포인트 적립, 정산, Analytics
- 운영 설정 UI
- Kafka, Redis, Kubernetes

## Business Rules and Invariants

- 허용 전이는 `PAID -> ACCEPTED | REJECTED`,
  `ACCEPTED -> PREPARING -> READY -> COMPLETED`뿐이다.
- `now >= acceptanceDeadlineAt`이면 수락은 실패하고 timeout 거절만 가능하다.
- `ACCEPTED` 이후 단순 거절은 `409 ORDER_STATE_CONFLICT`다.
- 같은 Idempotency-Key와 같은 payload는 최초 응답을 재생하고 다른 payload는
  `409 IDEMPOTENCY_KEY_REUSED`다.
- Order `REJECTED`는 Refund, 자원 복원 또는 알림 성공을 뜻하지 않는다.
- owner별 source reference와 DB Unique Constraint가 중복 보상을 막는다.
- 기본 만료 혜택 정책은 새 발급, 30일이며 운영 변경은 다음 거절부터 적용한다.
- 정책 version, mode와 validityDays는 `OrderRejectedV1`에 snapshot한다.

## Architecture and Transaction Boundaries

Store 명령 transaction은 Order 잠금, membership 검증, idempotency arbitration,
Aggregate 전이, AuditRecord, compensation case와 event publication을 함께 커밋한다.
거절 owner listener는 각 Context transaction에서 멱등하게 실행한다.

Refund와 Notification Provider 호출은 다음 경계를 사용한다.

```text
Tx 1: due work claim
commit
External Provider call
Tx 2: SUCCEEDED | FAILED | UNKNOWN | RETRY_SCHEDULED | MANUAL_REVIEW
```

수락과 timeout은 같은 Order row의 guarded transition으로 경쟁한다. 분산락은
사용하지 않는다.

## Alternatives Considered

- 모든 보상을 거절 HTTP transaction에서 동기 실행: 외부 latency와 owner 실패가
  원본 전이를 장시간 점유하므로 제외.
- 사용자 JWT의 store ID claim만 신뢰: membership 폐기와 다점포 권한을 검증할 수 없어
  제외.
- custom outbox 또는 Kafka: 현재 단일 PostgreSQL 배포 단위에서는 Spring Modulith
  publication registry보다 운영 비용이 크므로 제외.
- 만료된 원 혜택의 expiry 수정: 과거 발급 사실을 변경하므로 새 보상 발급을 선택.

## Failure Semantics

- publication 저장 실패는 원본 Order 전이를 rollback한다.
- listener 실패 publication은 bounded retry 후 `MANUAL_REVIEW` case를 남긴다.
- Refund timeout은 `UNKNOWN`이며 동일 refund를 다시 호출하지 않고 Provider 조회만
  수행한다.
- Notification ACK 유실은 성공으로 단정하지 않고 같은 provider idempotency key로
  재처리한다.
- owner 보상 성공 뒤 compensation step 갱신이 실패하면 중복 event에서
  `ALREADY_APPLIED`를 확인하고 step만 복구한다.

## Data and Migration

기존 migration은 수정하지 않고 forward migration을 추가한다.

- Identity membership
- Order lifecycle timestamp, state constraint와 deadline index
- store command idempotency
- event publication
- expired-benefit policy version/head
- rejection compensation case/step
- reservation rejection states와 source references
- Refund와 reconciliation
- NotificationDelivery와 retry claim

기존 `PAID` row는 `updated_at`을 `paid_at` 기준으로 backfill한다. production row가
존재하면 worker 활성화 전에 별도 운영 검증을 수행한다.

## API and Event Contracts

- `GET /api/v1/store-orders/{orderId}`
- `PATCH /api/v1/store-orders/{orderId}/status`
- `GET /api/v1/operations/policies/expired-benefit-restoration`
- `PATCH /api/v1/operations/policies/expired-benefit-restoration`
- `StoreAcceptanceWarningRequestedV1`
- `OrderAcceptedV1`
- `OrderReadyV1`
- `OrderCompletedV1`
- `OrderRejectedV1`

정상 전이는 `200`, 거절은 compensation 진행을 포함한 `202`다.

## Milestones

1. 정책·ADR·OpenAPI와 이 ExecPlan을 확정한다.
2. Identity membership과 Order lifecycle Aggregate/API를 구현한다.
3. 영속 publication, warning/timeout worker와 compensation case를 구현한다.
4. 슬롯·재고·쿠폰·포인트 owner 보상을 구현한다.
5. Refund와 Notification의 외부 호출 분리·reconciliation을 구현한다.
6. end-to-end 동시성·장애 테스트와 운영 관측을 완료한다.

## Required Tests

- 전체 Order 정상·거절 전이와 정확한 deadline 경계
- 역할, ACTIVE/REVOKED membership과 타 매장 접근
- store command replay, payload 충돌과 동시 요청
- 수락 대 timeout, 수동 거절 대 timeout
- owner별 중복 event와 수량·원장 tie-out
- 두 expired-benefit policy mode와 policy version snapshot
- Refund timeout/명시 실패/수동 검토
- Notification 1분·5분·30분 retry와 네 번째 실패
- publication failure, restart와 bounded resubmission
- Spring Modulith 모듈 경계와 기존 주문·결제 회귀

## Validation Commands

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew clean build --stacktrace
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.order.store_transition.count{from,to,outcome}`
- `beanflow.order.acceptance.duration`
- `beanflow.order.acceptance.timeout.count`
- `beanflow.order.rejection.compensation.lag`
- `beanflow.order.rejection.compensation.failure`
- `beanflow.payment.refund.unknown.count`
- `beanflow.event.publication.pending.count`
- `beanflow.event.publication.oldest.age`
- `beanflow.notification.delivery.count{outcome}`

Order, Store와 Customer ID는 metric tag로 사용하지 않는다.

## Documentation Updates

- Business Policy BR-06
- ADR-010, ADR-011, ADR-015, ADR-024
- 신규 membership 인가 ADR과 동적 만료 혜택 ADR
- state machine, transaction boundary, event catalog, policy traceability
- OpenAPI와 error catalog
- store lifecycle, refund, notification runbook

## Progress

- [x] 계획과 제품 정책 선택 확정
- [ ] 문서·계약 반영
- [ ] Identity와 Order lifecycle
- [ ] persistent publication과 compensation case
- [ ] owner별 자원 복원
- [ ] Refund와 Notification
- [ ] end-to-end 검증

## Surprises & Discoveries

- 기존 `README.md`에 사용자 작업이 있어 이 Feature의 commit에서 제외한다.
- 전체 Testcontainers 테스트는 현재 Docker daemon 미탐지로 기준선을 재현하지 못했다.
  Docker가 필요 없는 순수 단위·구조 테스트는 통과했다.

## Decision Log

| Date | Status | Decision | Record |
|---|---|---|---|
| 2026-07-30 | Accepted | 만료 혜택은 기본 30일 새 보상 발급 | BR-06, ADR-028 |
| 2026-07-30 | Accepted | 운영 정책 변경은 다음 거절부터 snapshot 적용 | BR-06, ADR-028 |
| 2026-07-30 | Accepted | 운영 UI 제외, 감사 가능한 정책 API 포함 | ADR-028 |
| 2026-07-30 | Accepted | JPA Event Publication Registry 사용 | ADR-010 |

## Outcomes & Retrospective

구현 완료 후 실제 테스트 수, 실패·복구 결과와 남은 위험을 기록한다.

## Revision Notes

- 2026-07-30: 최초 작성.
