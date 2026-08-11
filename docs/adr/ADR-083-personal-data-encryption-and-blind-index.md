# ADR-083: Vault Transit 개인데이터 암호화와 keyed blind index

- **Status:** Accepted
- **Date:** 2026-08-10
- **Accepted:** 2026-08-11

## Context

전화번호와 이메일의 exact search, 그리고 owner Context가 소유하는 최소 고객·매장·외부 courier 프로필에는
원문 개인정보가 필요하다. 평문 DB column/index 또는 Support의 장기 원문 복제는 허용할 수 없다. 검색 가능한
파생값과 복호화 가능한 원문을 분리하면서 provider, 인증 경계, 키 계층, 회전, startup 검증과 장애 동작을
하나의 운영 계약으로 고정해야 한다.

## Decision

### Provider와 인증 경계

Production 개인데이터 암호화와 keyed exact-search index는 HashiCorp Vault Transit을 사용한다. 애플리케이션은
외부 Vault cluster나 Vault token에 직접 접근하지 않는다. 같은 workload의 loopback Vault Proxy가 deployment
identity로 auto-auth하고 `use_auto_auth_token = "force"`로 요청에 token을 주입한다. 애플리케이션 설정에는
loopback Proxy base URI, mount와 key 이름, timeout, 허용 key version만 있고 token/secret은 없다.

개발·테스트 fake는 test source 또는 명시적인 local profile에서만 사용할 수 있다. Production profile은
in-memory/local/mock/no-op crypto나 자동 fallback을 등록하지 않는다. Vault Proxy 미설정, 원격/non-loopback URI,
필수 key 설정 누락 또는 startup 검증 실패는 애플리케이션 시작 실패다.

### Port와 키 분리

공유 application boundary는 provider-neutral `PersonalDataCryptoPort`와 `KeyedBlindIndexPort`를 노출한다.
구현은 두 개의 서로 다른 Transit keyring을 사용한다.

- `personal-data-encryption`: `aes256-gcm96`, non-derived, non-convergent, non-exportable, deletion disabled
- `personal-data-exact-index`: `hmac`, non-exportable, deletion disabled, HMAC-SHA-256 전용

실제 key 이름은 필수 configuration으로 주입하며 위 논리 이름을 문서와 검사에 사용한다. 두 설정이 같은
Vault key를 가리키면 startup을 실패시킨다. 애플리케이션, DB와 로그에는 plaintext key, Vault token, DEK 또는
키 material을 저장하지 않는다.

### 원문 암호화

Owner Context만 정규화 전 원문을 Transit `encrypt`로 암호화하고 ciphertext를 저장한다. 각 암호문에는 다음
metadata를 별도 column으로 둔다.

- Transit key version (`vault:vN:` prefix와 일치해야 함)
- AAD schema version
- 암호화된 field의 logical type

AES-GCM associated data는 `beanflow-personal-data:v1`, owner Context, subject UUID와 field type을 길이 구분된
canonical bytes로 결합한다. 이 값은 secret이 아니지만 ciphertext를 다른 owner/subject/field row로 바꾸는
공격을 인증 실패로 만든다. Search 경로는 원문을 복호화하지 않으며 owner가 미리 계산해 보관한 masked
derivative만 반환한다.

### Keyed exact-search index

전화번호와 이메일은 고정된 normalization 뒤
`beanflow-exact-search:v1 || criterion-type || normalized-value` 형식의 길이 구분된 canonical bytes를 Transit
HMAC-SHA-256에 전달한다. Owner Context는 digest와 index key version만 저장한다. 원문 암호문과 blind index는
서로 다른 table/column 및 keyring을 사용한다.

Owner별 index table은 `(subject_id, criterion_type, index_key_version)` 한 행을 가지며
`(criterion_type, index_key_version, blind_index, subject_id)` B-tree index로 bounded exact lookup을 지원한다.
Support는 raw criterion을 POST body에서만 받고 필요한 active search key version들의 HMAC을 DB transaction 밖에서
계산한다. Owner public query API는 digest로 owner table을 조회하고 masked DTO만 반환한다. Support는 owner
Repository/Entity/table을 직접 읽거나 원문·digest를 저장하지 않는다.

### Rotation과 backfill

암호화 keyring은 초기 운영값으로 90일 `auto_rotate_period`를 사용하며 보안 사고나 Vault의 key 사용 한계 전에
수동 회전할 수 있다. 이전 version은 decrypt 가능하게 유지하고 owner-local maintenance가 Transit `rewrap`으로
plaintext를 애플리케이션에 반환하지 않은 채 최신 version으로 이동한다. coverage가 확인되기 전에 이전
decryption version을 폐기하거나 `min_decryption_version`을 올리지 않는다.

Blind-index keyring은 자동 회전하지 않는다. 회전은 다음 순서의 versioned rollout으로 수행한다.

1. Vault key를 rotate하고 새 write version을 기존 search version 목록에 추가한다.
2. 신규/변경 profile은 새 version index를 쓰되 검색은 old/new version을 모두 계산한다.
3. owner-local bounded backfill이 새 version index를 생성하고 version별 coverage·오류를 PII-free metric으로 남긴다.
4. PostgreSQL에서 누락 0과 duplicate/collision 검사를 확인한 뒤 old write를 중단한다.
5. 관찰 기간 후 old search version을 제거한다. old key material 폐기와 minimum version 증가는 별도 승인 작업이다.

회전 중 partial index 생성은 성공으로 간주하지 않는다. 재시도 가능한 row 상태/coverage로 남기며 검색은
구성된 모든 active version을 사용한다.

### Startup와 runtime failure

Production startup은 Vault Proxy를 통해 두 Transit key metadata를 읽고 서로 다른 key 이름, type, derived,
convergent, exportable, deletion 허용, latest/minimum version과 configured active version을 검증한다. timeout,
permission denial, sealed/uninitialized/disconnected Vault, key 부재, malformed response 또는 정책 불일치는 startup
실패다.

Runtime Vault timeout/5xx/permission/key-version/response 오류는 `DEPENDENCY_UNAVAILABLE`과 HTTP 503으로
매핑한다. raw criterion, ciphertext, digest, key name/version 조합 또는 Vault 응답 body는 error/log/metric/Audit에
넣지 않는다. local HMAC, plaintext scan, cached/stale result 또는 empty 200 fallback은 없다. 현재 deployment는
single-region fail-closed를 기본으로 하며 암묵적 cross-region failover를 하지 않는다. Multi-region이 필요하면
Vault Enterprise/HCP Performance/DR replication, data residency와 failover authority를 새 ADR로 정한다.

## Alternatives Considered

- 평문 + DB access control: DB 유출 시 원문과 검색 index가 즉시 노출돼 기각했다.
- DB-side `pgcrypto`: 애플리케이션 DB credential과 key 관리 경계가 결합되고 provider outage/startup 검증 계약이
  약해져 기각했다.
- application-local envelope encryption + cloud KMS: provider-neutral하지만 DEK cache/rotation/region별 KMS
  adapter를 S30에서 새로 운영해야 하므로 선택하지 않았다.
- deterministic encryption search: 원문 암호화와 equality search가 같은 key/primitive에 결합되고 rotation
  backfill이 불명확해 기각했다.
- Elasticsearch encrypted index: exact-only 요구에 불필요한 복제·운영·freshness 실패 모델을 추가해 기각했다.
- 앱의 Vault token file 사용: token 노출 surface와 renewal 책임을 앱에 추가하므로 Vault Proxy 강제 주입보다
  열등해 기각했다.

## Rationale

Vault Transit은 versioned keyring, AEAD encrypt/decrypt, HMAC, rotate와 plaintext를 반환하지 않는 rewrap을 한
provider 경계에서 제공한다. 별도 keyring과 별도 Port는 원문 복호화 권한과 equality-search 권한을 분리한다.
Vault Proxy는 workload 인증과 token renewal을 애플리케이션 코드 밖에 두면서도 실패를 명시적으로 유지한다.

## Consequences

- Vault cluster와 Vault Proxy는 production 필수 의존성이며 가용성, policy, backup/restore와 rotation runbook이
  필요하다. 제거 시 ciphertext/index 재암호화 migration 비용이 발생한다.
- exact blind index는 동일 normalized input의 equality와 빈도를 누설한다. bounded authorization, rate limit,
  PII-free search Audit와 DB 접근 통제가 필요하며 fuzzy/prefix search에는 재사용하지 않는다.
- masked derivative는 owner Context에 보관하므로 mask 정책 변경 시 owner-local 재계산이 필요하다.
- S30은 이 Accepted 결정과 completed S20을 direct input으로 detailed ExecPlan을 작성할 수 있다.

## Verification

- phone/email normalization vector와 criterion domain-separation
- 동일 input/version exact match, 다른 type/version mismatch, dual-version rotation/backfill과 collision handling
- encrypt/decrypt AAD binding, ciphertext/key-version metadata 일치와 rewrap metadata
- Support response, log, metric, exception, Audit와 snapshot의 raw PII/digest/ciphertext 부재
- production missing/malformed config, 동일 key 이름, 잘못된 key type/policy, unreachable/sealed Vault startup 실패
- runtime Vault failure 503, Audit failure no response, no empty/local/cache fallback
- PostgreSQL Testcontainers index constraint와 comparable `EXPLAIN (ANALYZE, BUFFERS)` fixture

## Metrics

허용 label만으로 operation, outcome, owner Context, key-version coverage와 latency histogram을 기록한다. key name,
subject ID, raw/normalized value, ciphertext, digest와 search result는 metric label에 넣지 않는다.

## Revisit Conditions

- multi-region 또는 legal residency 요구
- Vault Enterprise/HCP replication이나 auth method 변경
- measured exact-search load가 현재 bounded PostgreSQL lookup을 초과
- normalization/masking policy 또는 지원 criterion type 변경
- 암호화 key 자동 회전 주기 변경, key compromise 또는 minimum-version 폐기

## Related Decisions

ADR-009, ADR-020, ADR-070, ADR-072, ADR-081, ADR-082, ADR-087, ADR-089.

## Provider References

- [Vault Transit API](https://developer.hashicorp.com/vault/api-docs/secret/transit)
- [Vault Transit secrets engine](https://developer.hashicorp.com/vault/docs/secrets/transit)
- [Vault Proxy auto-auth](https://developer.hashicorp.com/vault/docs/agent-and-proxy/autoauth)
- [Vault Proxy API proxy](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent/apiproxy)
- [Vault health API](https://developer.hashicorp.com/vault/api-docs/system/health)
