# ADR-096: 내부 UUID와 분리한 공개 주문번호

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Public order reference](../exec-plans/completed/productization-10-public-order-reference.md)

## Context

`ordering_order`의 PK는 UUID이고, 고객·점주·운영자 화면 모두 이 UUID를 그대로 노출하거나 심지어
입력받는다. 고객은 자기 주문의 UUID를 알 수도 기억할 수도 없고, 전화 문의에서 읽어줄 수도 없다.
점주는 픽업 호출에 UUID를 쓸 수 없다.

한편 UUID는 내부에서 잘 동작한다. FK, 이벤트 Aggregate ID, 로그 상관에 이미 널리 쓰이고 있으며
([ADR-003](ADR-003-aggregate-reference-by-id.md)), 이를 바꾸면 결제·정산·보상·감사까지 전부
영향을 받는다.

UUID 앞 8자리를 잘라 표시하는 방식은 주문번호가 아니다. 충돌 정책도, 유일성 제약도, 추측
저항성도 없다.

## Decision

주문에 **외부 공개 식별자** `publicReference`를 추가한다. 내부 `orderId` UUID는 그대로 유지한다.

### 형식

```text
BF-7K3M-9Q2P
```

- 접두사 `BF-` + 4자 그룹 2개
- 문자 집합은 `23456789ABCDEFGHJKMNPQRSTUVWXYZ` 31자다. 혼동 문자 `0`, `O`, `1`, `I`,
  `L`을 제외한다.
- 암호학적으로 안전한 난수로 생성한다. 순번이나 시각을 인코딩하지 않는다.
- 대소문자를 구분하지 않고 저장·조회한다. 저장은 대문자로 정규화한다.

### 제약과 생성

```sql
ALTER TABLE ordering_order ADD COLUMN public_reference varchar(12) NOT NULL;
CREATE UNIQUE INDEX ux_ordering_order_public_reference ON ordering_order (public_reference);
ALTER TABLE ordering_order ADD CONSTRAINT ck_ordering_order_public_reference
  CHECK (public_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$');
```

- 유일성은 애플리케이션 사전 조회가 아니라 DB Unique Constraint가 보장한다.
- 주문 row insert의 unique 예외를 같은 transaction에서 잡아 재시도하지 않는다. PostgreSQL에서는
  statement 실패가 transaction을 abort시킬 수 있으므로 `ordering_public_reference_registry`에
  `INSERT ... ON CONFLICT DO NOTHING RETURNING`으로 먼저 예약한다.
- 최초 시도를 포함해 최대 5회 생성·예약한다. 5회 모두 충돌이면 `503 ORDER_REFERENCE_EXHAUSTED`로
  주문 생성 전체를 rollback하고 metric을 남긴다. 무한 재시도하거나 UUID를 fallback으로 노출하지 않는다.
- registry 예약과 주문 insert는 같은 주문 생성 transaction이다. 주문 생성이 rollback되면 예약도
  rollback된다. 커밋된 public reference는 이후 Order 보존 종료 뒤에도 재사용하지 않는다.

### 권한 규칙

- **주문번호는 권한 증명이 아니다.** 주문번호를 안다고 다른 고객의 주문을 조회할 수 없다.
- 고객 경로 `GET /me/orders/{orderReference}`는 Session actor의 소유권을 함께 검증한다.
  소유자가 아니면 403, 존재하지 않으면 404다
  ([ADR-030](ADR-030-customer-cancellation-authorization.md)의 기존 규칙을 그대로 따른다).
- 매장 경로 `GET /stores/{storeId}/orders/{orderReference}`는 `StoreMembership`과 주문의
  `storeId` 일치를 함께 검증한다.
- 운영자 조회는 주문번호를 **검색 입력**으로 쓰지만, 결과 노출은 permission grant와 감사에 따른다.

### 마이그레이션

- 기존 주문에 backfill한다. backfill은 registry의 같은 원자 예약을 사용하고 각 행의 유일성은 DB가
  보장한다.
- backfill 완료 후 `NOT NULL`을 적용한다.
- 기존 UUID 경로·응답은 호환 전환 동안 유지한다. 새 고객·점주 사람용 API는 `publicReference`만
  노출하고 내부 `orderId`를 응답에 포함하지 않는다. 내부 FK·이벤트·로그는 UUID를 계속 사용한다.

## Alternatives Considered

### 1. UUID를 그대로 노출

- 장점: 변경이 없다.
- 단점: 사람이 쓸 수 없다. 고객 문의·픽업 호출·매장 업무 어디에도 맞지 않는다.

### 2. UUID 앞 8자리 표시(`compactId`)

- 장점: 스키마 변경이 없다.
- 단점: 충돌 가능성이 있고 정책이 없다. 잘린 값으로 조회를 허용하면 접두사 열거가 가능해진다.

### 3. UUID를 짧은 문자열로 완전히 교체

- 장점: 식별자가 하나다.
- 단점: 내부 식별과 외부 식별이 섞인다. 외부에 노출된 값이 FK와 이벤트 키가 되면 형식을 바꿀 수
  없고, 결제·정산·감사 전체의 마이그레이션 비용이 매우 크다.

### 4. 매장·일자 기반 순번을 주문번호로 사용

- 장점: 짧고 읽기 쉽다.
- 단점: 전역 유일하지 않고 추측 가능하다. 이 역할은 픽업번호가 맡는다
  ([ADR-097](ADR-097-store-pickup-number.md)).

## Rationale

식별자의 역할을 나누면 각각을 최적화할 수 있다. UUID는 내부 참조 안정성을, 공개 주문번호는
사람의 가독성과 추측 저항성을 담당한다. 둘을 하나로 합치려는 시도가 위 대안들의 공통 실패
원인이다.

## Consequences

- Flyway migration 1건과 backfill 작업이 필요하다. 기존 주문이 있는 환경에서 무중단 절차를 문서화해야 한다.
- OpenAPI 응답에 필드가 추가된다. 기존 필드는 제거하지 않으므로 호환성 파괴는 없다.
- 공개 경로가 `{orderId}`에서 `{orderReference}`로 바뀌는 endpoint는 새 경로를 추가하고 기존
  경로를 유지한 뒤, 프론트엔드 전환 후 제거한다.
- 주문 생성 경로에 재시도 분기가 하나 늘어난다.
- 재시도를 transaction abort 없이 수행하기 위한 registry table이 추가된다. public reference 재사용을
  막기 위해 Order 보존 종료 뒤에도 registry row는 유지하며 PII를 저장하지 않는다.

## Verification

- 생성기 충돌을 주입해 Unique Constraint 위반 후 재생성이 동작하는지, 상한 초과가 명시적 실패인지 검증한다.
- 다른 고객의 `publicReference`로 조회 시 403인지 검증한다.
- 존재하지 않는 `publicReference` 조회가 404인지 검증한다.
- 대소문자 혼합 입력이 같은 주문으로 해석되는지 검증한다.
- backfill migration 후 기존 주문이 두 식별자 모두로 조회되는지 검증한다.
- 동시 주문 생성 부하에서 중복 `publicReference`가 생기지 않는지 PostgreSQL Testcontainers로 검증한다.

## Metrics

- 주문번호 충돌 재생성 횟수와 재시도 상한 초과 수
- 주문번호 기반 조회의 403/404 비율
- backfill 처리 건수와 소요 시간

## Revisit Conditions

- 충돌 재생성이 실제로 유의미한 빈도로 발생할 때(문자 수 확대 검토)
- 주문번호를 오프라인 인쇄물·바코드에 사용해야 할 때
- 다국가 확장으로 접두사 체계가 필요할 때

## Implementation Outcome (2026-08-12)

- V43가 nullable 컬럼, 영구 registry, 카운터를 열고 V44가 backfill preflight 뒤 Unique/FK/NOT NULL과
  불변 trigger를 닫는다.
- `OrderReferenceBackfillService`는 `(created_at, id)` keyset과 bounded transaction으로 재시작 가능하게
  구현됐고, 별도 Gradle CLI와 운영 runbook을 제공한다.
- 고객·매장 lookup은 canonical reference와 ownership/store predicate를 한 query에 포함하고, 별도 존재
  query로 403/404를 구분한다. 신규 응답은 내부 `orderId`를 제외한다.
- 충돌·상한 초과, backfill 처리/실패/시간, lookup 403/404 metric을 실제 코드에 연결했다.

## Related Decisions

- [ADR-097](ADR-097-store-pickup-number.md)
- [ADR-098](ADR-098-order-display-snapshots.md)
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-030](ADR-030-customer-cancellation-authorization.md)
