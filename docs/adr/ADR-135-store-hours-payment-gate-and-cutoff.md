# ADR-135: Store 영업시간 결제 gate와 단조 cutoff

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

ADR-117의 weekly hours는 고객 표시용이고 `orderingAvailable=acceptingOrders && pickupEnabled`와 분리되어
있다. 신규 즉시 주문은 슬롯 시작시각이 없으므로 원래 영업 구간을 보존하는 별도 cutoff가 필요하다. 기존
PAID는 정확히 `paidAt+3분`의 수락 deadline과 2분 warning을 갖고 worker가 미수락 거절·보상을 수행한다.

## Decision

- `Asia/Seoul`의 기존 요일별 같은 날 단일 구간 `[opensAt, closesAt)`만 사용한다. complete schedule이
  없으면 `STORE_HOURS_NOT_CONFIGURED`, 휴무·개점 전·마감은 `STORE_CLOSED`, 수동 OFF 또는 pickup disabled는
  `STORE_NOT_ACCEPTING_ORDERS`로 신규 결제를 막는다. source 장애는 `DEPENDENCY_UNAVAILABLE`다.
- Merchant가 소유한 availability API가 순수 판정과 필요한 Store root lock 읽기를 제공한다. Ordering은
  Merchant repository를 직접 조회하지 않는다.
- Tx A, confirm 전, Tx C와 매장 수락에서 동일 owner policy를 다시 검증한다. PG 호출 동안 DB lock을
  보유하지 않는다. 마감 경합으로 승인된 거래는 ADR-134의 Tx D로 복구한다.
- `IMMEDIATE` Order는 초안 생성 시 `orderingWindowClosesAt`을 저장한다. 재개점·시간 연장·다음 날 callback은
  이 값을 늘리거나 지난 거래를 되살리지 않는다. 결과 조회·reconciliation·refund는 마감 후에도 허용한다.
- 미수락 PAID의 `acceptanceDeadlineAt=min(paidAt+3분, orderingWindowClosesAt)`이다. 두 경계가 같으면
  `STORE_CLOSED`가 우선한다. warning이 deadline 미만일 때만 저장한다. 수동 주문받기 OFF만으로 기존 PAID를
  즉시 일괄취소하지 않으며 ACCEPTED 이후는 영업 종료만으로 취소하지 않는다.
- 현재 영업일의 마감 단축·휴무/미설정 전환은 membership shared lock 다음 Store commerce exclusive lock을
  보유한 schedule transaction에서 cutoff/deadline을 단조 감소시킨다. schedule과 cutoff 중 하나만 commit하지
  않는다. 주소나 다음 요일 변경은 현재 주문을 바꾸지 않는다. 실제 거절·PG 복구는 commit 뒤 기존 worker가
  수행한다.
- `IMMEDIATE PENDING_PAYMENT` overdue와 PAID due를 기존 acceptance worker 계열에서 처리하고 startup에도
  persisted overdue를 claim한다. source-aware replay는 합법적 단축과 이미 끝난 주문을 구분한다.

## Alternatives Considered

- 프런트 또는 승인 전 검사만: API 우회와 마감 경합 승인이 남아 제외한다.
- PG 호출 중 Store lock: DB connection·writer 지연을 키워 제외한다.
- 새 마감 scheduler/queue: 기존 acceptance worker와 중복되어 제외한다.
- 주기적으로 현재 open만 조회: 잠깐 닫은 뒤 재개점하면 과거 거래가 부활할 수 있어 제외한다.
- 야간/24시간 특수값: 현재 schedule 불변식을 우회하므로 제외한다.

## Rationale

Merchant 원본을 한 곳에서 판정하고 원래 영업 구간을 Order에 단조롭게 보존하면 외부 PG와 DB의 원자성
부재를 복구 가능하게 만들면서 기존 미수락 보상 흐름을 재사용할 수 있다.

## Consequences

영업시간 미설정 매장은 신규 결제가 차단되어 점주 설정이 필요하다. schedule 편집은 표시 전용 쓰기보다
lock과 cutoff update 비용이 늘어난다. worker 주기와 실제 환불 완료는 영업 마감 시각과 같지 않으므로 지연을
관측해야 한다. 야간·브레이크타임·휴일은 계속 지원하지 않는다.

## Verification

- 개점/마감 직전·정각·직후, 미설정·휴무·invalid source와 주소/다음 요일 변경을 검증한다.
- 마감 단축과 승인/수락 경합, rollback, 시간 연장·재개점·다음 날 callback을 PostgreSQL에서 검증한다.
- 3분/마감 동시 경계의 `STORE_CLOSED`, 조건부 warning, startup overdue와 다중 worker single winner를 검증한다.
- ACCEPTED/PREPARING/READY 유지와 legacy timeout/publication replay를 검증한다.

## Metrics

closed 판정 뒤 신규 confirm 0, 마감 뒤 신규 수락 0, 마감 경합 승인과 복구 결과, deadline-to-rejection,
refund delay, Store/Order lock wait를 관측한다. 현재 측정값은 없다.

## Revisit Conditions

야간·복수 interval·휴일 override가 제품 요구가 되거나 동기 cutoff update의 lock cost가 측정된 병목이면
versioned admission 또는 schedule 모델 확장을 별도 결정한다.

## Related Decisions

- BR-01, BR-03, BR-06, BR-14, BR-33, BR-50, BR-52
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)은 3분과 owner별 보상을 유지하고 effective
  deadline과 `STORE_CLOSED`를 이 ADR로 보완한다.
- [ADR-117](ADR-117-store-customer-display-profile.md)은 display ownership과 schedule shape를 유지하되
  `IMMEDIATE` 결제 gate의 표시-only 범위를 이 ADR로 대체한다.
- [ADR-118](ADR-118-merchant-transactional-catalog-lifecycle.md),
  [ADR-123](ADR-123-order-quote-trade-terms-and-shared-availability.md),
  [ADR-133](ADR-133-immediate-checkout-and-store-preparation.md),
  [ADR-134](ADR-134-post-approval-benefit-use-and-recovery.md)
