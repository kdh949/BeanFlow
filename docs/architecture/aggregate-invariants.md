# Aggregate Responsibilities and Invariants

| Aggregate Root | Responsibility | Core invariants | Other aggregate references |
|---|---|---|---|
| Store | 영업·픽업 가능 상태 | 폐점·휴점 매장은 새 주문 불가 | IDs |
| StoreDiscoveryProfile | 검색 가능한 공개 매장명·위치 | Store당 하나, non-blank name, SRID 4326 point, verified owner source 없이는 생성 금지 | `storeId` |
| StoreSettlementTerms | store별 versioned 수수료 계약 | applicable version 하나, fee rate `0..10000`, immutable history와 overlap 금지 | `storeId` |
| Menu | 메뉴·옵션·가격·판매 상태 | 음수 가격 금지, 유효 옵션만 선택 | `storeId` |
| MenuConfiguration | 주문 가능한 메뉴·옵션 구성과 재고 요구량 | 정규화한 option ID 집합은 메뉴 안에서 유일하고 sellable unit별 필요 수량은 양수 | `menuId`, `sellableUnitId` |
| Order | 항목 스냅샷, 금액과 상태 | 결제 시작 후 항목·금액 불변, 새 line은 정규화 option ID snapshot 필수, settlement input snapshot exactly one/tie-out, 허용 전이만 가능, `CANCELLED`는 취소 시각·원인 필수이고 그 외 상태에서는 취소 필드 부재 | IDs |
| PickupSlot | 시간 구간과 수용량 | 예약+확정 수량 ≤ capacity | `storeId` |
| PickupReservation | 주문의 슬롯 점유 | 주문당 활성 예약 하나, 만료 후 확정 불가, 종료 복원 state·trigger·source 일치 | `orderId`, `slotId` |
| SellableStock | 판매 단위 수량 | 가용·예약·확정 수량 음수 금지 | `storeId`, `menuOptionId` |
| StockReservation | 주문별 재고 점유 | 주문·SKU별 중복 활성 예약 금지, 종료 복원 state·trigger·source 일치 | IDs |
| Campaign | 대상 품목 기반 정액·정률 할인 정책·수량·부담 | type별 금액 필드, rate `1..10000`, minimum/maximum, 대상 목록과 burden share 합 10000 유효 | `storeId`, menu IDs |
| CouponIssuance | 발급 쿠폰 생명주기 | 동시에 두 주문에 사용 불가, 보상 issuance의 original/source/trigger/policy와 immutable terms 일치 | `memberId`, `campaignId`, `orderId` |
| CouponReservation | 주문 쿠폰 할인·비용 부담 leg | final discount=platform+store burden legs, reservation terms immutable | `orderId`, `couponIssuanceId` |
| PointAccount | 프로그램별 잔액 요약 | 가용 잔액 음수 금지 | IDs |
| PointLot | 발급분 available/reserved 잔액·만료·발급 비용 snapshot | 생성 시 만료 Lot 예약 금지, 가용·예약·잔여액 음수 금지, issuer type/reference 불변 | IDs |
| PointReservation | 주문별 PointLot allocation과 lease | 주문당 active 예약 하나, allocation 합계=예약 총액, 예약 시 유효한 allocation은 주문 lease까지 확정 가능, 복원 source/trigger/policy 일치 | `orderId`, `pointAccountId`, `pointLotId` |
| PointRecoveryPending | 환불 적립 포인트의 미회수 잔액과 후속 적립 상계 | refund source당 account별 하나, `PENDING` remaining 양수, `SETTLED` remaining 0, account summary와 tie-out | `pointAccountId`, refund source ID |
| PointAdjustmentCommandIdempotency | 감사형 포인트 조정의 terminal response 재생 | actor/operation/key unique, account/hash match일 때만 201 replay, 90일 retention | `actorId`, `pointAccountId` |
| Payment | 승인·불명·환불과 immutable Provider request snapshot | 동일 키 중복 승인 금지, 누적 환불 ≤ 승인액, external Payment당 snapshot 하나이며 Provider 입력은 생성 뒤 update/delete 금지 | `orderId`, `paymentMethodId` |
| Refund | 외부 환불 요청·조회·결과 | source/key 불변, request≤3, lookup≤5, Unknown 뒤 REQUEST 금지, 성공액은 요청액과 일치 | `paymentId`, `orderId` |
| PaymentMethod | PG token lifecycle·표시 정보·고객 default 선호 | 원본 카드번호·CVC·전체 유효기간 금지, owner/provider/token exact binding, customer별 ACTIVE default 0..1, deactivation 상태는 신규 결제 금지, 재활성화·hard delete 금지 | `memberId` |
| IdempotencyRecord | 명령 중복 실행 방지와 응답 재사용 | 같은 scope·key에 payload hash 하나, 처리 중/UNKNOWN은 정리 금지, terminal response는 만료 시각까지 보존 | `actorId`, target ID |
| SettlementItem | 완료 주문 단위의 불변 정산 명세 | Order/source당 하나, KRW 금액·수수료·혜택·순정산 tie-out, 서울 완료일 일치, OPEN Batch에만 귀속, update/delete 금지 | `orderId`, `settlementBatchId`, `storeId` |
| SettlementBatch | 서울 완료일·매장별 Item 귀속, 집계·확정 | store/date당 하나, Item은 OPEN Batch에만 귀속, `OPEN → CALCULATED → CONFIRMED`, summary·이월 tie-out, 확정 후 직접 수정 금지 | `storeId`, 이전 confirmed Batch ID |
| SettlementAdjustment | 확정 후 보정 | confirmed Item/Batch만 대상, signed KRW, source/reason unique, update/delete 금지, 미완료 고객 취소 환불 제외 증적으로 사용 금지 | Item/Batch/store IDs |
| SettlementDispute | Dispute Context의 이의제기 Workflow와 held 예상액 | confirmed Item만 대상, Item당 진행 중 하나, actor command key terminal replay, 재이의는 immediate previous ID·새 증빙·1회 제한, Adjustment commit 전 terminal success 금지 | Settlement Item/Adjustment/previous Dispute ID |
| NotificationDelivery | 발송·재시도 | logical source+recipient+channel 중복 금지, Provider attempt 상한 | IDs |
| ReprocessingCase | 운영 재처리 | 대상·사유·주체 필수, 중복 실행 방지 | IDs |
| RepairProposal | 금융 setup 복구의 2인 승인 | case당 active 하나, proposer≠decider, 30분 만료, terminal 재개 금지 | case/order/payment IDs |
| AcceptanceTimeoutWork | 관측된 PAID deadline winner의 내구 실행 | order+deadline source unique, claim lease, nonterminal 자동 정리 금지 | `orderId` |
| OrderCompensationCase | 주문 종료 후 owner 보상 추적 | order당 하나, trigger 필수, 여섯 step과 두 benefit policy snapshot | `orderId`, event/source IDs |
| AuditRecord | 중요 변경의 target별 감사 | category/class/immutable policy version snapshot, financial 5년·PII access 2년, append-only, action/target/source 중복 금지, 필수 주체·사유·correlation, PII 원문 금지 | target IDs |
| OperatorPermissionGrant | privileged operator permission source | actor/permission unique, ACTIVE/REVOKED lifecycle, role/JWT fallback 금지 | `actorId` |
| RetentionPolicyVersion/Head | 목적별 보존 규칙과 current pointer | version 수정·삭제 금지, category/class/duration 일치, Audit append가 head lock과 exact version을 snapshot | actor/evidence IDs |
| BenefitRestorationPolicyVersion | 종료 원인·혜택별 만료 복원 규칙 이력 | version ID 전역 유일, row 수정·삭제 금지, trigger/type/mode 유효 | actor ID |
| CompensationCouponTermsSnapshot | 종료 Campaign과 독립적인 보상 쿠폰 조건·비용 부담 | 원 issuance snapshot 복사, share 합 10000, 발급 후 불변 | CouponIssuance ID |
| OrderCompensationBenefitPolicySnapshot | Case가 확정한 혜택별 정책 참조 | Case당 COUPON·POINTS 각 하나, immutable version FK | `caseId`, `policyVersionId` |

`Analytics Read Model`은 쓰기 Aggregate가 아니며 원본 Context의 event ID와 payload version으로
멱등하게 갱신하는 projection이다. MVP Discovery Query Model도 쓰기 Aggregate가 아니지만 영속
복제본을 두지 않고 Merchant `StoreDiscoveryProfile`의 public DTO projection을 요청 범위에서만 쓴다.

## Aggregate size rules

- `OrderLine`은 Order 내부 Entity이며 별도 Repository를 만들지 않는다.
- `Fast Reorder`는 명령이고 별도 Aggregate나 Repository가 아니다.
- `SettlementBatch`가 모든 Item을 JPA 컬렉션으로 소유하지 않는다.
- `PointAccount`가 모든 PointLot을 컬렉션으로 로딩하지 않는다.
- `PointReservation`은 필요한 allocation만 소유하며 PointLot은 ID로 참조한다.
- 대량 원장은 필요한 행만 쿼리·잠금하며 Account 또는 Batch 요약과 같은 트랜잭션에서 검증한다.
- Aggregate를 JPA 객체 그래프와 동일시하지 않는다.

## Database reinforcement candidates

| Owner / Repository candidate | Aggregate Root | DB reinforcement candidate | Concurrency control |
|---|---|---|---|
| `StoreRepository` | Store | store identity, valid status check | optimistic version |
| `StoreSettlementTermsRepository` | StoreSettlementTerms | immutable version/source, fee `0..10000`, store interval overlap 금지 | store advisory lock + applicable interval query |
| `MenuRepository` | Menu | non-negative integer KRW price, unique store/menu code | optimistic version |
| `MenuConfigurationRepository` | MenuConfiguration | unique menu/normalized-option-set, positive sellable requirement | optimistic version |
| `OrderRepository` | Order | order number unique, non-negative totals, cancellation timestamp/cause/reason-code/detail과 state 조합 CHECK, OrderLine option snapshot state와 nullable JSON 조합 CHECK | optimistic version + guarded transition |
| `OrderSettlementInputSnapshotRepository` | OrderSettlementInputSnapshot | order당 exactly one, owner source FK, fee/coupon/point/benefit/net 공식·hash tie-out, update/delete 금지 | Order 생성 local transaction + order unique FK |
| `PickupSlotRepository` | PickupSlot | unique store/time range, non-negative capacity | conditional update or row lock |
| `PickupReservationRepository` | PickupReservation | active order reservation unique, 종료 복원 state/trigger/source CHECK | unique/partial index + row lock |
| `SellableStockRepository` | SellableStock | unique store/sellable unit, non-negative quantities | conditional update or row lock |
| `StockReservationRepository` | StockReservation | active order/SKU unique, 종료 복원 state/trigger/source CHECK | unique/partial index + row lock |
| `CampaignRepository` | Campaign | valid period/type/value/minimum/maximum/target/share ratio | optimistic version |
| `CouponReservationRepository` | CouponReservation | final discount=platform+store legs, burden source/version/share complete, row immutable | issuance lock + order/source unique |
| `CouponIssuanceRepository` | CouponIssuance | one active reservation/use per issuance, compensation source unique, restoration metadata와 terms snapshot CHECK | unique/partial index + guarded transition |
| `PointAccountRepository` | PointAccount | unique member/program, non-negative available balance | row lock/version |
| `PointLotRepository` | PointLot | non-negative available/reserved/remaining, amount tie-out, issuer type/reference NOT NULL·immutable; V14 non-empty legacy rows require one exact verified precheck mapping | ordered row lock |
| `PointReservationRepository` | PointReservation | active order reservation unique, allocation source unique | unique + guarded transition |
| `PointTransactionRepository` | PointTransaction | logical source unique, restoration type의 trigger/policy 필수, `RECOVERY`는 refund·Lot source와 deferred pending ID 일치, `ADJUSTMENT`는 CREDIT/DEBIT effect·command/Lot child source 관계와 target Audit source 필수 | unique source reference |
| `PointRecoveryPendingRepository` | PointRecoveryPending | account+refund source unique, initial/remaining 양수 범위와 PENDING/SETTLED state·timestamp CHECK, Account pending summary tie-out | PointAccount row lock + oldest pending row lock |
| `PaymentRepository` | Payment | provider transaction key and order/payment intent unique | unique + guarded transition |
| `RefundRepository` | Refund | source/provider key unique, reason/state check, request·lookup·total attempt tie-out | Payment row lock + claim lease + guarded transition |
| `PaymentCancellationRecoveryRepository` | PaymentCancellationRecoverySnapshot | order/payment당 하나, approved = prior succeeded + cancellation requested, requested 양수일 때 Refund 필수 | unique/FK/check + Payment row lock |
| `PaymentMethodRepository` | PaymentMethod | customer/provider/token reference unique, TOSS provider reference 필수, ACTIVE default customer당 0..1, lifecycle CHECK | customer advisory + token fingerprint advisory + row lock + unique/check |
| `PaymentMethodRegistrationRepository` | registration command/work | actor/operation/key와 customer/provider/authKey hash unique, raw authKey 부재, claim/result/retention 상태 tie-out | insert-first unique + durable single claim + startup unknown recovery + guarded transition |
| `PaymentMethodDeactivationRepository` | deactivation command/work | actor/operation/key unique, PaymentMethod당 active work 하나, DELETE claim 뒤 재호출 금지, unknown deadline 96시간 | PaymentMethod row lock + unique + guarded transition |
| `PaymentMethodDefaultCommandRepository` | default terminal response | actor/operation/key unique, same target/hash만 200 replay, terminal 90일 | customer advisory + deterministic row lock + unique |
| `ProviderNotificationInboxRepository` | 검증된 provider-neutral 폐기 알림 | provider/notification ID unique, token mapping 0/1/many 명시, mapped deactivation 원장 204 수렴, terminal 90일·manual 해소 전 보존 | notification/token advisory + deactivation/PaymentMethod row lock |
| `IdempotencyRecordRepository` | IdempotencyRecord | actor/operation/key unique, terminal `retention_expires_at` 필수와 non-terminal null CHECK | insert-first unique arbitration + terminal keyset retention worker |
| `PointAdjustmentCommandIdempotencyRepository` | PointAdjustmentCommandIdempotency | actor/operation/key unique, account/hash match에만 201 replay, terminal response 90일 retention | PointAccount lock + unique-conflict rollback/re-read + keyset retention worker |
| `SettlementItemRepository` | SettlementItem | order/source unique, 금액 공식과 서울 완료일, update/delete 금지, Batch store/date/OPEN 일치 | unique + CHECK + FK + insert/mutation trigger |
| `SettlementBatchRepository` | SettlementBatch | store/date unique, summary/carry/시각 all-or-none, Item은 OPEN guard, confirmed mutation 금지 | unique + row lock + DB transition/mutation trigger |
| `SettlementAdjustmentRepository` | SettlementAdjustment | confirmed target, source/reason unique, signed amount, append-only | unique/check/FK + immutable trigger |
| `SettlementDisputeRepository` (Dispute Context) | SettlementDispute | active Item 하나, actor/operation/key unique, terminal response, immediate previous와 새 evidence, refile count ≤ 1 | partial unique + advisory/row lock + guarded trigger |
| `NotificationDeliveryRepository` | NotificationDelivery | logical source/recipient/channel unique | unique + guarded attempt count |
| `ReprocessingCaseRepository` | ReprocessingCase | open case type/target unique where required | partial unique |
| `RepairProposalRepository` | RepairProposal | case당 active proposal 하나, actor 분리, guarded terminal transition | partial unique + row lock |
| `AcceptanceTimeoutWorkRepository` | AcceptanceTimeoutWork | order/deadline source unique, claim lease와 terminal retention guard | unique + skip-locked claim |
| `OrderCompensationCaseRepository` | OrderCompensationCase | order unique, trigger/step CHECK, case+step unique | unique + guarded step transition |
| `AuditRecordRepository` | AuditRecord | action/category mapping, immutable policy version FK, action/target/source unique, class/expiry/id index | append-only permissions + retention worker role |
| `OperatorPermissionGrantRepository` | OperatorPermissionGrant | actor/permission unique, active/revoked state and audit source | same transaction grant lock + guarded revoke |
| `RetentionPolicyVersionRepository` | RetentionPolicyVersion/Head | immutable version, category head PK, category/class/version composite FK | append head read lock + future audited activation lock |
| `BenefitRestorationPolicyRepository` | BenefitRestorationPolicyVersion/Head | global version PK, trigger+benefit head PK, append-only version | COUPON→POINTS head row lock + CAS |
| `OrderCompensationBenefitPolicySnapshotRepository` | OrderCompensationBenefitPolicySnapshot | case+benefit unique, policy version FK, Case당 두 row | Case 생성 transaction |

누적 환불액, PointLot 합계와 Settlement 합계처럼 여러 행의 합에 의존하는 불변식은
애플리케이션 사전 조회만으로 보호하지 않는다. owner summary row lock/version,
source-reference Unique Constraint와 같은 트랜잭션 최종 방어를 함께 사용한다.

## Support additions

- S20 SupportCase는 Aggregate transition matrix, current assignee, terminal `CLOSED`, version과 append-only
  assignment/state history를 보호한다. interaction/note는 Case collection이 아닌 별도 bounded record다.
- S40 VerificationSession은 actor+Case+SubjectLink+Subject+Purpose+action scope에, DataAccessGrant는
  requester+Case+SubjectLink+Subject+Purpose+closed field set+reason+expiry+budget에 묶인다. BreakGlassRequest는
  one emergency field와 requester/pre-approver/post-reviewer separation을 보호한다.
- ActionRequest revision과 ApprovalStep은 exact payload/policy/verification/aggregate version을 snapshot하며 actor separation을 DB와 service 양쪽에서 지킨다.
- CompensationRequest는 immutable policy와 cost responsibility, one-benefit execution, duplicate/rolling-limit key를 보호한다.
- PostAcceptanceResolutionCase는 trigger Order fact를 변경하지 않고 partial/unknown resolution을 별도 상태로 유지한다.
- DeliveryFulfillment는 canonical monotonic lifecycle과 Provider sync를 분리한다.
- LegalHold는 scoped/reviewed/expiring이며 deletion component success를 조작하지 않는다.

세부 책임과 후보 제약은 [Support aggregate invariants](support-aggregate-invariants.md)를 따른다.

주문 요청의 `optionIds`는 중복을 거부한 뒤 ID 오름차순으로 정규화하여
MenuConfiguration을 조회한다. 요청의 OrderLine 순서는 금액 배분 계약이므로
정규화하지 않는다. 여러 line이 같은 sellable unit을 요구하면 Ordering은
`quantityPerLineUnit * lineQuantity`를 overflow 없이 합산한 뒤 Inventory에 한 번
예약 요청한다.

새 OrderLine은 정규화된 option ID 배열을 이름 snapshot과 별도로 저장한다. 빈 배열은
검증된 무옵션 선택이고, legacy migration state는 검증된 ID snapshot이 없다는 뜻이므로 서로
구분한다. legacy line의 옵션명이나 sellable requirement를 option ID로 역추론하지 않으며 해당
line의 빠른 재주문은 `SOURCE_OPTION_SELECTION_UNAVAILABLE`로 실패한다.
