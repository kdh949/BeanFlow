# ADR-095: 인증 구현을 Application 계층에서 분리하는 CurrentActor

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Authentication foundation](../exec-plans/active/productization-20-authentication-foundation.md)

## Context

현재 여러 Controller가 Spring Security의 `Jwt`에 직접 의존해 `sub`에서 actor ID를, `roles`에서
역할을 꺼낸다. [ADR-092](ADR-092-hybrid-authentication.md)가 고객·점주를 Session으로 바꾸면 같은
Application Service를 두 인증 방식이 호출하게 된다.

이때 세 가지가 문제가 된다.

1. Application Service가 `Jwt`를 파라미터로 받으면 Session 요청을 처리할 수 없다.
2. Controller마다 actor 해석 코드가 흩어지면 "사용자 ID를 Body에서 받지 않는다"는 규칙을
   한 곳에서 강제할 수 없다.
3. 테스트가 인증 구현체를 흉내내야 해서, 인가 규칙을 검증하는 대신 토큰 조립을 검증하게 된다.

## Decision

인증 결과를 표현하는 sealed interface를 `shared/api`에 둔다.

```kotlin
sealed interface CurrentActor {
    val actorId: UUID
}

data class CustomerActor(
    override val actorId: UUID,
) : CurrentActor

data class MerchantActor(
    override val actorId: UUID,
    val accountState: MerchantAccountState,
) : CurrentActor

data class OperatorActor(
    override val actorId: UUID,
    val roles: Set<String>,
) : CurrentActor
```

### 규칙

- Controller는 `CurrentActor`를 argument resolver로 주입받는다. `Jwt`, `HttpSession`,
  `Authentication`을 직접 참조하지 않는다.
- Application Service와 Domain은 `CurrentActor` 또는 그보다 좁은 값 객체만 받는다.
  Spring Security 타입을 import하지 않는다. ArchUnit으로 강제한다.
- 고객·점주·운영자 ID는 **요청 Body와 Query에서 받지 않는다**. 기존
  `POST /operations/point-accounts/{accountId}/adjustments`처럼 대상 리소스 ID를 경로로 받는 것은
  이 규칙과 무관하다. 금지 대상은 "행위자 자신의 식별자"다.
- `MerchantActor`는 매장 목록을 담지 않는다. 매장 접근 권한은 요청 시점에
  `StoreAccessOperations`가 다시 조회한다([ADR-027](ADR-027-store-membership-authorization.md)).
  membership을 인증 객체에 캐시하면 revoke가 지연 반영된다.
- `OperatorActor`의 `roles`는 coarse gate일 뿐이며 `OperatorPermissionGrant`를 대체하지 않는다
  ([ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)).
- actor 유형이 기대와 다르면 403이다. 인증 자체가 없으면 401이다. 이 구분을 Application Service가
  아니라 argument resolver와 Chain 설정에서 결정한다.

## Alternatives Considered

### 1. `Principal` 또는 `Authentication`을 그대로 전달

- 장점: 추가 타입이 없다.
- 단점: Application 계층이 Spring Security에 결합된다. 인증 방식이 바뀔 때마다 유스케이스 코드가 바뀐다.

### 2. `UUID actorId`만 전달

- 장점: 가장 단순하다.
- 단점: actor 유형을 잃어버려 "고객 ID로 점주 API를 호출"하는 오류를 타입으로 막지 못한다.
  Controller마다 유형 검사를 다시 써야 한다.

### 3. `ThreadLocal` 기반 전역 컨텍스트

- 장점: 시그니처가 깨끗해 보인다.
- 단점: 의존이 숨겨지고 비동기·worker 경로에서 값이 사라진다. worker는 애초에 actor가 없어야 한다.

## Rationale

sealed interface는 actor 유형을 컴파일 시점에 드러내고, `when` 분기에서 누락을 잡는다.
인증 방식이 세 개인 상태에서 유형 안전성은 문서가 아니라 타입으로 유지하는 편이 싸다.

## Consequences

- 기존 Controller의 `Jwt` 파라미터를 모두 교체해야 한다. 변경 파일 수가 많지만 각 변경은 기계적이다.
- `HandlerMethodArgumentResolver` 구현과 등록이 필요하다.
- ArchUnit 규칙이 하나 늘어난다. 위반 시 빌드가 실패한다.
- worker와 스케줄러는 `CurrentActor`가 없다. 시스템 주체가 수행하는 작업임을 감사 기록에서
  구분해야 한다.

## Verification

- ArchUnit: `application`·`domain` 패키지가 `org.springframework.security` 타입을 참조하지 않는지 검증한다.
- 잘못된 actor 유형 요청이 403인지 Chain별로 검증한다.
- 인증 없는 요청이 401인지 검증한다.
- 요청 Body 계약에 행위자 `customerId`가 없고 unknown field로 주입하면 400이며, 유스케이스에는
  Session actor ID만 전달되는지 검증한다.
- 기존 운영자 endpoint의 permission grant 테스트가 회귀 없이 통과하는지 확인한다.

## Metrics

- Chain별 401/403 발생 수와 사유 분포
- actor 유형 불일치 거부 수

## Implementation Outcome (2026-08-13)

`CurrentActor` sealed API, browser/JWT adapter와 MVC argument resolver를 구현했다. 모든 Controller
method에서 `Jwt`, `Authentication`, `HttpSession` parameter를 제거하고 actor별 타입으로 바꿨다.
Application/Domain의 Spring Security 의존과 Controller의 금지 parameter를 구조 테스트로 고정했다.
Merchant membership과 Operations explicit permission은 Session에 넣지 않고 기존 DB source of truth를
요청 transaction에서 계속 조회한다.

## Revisit Conditions

- 서비스 계정 또는 machine-to-machine 호출이 필요해질 때
- actor에 조직·테넌트 개념이 추가될 때

## Related Decisions

- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
