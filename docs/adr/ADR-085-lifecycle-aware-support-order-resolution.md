# ADR-085: 주문 생명주기별 Support 변경과 post-acceptance resolution

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

기존 customer cancellation은 pre-acceptance 범위이며 상담원이 고객을 가장하거나 PREPARING 이후 상태를 rollback하면 actor 의미와 제조 사실·정산이 손상된다.

## Decision

Support는 별도 typed commands를 사용한다. PENDING_PAYMENT/PAID pre-acceptance는 owner cancellation/release/refund 흐름을 재사용하고 reschedule은 new-slot-first atomic swap이다. ACCEPTED는 매장 동의 또는 versioned delegation과 실행시 state check가 필요하다. Initial immutable policy version은 `support-order-change-policy/2026-08-12/v1`이다. cancellation delegation은 store+action+policy version에 고정된 10분/성공 1회, pickup reschedule은 30분/성공 3회다. exact idempotent replay는 추가 소비하지 않고 owner direct change가 commit된 실행만 budget을 소비한다. 건별 confirmation은 exact request/revision/action payload digest/target version/request expiry에 고정한다. 둘 다 store actor가 STORE 비용 책임을 명시 수락해야 하며 책임이 미확정이거나 PLATFORM 귀속이면 direct change를 금지한다. PREPARING/READY/COMPLETED는 Order를 되돌리지 않고 `PostAcceptanceResolutionCase`로 refund/benefit/settlement adjustment를 조정한다. Partial result와 UNKNOWN/RECONCILING을 명시하고 unknown cost owner fallback을 금지한다.

S80 initial ResolutionCase는 S60의 승인된 exact `POST_ACCEPTANCE_RESOLUTION` revision을 유일한 승인 source로
사용하고 별도 resolution approval을 만들지 않는다. Case state는 `PLANNED | EXECUTING |
PARTIALLY_RESOLVED | RECONCILING | RESOLVED | MANUAL_REVIEW`, owner step state는 `PENDING | PROCESSING |
RETRY_SCHEDULED | SUCCEEDED | NOT_REQUIRED | UNKNOWN | RECONCILING | MANUAL_REVIEW | BLOCKED`다.
`UNDETERMINED`에서도 승인된 고객 Refund와 원혜택 restoration은 진행할 수 있지만 cost-attribution/
Settlement step은 `BLOCKED`로 남는다. Store/Platform cost owner를 자동 추정하지 않는다.

### Store confirmation query amendment (2026-09-11)

매장 점주·직원은 자신의 ACCEPTED 주문에 대한 요청 ID로 현재 승인안의 작업·주문·버전·digest·만료만
조회할 수 있다. 상담 내용, 본인확인 세션, 증거와 상담원 식별자는 이 조회에 포함하지 않는다.
조회는 동의를 생성하거나 budget을 소비하지 않으며, 건별 동의 명령은 현재 binding과 서로 다른 actor를
다시 검증한다. 화면에서 STORE 비용 책임을 명시 수락한 경우에만 동의 또는 한시 위임을 제출한다.

### 상담 화면의 해결 후속 조회

승인 workflow는 기존 조회 권한 안에서 생성된 Resolution ID와 현재 주문 버전을 반환한다.
이미 소비된 승인안의 만료와 금융 후속 처리는 구분하며, 현재 Case 담당자·실행 권한을 가진
실행자는 기존 Resolution의 진행 또는 안전한 환불 LOOKUP을 요청할 수 있다. 각 명령은
현재 버전과 관계를 다시 검증한다. `SUPPORT_RESOLUTION_EXECUTE`를 가진 조회자는 기존 해결
응답이 이미 노출하는 주문 ID·상태·버전의 현재 값만 조회할 수 있으며 개인정보는 반환하지 않는다.
승인 요청의 실행 권한 재검사와 재배정도 S80 명령과 동일하게 `SUPPORT_RESOLUTION_EXECUTE`를
검사한다. 요청 권한 `SUPPORT_RESOLUTION_REQUEST`는 실행 권한을 대신하지 않는다.
화면은 금융 4단계와 고객 알림 상태를 구분한다.

### Planned resolution reassignment amendment (2026-09-12)

S60 실행자 재배정은 연결된 해결 건이 `PLANNED`인 경우 그 실행자도 같은 트랜잭션에서 변경한다.
잠금 순서는 기존 첫 실행과 동일한 Request → SupportCase → Resolution이며, 원 계획 작성자
`commandActorId` 및 승인 revision은 보존한다. 실행을 시작한 해결 건은 재배정할 수 없다.
현재 실행 권한 회수로 `REASSIGNMENT_REQUIRED`가 된 경우에도 동일한 계획으로 복구할 수 있다.

## Alternatives Considered

- 기존 customer endpoint impersonation: actor/audit/permission 오류로 기각.
- 모든 상태 direct cancel: lifecycle fact 손상으로 기각.
- 별도 지원용 Order 복제: owner divergence로 기각.
- S80 독립 approval: S60 exact revision과 승인 source가 중복돼 stale/one-time/separation 결과가 갈릴 수 있어 기각.
- `UNDETERMINED`에서 모든 고객 구제 보류: 내부 비용 귀속 지연을 고객 복구에 전파하므로 기각. 귀속 step만
  명시적으로 차단한다.

## Rationale

기존 cancellation/refund/settlement 불변식을 재사용하면서 사후 해결을 독립 추적한다.

## Consequences

Ordering/Fulfillment public commands와 resolution orchestration이 필요하다. ACCEPTED initial limits는
SP-19의 immutable versioned policy가 소유하고 실제 delegation data와 책임분쟁 패턴에 따라 새 policy
version으로만 변경한다. 기존 delegation의 expiry/budget은 소급 변경하지 않는다.

S70 implements the direct-change half through typed Support execution and store-authorization APIs. Support coordinates
the transaction but Ordering and Fulfillment public Application APIs own final state/version and slot/refund invariants.
The direct endpoint commits either an owner-confirmed `EXECUTED` outcome or an unchanged-order
`RESOLUTION_REQUIRED` handoff. S80 consumes the existing S60 lineage and owns the actual post-acceptance
ResolutionCase, partial owner outcomes and adjustment orchestration without mutating the Order fact.

## Verification

State matrix, ACCEPTED↔PREPARING race, exact confirmation binding, delegation expiry/use concurrency와 replay,
new-slot failure old-slot retained, cumulative refund, partial/unknown resolution tests.

Implementation evidence: V45 constraints, `SupportOrderChangeExecutionIntegrationTest`,
`OrderingSupportOrderChangeIntegrationTest`, `FulfillmentSupportPickupRescheduleIntegrationTest` and target/runtime
OpenAPI parity tests.

## Metrics

State별 decision/outcome, slot conflict, resolution partial/unknown duration.

## Revisit Conditions

매장 delegation 실제 데이터와 책임분쟁 패턴이 초기 assumptions를 벗어날 때.

## Related Decisions

ADR-029~040, ADR-048, ADR-061.
