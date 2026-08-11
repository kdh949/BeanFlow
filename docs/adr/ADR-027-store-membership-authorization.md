# ADR-027: 매장 membership 기반 객체 수준 인가

- **Status:** Accepted
- **Date:** 2026-07-30

## Context

JWT의 `STORE_OWNER` 또는 `STORE_STAFF` 역할만 검사하면 같은 역할을 가진 actor가 다른
매장의 주문을 변경할 수 있다. 매장 주문 명령은 역할과 주문이 속한 매장의 관계를 모두
검증해야 한다.

## Decision

- Identity가 `StoreMembership`을 소유한다.
- membership은 actor ID, store ID, `OWNER | STAFF` 역할과
  `ACTIVE | REVOKED` 상태를 가진다.
- `(actor_id, store_id)`는 Unique Constraint로 한 관계만 허용한다.
- **2026-08-12 Session amendment:** 고객·점주 Session 전환 뒤 Merchant Chain은 인증 결과를
  `MerchantActor`로 만들고 Controller는 점주 역할이나 store ID를 요청·Session에서 받지 않는다.
  Ordering Application Service는 Order의 store ID와 해당 operation이 허용하는 membership 역할 집합으로
  `StoreAccessOperations`를 호출한다. 주문 운영·부분 환불은 `OWNER | STAFF`, 정산·이의제기는
  `OWNER`만 허용한다.
- P0 점주 Session에는 role·membership을 캐시하지 않는다. 요청 시점의 `ACTIVE StoreMembership.role`이
  객체 관계와 세부 역할의 단일 authoritative source다. 따라서 revoke와 role 변경이 다음 요청에 즉시
  반영된다. `MerchantActor` 유형은 coarse merchant gate일 뿐 특정 store 권한이 아니다.
- 기존 JWT의 `STORE_OWNER | STORE_STAFF` claim과 membership role 일치 검사는 productization-20 전환
  전 runtime에만 적용한다. 전환 후 Merchant JWT 경로를 병행하거나 Session에 claim을 복제하지 않는다.
- membership이 없거나 `REVOKED`이거나 다른 매장 관계면 `403`이다.
- 권한 확인은 상태 변경 전에 수행하고 성공 actor type을 AuditRecord에 기록한다.

## Alternatives Considered

### JWT에 store ID 포함

- 읽기 비용은 작지만 membership 폐기와 token 만료 사이에 권한이 남고 다점포 관계
  변경을 즉시 반영하기 어렵다.

### Ordering이 membership 보유

- 한 유스케이스 안의 구현은 단순하지만 Identity 데이터 소유권이 Ordering으로
  번지고 메뉴·정산 등 다른 매장 기능에서 중복된다.

## Rationale

역할은 기능 진입 권한이고 membership은 객체 접근 권한이다. 둘을 분리하면 JWT가 가진
거친 역할을 유지하면서 현재 DB 상태로 매장 경계를 강제할 수 있다.

## Consequences

- 매장 명령에는 Identity 조회가 추가된다.
- membership 저장소 장애를 허용으로 대체하지 않고 요청을 실패시킨다.
- 지원 목적의 플랫폼 운영자 직접 상태 변경은 이번 API에 포함하지 않는다.

## Verification

- 잘못된 actor 유형, membership 없음, 타 매장, `REVOKED`, operation에 허용되지 않은 role `403`
- 해당 매장의 일치하는 `ACTIVE` membership 성공
- 동일 actor/store 동시 등록 Unique Constraint

## Metrics

- `beanflow.authorization.denied.count{operation,reason}`

## Revisit Conditions

세분화된 매장 권한, 한 매장의 복수 membership 역할 또는 위임이 필요할 때

## Related Decisions

- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-022](ADR-022-audit-record.md)
