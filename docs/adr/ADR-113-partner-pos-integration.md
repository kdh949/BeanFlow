# ADR-113: 외부 POS 파트너 연동 인증과 API 범위

- **Status:** Accepted
- **Date:** 2026-08-15
- **Implementation owner:** [Partner POS integration](../exec-plans/active/partner-pos-integration.md)

## Context

BeanFlow는 온라인 선주문·결제·픽업을 다루지만, 매장은 오프라인에서 오케이포스·이지포스 같은
별도 POS를 이미 쓰고 있다. 두 시스템이 분리돼 있으면 매장 직원이 온라인 주문을 놓치거나 이중
입력해야 한다. 이 문제를 풀려면 외부 POS가 BeanFlow의 온라인 주문을 읽어가고 처리 시작을
알릴 수 있는 API가 필요하다.

`docs/product/non-goals.md`의 "실제 POS·프린터 **장치**" 비목표는 하드웨어/프린터 드라이버
수준 통합을 가리키며, 외부 POS 소프트웨어가 HTTP API로 데이터를 가져가는 SaaS-to-SaaS 연동과는
다른 범위다. 이 ADR은 후자만 다룬다.

현재 `AuthenticationChain`은 `PUBLIC`/`OPERATIONS`(Bearer JWT)/`MERCHANT`·`CUSTOMER`(PostgreSQL
Session)뿐이며, 브라우저가 아닌 서버 간(server-to-server) 호출을 위한 인증 수단이 없다.
`MerchantCredentialAdministrationController`가 다루는 "Credential"은 점주 로그인 계정(ID+비밀번호)
자격증명이며, 이 ADR이 다루는 매장별 API Key와는 다른 개념이다.

## Decision

### 1. 신규 Bounded Context `partner`

`partner` 모듈을 API(`partner/api`)/internal(`partner/internal`)로 분리해 추가한다.
`allowedDependencies`는 `shared :: api`, `ordering :: api`, `merchant :: api`로 제한한다.

### 2. 신규 인증 체인 `PARTNER` — 매장 범위 API Key

```sql
CREATE TABLE partner_api_key (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    label varchar(120) NOT NULL,
    hashed_key varchar(255) NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE UNIQUE INDEX partner_api_key_hashed_key_idx ON partner_api_key (hashed_key);
CREATE INDEX partner_api_key_store_id_idx ON partner_api_key (store_id) WHERE state = 'ACTIVE';
```

- 발급 키 원문은 발급 응답에서 정확히 한 번만 노출하고 저장하지 않는다. 저장은 salted hash만
  한다(기존 점주 비밀번호 hash 방식 재사용).
- 요청 인증은 `X-BEANFLOW-PARTNER-KEY` 헤더. `AuthenticationChain.PARTNER`를
  `/api/v1/partner/**`에 등록하고, `PartnerApiKeyAuthenticationFilter`가 헤더 값을 hash해 조회한다.
  키가 없거나 `REVOKED`면 401. 키는 유효하지만 대상 `storeId` 경로와 발급 매장이 다르면 403.
- Operations 체인처럼 STATELESS·CSRF 비활성. 브라우저 세션이 아니므로 Merchant/Customer 체인의
  쿠키·CSRF 패턴을 재사용하지 않는다.
- 발급·회수는 점주가 Merchant 세션 체인에서 수행한다(`merchant/internal/PartnerApiKeyAdministrationController.kt`,
  `/api/v1/merchant/stores/{storeId}/partner-api-keys`). 매장당 활성 키 상한은 5개로 제한해
  무한정 발급을 막는다.

### 3. Pull 모델, read-only 주문/메뉴 노출 + 처리 확인 1개 write

- `GET /api/v1/partner/stores/{storeId}/orders` — cursor 페이지, 기존 signed cursor 포맷 재사용.
- `POST /api/v1/partner/stores/{storeId}/orders/{orderReference}/acknowledgements` — POS가
  주문을 받아갔음을 알리는 유일한 write. `Idempotency-Key` 필수.
- `GET /api/v1/partner/stores/{storeId}/menu` — 메뉴 마스터 read-only 조회.
- 고객 개인정보(전화번호, 이름 전체, 내부 UUID)는 노출하지 않는다. 픽업 호출에 필요한 표시
  정보(`pickupNumber`, `orderReference`, 품목/수량)만 반환한다.

## Alternatives Considered

- **Webhook(push) 모델**: POS가 콜백 URL을 등록하면 BeanFlow가 이벤트를 밀어준다. 재시도, 서명
  검증, 콜백 URL 소유권 확인까지 추가로 필요해 초기 범위에서 제외한다. 이후 실제 파트너
  요구가 확인되면 이 ADR을 개정해 추가한다.
- **OAuth2 client-credentials**: 표준적이지만 client 등록·토큰 발급 인프라를 새로 구축해야
  하고, 매장 단위 스코프를 토큰 클레임에 담는 설계가 API Key보다 복잡하다. 매장 수가 적고
  회전 주기가 느린 이번 범위에서는 API Key가 더 단순하다.
- **Merchant 세션 재사용**: 브라우저 세션은 서버 간 배치/폴링 호출에 맞지 않고, 세션 만료가
  POS 쪽 무인 폴링을 깨뜨린다.

## Rationale

매장 수가 적고 폴링 주기가 분 단위인 초기 단계에서는 API Key + pull 모델이 구현·운영 비용이
가장 낮다. 점주가 스스로 키를 발급·회수할 수 있어 Operations 개입 없이 온보딩할 수 있다.

## Consequences

- 새 Bounded Context, 새 인증 체인, 새 테이블이 추가된다.
- 파트너 API는 read 위주이므로 기존 주문 상태 기계에 새 전이를 추가하지 않는다(`acknowledgements`는
  Order 상태를 바꾸지 않고 별도 sync 여부만 기록한다).
- Webhook이 필요해지면 별도 ADR로 개정한다.

## Verification

- `AuthenticationPathRegistryTest`, 신규 `PartnerApiKeyAuthenticationFilterTest`
- 계약 테스트: 키 누락 401, 다른 매장 키 403, 정상 키 200/201
- `RuntimeOpenApiParityTest`가 신규 Partner 컨트롤러 매핑을 확인

## Revisit Conditions

- 실제 파트너(오케이포스, 이지포스 등)가 webhook을 요구하면 push 모델을 추가하는 개정을 연다.
- 매장당 활성 키 상한(5개)이 실사용에서 부족하면 정책을 재검토한다.

## Related Decisions

- [ADR-093](ADR-093-merchant-credential-lifecycle.md) — 점주 로그인 계정 자격증명(이 ADR과 다른 개념)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md) — signed cursor 포맷 재사용
- [docs/product/non-goals.md](../product/non-goals.md) — 실제 POS 장치 연동 비목표와의 구분
