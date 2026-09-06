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

## 3. 이미지 빌드와 서버 checkout

GitHub Actions의 `Build personal staging images`에서 배포할 branch/ref를 선택한다. 예를 들어:

```bash
gh workflow run build-personal-staging-images.yml \
  --repo kdh949/BeanFlow --ref feature/load-test-monitoring-foundation
```

API와 web 빌드가 모두 성공하면 Doppler의 `BEANFLOW_IMAGE_TAG`를 그 실행의 40자리 SHA로 설정한다.
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
- discovery 404: realm 존재, 실제 context path, reverse proxy routing과 접근망을 확인한다. 임의로
  `/auth` prefix를 붙이거나 issuer를 추정하지 않는다.
- TLS 실패: 서버와 브라우저가 신뢰하는 유효한 인증서 체인을 구성한다. 검증 비활성화를 사용하지 않는다.
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
