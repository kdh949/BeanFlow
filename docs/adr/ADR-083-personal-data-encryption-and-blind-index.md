# ADR-083: 개인데이터 암호화와 keyed blind index

- **Status:** Proposed
- **Date:** 2026-08-10

## Context

exact phone/email search와 owner raw PII가 필요하지만 plaintext index/Support 복제는 허용할 수 없다. Planning baseline은 envelope encryption과 keyed HMAC을 요구하나 KMS, key hierarchy, rotation과 outage behavior의 concrete provider는 선택되지 않았다.

## Decision

Owner Context는 `PersonalDataCryptoPort`와 `KeyedBlindIndexPort`, encrypted value/key version과 normalized keyed HMAC exact index를 사용한다. Support는 plaintext를 장기 복제하지 않는다. Production key configuration이 없거나 유효하지 않으면 startup을 실패시킨다. **Open implementation decision:** KMS/provider, envelope key hierarchy, rotation/backfill, regional outage recovery를 S30 전에 별도 Accepted amendment로 확정한다.

## Alternatives Considered

- plaintext + DB access control: breach impact가 커서 기각.
- deterministic encryption search: equality leakage/key rotation trade-off 때문에 초기 선택하지 않음.
- Elasticsearch encrypted index: exact-only requirement와 운영비에 부적합.

## Rationale

Raw value와 searchable derivative를 분리하고 key rotation을 명시적으로 만든다.

## Consequences

S30은 이 ADR이 Accepted되기 전 ready가 될 수 없다. key outage는 search/reveal 503이며 plaintext/local fallback이 없다.

## Verification

Normalization vectors, index collision/rotation, missing/malformed key startup, ciphertext/log leakage와 old-key migration tests.

## Metrics

Crypto/index latency, key-version coverage와 failure counts; key/PII label 금지.

## Revisit Conditions

Provider 선정, rotation 요구, multi-region 또는 legal residency 변경.

## Related Decisions

ADR-009, ADR-020, ADR-070, ADR-082.
