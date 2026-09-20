# ADR-131: 매장 거절 정산 제외와 기존 publication의 forward-only 복구

- **Status:** Accepted
- **Date:** 2026-09-14
- **Implementation owner:** [성능·용량·여정 부하 검증](../exec-plans/active/performance-capacity-and-journey-load-validation.md)

## Context

수락되지 않은 `PAID` Order가 매장 직접 거절 또는 수락 timeout으로 `REJECTED`되면
Payment는 전액 Refund를 만들고 `PaymentRefundedV1`을 발행한다. 이 event의
`completionDisposition=PRE_ACCEPTANCE_CANCELLATION`은 완료 전 정산 효과가 없다는 transport
분류이지 고객 취소 원인을 뜻하지 않는다. 기존 Settlement listener는 이 값을
ADR-048의 `CANCELLED/CUSTOMER_REQUEST` 전용 검증으로 보내 `REJECTED` Order를
`SETTLEMENT_SOURCE_CONFLICT`로 만들었다.

성능 기준선 중 2,315개의 Settlement target publication이 같은 계약 불일치로 retry를
소진하고 `MANUAL_REVIEW`에 도달했다. Analytics target은 구현되지 않았으므로 이 backlog의
해결이나 core transaction capacity 성공 조건으로 계산할 수 없다. 기존 event payload,
시도 이력과 수동 검토 evidence를 고쳐 쓰면 원인을 숨기고 replay 안전성을 잃는다.

## Decision

### 종료 원인과 public evidence

- Ordering은 `REJECTED` 전이에 자유 형식 `rejectionReason`과 별개인 폐쇄형
  `OrderRejectionCause`를 저장한다.
  - 매장 owner/staff 명령: `STORE_REJECTION`
  - deadline worker의 `SYSTEM_TIMEOUT`: `ACCEPTANCE_TIMEOUT`
- `IMMEDIATE` 주문의 deadline이 영업 마감으로 단축되어 자유 형식 reason이
  `STORE_CLOSED`인 경우에도 actor는 `SYSTEM_TIMEOUT`, cause는 `ACCEPTANCE_TIMEOUT`이다.
  분류는 reason text에 의존하지 않는다.
- 같은 transaction에서 `OrderRejectedV1` event ID, actor type과 terminal Order version을
  immutable rejection evidence로 저장한다. 원인과 actor는 위 대응 관계가 아니면 저장하지
  않는다.
- Ordering public API는 Order 상태, 고객, rejected 시각, 원인, actor type, event ID,
  terminal/current version과 lifecycle 시각을 Settlement에 제공한다. `rejectionReason`은
  제공하지 않고 원인 판정에 사용하지 않는다.
- 기존 `REJECTED` row는 같은 Order의 source-unique compensation case와 persisted
  `OrderRejectedV1`이 order ID, event ID, rejected 시각, terminal version과 actor type에서
  정확히 일치할 때만 새 evidence를 forward-only로 물질화한다. publication payload를
  수정하지 않는다. 누락·다중값·불일치 row는 nullable legacy evidence로 남아 정상 제외로
  추정되지 않는다.

### Settlement 분기와 결과

- `PaymentRefundedV1` payload와 version 1을 그대로 유지한다. V2를 추가하거나 V1/V2를
  dual publish하지 않는다. `PRE_ACCEPTANCE_CANCELLATION`은 다음 두 서비스를 state-aware로
  분기한다.
  - `CANCELLED`: ADR-048 고객 취소 전용 서비스
  - `REJECTED`: 이 ADR의 매장 거절 전용 서비스
- 매장 거절 전용 서비스는 다음을 모두 확인한다.
  - Order가 `REJECTED`이고 accepted/preparing/ready/completed 이력이 없음
  - persisted cause와 actor type이 `STORE_REJECTION ↔ STORE_OWNER|STORE_STAFF` 또는
    `ACCEPTANCE_TIMEOUT ↔ SYSTEM_TIMEOUT`으로 일치
  - current version, terminal version, event ID와
    `refundSource=event:{OrderRejectedV1.eventId}:payment-refund`가 일치
  - Refund가 `SUCCEEDED`, reason `STORE_ORDER_REJECTED`, 요청·성공·event 금액과 성공 시각,
    Refund version/source/Order가 일치
  - 해당 Order의 SettlementItem과 해당 Refund의 SettlementAdjustment가 없음
- 검증 성공은 정산상 `NOT_APPLICABLE`이다. SettlementItem과 SettlementAdjustment를 만들지
  않고 source당 정확히 하나의 append-only AuditRecord를 저장한 뒤에만 publication을
  완료한다.

  | 필드 | 매장 직접 거절 | 수락 timeout |
  |---|---|---|
  | `action` | `SETTLEMENT_REFUND_EXCLUDED` | `SETTLEMENT_REFUND_EXCLUDED` |
  | `reason` | `ORDER_NOT_COMPLETED_STORE_REJECTION` | `ORDER_NOT_COMPLETED_ACCEPTANCE_TIMEOUT` |
  | `targetType` | `REFUND` | `REFUND` |
  | `sourceReference` | Refund source | Refund source |

- event, Order, Refund, Item, Adjustment, cause, actor, source 또는 version이 없거나 다르면
  정상 결과를 추정하지 않는다. `SETTLEMENT_SOURCE_CONFLICT`로 실패하고 기존 bounded retry와
  `MANUAL_REVIEW`를 유지한다.
- Audit unique key는 기존 `(action, target_type, target_id, source_reference)`를 사용한다.
  이미 같은 key가 있으면 reason까지 같아야 멱등 성공이다. 다른 reason, 하나 초과 Audit,
  Item 또는 Adjustment는 conflict다.

### Forward-only recovery

- 기존 2,315건은 Refund를 다시 요청하거나 event payload를 수정·삭제하거나 publication을
  강제 완료하지 않는다. 기존 failure, attempt, case history도 보존한다.
- 배포 뒤 고객 취소, 매장 직접 거절, 수락 timeout canary가 모두 통과한 뒤에만 복구한다.
  각 canary는 exact terminal cause, Refund 성공 한 건, Settlement publication 완료,
  cause-specific Audit 한 건, Item/Adjustment 0건, 새 conflict/manual review 0건을 증명한다.
- 복구 전 read-only dry-run으로 `OrderRejectedV1` actor/source, Order evidence, Refund
  state/amount/version, Item/Adjustment/Audit를 대조한다. 적격 publication만 고정 목록으로
  보존하고 `1 → 10 → 100 → remainder` batch로 기존 manual-recovery 명령을 실행한다.
- 각 batch 뒤 신규 Audit 수와 publication 완료 수가 batch 크기와 같아야 한다. 중복 Audit,
  Item/Adjustment 또는 새 conflict/manual review가 하나라도 생기면 다음 batch와 부하를
  즉시 중단한다.
- 배포, canary, 실제 복구와 부하 재개는 서로 다른 실행 승인 경계다.

### Capacity claim boundary

- Analytics publication은 `NOT_IMPLEMENTED / NON_GATING`으로 보고하며 성공 target 수에 넣지
  않는다.
- 복구 뒤 산정하는 안정 처리량은 core transaction과 Settlement까지만 주장한다. 전체 플랫폼
  end-to-end capacity는 Analytics가 구현·검증되기 전 `NOT_ESTABLISHED`다.

## Alternatives Considered

### 고객 취소 서비스를 함께 사용

코드가 적지만 `REJECTED`를 `CANCELLED/CUSTOMER_REQUEST`로 오인하고 원인별 Audit를 만들 수
없다. 이번 장애의 직접 원인이므로 거절한다.

### `rejectionReason` 또는 Refund reason으로 원인 추정

기존 필드를 재사용할 수 있지만 자유 text와 Payment 소유 reason은 Ordering의 종료 원인이
아니다. 문구 변경이나 잘못된 source 결합으로 회계 결과가 달라지므로 거절한다.

### `PaymentRefundedV2` 또는 V1 payload 확장

consumer 분기가 단순해질 수 있지만 이미 2,315개의 persisted V1 publication이 있어 clean
cutover 조건을 충족하지 않는다. 기존 V1을 그대로 처리하는 forward-only consumer가 더 작고
안전하다.

### 0원 Adjustment 또는 publication 강제 완료

처리 흔적은 남지만 실제 정산되지 않은 거래를 원장에 넣거나 Audit 없는 성공을 만든다.
BR-16과 ADR-048의 원장 의미를 위반하므로 거절한다.

## Consequences

- Ordering Order schema와 reject transition, public evidence가 확장된다.
- Settlement에 고객 취소와 분리된 store-rejection exclusion service와 state-aware router가
  추가된다.
- 기존 exact event evidence가 없는 legacy `REJECTED` row는 계속 수동 검토이며 자동 복구율을
  높이기 위해 guard를 약화하지 않는다.
- Analytics backlog는 그대로 남고 capacity 보고서에 명시적으로 제외된다.

## Verification

- 새 매장 직접 거절과 timeout 각각의 cause/actor/event ID/version 원자 저장
- 고객 취소, 매장 직접 거절, timeout의 서로 다른 분기와 Audit reason
- 같은 event/new event ID replay의 Audit 한 건, Item/Adjustment 0건
- cause/actor/source/version/amount/time mismatch와 missing evidence의 conflict
- legacy backfill의 exact-match만 갱신하고 mismatch row는 null 유지
- Audit insert 실패 rollback과 publication 미완료
- Analytics target의 `NOT_IMPLEMENTED / NON_GATING` 보고

## Metrics

- `beanflow.settlement.refund.disposition.count{disposition,reason}`
- `beanflow.settlement.refund.exclusion_conflict.count{reason}`

Order, Refund, event와 publication ID는 metric tag로 사용하지 않는다.

## Revisit Conditions

`PaymentRefundedV1` incomplete inventory가 0이고 모든 consumer cutover가 가능한 시점, 또는 정산
제외 거래의 별도 보조원장이 필요한 시점에 재검토한다.

## Related Decisions

- BR-16
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-048](ADR-048-preacceptance-cancellation-settlement-exclusion.md)
- [ADR-058](ADR-058-paid-cancellation-deadline-timeout-work.md)
- [ADR-068](ADR-068-immutable-integration-event-snapshots.md)
- [ADR-125](ADR-125-publication-unknown-execution-recovery.md)
- [ADR-130](ADR-130-perf-selective-database-cutover.md)
