# 외부 Keycloak과 Doppler로 staging 배포

이 절차는 기존 외부 Keycloak을 사용한다. `BEANFLOW_KEYCLOAK_DB_PASSWORD`와
`BEANFLOW_KEYCLOAK_ADMIN_PASSWORD`는 필요하지 않으며 별도 Keycloak을 생성하지 않는다.
API, frontend, PostgreSQL 세 서비스를 기동한다. 기존 bundled 배포는
[portfolio runbook](portfolio-deployment-runbook.md)을 따른다.

## 사전 조건

- x86-64 Linux, Docker Engine, Docker Compose 2.24.4 이상, Git, Python 3, curl, Doppler CLI
- 저장소 밖 secret directory를 쓰고 Docker를 실행할 권한
- 외부 Vault Transit/AppRole/CA, AIStor private bucket/credential, Toss sandbox test key
- 빌드가 성공한 API/web GHCR 이미지와 같은 40자리 Git SHA의 clean checkout
- 외부 Keycloak issuer를 애플리케이션 서버와 사용자 브라우저에서 HTTPS로 접근 가능
- 정상 앱 기동 전 GLOBAL 포인트 적립 정책의 초기 bootstrap 완료

새 DB를 처음 배포한다면 [정책 bootstrap runbook](ordinary-point-accrual-policy-bootstrap-runbook.md)의
offline command를 먼저 수행한다. 승인된 적립률·발행자·유효기간과 verified OIDC workload identity가
필요하며, command가 migration과 version/head/Audit 생성을 수행한다. Doppler에 앱 연결값만 넣고
`up`을 실행하는 것으로는 이 초기화가 완료되지 않는다. 기존 정책이 있는 DB에는 반복 실행하지 않는다.

## 1. 외부 Keycloak client 확인

기존 realm에 BeanFlow 전용 client를 만들거나 기존 client의 다음 설정을 확인한다. 기존 realm 전체를
repository의 import JSON으로 덮어쓰지 않는다.

- Client authentication: Off (public client)
- Standard flow: On, PKCE method: S256
- Direct access grants, Implicit flow, Service accounts: Off
- Valid Redirect URIs: `<BEANFLOW_PUBLIC_ORIGIN>/ops/auth/callback`
- Valid post logout redirect URIs: `<BEANFLOW_PUBLIC_ORIGIN>/ops`
- Web Origins: `<BEANFLOW_PUBLIC_ORIGIN>` (wildcard 금지)
- access token의 `aud`에 실제 client ID 포함
- realm role mapper가 최상위 `roles` 배열을 access token에 포함
- 운영자에게 `PLATFORM_OPERATOR` realm role 부여. BeanFlow의 세부 permission grant는 별도다.

`deploy/keycloak/beanflow-realm.json`은 bundled client의 mapper 참고 자료다. 외부 client ID가
`beanflow`라면 audience도 `beanflow`여야 하며 bundled 기본값 `beanflow-operations`를 복사하지 않는다.
SPA client secret이나 Keycloak DB/관리자 비밀번호를 BeanFlow에 주입하지 않는다.

## 2. Doppler 입력

[staging.env.example](../../deploy/env/staging.env.example)의 모든 항목을 등록하고 실제 배포 값으로
바꾼다. API/web repository는 각각 `ghcr.io/kdh949/beanflow-api`, `ghcr.io/kdh949/beanflow-web`이다.
예제의 임시 image tag 대신 실제 성공한 빌드의 SHA를 사용한다.

[external-keycloak.env.example](../../deploy/env/external-keycloak.env.example)의 항목을 추가한다.

```dotenv
BEANFLOW_KEYCLOAK_MODE=external
BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL=https://sso.example.test:5443
BEANFLOW_OPERATIONS_OIDC_ISSUER_URI=https://sso.example.test:5443/realms/beanflow
BEANFLOW_OPERATIONS_OIDC_REALM=beanflow
BEANFLOW_OPERATIONS_OIDC_CLIENT_ID=beanflow
BEANFLOW_JWK_SET_URI=https://sso.example.test:5443/realms/beanflow/protocol/openid-connect/certs
```

issuer/base/realm/JWKS는 정확히 일치해야 한다. callback과 logout URI는 Compose가 BeanFlow의
`BEANFLOW_PUBLIC_ORIGIN`에서 만든다. 외부 Keycloak 주소와 BeanFlow 주소를 혼동하지 않는다.

### AIStor 이미지 공개 경로

`BEANFLOW_AISTOR_ENDPOINT`는 frontend와 API 컨테이너에서 접근할 수 있는 저장소 주소다.
`BEANFLOW_AISTOR_PUBLIC_ENDPOINT`는 브라우저용 서명 URL의 HTTPS origin이다.
공개 endpoint가 `BEANFLOW_PUBLIC_ORIGIN`과 같으면 준비 스크립트가 해당 bucket의
`stores/`, `menus/`, `campaigns/` GET/HEAD 프록시를 자동 생성한다. 예를 들어:

```dotenv
BEANFLOW_PUBLIC_ORIGIN=https://app.example.test
BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://app.example.test
BEANFLOW_AISTOR_ENDPOINT=http://aistor.internal.example:9000
BEANFLOW_AISTOR_BUCKET=beanflow-staging
```

서버 IP와 bucket은 저장소 공통 설정에 고정하지 않는다. 공개 endpoint가 다른 origin이면 해당
host의 프록시가 이미지 제공을 담당하며, BeanFlow Nginx에는 AIStor 경로를 추가하지 않는다.
같은 출처 프록시의 upstream은 path/query/credential 없는 HTTP(S) origin이어야 하고 공개 origin으로
되돌아가는 설정은 거절한다. HTTPS upstream은 컨테이너 CA bundle로 인증서를 검증하며 SNI를 사용한다.
사설 CA 사용 시 신뢰 체인을 별도로 구성하고 인증서 검증을 끄지 않는다.

생성 파일은 `<BEANFLOW_SECRETS_DIR의 상위 디렉터리>/external-keycloak.conf`에 0644로 저장한다.
비밀값은 없으며 frontend UID 101이 읽는 파일이다. secret 파일의 0600과 directory의 0700은 유지한다.
Compose는 이 생성 파일을 읽기 전용 mount하며, preflight는 현재 API signing 설정과 생성 파일의
일치 여부를 검사한다. `deploy/nginx/external-keycloak.conf`는 생성기의 입력이므로 직접 mount하지 않는다.

Bucket은 계속 비공개다. 프록시는 Cookie·Authorization·request body를 제거하고 원래 경로·query와
public signing Host를 전달한다. HEAD도 허용하지만 GET 서명으로 HEAD까지 인증되는 것은 아니다.
다른 HTTP method는 403, bucket listing과 다른 prefix는 404다. 이미지 응답은 `private, no-store`이며
저장소 오류를 SPA HTML로 바꾸지 않는다. 이미지 로그에는 method/status/request ID만 남긴다.

공통 비밀값은 다음 열 개다. 값은 Doppler UI 또는 안전한 secret 입력 도구에서 등록한다.

```text
BEANFLOW_POSTGRES_PASSWORD
BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL
BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL
BEANFLOW_AISTOR_ACCESS_KEY
BEANFLOW_AISTOR_SECRET_KEY
TOSS_CLIENT_KEY
TOSS_SECRET_KEY
BEANFLOW_VAULT_ROLE_ID
BEANFLOW_VAULT_SECRET_ID
BEANFLOW_VAULT_CA_PEM
```

DB password는 기존 DB와 같아야 하고 HMAC key 두 개는 서로 다른 32-byte base64url key다.
Vault CA는 실제 줄바꿈이 있는 PEM이다. 준비 스크립트는 기존 credential과 Doppler 값이 다르면
파일을 덮어쓰지 않고 중단한다. credential rotation이나 이전 잘못된 설정 복구를 배포 중에 추측하지 않는다.

Toss client/secret 키는 필수지만 배포와 `toss-sandbox` 시작 시 접두사로 차단하지 않는다.
테스트 키 선택과 실제 SDK/Provider 호환성은 배포 운영자가 확인한다. 이 검사를 제거해도
Standard Payment Window가 Payment Widget으로 바뀌거나 위젯 키 호환성이 보장되지는 않는다.

## 3. 이미지 빌드와 서버 checkout

GitHub Actions의 `Build personal staging images`에서 배포할 branch/ref를 선택한다. 예를 들어:

```bash
gh workflow run build-personal-staging-images.yml \
  --repo kdh949/BeanFlow --ref feature/load-test-monitoring-foundation
```

API와 web 빌드가 모두 성공하면 Doppler의 `BEANFLOW_IMAGE_TAG`를 그 실행의 40자리 SHA로 설정한다.
API 이미지는 게시 전에 실제 Vault TLS/AppRole 장애·복구와 전체 Spring 앱/DB 기동 검증을 통과해야 한다.
서버에서는 지정 branch를 fetch한 뒤 같은 SHA를 checkout한다. 기존 checkout의 변경을 먼저 확인하고
보존한다. 아래 예시는 `/srv/beanflow`를 배포 경로로 선택한 서버에서 실행한다.

```bash
cd /srv/beanflow
git status --short
git fetch origin feature/load-test-monitoring-foundation
doppler run --no-fallback -- bash -euc 'git checkout --detach "$BEANFLOW_IMAGE_TAG"'
```

private GHCR 이미지라면 배포 계정에서 `docker login ghcr.io -u <github-user>`로 read:packages 권한을
설정한다. API/web 이미지는 build workflow가 넣은 OCI revision label과 SHA가 같아야 한다.

## 4. 배포

Doppler의 해당 config에만 접근하는 read-only Service Token을 배포 디렉터리에 설정한다.
`/etc/beanflow/staging`이 root 소유라면 같은 root shell에서 Doppler 인증과 아래 명령을 실행한다.
`doppler run -- sudo ...`로 환경을 잃지 않도록 실행 사용자와 secret directory 소유권을 맞춘다.

```bash
cd /srv/beanflow
doppler run --no-fallback -- bash scripts/deploy/deploy-staging-doppler.sh
```

명령은 clean checkout/SHA/host를 확인하고, Doppler 입력을 private secret 파일과 일반
`deployment.env`로 준비한다. 이어서 mode-aware preflight, 외부 discovery/issuer/JWKS/PKCE 확인,
이미지 pull/revision 검사와 `up --no-build --pull never --wait`를 수행한다.
외부 Keycloak discovery가 404, TLS 오류 또는 설정 불일치이면 컨테이너 기동 전에 실패한다.

기존 Keycloak 컨테이너, DB, volume과 사용자 데이터는 자동 정리하지 않는다. 특히 기존 bundled
배포 전환에서 남은 컨테이너는 소유권을 확인한 뒤 별도로 정리한다. `down -v`, DB drop이나
`--remove-orphans`는 이 절차에 포함하지 않는다.

### 기존 배포에서 Nginx 설정만 갱신

기존 고정 설정 mount에서 생성 파일 mount로 전환하는 최초 적용은 **frontend 컨테이너 재생성**이
필요하다. API/web 이미지 재빌드는 이 프록시 수정에 필요하지 않지만 기존 배포의 이미지 tag를 유지한다.
Doppler 설정 준비와 `verify-deployment.sh staging --env-file <deployment.env>`를 먼저 통과시킨 뒤,
평소와 같은 project/env/Compose 파일 조합으로 아래 순서만 수행한다.

```bash
docker compose --env-file <deployment.env> \
  -f compose.portfolio.yml -f compose.staging.yml -f compose.external-keycloak.yml \
  run --rm --no-deps frontend nginx -t
docker compose --env-file <deployment.env> \
  -f compose.portfolio.yml -f compose.staging.yml -f compose.external-keycloak.yml \
  up -d --no-deps --no-build --pull never --force-recreate frontend
```

준비 스크립트는 생성 파일을 원자적으로 교체한다. 기존 파일 bind mount는 이전 inode를 계속 볼 수
있으므로 준비 후 `nginx -s reload`만으로 새 파일 적용을 보장하지 않는다. 실패 시 이전 non-secret
설정 파일과 Compose mount를 복원하고 frontend만 재생성한다. DB·객체·credential은 되돌리지 않는다.

Doppler 없이 파일 기반 배포를 준비할 때는 다음 명령으로 생성 후보를 만들고 0644로 배치한다.
경로는 위 secret directory의 상위 디렉터리를 사용한다. `verify-deployment.sh` 자체는 파일을 생성하지 않는다.

```bash
python3 scripts/deploy/render_external_nginx.py --env-file <deployment.env> > <external-keycloak.conf.candidate>
chmod 0644 <external-keycloak.conf.candidate>
mv <external-keycloak.conf.candidate> <external-keycloak.conf>
```

## 5. 확인과 오류 구분

```bash
doppler run --no-fallback -- bash -euc '
  curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
    "$BEANFLOW_OPERATIONS_OIDC_ISSUER_URI/.well-known/openid-configuration" >/dev/null
  curl --fail --silent --show-error --max-time 10 "$BEANFLOW_PUBLIC_ORIGIN/healthz"
  curl --fail --silent --show-error --max-time 10 "$BEANFLOW_PUBLIC_ORIGIN/api/v1/auth/operations/config"
'
```

- secret 파일 오류: `BEANFLOW_KEYCLOAK_MODE=external`과 새 배포 script가 적용됐는지 확인한다.
- Vault의 `local node not active but active cluster node not found`: 외부 Vault leader/HA 상태를
  [Vault runbook](personal-data-vault-transit-runbook.md#runtime-incident-handling)에 따라 조사한다.
- `GLOBAL ordinary point accrual policy must have exactly one complete current version`: 위 최초
  정책 bootstrap 또는 기존 head/version 정합성을 확인한다. startup precheck를 제거하지 않는다.
- `AccessDeniedException: /run/secrets/BEANFLOW_DB_PASSWORD`: API의 JVM secret 소유권 전달
  수정이 필요하다. 수정 이미지와 같은 SHA의 Compose를 함께 배포한다. Doppler 비밀번호는 유지하며,
  앱은 host 원본 대신 UID 10001 전용 `/run/beanflow-secrets` tmpfs를 읽는다.
- discovery 404: realm 존재, 실제 context path, reverse proxy routing과 접근망을 확인한다. 임의로
  `/auth` prefix를 붙이거나 issuer를 추정하지 않는다.
- TLS 실패: 서버와 브라우저가 신뢰하는 유효한 인증서 체인을 구성한다. 검증 비활성화를 사용하지 않는다.
- 이미지 URL이 HTML 200: 생성 설정과 mount 및 same-origin 여부를 확인한다. 새로 발급한 서명 URL의
  **GET 200 + image Content-Type**, query를 제거한 요청의 **403**, 업로드 method의 **403**을 확인한다.
  URL의 서명 query를 채팅·로그·명령 이력에 남기지 않는다.
- 이미지 502/504: frontend에서 AIStor endpoint로의 연결·DNS·TLS를 확인한다. 이미지 access log의 status와
  request ID를 사용한다. AIStor 주소는 Docker DNS `127.0.0.11`로 요청 시 해석하여 DNS 장애가 Nginx
  시작을 막지 않도록 한다. 이미지 장애와 `/healthz`·텍스트 API 상태는 별도로 확인한다.
- 로그인 redirect/CORS 오류: 외부 client의 정확한 callback, logout URI와 Web Origins를 확인한다.
- 로그인 후 401: issuer와 access-token audience가 입력과 일치하는지 확인한다.
- 로그인 후 403: `roles` 배열과 `PLATFORM_OPERATOR`, BeanFlow 세부 permission grant를 확인한다.

세 컨테이너 healthy와 API UP은 앱 기동의 증거다. 브라우저 로그인·callback·허가된 운영 조회·로그아웃까지
확인해야 OIDC 연결 완료다. 모니터링 ingest와 실제 부하 측정은 별도 검증이다.

## 관련 문서

- [ADR-122](../adr/ADR-122-external-keycloak-deployment.md)
- [Keycloak JavaScript client 설정](https://www.keycloak.org/securing-apps/javascript-adapter)
- [Doppler Service Tokens](https://docs.doppler.com/docs/service-tokens)
- [Docker Compose merge](https://docs.docker.com/reference/compose-file/merge/)
- [Nginx proxy URI와 Host 처리](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- [Nginx resolver와 GET/HEAD method 제한](https://nginx.org/en/docs/http/ngx_http_core_module.html#resolver)
