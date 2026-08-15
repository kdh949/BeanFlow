# 외부 POS 파트너(오케이포스·이지포스 등) 연동 API

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

매장이 오프라인에서 이미 쓰는 POS(오케이포스, 이지포스 등)가 BeanFlow의 온라인 선주문을
읽어가고 처리 시작을 알릴 수 있게 한다. 매장 직원이 온라인 주문을 놓치거나 이중 입력하는
문제를 없앤다. 신규 Bounded Context `partner`와 매장 범위 API Key 인증을 추가하고, 최소
read 위주 API(주문 목록, 메뉴, 처리 확인 1개 write)만 먼저 제공한다.

## Current State

- `partner` 모듈, `AuthenticationChain.PARTNER`, API Key 인증 어디에도 없음.
- `AuthenticationChain`은 `PUBLIC`/`OPERATIONS`(Bearer JWT)/`MERCHANT`·`CUSTOMER`(PostgreSQL
  Session)만 존재 (`shared/internal/AuthenticationPathRegistry.kt`).
- 매장 주문 조회는 Merchant 세션 체인의 `GET /api/v1/stores/{storeId}/orders`(store order
  board)만 있고, 서버 간 폴링에 맞는 별도 API가 없다.
- ADR-113이 인증 방식(API Key, pull 모델)과 대안 검토(webhook/OAuth2 제외 사유)를 이미 확정.

## Definitions

- **Partner API Key:** 매장(store) 단위로 발급하는 서버 간 인증 토큰. 브라우저 세션이 아니다.
- **Acknowledgement:** 외부 POS가 특정 주문을 받아갔음을 알리는 멱등 명령. Order 상태 전이가
  아니라 별도 sync 여부 기록이다.
- **Partner chain:** `/api/v1/partner/**`에 적용되는 신규 STATELESS 인증 체인.

## Scope

### In Scope

- `partner` Bounded Context 스캐폴딩(`api`/`internal`)
- `AuthenticationChain.PARTNER` + `PartnerApiKeyAuthenticationFilter`
- `partner_api_key` 테이블과 매장당 활성 키 상한(5개)
- 점주용 키 발급/회수 API(Merchant 세션 체인)
- `GET /api/v1/partner/stores/{storeId}/orders`(cursor 목록)
- `POST /api/v1/partner/stores/{storeId}/orders/{orderReference}/acknowledgements`
- `GET /api/v1/partner/stores/{storeId}/menu`
- target/runtime OpenAPI 반영, Scalar 문서에 "외부 파트너 연동" 태그 그룹 추가

### Non-goals

- Webhook(push) 모델 — ADR-113 Revisit Conditions로 이연
- 실제 POS 하드웨어·프린터 장치 연동(`docs/product/non-goals.md`)
- 파트너가 메뉴·매장 정보를 쓰는(write) API
- Partner API를 통한 주문 상태 전이(수락/거절 등은 여전히 Merchant 체인 전용)
- OAuth2 client-credentials, 다중 Provider 연동 관리 UI

## Business Rules and Invariants

- Partner API Key는 정확히 하나의 `storeId`에 스코프된다. 다른 매장 리소스 접근은 403이다.
- 키 원문은 발급 응답에서 한 번만 노출한다. 서버는 salted hash만 저장하며 원문을 복구할 수
  없다.
- `REVOKED` 키는 즉시 401이며, 회수 이후의 in-flight 요청도 재검증에서 거부된다.
- 매장당 동시 `ACTIVE` 키는 최대 5개다. 초과 발급 시도는 `409 PARTNER_API_KEY_LIMIT_EXCEEDED`.
- `acknowledgements`는 Order 상태를 바꾸지 않는다. 같은 주문에 대한 재호출은 최초 결과를
  재생하는 멱등 동작이다(Idempotency-Key 컨벤션 재사용).
- 주문 목록·메뉴 응답은 고객 개인정보(전화번호, 실명, 내부 UUID)를 포함하지 않는다.

## Architecture and Transaction Boundaries

`PartnerApiKeyAuthenticationFilter`는 Operations 체인의 `ActorCredentialIsolationFilter` 배치
패턴을 따라 `SecurityContextHolderFilter` 앞에 위치한다. 키 조회·hash 비교·`lastUsedAt` 갱신은
단일 짧은 트랜잭션으로 커밋하고, 이어지는 컨트롤러 트랜잭션과는 분리한다(로그인 시도 기록과
동일한 패턴).

주문 목록 조회는 기존 `StoreOrderBoardQueryService`/signed cursor 인프라를 재사용하는
전용 Read 경로를 추가한다(쓰기 모델을 확장하지 않는다). `acknowledgements`는 `ordering :: api`가
노출하는 좁은 커맨드(예: `RecordPartnerAcknowledgementCommand`)를 통해서만 Order 애그리게잇에
접근하고, `partner` 모듈이 `ordering.internal`을 직접 참조하지 않는다.

## Alternatives Considered

ADR-113 참고. Webhook, OAuth2 client-credentials, Merchant 세션 재사용을 검토 후 API Key +
pull 모델을 선택했다.

## Failure Semantics

- 키 조회 DB 장애는 401로 위장하지 않고 `503 DEPENDENCY_UNAVAILABLE`을 반환한다(다른 체인과
  동일 원칙).
- `acknowledgements`의 대상 주문이 없거나 다른 매장 소유면 404/403이며, 이미 acknowledge된
  주문의 같은 key/payload 재호출은 최초 응답을 재생한다.
- `lastUsedAt` 갱신 실패가 인증 자체를 실패시키지 않는다(관측용 부가 정보이므로 best-effort).

## Data and Migration

- `V63__create_partner_api_key.sql` — `partner_api_key` 테이블, unique index(hashed_key),
  partial index(store_id where state='ACTIVE').
- 기존 마이그레이션은 수정하지 않는다. `acknowledgements`는 새 컬럼을 Order 테이블에 추가하지
  않고 별도 `partner_order_acknowledgement` 테이블(orderId, storeId, acknowledgedAt, partnerApiKeyId)에
  기록한다.

## API and Event Contracts

- `POST /api/v1/merchant/stores/{storeId}/partner-api-keys` — 발급, `201`, 응답에 원문 키 1회 포함
- `DELETE /api/v1/merchant/stores/{storeId}/partner-api-keys/{keyId}` — 회수, `204`
- `GET /api/v1/merchant/stores/{storeId}/partner-api-keys` — 발급 이력 조회(원문 키 없음)
- `GET /api/v1/partner/stores/{storeId}/orders` — `200`, cursor 목록
- `POST /api/v1/partner/stores/{storeId}/orders/{orderReference}/acknowledgements` — `201`,
  `Idempotency-Key` 필수
- `GET /api/v1/partner/stores/{storeId}/menu` — `200`
- 새 이벤트는 없다(acknowledgement는 내부 상태 기록이며 다른 Context에 발행하지 않는다).

## Milestones

1. ADR-113과 이 ExecPlan 확정, `docs/api/api-conventions.md` Chain 표 갱신.
2. `partner` 모듈 스캐폴딩 + `AuthenticationChain.PARTNER` + 필터 + `V63` 마이그레이션.
3. 점주용 키 발급/회수 API(Merchant 체인) + 테스트.
4. Partner 주문 목록/메뉴/acknowledgements API + 테스트.
5. target/runtime OpenAPI 반영, `RuntimeOpenApiParityTest` 통과, Scalar "외부 파트너 연동" 태그 그룹.
6. end-to-end 인증 시나리오(401/403/200/409) 검증과 문서 최종화.

## Required Tests

- `AuthenticationPathRegistryTest`에 `/api/v1/partner/**` 매핑 추가 검증
- `PartnerApiKeyAuthenticationFilterTest` — 키 없음/무효/`REVOKED`/다른 매장 스코프
- 발급 API — 매장당 5개 상한, 원문 키 1회 노출, hash만 저장되는지
- Partner 주문 목록 — cursor 페이지, 고객 개인정보 미노출
- `acknowledgements` — 멱등 replay, 존재하지 않는/타 매장 주문
- `RuntimeOpenApiParityTest` — 신규 컨트롤러 매핑과 spec 정합
- Spring Modulith 모듈 경계 테스트(`partner`가 `ordering.internal`을 직접 참조하지 않는지)

## Validation Commands

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew clean build --stacktrace
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.partner.authentication.count{outcome}` (success/invalid_key/revoked/store_mismatch)
- `beanflow.partner.order_acknowledgement.count{outcome}`
- `beanflow.partner.api_key.issued.count`, `beanflow.partner.api_key.revoked.count`

Store ID, Order reference는 metric tag로 사용하지 않는다(cardinality/PII 원칙 재사용).

## Documentation Updates

- ADR-113 (완료)
- `docs/api/api-conventions.md` Chain 표 + Partner 섹션
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml` — Partner 태그, 예시, x-tagGroups
- 이 ExecPlan의 Progress/Decision Log

## Progress

- [x] ADR-113 작성
- [x] 이 ExecPlan 작성
- [ ] `partner` 모듈/인증 체인/마이그레이션 스캐폴딩
- [ ] 점주 키 발급/회수 API
- [ ] Partner 주문/메뉴/acknowledgements API
- [ ] OpenAPI 반영과 Scalar 문서 확인
- [ ] end-to-end 검증

## Surprises & Discoveries

(구현 진행에 따라 갱신)

## Decision Log

| Date | Status | Decision | Record |
|---|---|---|---|
| 2026-08-15 | Accepted | API Key + pull 모델 선택, webhook은 이연 | ADR-113 |
| 2026-08-15 | Accepted | 매장당 활성 키 상한 5개 | ADR-113 |
| 2026-08-15 | Accepted | acknowledgements는 Order 상태를 바꾸지 않는 별도 기록 | 이 ExecPlan |

## Outcomes & Retrospective

(완료 시 갱신)

## Revision Notes

- 2026-08-15: 최초 작성.
