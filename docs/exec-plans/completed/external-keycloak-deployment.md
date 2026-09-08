# 외부 Keycloak을 사용하는 staging 배포 지원

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/portfolio-deployment-stack.md`
> **Completed-At:** `2026-09-06`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

기존 외부 Keycloak을 사용하는 배포가 Keycloak DB·관리자 credential 없이 기동할 수 있게 한다.
실제 외부 환경의 로그인 검증과 저장소 구현 검증은 구분한다.

## Current State

portfolio Compose, preflight와 Nginx가 내장 Keycloak을 강제한다. OIDC runtime config API와
JWT 검증은 이미 외부 issuer/client/JWKS 입력을 지원한다.

## Definitions

`bundled`는 Keycloak을 같은 Compose에 설치하는 모드이고 `external`은 별도 운영되는 인증 서버를
사용하는 모드다. discovery는 realm이 공개하는 OIDC endpoint 목록이다.

## Scope

### In Scope

외부 Compose/Nginx 구성, mode-aware preflight, Doppler 입력 파일 준비와 staging 배포 명령,
회귀 검사와 운영 문서.

### Non-goals

외부 Keycloak 변경, 실제 서버 배포, commit/push, 제품 UI/API, Aggregate와 DB schema 변경.

## Business Rules and Invariants

BR-41 public client/PKCE와 기존 JWT issuer/audience/roles 검증을 유지한다. secret과 실제 환경 주소를
repository fixture/log에 기록하지 않는다. 필수 설정 오류를 fallback으로 숨기지 않는다.

## Architecture and Transaction Boundaries

ADR-122의 명시적 overlay를 사용한다. 제품 transaction, DB volume과 외부 신원 소유권은 바꾸지 않는다.

## Alternatives Considered

기본 bundled 구성 교체와 비밀번호 검사 우회 대신 별도 external overlay를 선택한다.

## Failure Semantics

모드 누락의 기존 bundled 기본값만 유지하고, external 필수 입력 누락/불일치는 거부한다.
외부 discovery 404, TLS와 로그인 실패는 별도 실패이며 내장 Keycloak을 자동 기동하지 않는다.

## Data and Migration

Flyway 변경 없음. 기존 Keycloak DB·volume·컨테이너를 자동 삭제하지 않는다.

## API and Event Contracts

기존 OIDC runtime config와 인증 API 사용. 공개 API/event 변경 없음.

## Milestones

1. ADR와 재현/회귀 검사 기록
2. external overlay, Nginx와 preflight 구현
3. Doppler 준비/배포 명령과 문서 작성
4. 계약·문서·실제 Nginx smoke 검증과 외부 검증 경계 기록

## Required Tests

내장 staging/prod 유지, 외부 Keycloak secret 없는 구성, 잘못된 모드/HTTPS/issuer/JWKS 거부,
secret 권한·PEM 보존·누락·기존 credential 변경 거부, Nginx의 API proxy/SPA/health와 auth 404.

## Validation Commands

    bash scripts/deploy/test-deployment-contract.sh
    python3 -m unittest discover -s scripts/deploy -p 'test_*.py'
    bash scripts/deploy/test-external-keycloak-nginx.sh
    bash scripts/deploy/test-frontend-image-contract.sh
    ./scripts/verify-docs.sh

## Observability

preflight 결과, Compose health, runtime OIDC config와 외부 discovery HTTP 결과를 구분한다.

## Documentation Updates

ADR-122, ADR-119 extension 링크, portfolio runbook, external Doppler runbook와 이 계획.

## Progress

- [x] 원인: 내장 Keycloak secret/DB/upstream 강제 확인
- [x] 외부 모드 결정과 구현 경계 기록
- [x] 외부 Compose와 preflight 구현
- [x] Doppler 준비/배포와 문서 구현
- [x] 로컬 검증 및 결과 기록

## Surprises & Discoveries

- 외부 issuer discovery는 현재 조사 환경에서 HTTP 404다. realm/공개 경로는 서버에서 재확인해야 한다.
- runtime OIDC API가 이미 외부 origin을 지원하므로 화면이나 JWT 검증 코드를 바꿀 필요가 없다.

## Decision Log

- 2026-09-06: 외부 인증 서버 재사용을 명시적 배포 모드로 지원하고 bundled 기본값을 보존한다.
- 2026-09-06: Nginx는 외부 인증 서버를 proxy하지 않고 browser가 외부 origin에 직접 연결한다.

## Outcomes & Retrospective

- Passed: `bash scripts/deploy/test-deployment-contract.sh` — bundled/external staging/prod,
  Keycloak credential 없는 external 구성과 mode/HTTPS/issuer/realm/JWKS 실패 경로.
- Passed: Python Doppler/discovery 15 tests — 파일 권한, multiline PEM, 누락 시 write 부재,
  기존 credential 보존, 외부 metadata issuer/JWKS/PKCE 검증과 response 비출력.
- Passed: 실제 pinned Nginx container의 syntax, health, SPA callback, API proxy,
  `/auth` 404와 callback query 비로깅 smoke. 테스트 container/network는 종료 후 제거했다.
- Passed: 기존 backend/frontend image contract와 staging deploy shell syntax.
- Passed: 기존 operations OIDC session unit test 4개. 제품 UI와 JWT source 변경 없음.
- Passed: 문서 검증(18 characterization tests와 OpenAPI semantic/ExecPlan checks), `git diff --check`.
- Observed: 제공된 외부 issuer의 discovery는 조사 환경에서 HTTP 404를 반환했다. realm 또는
  reverse proxy/access-network 원인 중 하나로 확정하지 않았으며 배포 서버에서 재확인이 필요하다.
- Not run: Doppler 실제 credential 준비, 새 이미지 build/publication, 실제 staging 서버 배포,
  외부 client 설정과 브라우저 login/logout. commit/push 없음.
- 이 계획의 완료는 external 배포 경로의 구현과 로컬 검증 완료이며 외부 OIDC 운영 완료가 아니다.

## Revision Notes

- 2026-09-06: 최초 작성.
- 2026-09-06: external 배포 경로, 회귀/런타임 Nginx 검증과 운영 문서 완료.
