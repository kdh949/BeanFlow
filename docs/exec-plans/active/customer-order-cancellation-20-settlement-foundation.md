# Settlement foundation과 고객 취소 제외 증적을 만든다

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

`COMPLETED` 주문만 정산 원천이 되고 매장 수락 전 고객 취소 환불은 정상적으로
정산되지 않는다는 BR-16/ADR-048을 실제 Settlement owner 모델과 멱등 consumer로
보호한다. 고객 취소 구현이 존재하지 않는 Settlement를 호출하거나 no-op으로 성공하지
않게 하는 선행 계획이다.

## Current State

- ADR-008/017/048은 SettlementItem/Batch/Adjustment와 고객 취소 `NOT_APPLICABLE`
  Audit을 Accepted로 정의한다.
- OpenAPI에는 Settlement 조회 계약이 있다.
- `src/main/kotlin`과 migration에는 Settlement package/table/consumer가 없다.
- 현재 `OrderCompletedV1`, `PaymentRefunded` 계약은 문서에 있으나 Settlement 소비 구현이 없다.

## Definitions

- **SettlementItem:** 완료 Order 한 건의 정산 입력 원장.
- **SettlementAdjustment:** 확정 후 변경을 덮어쓰지 않는 append-only 조정.
- **NOT_APPLICABLE:** 정상 미완료 고객 취소라 Settlement 원장을 만들지 않았음을 source별
  Audit으로 증명한 consumer 결과.

## Scope

### In Scope

- Settlement Context/module과 최소 Aggregate/Repository 경계
- `OrderCompletedV1` 기반 SettlementItem 생성의 source unique
- 고객 취소 Refund fact의 엄격한 `NOT_APPLICABLE` 판정과 target Audit
- 중복 event, 재시작, source 불일치와 missing dependency 실패
- 운영 조회와 runbook에 필요한 최소 상태
- ADR-062의 store+batch 범위 SettlementItem cursor 조회와 `itemId` discovery

### Non-goals

- 실제 매장 계좌 지급, 세금·회계 전표와 채권 관리
- 수수료율이나 비용 부담 정책 재선택
- 고객 취소 command
- 정상 조건을 0원 Adjustment나 no-op으로 기록

## Business Rules and Invariants

- SettlementItem은 `COMPLETED` Order source당 최대 하나다.
- 미완료 고객 취소에는 SettlementItem/Adjustment를 만들지 않는다.
- `CUSTOMER_REQUEST` cause, 고객 취소 Refund `SUCCEEDED`, source 일치와 Item 부재를
  모두 확인한 경우만 `NOT_APPLICABLE`이다.
- 판정 증거는 append-only AuditRecord source unique로 남는다.
- 조건 불일치나 필수 데이터 누락은 성공 완료가 아니다.

## Architecture and Transaction Boundaries

- `OrderCompletedV1` consumer transaction은 필요한 snapshot을 검증하고 SettlementItem과
  Audit을 함께 commit한다.
- 고객 취소 Refund consumer transaction은 Order/Refund source를 읽고 Item 부재를
  확인한 뒤 NOT_APPLICABLE Audit과 publication completion을 연결한다.
- 다른 Aggregate는 ID와 공개 Query/Application API로 참조하고 JPA 관계를 추가하지 않는다.
- Batch Item 목록은 별도 Query Repository/DTO projection으로 읽고 Batch 쓰기 Aggregate에
  Items 객체 연관관계를 추가하지 않는다.

## Alternatives Considered

- 고객 취소 consumer만 no-op 구현: 정상 제외 증거와 향후 정산 불변식이 없어 제외한다.
- 0원 SettlementAdjustment 생성: 정산된 적 없는 거래를 정산 원장에 넣어 제외한다.
- Settlement를 고객 취소 범위에서 제거: Accepted ADR-048/060 변경이므로 별도 사용자
  결정 없이 선택하지 않는다.

## Failure Semantics

- Order/Refund/source 조회 실패는 publication retry 또는 명시적 ReprocessingCase다.
- Audit 저장 실패는 consumer 성공이 아니다.
- source mismatch나 예상치 못한 기존 Item은 `MANUAL_REVIEW` 대상이며 덮어쓰지 않는다.

## Data and Migration

SettlementItem/Batch/Adjustment와 source unique, 금액 CHECK, Audit lookup index의
forward migration을 만든다. 기존 환경 적용 전략은 00 plan 결과를 따른다.

## API and Event Contracts

- `OrderCompletedV1`은 SettlementItem 생성 원천이다.
- `PaymentApproved`만으로 Item을 만들지 않는다.
- 일반 `PaymentRefunded`에서 customer-cancellation source를 식별해 ADR-048 분기로
  처리한다. 고객 알림 event를 정산 근거로 사용하지 않는다.
- `GET /stores/{storeId}/settlements/{settlementBatchId}/items`는
  `(completedAt ASC, settlementItemId ASC)` cursor page로 이의제기용 Item ID와 금액
  snapshot을 제공한다.

## Milestones

1. Settlement module과 Aggregate/DB 불변식을 만든다.
2. OrderCompleted consumer와 Item source unique를 구현한다.
3. 최소 조회 projection을 OpenAPI와 일치시킨다.
4. 고객 취소 Refund NOT_APPLICABLE consumer와 target Audit을 구현한다.
5. 중복·재시작·불일치 failure recovery를 검증한다.

## Required Tests

- Completed 주문 Item 단일 생성과 non-completed 거부
- customer cancellation Refund의 Item/Adjustment 0건과 Audit 1건
- cause/refund/source mismatch의 비완료 처리
- 기존 Item 존재 시 비덮어쓰기
- duplicate publication과 restart
- Batch별 Item empty/single/multi-page와 동률 cursor 경계
- store membership, Batch-store mismatch와 다른 Batch cursor 거부
- 조회한 `settlementItemId`와 dispute path contract 연결
- PostgreSQL constraint, Modulith 경계와 API contract

## Validation Commands

```bash
./gradlew test --tests '*Settlement*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

item creation, NOT_APPLICABLE, mismatch, retry와 manual review를 닫힌 outcome으로
측정한다. store/order/refund ID는 metric tag에 넣지 않는다.

## Documentation Updates

ADR-008/017/048 구현 evidence, context map, aggregate invariants, event catalog,
transaction boundaries, Settlement runbook과 quality evidence를 갱신한다.

## Progress

- [ ] Settlement module/schema
- [ ] OrderCompleted item consumer
- [ ] query contract
- [ ] customer cancellation exclusion consumer
- [ ] recovery/observability
- [ ] 전체 검증

## Surprises & Discoveries

- Accepted 정산 정책과 OpenAPI가 있지만 구현 package와 table은 하나도 없다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | 미수락 고객 취소는 정산 원장 없이 NOT_APPLICABLE Audit | 미완료 거래의 수익·조정 위장 방지 | BR-16, ADR-048 |
| 2026-08-01 | Accepted | 점주는 Batch별 cursor Item 목록에서 dispute용 itemId를 얻음 | 대량 Batch 응답을 피하면서 조회→이의제기 흐름 완결 | ADR-062 |

## Outcomes & Retrospective

미구현 상태다. 완료 또는 승인된 범위 amendment 없이는 고객 취소를 release하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-01: Batch별 SettlementItem 조회와 이의제기 식별 경로를 추가.
