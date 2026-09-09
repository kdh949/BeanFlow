# 같은 출처의 AIStor 이미지 요청을 저장소로 전달

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-09`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

외부 Keycloak 배포에서 이미지 공개 endpoint가 앱 origin과 같으면 이미지 요청을 AIStor로
전달한다. 현재 SPA fallback이 이미지 URL에 HTML 200을 반환하는 배포 설정 누락을 수정한다.

## Current State

수정 전 `compose.external-keycloak.yml`은 고정 `deploy/nginx/external-keycloak.conf`를 직접 mount했다.
이 설정에는 `/api/`, `/assets/`, SPA와 외부 Keycloak 격리만 있고 AIStor 경로는 없다.
서버별 endpoint와 bucket은 이미 Doppler 및 API 환경변수로 관리된다.

## Definitions

- 같은 출처: 이미지 공개 endpoint와 `BEANFLOW_PUBLIC_ORIGIN`의 scheme, hostname, port가 동일함.
- 생성 설정: 검증한 배포 입력으로 만든 저장소 밖 `external-keycloak.conf` 파일.

## Scope

### In Scope

Nginx 설정 생성, Doppler 준비 및 Compose mount 연결, 배포 preflight, 관련 회귀 검증과 runbook.

### Non-goals

서버 적용·재배포, 데모 데이터 투입, DB·API·화면·이미지 서명 정책 변경, bundled Keycloak 배포 변경.

## Business Rules and Invariants

ADR-115와 ADR-120의 private bucket 및 기존 presigned URL 계약을 유지한다.
설정된 bucket의 stores/menus/campaigns 경로에 GET/HEAD만 전달한다. 업로드 권한을 열지 않는다.
서명 query와 객체 경로, Cookie, Authorization은 프록시 로그에 기록하지 않는다.

## Architecture and Transaction Boundaries

배포 계층만 변경한다. DB transaction과 Aggregate 변경은 없다. 생성 파일은 원자적으로 교체하고
읽기 전용 mount한다. 환경별 주소를 공통 설정에 고정하지 않는다.

## Alternatives Considered

서버별 정적 IP·bucket 패치는 다른 환경에 재사용할 수 없다. 컨테이너 entrypoint 생성은 이미지
변경을 요구하므로 기존 Python 배포 준비 경로에서 생성한다. 별도 이미지 host는 기존 동작을 유지한다.

## Failure Semantics

잘못된 URL·bucket과 stale/missing 생성 설정은 배포 전에 실패한다. AIStor DNS 해석은 요청 때
Docker resolver로 수행하여 저장소 장애가 Nginx 시작을 막지 않게 한다. 저장소의 403/404/5xx와
연결 오류는 성공 HTML로 대체하지 않는다. HTTPS upstream은 인증서 검증과 SNI를 사용한다.

## Data and Migration

없음. 생성 설정에는 credential이 없고 기존 secret 회전·권한은 바꾸지 않는다.

## API and Event Contracts

변경 없음. 기존 path, raw query, public signing Host를 보존한다.

## Milestones

1. 입력 검증과 설정 생성 및 단위 검증.
2. Doppler·Compose·preflight 연결과 Nginx 컨테이너 경로 검증.
3. 운영 문서와 diff 검토.

## Required Tests

같은/다른 출처, 입력 오염·누락, 생성 설정 불일치, GET/HEAD 전달, 원본 URI/Host 보존,
Cookie/Authorization 제거, PUT/POST/DELETE 차단, 로그 비노출, upstream 실패와 health 독립성.

## Validation Commands

- `python3 -m unittest discover -s scripts/deploy -p 'test_*.py'`
- `bash scripts/deploy/test-deployment-contract.sh`
- `bash scripts/deploy/test-external-keycloak-nginx.sh`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

이미지 access log는 method/status/request ID만 포함한다. 이미지 error log의 request URI 노출을
막고 upstream 오류는 access status에서 확인한다. health 성공과 실제 이미지 GET 성공은 별도다.

## Documentation Updates

외부 Keycloak/Doppler runbook에 생성 파일, 같은/다른 출처 설정, 적용·검증·복구 절차를 기록한다.
Business Policy와 ADR-115/120의 저장소·서명 정책은 유지한다. ADR-122의 Nginx 제공 범위에는
같은 출처의 서명 이미지 조회를 명시하여 기존의 API/정적 파일 제한 문구와 정합성을 맞춘다.

## Progress

- 설정 생성, Doppler 준비, Compose mount, preflight와 운영 문서 반영 완료.
- Python 배포 검증 21개 Passed.
- bundled/external staging/prod 및 same-origin/stale 설정 배포 contract Passed.
- 실제 Nginx 컨테이너 syntax/HTTP 회귀 검증 Passed.
- 문서 검증 및 diff whitespace 검사 Passed.

## Surprises & Discoveries

- read-only 컨테이너의 UID 101이 설정을 읽을 수 있어야 한다. 비밀값이 없는 생성 설정만 0644로 생성한다.
- ADR-122의 API/정적 파일만 제공한다는 문구와 이미지 프록시 범위의 충돌을 보고하고 문서를 보완했다.
- Docker에서 저장소 컨테이너 중단은 연결 거절 502 또는 캐시된 IP 연결 timeout 504로 나타날 수 있다.

## Decision Log

- 2026-09-09: 기존 배포 입력에서 생성하고 Compose preflight에서 내용까지 비교한다.
- 2026-09-09: 요청한 이미지 프록시를 ADR-122의 제공 범위에 추가한다. 외부 Keycloak origin 소유권은 유지한다.

## Outcomes & Retrospective

같은 출처 이미지 요청은 환경별 upstream으로 전달되며 다른 이미지 origin에는 경로를 추가하지 않는다.
요청 계약 fixture를 사용하는 실제 Nginx 컨테이너에서 세 prefix의 GET/HEAD, raw path/query와
non-default signing port 보존, 앱 credential 제거, 다른 method 403, bucket 범위 밖 404,
upstream 403/404/503 및 연결 실패 502/504 전달, health/API 독립성과 로그 비노출을 확인했다.
테스트 fixture는 AIStor SigV4 검증을 대신하지 않는다.

서버 적용, 실제 AIStor 서명 URL, HTTPS upstream handshake, 앱 전체 빌드는 Not run이다.
최초 적용에는 생성 파일 mount로 전환하는 frontend 재생성이 필요하며 API/web 이미지 재빌드는 필요 없다.
관련 없는 작업 트리 변경과 데모 투입 작업은 보존했다. commit/push는 수행하지 않았다.

## Revision Notes

- 2026-09-09: 최초 계획 작성.
- 2026-09-09: 로컬 구현·검증 완료 및 적용 경계 기록.
