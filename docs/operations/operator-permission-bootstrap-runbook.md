# Operator Permission Offline Bootstrap Runbook

## Scope

이 절차는 HTTP API가 아닌 controlled deployment job에서 explicit operator permission을
grant, revoke 또는 regrant한다. default grant, 직접 SQL DML, application JWT,
`PLATFORM_OPERATOR` role과 static bootstrap secret은 대체 경로가 아니다.

## Preconditions

- Flyway `V39`가 성공적으로 적용되어 있어야 한다. V39는 기존 9개와 Support/Operations/Privacy 33개를
  합친 42개 closed permission vocabulary를 DB 제약에 등록한다.
- deployment job의 단기 OIDC token과 trusted public JWKS를 서로 다른 read-only regular file로
  mount한다. symlink, 빈 파일, writable file은 거부된다.
- issuer, audience, allowed subject와 signed deployment-run claim 이름을 배포 설정에서 고정한다.
- target actor UUID, closed permission, action, correlation ID, non-blank reason과 evidence reference를
  승인된 변경 기록에서 준비한다. evidence body 자체는 command에 넣지 않는다.
- 표준 Spring datasource 설정은 대상 PostgreSQL을 가리켜야 한다. 운영 애플리케이션 HTTP server는
  이 command에서 시작하지 않는다.

## Inputs and execution

배포 job이 아래 환경 변수를 주입한 뒤 repository artifact에서 command를 실행한다.

```text
BEANFLOW_OPERATOR_BOOTSTRAP_ACTION=GRANT|REVOKE|REGRANT
BEANFLOW_OPERATOR_BOOTSTRAP_ACTOR_ID=<operator UUID>
BEANFLOW_OPERATOR_BOOTSTRAP_PERMISSION=<closed OperatorPermission enum value>
BEANFLOW_OPERATOR_BOOTSTRAP_REASON=<approved change reason>
BEANFLOW_OPERATOR_BOOTSTRAP_EVIDENCE_REFERENCE=<immutable evidence reference>
BEANFLOW_OPERATOR_BOOTSTRAP_CORRELATION_ID=<deployment correlation ID>
BEANFLOW_OPERATOR_BOOTSTRAP_TOKEN_FILE=<read-only token file>
BEANFLOW_OPERATOR_BOOTSTRAP_JWK_SET_FILE=<read-only public JWKS file>
BEANFLOW_OPERATOR_BOOTSTRAP_ISSUER=<exact issuer>
BEANFLOW_OPERATOR_BOOTSTRAP_AUDIENCE=<required audience>
BEANFLOW_OPERATOR_BOOTSTRAP_ALLOWED_SUBJECTS=<comma-separated exact subjects>
BEANFLOW_OPERATOR_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM=<signed run-reference claim name>
```

```bash
./gradlew operator-permission-bootstrap
```

token은 command argument나 일반 environment variable로 전달하지 않는다. raw token, reason과
evidence body를 shell trace, job annotation 또는 log collector에 복제하지 않는다. command는 입력
reason을 보존하지 않고 Audit에 고정된 lifecycle reason만 기록하며, evidence reference와 verified
release-principal whitelist projection만 Audit에 연결한다.

Support-prefixed permission은 owning Support use case나 endpoint를 자동 활성화하지 않는다. 현재 존재하지
않는 capability를 role/default grant로 보완하지 않으며, approved deployment record가 지시한 exact permission만
변경한다.

## Terminal results

| Result | Exit | Meaning |
|---|---:|---|
| `APPLIED` | 0 | grant state/version과 Audit이 같은 transaction으로 commit됨 |
| `INVALID_INPUT` | 2 | action, UUID, permission, reason, evidence reference 또는 correlation contract 위반 |
| `IDENTITY_VERIFICATION_FAILED` | 3 | token/JWKS file, signature, issuer, audience, subject, `exp`, `nbf` 또는 run claim 검증 실패 |
| `GRANT_STATE_CONFLICT` | 4 | GRANT 대상이 absent가 아니거나 REVOKE/REGRANT의 현재 state가 맞지 않음 |
| `DEPENDENCY_UNAVAILABLE` | 5 | DB, lock, grant persistence 또는 Audit commit 실패 |

`APPLIED` 외 결과를 성공으로 간주하지 않는다. 실패 뒤 direct SQL repair나 role-only endpoint
activation을 하지 않고 원인을 해결한 새 deployment run identity와 승인 기록으로 다시 실행한다.

## Read-only verification

민감 입력을 조회하지 않고 actor, permission, state/version과 Audit source 결합만 확인한다.

```sql
SELECT g.actor_id, g.permission, g.state, g.version,
       g.granted_at, g.revoked_at, g.audit_source_reference,
       a.action, a.occurred_at, a.correlation_id
FROM operations_operator_permission_grant g
LEFT JOIN operations_audit_record a
  ON a.source_reference = g.audit_source_reference
WHERE g.actor_id = :actor_id
  AND g.permission = :permission;
```

Audit가 없거나 source가 맞지 않으면 endpoint를 활성화하지 않는다. grant row 또는 Audit를 직접
수정하지 않는다.

## Rotation, revocation and observability

- workload token은 짧은 수명으로 매 실행 새로 발급한다. JWKS rotation은 새 public key file을
  read-only mount하고 서명 key와 함께 원자적으로 배포한다.
- allowed subject, issuer 또는 audience 변경은 trust policy 검토 뒤 배포 설정으로 반영한다.
- operator 접근 제거는 `REVOKE`, 다시 부여는 `REGRANT`를 사용한다. row 삭제와 새 GRANT로 이력을
  끊지 않는다.
- process terminal result와 다음 closed-tag metric을 감시한다.
  `beanflow.operations.permission.check.count{permission,outcome}`,
  `beanflow.operations.permission.grant.revoke.count{permission,outcome}`,
  `beanflow.operations.permission.bootstrap.count{action,outcome}`.
- actor ID, token/file path, reason, evidence와 correlation ID를 metric tag에 넣지 않는다.
