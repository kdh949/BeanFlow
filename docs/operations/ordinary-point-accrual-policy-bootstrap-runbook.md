# Ordinary Point Accrual Policy Initial Bootstrap Runbook

## Scope

이 절차는 V16 적용 뒤 정상 HTTP application을 처음 시작하기 전에 GLOBAL 일반 포인트 적립
정책 version/head/Audit를 한 번만 생성한다. controlled deployment job의 verified OIDC workload
identity만 사용할 수 있다. migration seed, 직접 SQL DML, application JWT, 운영자 role, static
bootstrap secret과 임의 기본 정책은 대체 경로가 아니다.

최초 bootstrap 이후 정책 변경은 운영자 API의 append-only version write를 사용한다. 이 command를
정책 수정이나 복구 도구로 재사용하지 않는다.

## Release sequence

1. 정상 HTTP application replica를 시작하지 않은 상태에서 대상 PostgreSQL과 backup/rollback
   절차를 확인한다.
2. deployment job이 사용하는 artifact가 V16과 `ordinary-accrual-policy-bootstrap` task를 포함하는지
   확인한다. command가 시작되면 같은 datasource에 Flyway migration을 먼저 적용한다.
3. 아래 정책 값과 승인 증적을 검토한다. 정책 값은 bootstrap 뒤 생성되는 주문에만 적용되고,
   이후 변경은 append-only version으로 수행한다.
4. 단기 OIDC token과 trusted public JWKS를 서로 다른 read-only regular file로 mount한다.
5. 환경 변수를 주입해 bootstrap을 한 번 실행하고 `APPLIED`와 exit 0을 확인한다.
6. read-only SQL로 GLOBAL head, immutable version과 Audit source 결합을 검증한다.
7. 그 뒤에만 정상 HTTP application을 기동한다. 정상 application의 startup precheck가 complete
   GLOBAL head를 정확히 하나 확인하지 못하면 배포는 실패해야 한다.

동시에 둘 이상의 bootstrap job을 실행하지 않는다. DB advisory lock이 중복 생성을 막지만
`POLICY_ALREADY_INITIALIZED`는 성공이나 replay가 아니다.

## Inputs and execution

다음 환경 변수를 deployment job에서 주입한다.

```text
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_RATE_BPS=<0..10000>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ROUNDING_MODE=FLOOR|HALF_UP
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_TYPE=PLATFORM|BRAND|STORE
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_REFERENCE=<trimmed literal, 1..240 chars>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EXPIRY_RULE=EXACT_DURATION_FROM_COMPLETION|SEOUL_CALENDAR_DAYS_FROM_COMPLETION
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_VALIDITY_DAYS=<1..3650>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_REASON=<approved policy reason, 1..500 chars>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EVIDENCE_REFERENCE=<immutable evidence reference, 1..500 chars>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_CORRELATION_ID=<deployment correlation ID, 1..160 chars>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_TOKEN_FILE=<read-only token file>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_JWK_SET_FILE=<read-only public JWKS file>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER=<exact OIDC issuer>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_AUDIENCE=<required audience>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ALLOWED_SUBJECTS=<comma-separated exact subjects>
BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM=<signed run-reference claim name>
```

```bash
./gradlew ordinary-accrual-policy-bootstrap
```

token은 command argument나 일반 token environment variable로 전달하지 않는다. raw token, file
content, issuer reference, reason과 evidence body를 shell trace, job annotation 또는 log collector에
복제하지 않는다. `ISSUER_REFERENCE`는 현재 authoritative registry lookup 없이 literal snapshot으로
보존되므로 승인된 값을 정확히 사용한다.

## Terminal results

command는 다음 closed result 한 줄만 stdout 또는 stderr에 기록한다.

```text
operation=INITIALIZE principal=verified-release-principal result=<RESULT>
```

| Result | Exit | Meaning |
|---|---:|---|
| `APPLIED` | 0 | initial GLOBAL version/head/Audit가 같은 transaction으로 commit됨 |
| `INVALID_INPUT` | 2 | 정책 값, reason, evidence 또는 correlation contract 위반 |
| `IDENTITY_VERIFICATION_FAILED` | 3 | token/JWKS file, signature, issuer, audience, subject, `exp`, `nbf` 또는 run claim 검증 실패 |
| `POLICY_ALREADY_INITIALIZED` | 4 | GLOBAL head가 이미 있어 아무것도 변경하지 않음 |
| `DEPENDENCY_UNAVAILABLE` | 5 | DB, advisory lock, version/head 또는 Audit commit 실패 |

`APPLIED` 외 결과를 성공으로 간주하지 않는다. 실패하면 정상 application을 시작하거나 migration
seed, direct SQL, 0 bps 또는 in-memory policy로 대체하지 않는다. 원인을 해결한 새 deployment run
identity와 승인 기록으로 재실행한다. 이미 초기화된 환경에서는 운영자 policy API를 사용한다.

## Read-only verification

```sql
SELECT h.scope_type,
       h.scope_reference,
       h.policy_version_id,
       v.state,
       v.accrual_rate_bps,
       v.rounding_mode,
       v.issuer_type,
       v.issuer_reference,
       v.expiry_rule,
       v.validity_days,
       v.effective_at,
       v.payload_hash,
       v.source_reference,
       a.action,
       a.occurred_at,
       a.correlation_id
FROM operations_point_accrual_policy_head h
JOIN operations_point_accrual_policy_version v
  ON v.policy_version_id = h.policy_version_id
 AND v.scope_type = h.scope_type
 AND v.scope_reference = h.scope_reference
LEFT JOIN operations_audit_record a
  ON a.source_reference = v.source_reference
WHERE h.scope_type = 'GLOBAL'
  AND h.scope_reference = '00000000-0000-0000-0000-000000000000'::uuid;
```

정확히 한 row, `OVERRIDE`, 입력과 같은 complete policy values,
`POINT_ACCRUAL_POLICY_BOOTSTRAPPED` Audit와 동일 source reference를 확인한다. Audit가 없거나
head/version scope가 다르면 정상 application을 활성화하지 않는다. version, head 또는 Audit를 직접
수정하지 않는다.

## Rotation and observability

- workload token은 짧은 수명으로 매 실행 새로 발급한다. JWKS rotation은 새 public key file과
  signing key를 원자적으로 배포한다.
- allowed subject, issuer, audience 또는 signed deployment-run claim 변경은 trust policy 검토 뒤
  배포 설정으로 반영한다.
- terminal result와
  `beanflow.operations.point_accrual_policy.bootstrap.count{outcome}`,
  `beanflow.operations.point_accrual_policy.precheck.count{outcome}`을 감시한다.
- actor, token/file path, 정책 값, reason, evidence와 correlation ID를 metric tag에 넣지 않는다.
