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

## Metrics

Category/policy별 candidate/deletion/retry/backlog/hold overdue; identifiers/PII label 금지.

## Revisit Conditions

법률 검토, 처리 규모·민감정보 범위, backup/KMS architecture 변경.

## Related Decisions

ADR-022, ADR-069.
