# Fast Reorder Release Evidence

## Evidence identity

- **Recorded at:** 2026-08-09
- **Implementation baseline:** `c7370a8`
- **Code head before completion evidence:** `4017f01`
- **Post-completion review correction code head:** `4dc40fc`
- **Migration:** `V36__add_fast_reorder_snapshots_and_idempotency_retention.sql`
- **Decision:** [ADR-077](../adr/ADR-077-fast-reorder-order-creation-api-identity.md)
- **ExecPlan:** [Completed Fast Reorder Vertical Slice](../exec-plans/completed/fast-reorder-vertical-slice.md)
- **Runbook:** [Fast Reorder Idempotency and V36 Runbook](../operations/fast-reorder-runbook.md)

## Contract and runtime evidence

- `POST /api/v1/orders/{sourceOrderId}/reorders`는 인증된 `CUSTOMER`의 소유 source에만 허용하며
  없는 source는 404, 다른 고객 source는 403으로 구분한다. ownership 확인 전 line, 금액과 과거
  개인정보를 응답이나 일반 log에 노출하지 않는다.
- source에서는 `menuId`, ID 오름차순·중복 없는 option IDs와 quantity만 사용한다. 새 slot,
  optional coupon과 points는 request가 명시하고 과거 이름·가격·benefit·payment/refund·slot·settlement
  snapshot은 새 주문 계산에 쓰지 않는다.
- Merchant current quote의 현재 이름·가격·판매 가능성과 기존 Fulfillment, Inventory, Promotion,
  Loyalty owner reservation을 `OrderCreationWorkflow(MANDATORY)` 하나로 재사용한다. unavailable line
  하나라도 있거나 owner write가 실패하면 새 Order와 모든 reservation은 0건이다.
- 성공은 새 `PENDING_PAYMENT` 또는 benefit-only `PAID` Order의 201이며 required price comparison은
  source/current 혜택 전 subtotal, signed current-source difference와 changed line만 보존한다.
- target/runtime OpenAPI는 28 paths/30 operations로 일치하고 `RuntimeOpenApiParityTest`가 실제 Spring
  handler inventory와 양방향 exact parity를 검증한다.

## Schema, transaction and idempotency evidence

- V36은 기존 OrderLine을 `LEGACY_UNAVAILABLE/null`로 명시하고 future OrderLine을
  `SNAPSHOTTED`와 JSON option ID 배열로 저장한다. immutable DB function/CHECK가 각 원소의 canonical
  UUID, 중복 없음과 오름차순을 검증한다. 검증된 무옵션 `[]`과 identity를 알 수 없는 legacy row를
  구분하며 이름이나 current catalogue로 option ID를 추론하지 않는다.
- direct create와 reorder는 같은 Tx O workflow를 사용한다. 새 Order, pickup/stock/coupon/point,
  benefit-only Payment, settlement/point-accrual snapshot, Audit와 최초 201 idempotency response가 같은
  transaction에서 commit 또는 rollback한다.
- scope는 `(customerId, REORDER_ORDER_V1, Idempotency-Key)`이고 canonical hash는 source, slot,
  nullable coupon과 points를 모두 포함한다. same-key/same-payload는 최초 terminal status/body를 exact
  replay하고 다른 source 또는 payload는 owner work 전에 409다.
- terminal idempotency는 `completed_at + 90 days`까지 보존되고 PROCESSING/MANUAL_REVIEW는 자동
  삭제하지 않는다. threshold를 넘은 PROCESSING은 intended Order 존재만 확인한 뒤 response를
  재구성하거나 재주문을 자동 실행하지 않고 reason, review 시각과 intended Order 존재 여부를 기록해
  MANUAL_REVIEW로 격리한다. same-key 응답은 non-retry
  `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`이며 `Retry-After`가 없다.
- Merchant current batch quote는 store/menu/options/configurations/requirements를 각각 한 번씩 bulk
  조회해 small/large fixture 모두 5 statement다. `normalized_option_key`도 DB canonical CHECK로
  보호하며 adapter 방어 경계에서 malformed/non-canonical 값은 `503 DEPENDENCY_UNAVAILABLE`로
  terminal 저장하고 same-key exact replay한다.

## Validation result

- `./gradlew test --tests '*Reorder*' --tests '*OrderCreation*' --tests '*Reservation*'`: Passed,
  `BUILD SUCCESSFUL in 41s`, exit 0.
- `./gradlew test --tests '*RuntimeOpenApi*' --tests '*ModularityTests'`: Passed,
  `BUILD SUCCESSFUL in 5s`, exit 0.
- `./gradlew test --tests '*FastReorder*'`: Passed, `BUILD SUCCESSFUL in 1m`, exit 0.
- `./gradlew test --tests '*CreateOrder*' --tests '*BenefitOnlyOrderCreationTest' --tests
  '*OrderControllerContractTest'`: Passed, `BUILD SUCCESSFUL in 882ms`, exit 0. 결과 회수 session이
  종료돼 같은 명령을 재실행한 최종 결과다.
- `./gradlew test --tests '*OrderingIdempotencyRetention*' --tests '*ModularityTests' --tests
  '*RuntimeOpenApiParityTest'`: Passed, `BUILD SUCCESSFUL in 6s`, exit 0.
- 최초 `./gradlew clean test`: Failed, 561 tests 중 44 failures와 1 skipped, 6분 51초, exit 1.
  최초 19건은 V36 뒤 열 목록 없는 환불 fixture가 option provenance에 null을 넣은 회귀였고, 이후
  PostgreSQL connection timeout이 나머지 context에 연쇄됐다. fixture가
  `LEGACY_UNAVAILABLE/null`을 명시하도록 수정했다.
- `./gradlew test --tests '*PartialRefundAllocationRepositoryTest'`: Passed,
  `BUILD SUCCESSFUL in 21s`, exit 0. 서비스 테스트와 함께 재실행한 묶음도 24초에 통과했다.
- 수정 후 `./gradlew clean test`: Passed, 561 tests, failures/errors 0, skipped 1,
  `BUILD SUCCESSFUL in 7m 19s`, exit 0.
- 최초 `./gradlew clean build`: Failed, Spotless Kotlin 위반 16개와 병렬 compile snapshot 2차 오류로
  test 전 중단, 6초, exit 1. `./gradlew spotlessApply`는 558ms에 통과했다.
- 최종 `./gradlew clean build`: Passed, 561 tests, failures/errors 0, skipped 1, Spotless, compile,
  bootJar와 PostgreSQL Testcontainers 포함, `BUILD SUCCESSFUL in 7m 19s`, exit 0.
- `bash scripts/verify-docs.sh`: Passed, target/runtime 각각 28 paths/30 operations, 83 schemas,
  32 business policies, 77 ADRs, 160 Markdown files와 28 ExecPlans, exit 0.
- `git diff --check`: Passed, 출력 없음, exit 0.

### Post-completion review correction validation

- 최초 집중 테스트는 새 `ManualReviewRequired` 계약 부재로 test compilation 실패, exit 1이었다.
- 구현 후 `./gradlew test --tests '*FastReorderMigrationTest' --tests
  '*JpaMenuQuoteServiceQueryCountTest' --tests '*FastReorderServiceTest' --tests
  '*FastReorderIdempotencyReconciliationTest' --tests '*FastReorderControllerContractTest' --tests
  '*CreateOrderServiceTest'`: Passed, 39 tests, failures/errors/skipped 0,
  `BUILD SUCCESSFUL in 52s`, exit 0.
- 포맷과 canonical UUID boundary 보강 후 위 집중 대상에 `*RuntimeOpenApiParityTest`를 추가해
  재실행: Passed, 40 tests, failures/errors/skipped 0, `BUILD SUCCESSFUL in 46s`, exit 0.
- `bash scripts/verify-docs.sh`: Passed, target/runtime 각각 28 paths/30 operations와 83 schemas,
  exit 0. Markdown/ADR/ExecPlan inventory 숫자는 이 branch 범위 밖의 동시 worktree 문서를 포함해
  correction release inventory 근거로 사용하지 않는다.
- `git diff --check`: Passed, 출력 없음, exit 0.
- 실제 Tx I2 fault injection은 `FAILED` transition DB trigger가 Tx I2 commit만 실패시키며, 최초 503,
  Tx O의 Order·pickup/stock reservation·Audit rollback, PROCESSING 잔류와 worker의 자동 비실행을
  검증한다. intended Order 존재/부재 fixture 모두 metadata를 검증한다.

## Measurements and limits

- 검증 환경은 macOS arm64, Java/Gradle wrapper 9.6.1과 Docker Testcontainers의 PostgreSQL이다.
  전체 suite는 120 test suites, 561 tests였고 1개 benchmark 성격의 기존 test가 skipped 상태다.
- 측정한 계약 inventory는 target/runtime 각각 28 paths/30 operations다. 테스트 시간은 위 명령의
  Gradle wall time이며 production latency나 throughput이 아니다.
- 실제 non-local migration/deployment, external Provider 장애 주입, production traffic과
  p50/p95/p99, source age, lock wait, item-unavailable 비율, retention throughput은 측정하지 않았다.
  따라서 성능 개선, SLA 또는 배포 성공을 주장하지 않는다.
- 최초 non-local migration 전 terminal idempotency corruption precheck와 V36 forward-only 적용을
  환경별로 확인한다. runtime traffic이 생기면 bounded metric으로 latency, stuck PROCESSING,
  MANUAL_REVIEW, owner 503와 retention backlog의 기준선을 먼저 수집한다.
