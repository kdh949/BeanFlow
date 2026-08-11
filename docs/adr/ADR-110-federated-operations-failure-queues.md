# ADR-110: 소유 Context 기반 운영 실패 큐 연합 조회

- **Status:** Accepted
- **Date:** 2026-08-12
- **Implementation owner:** [Operations work queues](../exec-plans/active/productization-100-operations-work-queues.md)

## Context

운영자 화면은 결제 `UNKNOWN`, 알림 재시도·실패와 정산 실패를 한 곳에서 찾아야 한다. 하지만 현재
`operations_reprocessing_case`는 `OPEN`, `RUNNING`, `RESOLVED`, `MANUAL_REVIEW`만 저장하고 실제
Payment·NotificationDelivery의 상태와 시도 횟수는 각 소유 table에 있다.

ReprocessingCase를 공통 queue로 확장해 모든 원본 상태를 복제하면 Payment가 승인·환불로 수렴하거나
Notification retry가 성공했을 때 두 상태를 함께 갱신해야 한다. 그 동기화 실패는 운영 화면이 틀린
명령을 제안하는 새 실패 모드다.

## Decision

BR-43에 따라 P0는 source-owned Query Projection의 유형별 연합 조회를 사용한다.

```text
OperationsFailureQueryService
  PAYMENT      → PaymentFailureQueryOperations
                 + OperationsReprocessingCaseQuery(PAYMENT_RECONCILIATION,
                                                     PAYMENT_CANCELLATION_SETUP)
  NOTIFICATION → NotificationFailureQueryOperations
                 + OperationsReprocessingCaseQuery(NOTIFICATION_DELIVERY)
  SETTLEMENT   → OperationsReprocessingCaseQuery(SETTLEMENT_LATE_ITEM,
                                                  SETTLEMENT_ADJUSTMENT,
                                                  SETTLEMENT_DISPUTE)
```

- Payment와 Notification port는 자기 Context DTO만 반환하고 Operations가 그 table/Repository를 직접
  읽지 않는다. ReprocessingCase query는 Operations가 자기 table에 구현한다.
- ReprocessingCase는 source 상태 mirror가 아니라 기존 수동 복구 case다. Payment/Notification의 같은
  source와 case가 함께 있으면 typed owner reference로 하나의 work item으로 병합하며 두 카드로 세지
  않는다. 형식을 모르는 owner reference를 문자열 parsing해 추측하지 않는다.
- 정산 P0 queue는 기존 세 `SETTLEMENT_*` case가 source다. 별도 Settlement failure table이나
  `SettlementFailureQueryOperations`를 만들어 사실을 복제하지 않는다.
- endpoint는 queue type을 path에 포함하고 각 type의 signed cursor를 분리한다.
- source DTO는 원본 state와 closed `attentionState`, attempt count availability, timestamps,
  correlationId, sanitized summary와 source-owned action capability를 제공한다.
- `allowedActions`는 Operations가 현재 actor의 command permission까지 확인해 교집합으로 계산한다.
  read grant만 있는 actor에게 command를 표시하지 않는다.
- `GET /operations/failure-queues/summary`와 exact correlation search는 세 port를 호출한다. 결과 일부를
  반환하지 않으며 어느 port든 실패하면 503이다.
- P0 유형은 PAYMENT, NOTIFICATION, SETTLEMENT다. `EVENT_PUBLICATION`과
  `ACCEPTANCE_TIMEOUT_WORK` 전용 timeline/queue는 P1이다.

## Alternatives Considered

### ReprocessingCase 중앙 복제

단일 SQL·cursor는 단순하지만 source 전이마다 mirror update와 repair가 필요하고 불일치 시 어느 상태가
진실인지 모호하다.

### 이벤트 기반 Operations Read Model

장기 확장성은 좋지만 projection lag, 재생 순서, rebuild·backfill, schema version과 운영 lag 표시가
필요하다. P0의 같은 DB 조회 요구에는 과하다.

### 전 유형 SQL UNION

단일 정렬은 가능하지만 Operations가 Payment·Notification·Settlement table schema에 직접 결합하고
Context별 cursor·retention 변경이 전체 query를 깨뜨린다.

## Rationale

유형별 tab은 운영자가 먼저 실패 종류를 좁히는 실제 작업 방식과 맞고, source of truth를 복제하지
않는다. 전 유형 single page를 포기하는 대신 상태 정확성·장애 의미와 모듈 소유권을 유지한다.

## Consequences

- UI와 API는 유형별 cursor를 따로 관리한다.
- summary와 correlation search는 세 port fan-out 비용이 있다. 측정 전 cache나 read model을 추가하지 않는다.
- Context마다 Projection query·index가 필요할 수 있으나 쓰기 Aggregate 객체 그래프는 커지지 않는다.
- 원본에 attempt count가 없으면 `attemptCountAvailable=false`로 표시한다.
- Operations는 Payment/Notification/ReprocessingCase page를 무제한 메모리 병합하지 않는다. type별
  query가 공통 `(updatedAt DESC, stableId DESC)` tuple의 bounded candidate를 반환하고 scan-boundary
  cursor로 중복 제거 뒤 다음 page를 이어간다.

## Verification

- 세 type의 source state→attentionState exhaustive mapping.
- type/filter/cursor mismatch 400과 각 page의 stable keyset.
- 한 port timeout/DB failure에서 summary/search가 부분 200이 아닌 503.
- source 전이 뒤 별도 sync 없이 다음 조회가 최신 상태를 반환.
- 같은 owner source와 ReprocessingCase의 결정적 dedupe, source map 밖 case의 P0 제외.
- read-only grant와 command permission 교집합에 따른 allowedActions.
- Provider secret, notification destination, raw error/PII 비노출 계약 테스트.
- Modulith/ArchUnit으로 Operations의 다른 Context internal Repository 접근 금지.

## Metrics

- type별 query p50·p95·p99와 result count
- attentionState별 queue depth와 oldest age
- summary/correlation fan-out 지연과 dependency failure
- cursor invalid/mismatch 수

## Revisit Conditions

- type 간 단일 SLA priority queue가 제품 요구가 될 때
- fan-out 지연·DB 부하가 실제 목표를 넘고 event projection 운영 비용보다 커질 때
- 별도 Operations 저장소·서비스로 분리할 때

## Related Decisions

- [BR-39 운영자 P0 조회 권한](../product/business-policy-decisions.md)
- [BR-43 운영 실패 큐](../product/business-policy-decisions.md)
- [ADR-009 실패 상태와 자동 fallback 금지](ADR-009-explicit-failure-semantics.md)
- [ADR-019 알림 retry와 수동 복구](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-069 Operator permission grant](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [ADR-070 signed cursor](ADR-070-signed-cursor-and-pagination-contract.md)
