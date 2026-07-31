# Customer Order Cancellation Decision Closure

이 표는 고객 취소 구현에 필요한 제품·구조 결정을 canonical source에 연결한다.
`Canonical documents aligned=Yes`는 최신 Accepted amendment까지 문서가 일치한다는
뜻이다. `Product owner confirmed=Yes`는 2026-07-31 product owner가 이 표의 모든 최종
결정과 clean-cutover 운영 사실을 명시적으로 승인·확인했고, 2026-08-01 자 후속 행은
같은 product owner가 문서 간 모순·모호성 검토 결과를 보고 추가로 확정했다는 뜻이다. 문서
정합성 판정과 승인 증거는 서로 다른 열로 유지한다.

| Topic | Final decision | Canonical source | Canonical documents aligned | Product owner confirmed | Date | Prerequisite |
| ----- | -------------- | ---------------- | ---------------------------: | ----------------------- | ---- | ------------ |
| Allowed states | `PENDING_PAYMENT`, acceptance deadline 전 `PAID`만 고객 취소 | BR-14, ADR-029 | Yes | Yes | 2026-07-31 | 정확한 deadline guard |
| Terminal model | 별도 Cancellation Aggregate 없이 Order `CANCELLED` | ADR-029, ADR-031 | Yes | Yes | 2026-07-31 | Order cause/reason/time DB invariant |
| HTTP success | C0 200, C1 202 | ADR-031, ADR-035 | Yes | Yes | 2026-07-31 | 각 commit gate 완성 |
| Recovery schemas | 승인 조회 `PaymentApprovalRecoverySummary`, 취소 환불 `CancellationRefundRecoverySummary` 분리 | ADR-031, OpenAPI | Yes | Yes | 2026-07-31 | contract reference check |
| Authorization | CUSTOMER가 자기 Order만 취소; GET과 같은 403 | ADR-030 | Yes | Yes | 2026-07-31 | ownership contract test |
| Reason | Order reason/detail, Audit reason, Refund/Provider normalized reason; event/log에는 reason/detail 없음 | BR-14, ADR-055 | Yes | Yes | 2026-07-31 | validation/redaction test |
| Idempotency | Order lock transaction, stored 200/202 body, replay 표시 없음 | ADR-032, ADR-057, ADR-064 | Yes | Yes | 2026-08-01 | cancellation idempotency schema와 위험 기반 모델 선택 |
| Prior partial refund | 취소 허용, remaining cash와 미복원 allocation만 처리 | ADR-036 | Yes | Yes | 2026-07-31 | allocation foundation |
| Unresolved refund | REQUESTED/PROCESSING/RETRY_SCHEDULED/UNKNOWN/RECONCILING/MANUAL_REVIEW는 409; FAILED 허용 | BR-14, ADR-031, ADR-036, ADR-038 | Yes | Yes | 2026-08-01 | Order→Payment lock order |
| Refund request budget | 안전 allowlist 실패만 최초 포함 3 REQUEST | ADR-038 | Yes | Yes | 2026-07-31 | Provider code contract |
| Refund lookup budget | UNKNOWN 뒤 REQUEST 중단, LOOKUP 최대 5 | ADR-037 | Yes | Yes | 2026-07-31 | 분리 attempt schema |
| Customer projection | 내부 진행/불명은 PROCESSING, 실패/manual/setup 손상은 PROCESSING+REFUND_DELAYED | ADR-038, ADR-050 | Yes | Yes | 2026-07-31 | snapshot/setup detector |
| BENEFIT_ONLY | Refund 없음, PAYMENT NOT_REQUIRED, 네 금액 0, C1 202 | ADR-039 | Yes | Yes | 2026-07-31 | common Case |
| Compensation model | trigger-aware OrderCompensationCase와 six steps | ADR-033 | Yes | Yes | 2026-07-31 | migration strategy |
| Benefit policy | 종료용 trigger×COUPON/POINTS 네 head와 PARTIAL_REFUND×POINTS 한 head; 종료 Case당 두 snapshot | ADR-041, ADR-063 | Yes | Yes | 2026-08-01 | Plan 10 policy foundation |
| Owner resource state | common RELEASED_AFTER_TERMINATION + trigger/source | ADR-040 | Yes | Yes | 2026-07-31 | owner migration |
| Cancellation event | PAID에서 four-owner `OrderCancelledV1`; Payment/Notification 제외 | ADR-034, ADR-044 | Yes | Yes | 2026-07-31 | event compatibility strategy |
| Event payload | customer/store/reason/payment/detail 없는 최소 payload | ADR-055 | Yes | Yes | 2026-07-31 | contract serialization test |
| Release paths | gate 전체 0은 clean cutover, nonzero/unknown은 forward migration/compatibility | ADR-059, BR-14 | Yes | Yes | 2026-07-31 | point-in-time release evidence와 재확인 |
| Accepted notification | C0/C1에서 Delivery 직접 저장 | ADR-044 | Yes | Yes | 2026-07-31 | Notification public API |
| Refund notifications | Payment terminal result event로 success/delayed 각각 한 번 | ADR-045, ADR-046 | Yes | Yes | 2026-07-31 | terminal result publication |
| Notification step | 기본 accepted Delivery만 단조 추적 | ADR-047 | Yes | Yes | 2026-07-31 | common Case integration |
| Settlement | Item/Adjustment 없음, source-unique NOT_APPLICABLE Audit | ADR-048 | Yes | Yes | 2026-07-31 | Settlement foundation |
| Setup detection | inline + 1분 batch-100 scanner, 저장 실패는 503/retry | ADR-051 | Yes | Yes | 2026-07-31 | Reprocessing schema |
| Safe repair | 완전 snapshot+Refund만 누락 시 LOOKUP-only | ADR-052 | Yes | Yes | 2026-07-31 | immutable fingerprint |
| Repair approval | 서로 다른 두 active operator, 30분 window | ADR-053 | Yes | Yes | 2026-07-31 | Identity/Operations API |
| Deadline timeout | CT가 durable work 저장 후 409 | ADR-058 | Yes | Yes | 2026-07-31 | AcceptanceTimeoutWork |
| Clean cutover gate result | `PASSED`; 모든 운영 항목이 명시적 0 | ADR-059, release-gate evidence | Yes | Yes | 2026-07-31 | 구현·배포 직전 inventory 재확인 |
| Migration strategy now | ADR-059 clean cutover; producer·consumer·fixture 동시 전환, legacy compatibility/이중 발행 없음 | ADR-059, release-gate evidence | Yes | Yes | 2026-07-31 | allocation·Settlement·compensation foundation |
| Implementation scope | allocation, Settlement, compensation, command, recovery 모두 포함 | ADR-060 | Yes | Yes | 2026-07-31 | 여섯 ExecPlan 순차 완료 |
| NOT_REQUIRED 금액 | 요청액 0만 뜻하며 나머지 세 금액 0을 강제하지 않음; 네 금액은 all-or-nothing | ADR-036, ADR-031, OpenAPI | Yes | Yes | 2026-08-01 | recovery snapshot |
| Order 취소 필드 projection | 고객 `Order`는 cancelledAt·cancellationCause·cancellationReasonCode; 매장 `StoreOrder`는 cancelledAt·cancellationCause만 | ADR-030, ADR-031, OpenAPI | Yes | Yes | 2026-08-01 | Order cancellation 컬럼 |
| Cancellation detail 노출 | 어떤 projection·event·Audit·Provider 요청·log에도 없음 | BR-14, ADR-029, ADR-055 | Yes | Yes | 2026-08-01 | redaction test |
| Refund attempt 예산 | REQUEST 3과 LOOKUP 5는 독립 예산이고 전체 상호작용 상한은 8; 전체 `attempt_count`에 6 상한 없음 | ADR-038, ADR-037, ADR-036 | Yes | Yes | 2026-08-01 | 분리 attempt schema |
| PENDING_PAYMENT 금액 | `NOT_REQUIRED`이면서 네 금액을 0으로 표현하지 않고 함께 생략; 네 금액 0은 BENEFIT_ONLY뿐 | ADR-031, ADR-036, ADR-050, OpenAPI | Yes | Yes | 2026-08-01 | 조건부 금액 계약 |
| 매장 보상 projection | 매장은 `trigger`·case `state`·`updatedAt`만 담은 `StoreCompensationSummary`; step·attempt·error·caseId·policy version은 운영자 전용 | ADR-030, ADR-033, authorization matrix, OpenAPI | Yes | Yes | 2026-08-01 | 공통 compensation foundation |
| 부분 환불 쿠폰 | 쿠폰은 복원하지 않고 allocation은 귀속·감사 원장으로만 저장; 후속 전체 종료가 원 쿠폰을 한 번 복원 | BR-12, ADR-014, ADR-036 | Yes | Yes | 2026-08-01 | allocation foundation |
| Refund 비동기 projection | 요청 금액은 항상 반환하고 현금·포인트 상태와 확정 금액은 owner별로 분리 | ADR-061, OpenAPI | Yes | Yes | 2026-08-01 | Payment/Loyalty query composition |
| 부분 환불 만료 포인트 | PARTIAL_REFUND×POINTS 기본 COMPENSATE_WITH_NEW_ISSUANCE/30일; PointReservation은 USED 유지 | ADR-063 | Yes | Yes | 2026-08-01 | policy snapshot과 allocation 원장 |
| Policy foundation 소유권 | 최종 다섯 head 저장소·seed·운영 API는 Plan 10이 단독 구현; Plan 30은 종료용 네 head 소비 | ADR-063, Plan 10, Plan 30 | Yes | Yes | 2026-08-01 | migration 중복 부재 |
| 환불 적립 포인트 회수 | 실제 차감은 음수 `RECOVERY` PointTransaction, 미회수 잔액은 별도 PointRecoveryPending으로 보존하고 이후 적립이 먼저 상계 | BR-13, ADR-065, OpenAPI | Yes | Yes | 2026-08-01 | Plan 10 recovery ledger foundation |
| Order 취소 migration 소유권 | ADR-029의 네 컬럼·세 CHECK·precheck는 Plan 40이 단독 구현 | ADR-059, Plan 30, Plan 40 | Yes | Yes | 2026-08-01 | command mapping과 동시 반영 |
| Settlement item 발견 | batch-scoped item 목록 API로 store member가 dispute용 itemId를 조회 | ADR-062, OpenAPI | Yes | Yes | 2026-08-01 | Settlement foundation |

## Open items

- partial refund allocation, Settlement와 공통 compensation foundation은 아직
  구현되지 않았다.
- point-in-time evidence가 무효화되거나 재확인에서 nonzero/unknown이 나오면 clean
  cutover를 중단하고 실제 schema/publication/consumer inventory를 입력으로 forward
  migration/compatibility ADR을 확정해야 한다.

새로운 제품 정책 질문은 없고 위 모든 결정은 product owner가 승인했다. clean-cutover
fact gate도 닫혔지만 이는 고객 취소 command 구현 완료를 뜻하지 않는다.
