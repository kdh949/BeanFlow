# ADR-070: Versioned HMAC cursor와 pagination 상한

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Nearby discovery plan](../exec-plans/active/nearby-store-discovery.md), [Plan 20](../exec-plans/active/customer-order-cancellation-20-settlement-foundation.md), [Settlement lifecycle plan](../exec-plans/active/settlement-batch-adjustment-and-dispute.md)

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
`limit`은 default `20`, minimum `1`, maximum `100`이다.

### Cursor format and validation

생성 token은 다음 네 부분을 dot으로 연결한다.

```text
v1.<key-id>.<base64url(canonical-json-payload)>.<base64url(hmac-sha-256)>
```

signature input은 `v1.<key-id>.<encoded-payload>`의 UTF-8 bytes다. canonical payload는 다음만
포함한다.

- `endpoint`: 고정 endpoint identifier
- `filterHash`: endpoint path parameter와 query filter를 canonical JSON으로 정렬한 SHA-256 hash
- `sort`: 문서화된 stable sort tuple의 마지막 값
- `issuedAt`, `expiresAt`: epoch second

cursor에는 raw customer coordinate, raw filter value, actor identity, request URI, Authorization data 또는
secret을 넣지 않는다. HMAC token은 암호화 token이 아니므로 caller에게 tuple을 비밀로 약속하지 않으며,
API는 token의 내용을 계약으로 노출하거나 해석 가능하다고 보장하지 않는다. Nearby의 filter hash에는
latitude/longitude/radius의 raw text를 넣지 않는다.

검증 시 endpoint, filterHash, `expiresAt`, token version, active/retired verification key와 HMAC을 모두
확인한다. endpoint/path/filter mismatch, malformed encoding, unknown version/key, invalid signature 또는
expired token은 모두 `400 INVALID_REQUEST`다. 구현은 원인을 response, metric tag 또는 log에 세분화해
노출하지 않는다. object-level authorization은 cursor 검증과 독립적으로 매 요청에 다시 수행한다.

cursor lifetime은 발급 시점부터 최대 24시간이다. page limit은 cursor payload에 포함하지 않으므로
동일 scope cursor로 `1..100` 사이의 다른 limit을 요청할 수 있다. 서버는 requested limit이 없으면 20을
사용하며, 100 초과 또는 1 미만은 query 실행 전에 400으로 거부한다.

### Key source and rotation

- configuration은 `beanflow.pagination.cursor-hmac.active-key-id`와 secret-store가 주입하는
  verification key ring을 요구한다. key ID는 `[A-Za-z0-9_-]{1,32}`이고 key material은 최소 256-bit
  random secret이다.
- active key가 key ring에 없거나, key ID/key material이 malformed·duplicate이거나 key ring이 비어
  있으면 application startup을 실패시킨다. source code, local default, test fixture 또는 fallback key를
  production profile에서 선택하지 않는다.
- rotation은 새 key를 verification ring에 추가한 뒤 active key로 전환하고, 이전 key를 최소 24시간과
  배포 propagation window 동안 verifier로 유지한 뒤 제거한다. retired key로 서명된 유효 token은 그
  기간에만 검증한다.
- key ID, payload, signature과 filter hash는 log/metric tag/AuditRecord에 기록하지 않는다.

### Endpoint bindings

| Endpoint | Fixed sort tuple | filterHash inputs |
|---|---|---|
| `GET /stores/nearby` | `(distanceMeters ASC, storeId ASC)` | latitude/longitude/radius와 endpoint |
| `GET /point-accounts/{accountId}/transactions` | ledger order documented by its endpoint | account ID와 endpoint |
| `GET /stores/{storeId}/settlements` | `(settlementDate DESC, settlementBatchId DESC)` | store ID와 endpoint |
| `GET /stores/{storeId}/settlements/{settlementBatchId}/items` | `(completedAt ASC, settlementItemId ASC)` | store ID, Batch ID와 endpoint |

새 cursor endpoint는 sort tuple과 canonical filter list를 ADR-070 amendment 또는 새 pagination ADR에
추가한 뒤 같은 codec을 사용한다. endpoint마다 별도 unsigned codec, pagination store 또는 arbitrary
base64 parsing을 만들지 않는다.

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
- existing callers가 limit을 생략하면 20개 page를 받으며 100보다 큰 값을 보낼 수 없다.
- cursor invalidity는 retryable server failure가 아니라 client-correctable 400이다. DB/timeouts는 계속
  `503 DEPENDENCY_UNAVAILABLE`으로 구분한다.
- Nearby raw location data는 HMAC payload, logs, traces와 metrics에 추가되지 않는다.

## Verification

- same filter/sort cursor가 stable keyset page를 빠짐·중복 없이 완주한다.
- radius, account, store, Batch, endpoint, sort tuple, version, key ID 및 signature 변조와 expired token이
  모두 400이며 repository query를 실행하지 않는다.
- previous rotation key의 unexpired cursor는 검증되고 제거 뒤에는 400이다.
- required key configuration의 missing/malformed/duplicate cases가 startup failure다.
- omitted/1/100 limit은 허용되고 0/101은 400이다.
- cursor가 raw coordinate, secret 또는 Authorization value를 포함하지 않으며 logs/metric tags에도 남지
  않는다.

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
