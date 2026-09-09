# Sophos 뒤 개인 서버용 포트폴리오 배포 스택을 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-02`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신한다.

## Purpose / Big Picture

Sophos WAF 뒤의 개인 서버에서 BeanFlow를 staging과 portfolio production-like 환경으로 반복 배포한다.
백엔드·프론트 이미지를 만들고, 영구 PostGIS와 Keycloak을 Compose로 실행하며, 외부 AIStor와 Vault를
저장소 밖 secret으로 연결한다. 한 개의 DMZ Nginx port만 공개하고 image tag 교체로 애플리케이션을
롤백할 수 있어야 한다.

## Current State

- `docker-compose.demo.yml`은 PostGIS를 tmpfs에 두는 local demo 전용이다.
- backend/frontend Dockerfile, Nginx 설정, staging/prod Compose와 image version 계약이 없다.
- application env placeholder는 있으나 secret 파일 주입과 일반 `.env`/key 차단 규칙이 없다.
- `prod`는 실제 Provider가 없어 포트폴리오 목적의 sandbox 배포로 사용할 수 없다.
- Vault Transit startup validation은 `prod`에서만 실행된다.

## Definitions

- **portfolio:** 외부에서 볼 수 있지만 실제 자금을 다루지 않는 sandbox 시연 runtime.
- **deployment environment:** staging 또는 production-like Compose project. 둘 다 `portfolio` profile을
  사용하고 데이터 volume과 env/secret 디렉터리를 분리한다.
- **external infrastructure:** 이 저장소가 수명주기와 root credential을 소유하지 않는 Vault와 AIStor.
- **application rollback:** migration downgrade 없이 이전 backend/frontend image tag를 다시 실행하는 것.

## Scope

### In Scope

- ADR-119, `portfolio`/`vault-enforced` profile과 startup tests
- backend multi-stage image와 loopback Vault Proxy
- frontend static image, SPA fallback, `/api`·`/auth` reverse proxy와 안전한 log
- staging/prod Compose, persistent PostGIS, Keycloak realm, healthcheck와 명시적 publish address
- config tree secret 계약, `.gitignore`/`.dockerignore`, secret 생성/검증 안내
- Vault Transit/AppRole policy/bootstrap 자료
- deploy preflight, image build, Compose render와 rollback runbook

### Non-goals

- 실제 PG·알림·본인확인·결제수단 Provider
- PostgreSQL/AIStor 백업·복구 자동화와 법률 검토
- Prometheus/Grafana/OTLP, alert, SLO와 부하 측정
- AIStor license, Vault unseal/root credential과 Sophos rule 자동 구성
- Kubernetes, 무중단 배포와 다중 호스트 HA

## Business Rules and Invariants

- Toss는 test client/secret key만 허용하며 상용 운영으로 표시하지 않는다.
- `prod` guard를 약화하거나 `portfolio`에서 live key를 허용하지 않는다.
- Vault Transit validation 실패는 startup failure다.
- secret 값, key, token과 실제 host/domain 값은 tracked 파일에 넣지 않는다.
- frontend 외 service port는 host에 publish하지 않는다.
- DB는 tmpfs가 아니라 environment별 named volume을 사용한다.
- container health와 Compose render 실패를 배포 성공으로 간주하지 않는다.

## Architecture and Transaction Boundaries

도메인, Aggregate, API, event와 DB transaction을 변경하지 않는다. 외부 Provider 호출의 기존
transaction 분리도 그대로다. Compose의 PostgreSQL은 Flyway migration과 Spring Session을 같은 DB에,
Keycloak은 별도 DB/user에 둔다. Vault Proxy는 backend 컨테이너 안의 loopback listener로 실행해
애플리케이션이 credential을 직접 보유하지 않게 한다.

## Alternatives Considered

ADR-119의 네 대안을 따른다. Compose 전체 복제와 공통 base+override도 비교한다. 환경 drift를 줄이기
위해 공통 service 정의를 두고 staging/prod 파일은 environment identity와 volume/network만 고정한다.

## Failure Semantics

- 필수 env/secret/image tag가 없으면 Compose interpolation 또는 preflight가 실패한다.
- Vault Proxy, Vault Transit, PostGIS, Keycloak 또는 AIStor credential/bucket이 잘못되면 해당 service나
  API startup이 실패한다. fake/no-op fallback은 없다.
- Toss timeout/불명 응답은 기존 `UNKNOWN`/reconciliation 계약을 유지한다.
- rollback은 이전 image를 실행할 뿐 schema를 되돌리지 않는다. migration 실패 시 새 API를 traffic에
  연결하지 않는다.

## Data and Migration

새 Flyway migration은 없다. PostgreSQL과 Keycloak named volume만 추가한다. 자동 backup/restore는
범위 밖이며 문서에서 미구현으로 표시한다.

## API and Event Contracts

공개 API와 event 계약은 변경하지 않는다. Nginx가 same-origin `/api`와 Keycloak `/auth/realms` 경로를
전달한다.

## Milestones

1. ADR, profile/Vault enforcement와 secret repository boundary
2. backend image와 Vault Proxy process contract
3. frontend Nginx image와 safe reverse-proxy contract
4. PostGIS/Keycloak/Compose와 env/secret preflight
5. deployment/rollback runbook, 전체 검증과 PR

## Required Tests

- `portfolio` required profile 조합, forbidden overlap와 Vault validator 활성
- tracked secret/key 차단과 example 파일의 placeholder-only 검사
- backend의 root launcher와 별도 non-root JVM/Vault Proxy UID, healthcheck와 pinned base image 검사
- Nginx `/api`, `/auth/realms`, `/auth/admin` 차단, SPA fallback과 query 없는 log 검사
- staging/prod Compose의 single published port, persistent volumes, secret files, healthchecks, image tag,
  sandbox profile과 private service 검사
- existing provider safety, Vault startup, modularity와 frontend build 회귀

## Validation Commands

- `./gradlew test --tests '*Portfolio*' --tests '*VaultTransitStartupValidationTest' --tests '*ProviderSafety*'`
- `bash scripts/deploy/test-deployment-contract.sh`
- `bash scripts/deploy/verify-deployment.sh staging --env-file <non-secret-env>`
- `bash scripts/deploy/verify-deployment.sh prod --env-file <non-secret-env>`
- `docker build -t beanflow-api:validation .`
- `docker build -f frontend/Dockerfile -t beanflow-web:validation .`
- `npm --prefix frontend run build`
- `npm --prefix frontend run test:sites`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

새 exporter/dashboard/alert/SLO는 추가하지 않는다. 기존 `/actuator/health`와 container health status를
배포 gate로 사용하며 실행하지 않은 부하·지연 수치를 주장하지 않는다.

## Documentation Updates

- `docs/operations/portfolio-deployment-runbook.md`
- `docs/index.md`, README 배포 진입점
- 환경변수·secret 파일 예시와 Vault bootstrap 안내
- 이 ExecPlan의 실제 검증 결과와 미실행 범위

## Progress

- [x] ADR과 Implementation-Ready plan 작성
- [x] profile/Vault enforcement와 secret boundary
- [x] backend image와 Vault Proxy
- [x] frontend Nginx image
- [x] Compose/Keycloak/preflight
- [x] runbook과 로컬 검증
- [x] PR 원격 CI 확인

## Surprises & Discoveries

- 2026-09-02: `prod` profile 자체를 재사용하면 네 종류의 미구현 production Provider guard가 시작을
  거부한다. guard를 완화하지 않고 별도 `portfolio` profile로 격리한다.
- 2026-09-02: Vault adapter는 loopback Proxy URI만 허용한다. Compose service DNS를 허용하도록 보안
  검증을 넓히지 않고 backend 컨테이너 안에 전용 Vault Proxy process를 둔다.
- 2026-09-02: AIStor production 배포는 license와 TLS/KMS 경계를 요구한다. 저장소가 임의 container를
  bootstrap하지 않고 외부 private endpoint와 최소 bucket credential만 받는다.
- 2026-09-02: 공식 `postgis/postgis:17-3.5` image는 ARM64 manifest가 없다. 배포 계약은
  `linux/amd64`를 기본값으로 명시하고 ARM 서버 사용은 별도 검증 대상으로 남긴다.
- 2026-09-02: Keycloak의 management health endpoint도 `KC_HTTP_RELATIVE_PATH=/auth`를 상속한다.
  실제 기동 테스트에서 `/health/ready`의 404를 확인하고 `/auth/health/ready`로 수정했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-09-02 | Accepted | 공개 포트폴리오 배포는 `portfolio` profile과 Toss sandbox를 사용하고 `prod` guard는 보존 | 실제 자금/계약이 없는 시연과 상용 운영 의미를 분리 | ADR-119 |
| 2026-09-02 | Accepted | Vault Proxy를 backend 컨테이너의 loopback process로 실행 | 기존 loopback-only 보안 불변식을 유지하고 AppRole token을 애플리케이션에 노출하지 않음 | ADR-119, 이 plan |
| 2026-09-02 | Accepted | Vault와 AIStor 서버는 외부 private infrastructure로 둠 | unseal/root credential과 AIStor license/TLS/KMS를 앱 배포가 소유하지 않음 | ADR-119 |

## Outcomes & Retrospective

- `portfolio` profile이 Toss sandbox와 scripted notification을 의도적으로 선택하면서 `prod` guard와 Vault
  startup fail-closed 조건은 유지한다.
- backend의 별도 non-root JVM/Vault Proxy UID, frontend non-root image, path-only Nginx access log, 단일
  published port, 영구 PostGIS volume, Keycloak PKCE realm, config-tree secret과 Vault AppRole bootstrap을
  하나의 Compose 계약으로 묶었다.
- staging/prod Compose 계약, backend/frontend image 계약, 실제 PostGIS+Keycloak realm import smoke,
  관련 Spring 안전 테스트 18개, backend build without tests, frontend production build와 site test 4개,
  production npm audit, CI script와 문서 검증이 통과했다.
- 로컬 unsharded 전체 Gradle test는 46분 동안 실패 출력 없이 진행됐지만 완료 전에 중단했다. PR의 공식
  6-way shard와 최종 build gate가 모두 통과한 결과를 완료 근거로 사용했다.
- PR 리뷰에서 확인된 raw Referer, OIDC issuer/audience, Vault AppRole UID·파일 경계, Proxy readiness
  deadline, root 전용 runbook 권한과 trusted proxy allowlist 문제를 각각 회귀 계약과 함께 수정했다.
- Sophos, 외부 Vault, AIStor와 실제 서버 smoke는 이 checkout에서 실행하지 않았다. runbook의 서버
  preflight와 네 서비스 health가 실제 배포 승인 기준이다.

## Revision Notes

- 2026-09-02: 사용자 확정 범위와 현재 코드/인프라 조사 결과로 최초 작성.
- 2026-09-02: 실제 이미지·identity stack 검증과 PR의 전체 CI 통과 후 완료 상태로 이동.
