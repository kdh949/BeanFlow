# Customer Order Cancellation Decision Closure

이 표는 고객 취소 구현에 필요한 제품·구조 결정을 canonical source에 연결한다.
`Confirmed=Yes`는 기존 Accepted 기록이 명확하다는 뜻이며 구현 또는 release gate 완료를
뜻하지 않는다. 외부 사실이 확인되지 않은 행은 `No`다.

| Topic | Final decision | Canonical source | Confirmed | Date | Prerequisite |
| ----- | -------------- | ---------------- | --------: | ---- | ------------ |
| Allowed states | `PENDING_PAYMENT`, acceptance deadline 전 `PAID`만 고객 취소 | BR-14, ADR-029 | Yes | 2026-07-31 | 정확한 deadline guard |
| Terminal model | 별도 Cancellation Aggregate 없이 Order `CANCELLED` | ADR-029, ADR-031 | Yes | 2026-07-31 | Order cause/reason/time DB invariant |
| HTTP success | C0 200, C1 202 | ADR-031, ADR-035 | Yes | 2026-07-31 | 각 commit gate 완성 |
| Authorization | CUSTOMER가 자기 Order만 취소; GET과 같은 403 | ADR-030 | Yes | 2026-07-31 | ownership contract test |
| Reason | 여섯 reasonCode 필수, normalized detail 선택·비전파 | BR-14, ADR-031 | Yes | 2026-07-31 | validation/redaction test |
| Idempotency | Order lock transaction, stored 200/202 body, replay 표시 없음 | ADR-032, ADR-057 | Yes | 2026-07-31 | cancellation idempotency schema |
| Prior partial refund | 취소 허용, remaining cash와 미복원 allocation만 처리 | ADR-036 | Yes | 2026-07-31 | allocation foundation |
| Unresolved refund | REQUESTED/PROCESSING/RETRY_SCHEDULED/UNKNOWN/RECONCILING/MANUAL_REVIEW는 409; FAILED 허용 | BR-14, ADR-038 | Yes | 2026-07-31 | Order→Payment lock order |
| Refund request budget | 안전 allowlist 실패만 최초 포함 3 REQUEST | ADR-038 | Yes | 2026-07-31 | Provider code contract |
| Refund lookup budget | UNKNOWN 뒤 REQUEST 중단, LOOKUP 최대 5 | ADR-037 | Yes | 2026-07-31 | 분리 attempt schema |
| Customer projection | 내부 실패·불명·manual은 PROCESSING, 필요 시 REFUND_DELAYED | ADR-038, ADR-050 | Yes | 2026-07-31 | snapshot/setup detector |
| BENEFIT_ONLY | Refund 없음, PAYMENT NOT_REQUIRED, 네 금액 0, C1 202 | ADR-039 | Yes | 2026-07-31 | common Case |
| Compensation model | trigger-aware OrderCompensationCase와 six steps | ADR-033 | Yes | 2026-07-31 | migration strategy |
| Benefit policy | trigger×COUPON/POINTS 네 head와 Case당 두 snapshot | ADR-041 | Yes | 2026-07-31 | policy foundation |
| Owner resource state | common RELEASED_AFTER_TERMINATION + trigger/source | ADR-040 | Yes | 2026-07-31 | owner migration |
| Cancellation event | PAID에서 four-owner `OrderCancelledV1`; Payment/Notification 제외 | ADR-034, ADR-044 | Yes | 2026-07-31 | event compatibility strategy |
| Event payload | customer/store/reason/payment/detail 없는 최소 payload | ADR-055 | Yes | 2026-07-31 | contract serialization test |
| Accepted notification | C0/C1에서 Delivery 직접 저장 | ADR-044 | Yes | 2026-07-31 | Notification public API |
| Refund notifications | Payment terminal result event로 success/delayed 각각 한 번 | ADR-045, ADR-046 | Yes | 2026-07-31 | terminal result publication |
| Notification step | 기본 accepted Delivery만 단조 추적 | ADR-047 | Yes | 2026-07-31 | common Case integration |
| Settlement | Item/Adjustment 없음, source-unique NOT_APPLICABLE Audit | ADR-048 | Yes | 2026-07-31 | Settlement foundation |
| Setup detection | inline + 1분 batch-100 scanner, 저장 실패는 503/retry | ADR-051 | Yes | 2026-07-31 | Reprocessing schema |
| Safe repair | 완전 snapshot+Refund만 누락 시 LOOKUP-only | ADR-052 | Yes | 2026-07-31 | immutable fingerprint |
| Repair approval | 서로 다른 두 active operator, 30분 window | ADR-053 | Yes | 2026-07-31 | Identity/Operations API |
| Deadline timeout | CT가 durable work 저장 후 409 | ADR-058 | Yes | 2026-07-31 | AcceptanceTimeoutWork |
| Clean cutover eligibility | 모든 운영 gate 항목이 명시적으로 0일 때만 허용 | ADR-059 | No | 2026-07-31 | external release evidence |
| Migration strategy now | evidence 전 V8/V1 수정 금지 | ADR-059 audit clarification | Yes | 2026-07-31 | gate 통과 또는 forward ADR |
| Implementation scope | allocation, Settlement, compensation, command, recovery 모두 포함 | ADR-060 | Yes | 2026-07-31 | 여섯 ExecPlan 순차 완료 |

## Open items

- clean-cutover 대상 운영 사실은 확인되지 않았다.
- nonzero 또는 unknown gate 결과에 사용할 구체 forward migration/compatibility 전략은
  실제 schema/publication/consumer inventory가 확보된 뒤 새 ADR로 확정해야 한다.

새로운 제품 정책 질문은 없다. 위 Open item은 제품 선택이 아니라 fact verification과
그 결과에 따른 구조 결정이다.
