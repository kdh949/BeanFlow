# ADR-032: 고객 취소 명령의 멱등성 모델과 멱등 레코드 수명

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amended by:** ADR-064의 위험 기반 사전등록/명령 트랜잭션 선택 기준

## Context

`openapi/beanflow-v1.yaml`의 `cancelOrder`는 `Idempotency-Key`를 필수 header로
계약했고, `api-conventions.md`의 Idempotency 절은 주문 취소를 멱등 명령으로 열거하며,
BR-14 Required Tests에는 "중복 취소 멱등성"이 있다. 그러나 저장소에는 성격이 다른 두
멱등성 모델이 이미 존재하고 취소가 어느 쪽을 따르는지는 정해지지 않았다.

- **사전등록 모델(ADR-025):** Tx I1이 `PROCESSING` 레코드를 먼저 커밋해 동시 요청의
  승자를 정하고, Tx O가 성공 응답을, Tx I2가 확정 실패 응답을 저장한다. 처리 중
  재요청은 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`이고 멈춘 `PROCESSING`은
  reconciliation과 `MANUAL_REVIEW`로 종결한다. 테이블은
  `ordering_idempotency_record`이며 `intended_order_id`가 `NOT NULL`인 주문 생성 전용
  구조다.
- **명령 트랜잭션 모델(V7, `StoreOrderTransitionService`):** 단일 로컬 트랜잭션이
  Order row를 잠그고 멱등 레코드를 조회한 뒤, 없으면 전이를 실행하고 최초 응답을 같은
  트랜잭션에서 저장한다. `PROCESSING` 상태가 없고 `response_status`는 `NOT NULL`이며
  롤백된 요청은 레코드를 남기지 않는다.

취소 명령은 대상 Order가 이미 존재하고, 외부 Provider 호출이 트랜잭션 안에 없으며,
BR-14 Contention Amendment에 따라 Order row lock 위의 guarded transition으로 경쟁한다.
따라서 사전등록 모델이 방어하는 위험 — 중복 Aggregate 생성, 외부 호출 중 장기
`PROCESSING`, 사전 arbitration 부재 — 이 취소에는 구조적으로 존재하지 않는다.

## Scope of this ADR

이 ADR은 고객 취소 명령의 **멱등성 모델, 멱등 scope와 canonical payload, 재요청·키
재사용 응답, 멱등 레코드의 저장 구조와 보존** 네 가지만 소유한다. 취소 트랜잭션이
어떤 Aggregate와 부수효과를 포함하는지, 이벤트와 보상 Case 계약, 외부 환불 재시도는
후속 ADR이 소유하며 여기에 추가하지 않는다. 이 ADR은 멱등 레코드가 그 명령
트랜잭션과 **같은 로컬 트랜잭션에서 commit된다는 것**만 정한다.

## Decision

### 멱등성 모델

- 고객 취소는 명령 트랜잭션 모델을 사용한다. 하나의 로컬 트랜잭션이 Order row를 잠근
  뒤 멱등 레코드를 조회하고, 없으면 취소를 실행한 다음 최초 응답을 같은 트랜잭션에서
  저장한다.
- `PROCESSING` 상태, 사전등록 트랜잭션, stuck 레코드 reconciliation worker와
  `MANUAL_REVIEW`를 도입하지 않는다. 취소 트랜잭션에는 외부 호출이 없어 결과가 불명인
  구간이 존재하지 않는다.
- 동시 같은 key 요청은 Order row lock으로 직렬화된다. 대기한 요청은 잠금을 얻은 뒤
  커밋된 레코드를 발견해 재생한다. 따라서 취소는
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용하지 않는다.
- Order row lock 획득이 요청 timeout을 넘기면 성공이나 부분 결과로 표현하지 않고
  `503 DEPENDENCY_UNAVAILABLE`을 반환한다.

### 멱등 scope와 canonical payload

- scope는 BR-25 그대로 `actorId + operation + Idempotency-Key`다. `operation` 값은
  `CUSTOMER_ORDER_CANCELLATION`이며 매장 명령의 `STORE_ORDER_TRANSITION`과 겹치지
  않는다.
- canonical payload는 `orderId`, `reasonCode`, 정규화한 `detail` 세 값을 JSON object
  key 오름차순으로 직렬화한 뒤 SHA-256으로 해싱한다. `detail`은 BR-14 Cancellation
  Reason Amendment의 정규화(`trim`, 빈 문자열은 부재)를 적용한 **뒤** 해싱하며, 부재는
  `null`로 materialize한다.
- **path의 `orderId`를 canonical payload에 포함한다.** 같은 actor가 같은 key를 다른
  주문에 재사용하면 payload hash가 달라 `409 IDEMPOTENCY_KEY_REUSED`로 거부되고, 첫
  주문의 응답이 다른 주문의 결과로 재생되지 않는다.
- 레코드 조회는 scope 세 값으로 하고, hash 비교는 Order 상태 전이나 자원 변경 이전에
  수행한다.

### 재요청 응답

| 상황 | 응답 |
|---|---|
| 같은 key, 같은 hash | 저장된 최초 status(`200` 또는 `202`)와 body를 그대로 반환. 새 부수효과 없음 |
| 같은 key, 다른 hash (`reasonCode`·`detail`·`orderId` 중 하나라도 다름) | `409 IDEMPOTENCY_KEY_REUSED`. Order와 자원을 변경하지 않음 |
| 다른 key, 이미 취소된 같은 주문 | `409 ORDER_STATE_CONFLICT`. BR-14 Contention Amendment가 소유 |
| 커밋 시 `(actor_id, operation, idempotency_key)` unique 위반 | `409 IDEMPOTENCY_KEY_REUSED`로 번역한다. `500`으로 노출하지 않는다 |
| 롤백된 요청의 같은 key 재시도 | 레코드가 없으므로 재실행한다. 중복 취소는 Order 상태 guard가 막는다 |

- 재생 응답은 최초 body를 **그대로** 반환한다. ADR-031이 확정한 `Cancellation` 필드
  집합을 바꾸지 않기 위해 `replayed` 같은 표시 필드를 추가하지 않는다. ADR-057은
  매장 명령에서도 이 필드를 제거해 terminal 명령 응답을 통일했다.
- 확정 실패(4xx)를 멱등 레코드에 저장하지 않는다. 취소는 새 Aggregate를 만들지 않고
  대상 Order가 이미 존재하므로 재실행이 상태 guard에 의해 결정적이며, 실패를 고정하면
  일시적 `503` 이후 정상 재시도를 막는다. 이 점이 ADR-025와 의도적으로 다르다.

### 저장 구조와 보존

- 신규 테이블 `ordering_cancellation_command_idempotency`를 `V13`에서 만든다. 기존
  `ordering_store_command_idempotency`를 재사용하거나 rename하지 않는다. 매장 명령
  자산 변경은 이번 범위 밖이다.

  | 컬럼 | 제약 |
  |---|---|
  | `id` | `uuid PRIMARY KEY` |
  | `actor_id` | `uuid NOT NULL` |
  | `order_id` | `uuid NOT NULL REFERENCES ordering_order(id)` |
  | `operation` | `varchar(80) NOT NULL` |
  | `idempotency_key` | `varchar(128) NOT NULL`, 길이 8~128 CHECK |
  | `payload_hash` | `varchar(64) NOT NULL`, 길이 64 CHECK |
  | `response_status` | `integer NOT NULL CHECK (response_status IN (200, 202))` |
  | `response_body` | `text NOT NULL` |
  | `response_version` | `integer NOT NULL` |
  | `created_at` | `timestamptz NOT NULL` |
  | `retention_expires_at` | `timestamptz NOT NULL` |

- `UNIQUE (actor_id, operation, idempotency_key)`가 BR-25 scope를 DB에서 강제한다.
- `response_version`은 90일 보존 동안 응답 schema가 진화해도 저장된 body를 해석할 수
  있게 한다. ADR-025 Consequences의 같은 요구를 적용한 것이다.
- 레코드는 저장 시점에 이미 terminal이므로 BR-26의 90일을 `created_at` 기준으로
  적용한다. `retention_expires_at = created_at + 90일`을 컬럼으로 materialize한다.
- 정리는 `AuditRetentionWorker`와 같은 패턴의 chunk worker가 수행한다.
  `(retention_expires_at, id)` index 순서로 제한된 chunk를 삭제하고, 중단·재실행 시
  due 이전 레코드를 삭제하지 않으며, 일반 비즈니스 트랜잭션과 분리한다.

## Alternatives Considered

### 사전등록 모델(ADR-025) 적용

- 확정 실패까지 결정적으로 재생하고 주문 생성과 모델이 하나로 통일된다.
- 그러나 취소 트랜잭션에는 외부 호출이 없어 `PROCESSING` 창이 사실상 존재하지 않는데도
  Tx I1/I2, stuck scan worker, `MANUAL_REVIEW` 종결 절차가 함께 추가된다.
  `ordering_idempotency_record`의 `intended_order_id NOT NULL`이 취소에 맞지 않아
  테이블을 분리하거나 제약을 완화해야 한다. 롤백된 `503`을 `FAILED`로 굳히면 재시도
  하려는 고객이 새 key를 강제당한다.

### 멱등 레코드 없이 Order 상태 guard만

- 테이블과 migration이 전혀 필요 없고 두 번째 취소는 자연히 `409 ORDER_STATE_CONFLICT`가
  된다.
- 그러나 성공 응답이 유실된 고객의 재시도가 자신의 성공한 취소를 `409` 실패로 보게
  된다. OpenAPI가 `Idempotency-Key`를 필수로 계약했는데 서버가 이를 저장하지 않아
  계약과 구현이 어긋나고 BR-14·BR-25 Required Tests를 충족하지 못한다.

### 상태별 혼합(`PENDING_PAYMENT`은 명령 트랜잭션, `PAID`는 사전등록)

- 보상이 남는 쪽만 강한 보장을 준다.
- 그러나 한 endpoint가 두 멱등성 계약을 갖게 되어 문서·테스트·운영이 두 배가 된다.
  `PAID` 취소도 외부 호출은 트랜잭션 밖이므로 사전등록이 주는 추가 이득이 없다.

### `ordering_store_command_idempotency` 재사용

- 테이블이 하나로 유지되고 `order_id` 컬럼도 이미 있다.
- 그러나 `operation` 값만으로 서로 다른 actor 유형(고객 대 매장 구성원)의 명령을 한
  테이블에 섞게 되고, 아래 Consequences에 기록한 기존 payload hash 결함을 상속한다.

### 기존 매장 명령 레코드까지 같은 정리 worker로 처리

- BR-26 미충족을 한 번에 해소한다.
- 그러나 이번 Feature가 고객 취소와 무관한 기존 매장 명령 자산과 migration을 함께
  바꾸게 되어 범위가 넓어진다. 이 ADR에서는 분리하고 ADR-056에서 통합 worker로
  확정했다.

## Rationale

취소 명령의 위험 모델이 주문 생성과 다르다. 대상 Order가 이미 존재해 중복 생성 위험이
없고, Order row lock이 동시 요청 arbitration을 이미 수행하며, 외부 호출이 트랜잭션 밖에
있어 결과 불명 구간이 없다. 같은 위험 모델을 가진 매장 거절이 이미 명령 트랜잭션
모델과 `200`/`202` 응답 저장을 쓰고 있고, ADR-031이 취소의 성공 표현을 매장 거절과
동일하게 확정했으므로 멱등성 구조까지 같게 두면 저장소 안에서 대칭이 완성된다.

`orderId`를 canonical payload에 넣는 것은 BR-25의 scope 정의(`actorId + operation +
key`)를 바꾸지 않으면서 교차 주문 키 재사용을 BR-25가 이미 정한 "같은 키·다른 payload는
409" 규칙으로 자연스럽게 흡수한다.

## Consequences

- 취소 경로는 추가 DB 트랜잭션 없이 명령 트랜잭션 하나로 끝난다. 주문 생성과 달리
  stuck 레코드 scan worker가 필요 없다.
- 저장소에 멱등성 모델이 두 가지로 확정된다. 일반 선택 기준은 ADR-064가 정한 기존
  직렬화 root·로컬 원자성·외부 결과 불명 위험이며, 이 ADR이 정한 고객 취소의 command
  transaction 결론은 유지한다.
- 확정 실패가 재생되지 않으므로 같은 key의 재시도가 재실행된다. 취소 경로에 상태
  guard로 보호되지 않는 부수효과를 추가하면 이 결정이 깨진다.
- 재생 응답의 `paymentRecovery`는 취소 시점 snapshot이며 최신 보상 진행이 아니다.
  최신 상태는 ADR-031이 정한 `GET /api/v1/orders/{orderId}`로 조회한다.
- `V13` migration에 새 테이블 하나와 `(retention_expires_at, id)` index가 추가되고,
  chunk 정리 worker와 그 운영 절차가 추가된다.
- ~~**발견된 기존 결함:** `StoreOrderTransitionService`의 payload hash가
  `(targetState, reason)`만 해싱하고 레코드 조회 시 `order_id`를 비교하지 않는다~~ —
  BR-25 Store Command Scope Amendment(2026-07-31)가 매장 전이의 canonical payload를
  `(orderId, targetState, reason)`으로 개정하고 `operation`을
  `STORE_ORDER_TRANSITION_V2`로 승격하도록 확정했다. 두 명령의 멱등성 계약이 다시
  대칭이 됐다.
- ~~`ordering_store_command_idempotency`에는 BR-26 정리 작업과 정리용 index가
  없다.~~ ADR-056이 두 Ordering 명령 table의 통합 retention worker, store expiry
  backfill과 table별 독립 transaction을 확정했다.

## Failure Scenarios

- 성공 응답이 네트워크에서 유실되고 고객이 같은 key로 재시도하면 저장된 최초 응답이
  재생된다. body의 `paymentRecovery`는 최초 시점 값이므로 고객이 이를 최신 환불 진행
  으로 오인할 수 있다. 클라이언트는 `202` 이후 주문 조회로 폴링해야 한다.
- 같은 key로 서로 다른 두 주문 취소가 동시에 도달하면 두 요청이 서로 다른 Order row를
  잠그므로 직렬화되지 않고 커밋 시점에 unique 위반이 발생한다. 이를 `500`으로 노출하지
  않고 `409 IDEMPOTENCY_KEY_REUSED`로 번역해야 한다.
- 트랜잭션이 롤백되면 멱등 레코드가 남지 않는다. 향후 취소 경로에 상태 guard 밖의
  부수효과가 추가되면 같은 key 재시도가 그 부수효과를 두 번 만든다.
- Order row lock 대기가 길어질 때 부분 결과나 stale 상태를 성공으로 반환하면 실패가
  성공으로 위장된다. `503 DEPENDENCY_UNAVAILABLE`로 명시한다.
- 저장된 응답 body의 schema가 바뀐 뒤 90일 보존 레코드를 재생하면 클라이언트가 해석에
  실패할 수 있다. `response_version`을 함께 저장해 해석 경로를 분기한다.
- 정리 worker가 due 판정을 잘못해 아직 재시도 가능한 레코드를 지우면 재생이 재실행으로
  바뀐다. `retention_expires_at` 기준과 chunk 재실행 안전성을 테스트로 고정한다.
- 이 테이블에 향후 non-terminal 성격의 상태 컬럼이 추가되면 "terminal만 정리한다"는
  BR-26 규칙이 자동 충족되지 않는다.

## Verification

- 같은 key·같은 payload 재요청이 최초 status와 body를 그대로 반환하고 Order 상태, 네
  자원 수량, 이벤트 수와 AuditRecord 수가 변하지 않는다.
- 같은 key·다른 payload가 Order 상태 전이와 자원 변경 이전에 `409`로 거부된다.
- 동시 같은 key 두 요청 중 하나만 취소를 실행하고 두 응답이 동일하다.
- 롤백된 요청 이후 멱등 레코드가 존재하지 않는다.
- `created_at + 90일` 이전 레코드가 정리되지 않고 이후 레코드만 정리된다.

## Required Tests

- 같은 key·같은 payload 순차 재요청의 `200` body 재생과 부수효과 부재
- 같은 key·같은 payload 순차 재요청의 `202` body 재생과 보상 Case 중복 생성 부재
- 같은 key·다른 `reasonCode` → `409 IDEMPOTENCY_KEY_REUSED`
- 같은 key·다른 `detail` → `409 IDEMPOTENCY_KEY_REUSED`
- `trim` 후 동일해지는 `detail`과 빈 문자열·부재의 hash 동일성
- 같은 key·다른 `orderId` → `409 IDEMPOTENCY_KEY_REUSED`이며 첫 주문 응답이 재생되지
  않음
- 서로 다른 두 주문에 같은 key를 쓰는 동시 요청에서 unique 위반이 `409`로 번역됨
- 다른 key로 이미 취소된 주문 재취소 → `409 ORDER_STATE_CONFLICT`
- 같은 key 동시 요청 2건에서 단일 실행과 동일 응답
- `409 ORDER_STATE_CONFLICT`로 롤백된 뒤 같은 key 재시도의 재실행과 동일 결과
- `503`으로 롤백된 뒤 같은 key 재시도의 정상 성공
- 재생 응답에 `detail`, `cancellationId`, `replayed` 필드 부재
- 다른 actor가 같은 key를 사용해도 scope가 분리됨
- `response_status`가 `200`·`202` 외 값이면 CHECK 위반
- 90일 경계 전후 정리와 chunk 중단·재실행 안전성

## Metrics

- `beanflow.order.customer_cancellation.idempotency.count{outcome}` — `outcome`은
  `FIRST_EXECUTION`, `REPLAYED`, `KEY_REUSED`
- `beanflow.order.customer_cancellation.idempotency.lock_wait_timeout.count`
- `beanflow.order.customer_cancellation.idempotency.retention_deleted.count`
- `beanflow.order.customer_cancellation.idempotency.oldest_record_age`

Order, Store, Customer ID와 `Idempotency-Key`, `detail`은 metric tag로 사용하지 않는다.

- **Not measured:** 실제 클라이언트 재시도율, 재생 비율과 레코드 증가율

## Revisit Conditions

취소 명령 트랜잭션에 외부 Provider 호출이 들어가거나, Order row lock 없이 실행되는
취소 경로(운영자 취소 등)가 도입되거나, 확정 실패의 결정적 재생이 측정된 근거와 함께
요구되거나, Order row lock 대기가 측정된 병목이 될 때

## Related Decisions

- BR-14, BR-25, BR-26
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-030](ADR-030-customer-cancellation-authorization.md)
- [ADR-031](ADR-031-customer-cancellation-api-contract.md)
- [ADR-056](ADR-056-ordering-idempotency-retention-worker.md)
- [ADR-057](ADR-057-idempotent-response-replay-indicator.md)
- [ADR-058](ADR-058-paid-cancellation-deadline-timeout-work.md)
