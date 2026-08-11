# ADR-089: 목적별 retention, expiring LegalHold와 deletion replay

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

현재 Audit는 모두 서울 기준 5년 만료다. 이를 PII Audit 2년으로 일괄 축소하면 financial evidence를 약화시키고, 반대로 Support PII를 5년 유지하면 최소화 원칙을 위반한다.

## Decision

AuditCategory+RetentionClass+immutable PolicyVersion으로 financial transaction 5y, Support content 3y, PII access 2y, delivery contact 90d, current location 24h, raw webhook 7d 등 Initial policy를 분리한다. 법적 최소 record는 active PII와 분리한다. LegalHold는 scoped, distinct approval, next-review와 expiry가 필수다. Deletion은 DB/Crypto/Object/Index/Projection component states와 redacted ledger를 보존하며 backup restore 전에 deletion decisions를 재적용한다.

## Alternatives Considered

- 모든 Audit 5y: PII 최소화 부족.
- 모든 Audit 2y: financial retention 회귀.
- hard delete row only: object/index/backup 재노출과 거래 FK 문제.
- indefinite hold: 목적 제한·review 부재.

## Rationale

법적 최소 기록과 운영 PII를 분리하고 부분 실패/복구를 관측 가능하게 한다.

## Consequences

기간은 Initial policy이며 **Legal review required before production**. 기존 row 재분류는 별도 승인/migration이고 S120 전 backup procedure가 필요하다.

## Verification

Boundary clock, 5y/2y regression, LegalHold race/review/expiry, component partial failure, restore replay와 PII-free ledger tests.

## Implementation Evidence

S10의 Flyway V39는 Operations-owned immutable policy version/head, Audit action/category mapping과 Audit
category/class/version/provenance를 추가했다. 기존 Audit expiry는 불변이며, legacy row는 과거 append snapshot을
가장하지 않도록 `PRESERVE_STORED_EXPIRY` version과 `LEGACY_MIGRATION_CLASSIFICATION`으로 기록한다. 신규
financial 5년과 PII access 2년 snapshot, raw PII value/reason fail-closed validation, category별 due 경계, policy
결함 시 caller transaction rollback, concurrent worker의 `SKIP LOCKED` claim과 persistent permission grant/revoke
직렬화를 PostgreSQL Testcontainers로 검증했다. V39은 old binary의 all-null Audit insert를 fail-closed DB
compatibility trigger로만 일시 지원하는 expand/backfill migration이며, physical `NOT NULL`/constraint validation과
trigger removal은 fleet drain 및 measurement가 기록된 후속 contract migration에서 수행한다.

이 증거는 Audit와 permission foundation에만 해당한다. SupportCase, PII reveal, LegalHold,
owner별 retention port, component deletion ledger와 backup replay는 구현되지 않았으며 기존
**Legal review required before production** 조건도 변경하지 않는다.

## Metrics

Category/policy별 candidate/deletion/retry/backlog/hold overdue; identifiers/PII label 금지.

## Revisit Conditions

법률 검토, 처리 규모·민감정보 범위, backup/KMS architecture 변경.

## Related Decisions

ADR-022, ADR-069.
