# ADR-070: Versioned HMAC cursor와 pagination 상한

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owner:** [Signed cursor foundation](../exec-plans/completed/signed-cursor-foundation.md)

## Context

API convention은 cursor를 수정할 수 없는 opaque string으로 요구하고 Nearby plan은 server-signed
cursor를 요구한다. 하지만 signing algorithm, key source, version, filter binding, rotation과 common
page limit의 default/maximum이 정해지지 않았다. 같은 common `Cursor`/`Limit` parameter는 Nearby,
PointTransaction, SettlementBatch와 SettlementItem 목록에 적용된다.

단순 base64 tuple은 radius, store 또는 Batch scope를 바꿔 다른 page를 조회하게 할 수 있고, 제한 없는
limit은 DB와 응답 메모리를 예측할 수 없게 만든다. cursor secret이 없을 때 unsigned cursor나 local
default key로 시작하면 failure semantics를 위반한다.

## Decision

모든 public cursor endpoint는 versioned, stateless, HMAC-SHA-256 signed cursor를 쓴다. common
`limit`은 default `20`, minimum `1`, maximum `100`이다. signed-cursor foundation이
codec/configuration을 단독 구현하고 endpoint plan은 typed adapter만 소비한다.

### Cursor format and validation

생성 token은 다음 네 부분을 dot으로 연결한다.

```text
v1.<key-id>.<base64url(canonical-json-payload)>.<base64url(hmac-sha-256)>
```

signature input은 정확히 `v1.<key-id>.<encoded-payload>` 문자열의 UTF-8 bytes다. canonical
payload는 다음 wire contract를 모두 지키는 UTF-8 JSON이다.

- 공백·줄바꿈·들여쓰기가 없는 JSON을 사용하고 property 순서는 정확히
  `endpoint`, `filterHash`, `sort`, `issuedAt`, `expiresAt`다.
- 위 다섯 property 외의 property와 `null`은 허용하지 않는다.
- `endpoint`는 고정 endpoint identifier, `filterHash`는 endpoint path parameter와 query filter의
  canonical form을 SHA-256으로 계산한 64자리 lowercase hexadecimal string이다.
- `sort`는 문서화한 stable sort tuple 값을 순서 그대로 담는 JSON string array다. UUID sort value는
  lowercase canonical UUID string이다.
- `issuedAt`과 `expiresAt`은 epoch second를 나타내는 JSON integer다. JSON number의 fraction,
  exponent 또는 string 표현은 허용하지 않는다.
- payload와 signature는 padding 없는 Base64URL로 인코딩한다. `=` padding, 표준 Base64 alphabet 또는
  malformed UTF-8/JSON은 허용하지 않는다.

cursor에는 raw customer coordinate, raw filter value, actor identity, request URI, Authorization data 또는
secret을 넣지 않는다. HMAC token은 암호화 token이 아니므로 caller에게 tuple을 비밀로 약속하지 않으며,
API는 token의 내용을 계약으로 노출하거나 해석 가능하다고 보장하지 않는다. Nearby의 filter hash에는
latitude/longitude/radius의 raw text를 넣지 않는다.

검증 시 endpoint, filterHash, `expiresAt`, token version, active/retired verification key와 HMAC을 모두
확인한다. `now >= expiresAt`이면 만료다. public cursor는 최대 `2048`자이며, 이를 넘는 token은 decode나
query 전에 malformed로 거부한다. endpoint/path/filter mismatch, malformed encoding, unknown version/key,
invalid signature 또는 expired token은 모두 `400 INVALID_REQUEST`다. 구현은 원인을 response, metric tag 또는
log에 세분화해 노출하지 않는다. object-level authorization은 cursor 검증과 독립적으로 매 요청에 다시 수행한다.

cursor lifetime은 발급 시점부터 최대 24시간이다. page limit은 cursor payload에 포함하지 않으므로
동일 scope cursor로 `1..100` 사이의 다른 limit을 요청할 수 있다. 서버는 requested limit이 없으면 20을
사용하며, 100 초과 또는 1 미만은 query 실행 전에 400으로 거부한다.

### Key configuration and rotation

configuration은 required startup dependency이며 다음 구조를 사용한다.

```yaml
beanflow:
  pagination:
    cursor-hmac:
      active-key-id: current
      keys:
        - id: current
          secret-base64-url: ${BEANFLOW_CURSOR_HMAC_CURRENT_KEY}
```

- `keys`는 duplicate key ID를 검출할 수 있는 list다. key ID는 `[A-Za-z0-9_-]{1,32}`이다.
- `secret-base64-url`은 padding 없는 Base64URL로 읽고 decode 뒤 최소 32 bytes여야 한다.
- malformed Base64URL, duplicate key ID, empty key ring, unknown active key 또는 짧은 secret은 모두
  application startup failure다.
- source, 기본 설정, production 또는 local runtime configuration에 fallback secret을 넣지 않는다.
- active key가 key ring에 없거나, key ID/key material이 malformed·duplicate이거나 key ring이 비어
  있으면 application startup을 실패시킨다.

### Public test-vector key exception

실제 deployment secret은 source, fixture, 문서, log와 test output에 기록하지 않는다. 다만 암호학적
비밀이 아닌 **공개된 test-vector 전용 key material**은 deterministic signing test를 위해 test source에서만
사용할 수 있다.

- 이름과 주석으로 test-vector 전용임을 표시한다.
- production 또는 local runtime configuration에서 선택할 수 없고, 실제 deployment secret과 같은
  environment variable 이름을 사용하지 않는다.
- test result와 log에 key material을 출력하지 않으며, 운영 fallback으로 사용할 수 없다.

- rotation은 새 key를 verification ring에 추가한 뒤 active key로 전환하고, 이전 key를 최소 24시간과
  배포 propagation window 동안 verifier로 유지한 뒤 제거한다. retired key로 서명된 유효 token은 그
  기간에만 검증한다.
- key ID, payload, signature과 filter hash는 log/metric tag/AuditRecord에 기록하지 않는다.

### Endpoint bindings

| Endpoint | Fixed sort tuple | filterHash inputs |
|---|---|---|
| `GET /stores/nearby` | `(distanceMicrometers ASC, storeId ASC)` | canonical latitude/longitude/radius와 endpoint |
| `GET /point-accounts/{accountId}/transactions` | `(occurredAt DESC, transactionId DESC)` | account ID와 endpoint |
| `GET /operations/point-accounts/{accountId}/transactions` | `(occurredAt DESC, transactionId DESC)` | account ID와 Operations endpoint |
| `GET /stores/{storeId}/settlements` | `(settlementDate DESC, settlementBatchId DESC)` | store ID와 endpoint |
| `GET /stores/{storeId}/settlements/{settlementBatchId}/items` | `(completedAt ASC, settlementItemId ASC)` | store ID, Batch ID와 endpoint |
| `GET /payment-methods` | `(isDefault DESC, createdAt DESC, paymentMethodId DESC)` | authenticated customer ID와 endpoint |
| `GET /me/orders` | `(createdAt DESC, orderId DESC)` | endpoint, authenticated customer ID, `ALL\|ACTIVE\|PAST`, 서울 날짜 `from`/`to` |
| `GET /support/cases` | `(openedAt DESC, caseId DESC)` | endpoint, optional state와 optional assignee ID |
| `GET /support/cases/{caseId}/timeline` | `(occurredAt DESC, sourceRank ASC, itemId DESC)` | endpoint, Case ID, sorted distinct source/type filters |
| `GET /support/orders/{orderId}/timeline` | `(occurredAt DESC, sourceRank ASC, itemId DESC)` | endpoint, Case ID, Order ID, sorted distinct source/type filters |
| `GET /stores/search` (`sort=relevance`) | `(relevanceRank ASC, distanceMicrometers ASC, storeId ASC)` | endpoint, 정규화 토큰 배열, `sort`, `pickupAvailable`, `openOnly`, canonical latitude/longitude/radius |
| `GET /stores/search` (`sort=distance`) | `(distanceMicrometers ASC, storeId ASC)` | 위와 동일 |
| `GET /operations/brands` | `(normalizedName ASC, brandId ASC)` | endpoint |
| `GET /regions` | `(fullName ASC, code ASC)` | endpoint와 정규화 질의어 |

새 cursor endpoint는 sort tuple과 canonical filter list를 ADR-070 amendment 또는 새 pagination ADR에
추가한 뒤 같은 codec을 사용한다. endpoint마다 별도 unsigned codec, pagination store 또는 arbitrary
base64 parsing을 만들지 않는다.

2026-08-13 Plan 50 amendment: 고객 주문 목록의 endpoint identifier는 `customer-orders`, expiry는
24시간이다. typed sort는 `createdAt` UTC ISO-8601 Instant와 lowercase canonical UUID `orderId`를
순서대로 사용한다. filter hash canonical form은 property 순서를 `endpoint`, `customerId`, `status`,
`from`, `to`로 고정한 JSON이다. `status` 생략은 `ALL`, 지정 값은 `ACTIVE|PAST`이고 `from`/`to`는
기본값을 적용한 `Asia/Seoul` ISO 날짜다. 따라서 기본 기간과 명시적으로 같은 기간은 같은 scope이고,
고객·상태·기간을 바꾼 cursor는 400이다. limit은 cursor에 넣지 않는 공통 규칙을 유지한다.

2026-08-09 PaymentMethod amendment: PaymentMethod typed cursor는 `isDefault`를 `1|0`,
`createdAt`을 UTC ISO-8601 Instant, ID를 lowercase canonical UUID로 고정해 세 값을 string array로
인코딩한다. endpoint identifier는 `payment-methods`, filter hash는 endpoint와 authenticated
customer ID의 canonical UUID로 계산한다. 매 요청 customer 인가를 다시 수행하며 다른 customer의
cursor는 400이다. default 또는 lifecycle 상태가 page 요청 사이 바뀌면 snapshot consistency를
보장하지 않으므로 client는 최신 first page를 다시 읽는다.

2026-08-11 S20 amendment: SupportCase list typed cursor는 `openedAt` UTC ISO-8601 Instant와 Case ID
lowercase canonical UUID를 string array로 인코딩한다. endpoint identifier는 `support-cases`, filter hash는
endpoint와 optional state/assignee ID의 canonical value로 계산하고, expiry는 15분이다. interaction/note는
Case Aggregate collection에 올리지 않으며 cursor response에도 포함하지 않는다.

2026-08-12 S50 amendment: Support timeline cursor는 `occurredAt` UTC ISO-8601 Instant, 두 자리 zero-padded
decimal `sourceRank`, lowercase canonical UUID `itemId`를 이 순서의 string array로 인코딩한다. Case endpoint
identifier는 `support-case-timeline`, Order endpoint identifier는 `support-order-timeline`이며 expiry는 모두
15분이다. filter hash canonical form은 property 순서가 고정된 JSON으로 endpoint, lowercase Case ID,
Order endpoint에만 lowercase Order ID, alphabetical order의 중복 없는 source/type 배열을 포함한다. 빈 filter
배열은 all-source/all-type을 뜻하고 생략과 같은 canonical value를 사용한다. page limit은 common default 20,
maximum 100이다. Source rank는 공개 contract의 closed vocabulary에 고정되며 새 source가 추가돼도 기존 rank를
재배치하지 않는다. 매 page에서 Case scope, active Order link, persistent permission을 다시 확인한다.

2026-08-15 Plan 70 amendment: 매장 통합 검색 cursor는
[ADR-103](ADR-103-store-search-strategy.md)의 2026-08-15 Amendment를 따른다. endpoint identifier는
`sort` 값에 따라 `stores-search-relevance`와 `stores-search-distance`로 분리하고, `sort` 자체도
filter hash에 포함한다. 같은 검색어라도 정렬을 바꾸면 이전 cursor가 400이 되어야 하기 때문이다.

`relevanceRank`는 `1_000_000 - floor(relevance * 1_000_000)`로 계산한 `0..1_000_000` 정수이며
zero-padded 없는 decimal string으로 인코딩한다. 관련도를 부동소수 그대로 인코딩하면 page 경계에서
동점 판정이 흔들려 누락·중복이 생기므로, nearby의 `distanceMicrometers`와 같은 정수 양자화를
사용하고 전체 tuple을 all-ASC로 맞춘다. 좌표가 없는 `relevance` 검색의 `distanceMicrometers`
항은 상수 `0`이다.

filter hash canonical form은 property 순서가 고정된 JSON으로 endpoint, alphabetical 정렬 없이
**입력 순서를 유지한** 정규화 토큰 배열, `sort`, `pickupAvailable`, `openOnly`,
좌표가 있을 때만 canonical latitude/longitude/radiusMeters를 포함한다. 토큰 순서를 유지하는 이유는
동일 토큰 집합의 다른 순서가 같은 결과 집합을 만들더라도 순서를 정규화하는 추가 규칙이 필요 없기
때문이다. raw 검색어 원문과 raw 좌표 text는 payload에 넣지 않는다. expiry는 24시간, page limit은
common default 20에 Discovery maximum 50이다.

`GET /operations/brands`는 endpoint identifier `operations-brands`, `GET /regions`는 `regions`를
사용하고 expiry는 모두 24시간이다.

2026-08-03 implementation evidence: Settlement Batch 목록은 active OWNER membership 확인 뒤
`CALCULATED`/`CONFIRMED` summary만 `(settlementDate DESC, settlementBatchId DESC)`로 반환하고
`OPEN` summary를 0으로 만들지 않는다. default 20/maximum 100, 15분 expiry, endpoint+store filter
hash를 사용하며 다른 store cursor, malformed/expired/signature mismatch와 staff/revoked membership을
통합 테스트에서 거부했다. Plan 20 Item endpoint의 store+Batch scope와 오름차순 cursor 계약도
그대로 유지했다.

### Nearby distance와 filter canonicalization

`GET /stores/nearby`의 `radiusMeters`는 integer `1..10000`이다. DB range predicate는 raw
PostGIS geography distance로 `ST_DWithin(location, queryPoint, radiusMeters)`를 사용한다.
pagination sort/cursor predicate는 같은 query projection에서 만든
`distanceMicrometers = floor(ST_Distance(location, queryPoint) * 1_000_000)`와 `storeId`를
쓴다. response `distanceMeters`는 `floor(distanceMicrometers / 1_000_000)`이다. 따라서 response
표시값을 cursor tuple로 재사용하거나 raw double과 rounded integer를 섞지 않는다.

latitude/longitude는 request binder가 finite `BigDecimal`로 파싱한 뒤, `stripTrailingZeros()`의
`toPlainString()`으로 canonicalize하고 signed zero는 `"0"`으로 바꾼다. filter hash input은 key-order가
고정된 canonical JSON의 `endpoint`, canonical latitude, canonical longitude와 integer radius다.
따라서 `37.5`와 `37.5000`은 같은 filter hash를 만들며 raw coordinate text는 token, log, trace or
metric에 남지 않는다.

## Alternatives Considered

### 암호화된 stateless cursor

payload confidentiality에는 유리하지만 MVP가 보호해야 하는 요구는 tamper/scoping 방지이며, key
rotation과 diagnostics 복잡도가 더 크다. Raw coordinate를 payload에서 제외하면 HMAC suffices.

### 서버 저장형 cursor ID

payload 비공개와 revoke는 쉬우나 DB lifecycle, cleanup, affinity와 page-state failure path가 새로
필요하다. 현재 stable keyset query에 불필요하다.

### endpoint별 ad hoc encoding과 unbounded limit

구현은 빠르지만 서로 다른 scope 검증, key failure와 DB load 정책을 만들고 common API contract를
지킬 수 없다.

## Rationale

HMAC은 stateless keyset pagination의 낮은 운영 비용으로 tuple·scope 위변조를 막는다. endpoint와
canonical filter hash를 signature 대상에 넣으면 다른 radius, account, store 또는 Batch에서 cursor를
재사용할 수 없다. bounded limit과 finite cursor lifetime은 key rotation 및 request resource usage를
예측 가능하게 만든다.

## Consequences

- public pagination 구현은 common codec/configuration을 사용해야 하며 missing secret은 startup blocker다.
- Nearby, point ledger, Plan 20 Item 및 Settlement lifecycle은 foundation outcome이 없으면 codec/key
  configuration을 자체 구현하지 않는다.
- existing callers가 limit을 생략하면 20개 page를 받으며 100보다 큰 값을 보낼 수 없다.
- cursor invalidity는 retryable server failure가 아니라 client-correctable 400이다. DB/timeouts는 계속
  `503 DEPENDENCY_UNAVAILABLE`으로 구분한다.
- Nearby raw location data는 HMAC payload, logs, traces와 metrics에 추가되지 않는다.

## Verification

- same filter/sort cursor가 stable keyset page를 빠짐·중복 없이 완주한다.
- Nearby의 `1/10000/10001` radius, raw range boundary, micrometer tie/store-ID tie,
  `37.5`/`37.5000` normalization과 response-meter/cursor-tuple 분리를 검증한다.
- radius, account, store, Batch, endpoint, sort tuple, version, key ID 및 signature 변조와 expired token이
  모두 400이며 repository query를 실행하지 않는다.
- canonical payload field order, JSON string-array sort, integer timestamp, lowercase UUID/filter hash,
  padding 없는 Base64URL, `now >= expiresAt` boundary와 `2048`자 maximum을 deterministic test vector로
  검증한다.
- previous rotation key의 unexpired cursor는 검증되고 제거 뒤에는 400이다.
- required key configuration의 missing/malformed/duplicate/short-secret/unknown-active-key cases가 startup
  failure다. production/local runtime configuration에 fallback secret이나 test-vector key를 넣으면 안 된다.
- omitted/1/100 limit은 허용되고 0/101은 400이다.
- cursor가 raw coordinate, secret 또는 Authorization value를 포함하지 않으며 logs/metric tags에도 남지
  않는다.

**Plan 20 endpoint evidence (2026-08-03):** Settlement Batch Item 조회가 common codec의
endpoint/filter binding, 15분 expiry와 `(completedAt ASC, itemId ASC)` tuple adapter를 사용한다.
default/max limit, tamper/expiry, 다른 store/Batch scope 재사용과 authorization 재검증 통합 테스트가
통과했으며 별도 key/secret fallback은 추가하지 않았다.

**Plan 14 endpoint evidence (2026-08-06):** PointAccount ledger는 common codec의 24시간 expiry와
`point-account-transactions` endpoint/account SHA-256 scope를 사용한다. typed adapter는
`(occurredAt, transactionId)`를 UTC `Instant`와 lowercase canonical UUID로 검증하고 JDBC keyset
parameter로 bind한다. PostgreSQL HTTP tests는 default 20, limit 1, tamper와 다른 account cursor의 400을
검증했다. endpoint는 별도 cursor secret, unsigned/base64 fallback 또는 cursor store를 추가하지 않았다.

**Nearby endpoint evidence (2026-08-06):** `GET /stores/nearby`는 common codec의 24시간 expiry와
`stores-nearby` endpoint scope를 사용한다. filter hash는 key 순서가 고정된 canonical JSON
(`endpoint`, canonical latitude, canonical longitude, integer `radiusMeters`)의 SHA-256이며 raw
coordinate text는 token에 없다. `37.5`와 `37.5000`, `0`/`-0`/`0.0`/`0.000`이 같은 hash를 만들고
radius 또는 좌표를 바꾸면 hash가 달라짐을 단위 테스트가 고정한다. typed adapter는
`(distanceMicrometers, storeId)`를 unsigned decimal과 lowercase canonical UUID로 round-trip
검증하고 negative, leading-zero, uppercase UUID, arity 불일치를 거부한다. DB range predicate는 raw
`ST_DWithin`, sort/cursor predicate는 `floor(ST_Distance * 1_000_000)`, 응답은
`floor(distanceMicrometers / 1_000_000)`이라 표시값과 keyset 값이 분리된다. PostgreSQL 통합
테스트가 default 20, limit 1/100, `0/101` 400, 같은 거리 store-ID tie의 3-page 완주, 다른 radius
및 다른 좌표 cursor, 다른 endpoint scope cursor, unknown key ID, signature 변조, 만료 token,
2049자 token과 빈 cursor의 400을 검증했다. limit과 좌표 검증은 spatial query 이전에 실행된다.
endpoint는 별도 cursor secret, unsigned/base64 fallback 또는 cursor store를 추가하지 않았다.
PostgreSQL은 `uuid`를 bytewise로 정렬하므로 tie-break 순서는 canonical hex 문자열 순서다.

## Metrics

- `beanflow.pagination.cursor.validation.count{endpoint,outcome}`
- `beanflow.pagination.page.size{endpoint}`

endpoint와 closed outcome만 tag로 사용한다. cursor, key ID, filter hash, account/store/Batch ID와
coordinate는 tag로 사용하지 않는다.

## Revisit Conditions

cursor payload confidentiality, user-specific page snapshot consistency, result set pinning 또는 24시간보다
긴 resume window가 필요해져 server-side cursor state나 encrypted token이 정당화될 때 재검토한다.

## Related Decisions

- BR-28
- [ADR-020](ADR-020-nearby-location-privacy.md)
- [ADR-062](ADR-062-settlement-batch-item-discovery.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
