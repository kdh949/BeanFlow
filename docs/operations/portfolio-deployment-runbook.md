# 포트폴리오 서버 배포 Runbook

## 결론

`staging`부터 올린 뒤 같은 절차를 `prod`에 반복한다. 두 환경 모두 **실결제가 아닌 Toss sandbox**이며,
Sophos WAF의 HTTPS 443만 외부에 공개한다. 서버에서는 frontend의 8080만 DMZ 주소에 bind되고 API,
PostgreSQL, Keycloak 관리 포트는 공개되지 않는다.

이 runbook은 단일 호스트 포트폴리오 배포용이다. 상용 운영, 무중단 배포, HA, 백업·복구, SLO와
법률 검토를 보장하지 않는다.

## 0. 먼저 준비

- x86-64 Linux 서버, Docker Engine과 Docker Compose v2
- 배포 도메인과 Sophos에서 사용할 TLS 인증서
- private HTTPS Vault와 Transit 사용 권한
- licensed AIStor private endpoint, bucket과 전용 access key
- Toss의 `test_ck_...` / `test_sk_...` 키

공식 PostGIS 17 이미지는 `linux/amd64`로 고정한다. 서버가 ARM이면 이 구성을 그대로 운영하지 말고
PostGIS 이미지·성능을 별도로 검증한다.

## 1. 서버에 환경 파일 만들기

아래에서 `staging`을 먼저 사용한다. `prod`는 경로와 overlay만 바꾼다.

```bash
sudo install -d -m 0700 /etc/beanflow/staging/secrets
sudo cp deploy/env/staging.env.example /etc/beanflow/staging/deployment.env
sudo chmod 0600 /etc/beanflow/staging/deployment.env
sudoedit /etc/beanflow/staging/deployment.env
```

반드시 바꿀 값:

- `BEANFLOW_BIND_ADDRESS`: 서버의 DMZ IP
- `BEANFLOW_PUBLIC_ORIGIN`: `https://` 공개 주소, 끝 `/` 없음
- `BEANFLOW_IMAGE_TAG`: 배포할 Git SHA. `latest` 금지
- `BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS`: staging frontend `172.28.0.10/32`와 Sophos 주소만. 각 주소는
  단일 호스트 `/32` 또는 `/128`로 작성하며 Sophos 주소를 하나 이상 포함
- AIStor와 Vault 주소·bucket·key version

## 2. secret 파일 채우기

DB와 HMAC secret을 생성한다. 기존 파일은 덮어쓰지 않는다.

```bash
sudo bash scripts/deploy/create-secret-files.sh /etc/beanflow/staging/secrets
```

다음 파일은 secret 값을 명령행에 넣지 말고 `sudoedit` 등으로 직접 채운다.

```text
BEANFLOW_AISTOR_ACCESS_KEY
BEANFLOW_AISTOR_SECRET_KEY
TOSS_CLIENT_KEY
TOSS_SECRET_KEY
BEANFLOW_VAULT_CA_PEM
```

Vault 관리자는 인증된 Vault CLI에서 Transit key와 최소 권한 AppRole을 만든다.

```bash
sudo --preserve-env=VAULT_ADDR,VAULT_TOKEN,VAULT_CACERT \
  bash deploy/vault/bootstrap-transit.sh /etc/beanflow/staging/secrets
```

이 명령은 AppRole 파일을 생성하지만 Vault CA 파일은 생성하지 않는다. AppRole Secret ID는 자동 만료하지
않으므로 유출 의심이나 관리자 변경 때 재발급하고, 파일 교체 후 API를 재시작한다.

마지막으로 권한을 고정한다.

```bash
sudo chown -R root:root /etc/beanflow/staging
sudo find /etc/beanflow/staging -type d -exec chmod 0700 {} \;
sudo find /etc/beanflow/staging -type f -exec chmod 0600 {} \;
```

## 3. 배포 전 검사

```bash
sudo bash scripts/deploy/verify-deployment.sh staging \
  --env-file /etc/beanflow/staging/deployment.env
```

`/etc/beanflow`은 root 전용(0700/0600)이므로 이 단계부터 secret 또는 환경 파일을 읽는 모든 명령은
`sudo`로 실행한다.

`deployment preflight passed`가 아니면 배포하지 않는다. 특히 live Toss key, 빈 secret, 느슨한 파일 권한,
wildcard bind 주소와 `latest` tag는 실패해야 정상이다.

## 4. 이미지 빌드와 기동

```bash
sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  build --pull api frontend

sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  up -d --wait
```

완료 조건은 네 서비스가 모두 `healthy`인 것이다.

```bash
sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  ps

sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  exec api curl --fail --silent http://127.0.0.1:8080/actuator/health
```

## 5. Keycloak 운영자 한 명 만들기

Keycloak admin URL은 Nginx가 차단한다. 서버 SSH에서 CLI로만 초기화한다.

```bash
sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  exec keycloak /bin/bash -ec \
  'export KC_CLI_PASSWORD="$(</run/secrets/BEANFLOW_KEYCLOAK_ADMIN_PASSWORD)"; \
  exec /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://127.0.0.1:8080/auth --realm master --user beanflow-admin'
```

관리자 password는 출력하거나 shell argument에 넣지 않고 컨테이너 secret 파일에서 읽는다. 인증 후 같은
`sudo docker compose ... exec keycloak` 접두어로 실행한다. `set-password`는 새 운영자 password만 대화형으로
입력받는다.

```bash
/opt/keycloak/bin/kcadm.sh create users -r beanflow \
  -s username=portfolio-operator -s enabled=true
/opt/keycloak/bin/kcadm.sh set-password -r beanflow \
  --username portfolio-operator
/opt/keycloak/bin/kcadm.sh add-roles -r beanflow \
  --uusername portfolio-operator --rolename PLATFORM_OPERATOR
```

## 6. Sophos 연결

Sophos Firewall에서 다음 네 가지만 맞춘다.

1. Web server: 서버 DMZ IP의 HTTP `8080`
2. WAF rule: 공개 도메인, HTTPS `443`, 인증서 선택
3. staging allowed client: VPN 대역만. 공개 portfolio 환경만 필요한 인터넷 대역 허용
4. `Host`, `Authorization`, Cookie와 `X-Forwarded-For` 전달

서버 `8080`은 Sophos IP 이외에서 직접 접근되면 안 된다. Docker published port는 UFW보다 먼저 처리될
수 있으므로 UFW만 보고 안전하다고 판단하지 않는다. 서버의 Docker firewall backend에 맞춰
`DOCKER-USER` 또는 동등한 nftables forward rule로 Sophos source만 허용하고, 다른 Docker 서비스에
미치는 영향을 검토한 뒤 영구화한다.

외부 확인:

```bash
curl --fail --silent https://staging.beanflow.example/healthz
curl --fail --silent \
  https://staging.beanflow.example/auth/realms/beanflow/.well-known/openid-configuration
```

## 7. prod 승격

staging 확인 뒤 `/etc/beanflow/prod`와 `compose.prod.yml`로 1~6단계를 반복한다. prod도 `portfolio`
profile과 Toss sandbox만 사용한다. 이를 실결제 또는 상용 운영이라고 표시하지 않는다.

## 8. 애플리케이션 롤백

이전 정상 Git SHA 이미지가 서버에 남아 있어야 한다.

```bash
sudoedit /etc/beanflow/staging/deployment.env
sudo bash scripts/deploy/verify-deployment.sh staging \
  --env-file /etc/beanflow/staging/deployment.env
sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  up -d --no-build --wait api frontend
```

여기서 바꾸는 값은 `BEANFLOW_IMAGE_TAG` 하나다. Flyway schema는 자동 downgrade하지 않는다. 이전
이미지가 현재 schema와 호환되지 않으면 롤백하지 말고 traffic을 차단한 뒤 forward-fix한다.

## 빠른 진단

```bash
sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  ps

sudo docker compose \
  --env-file /etc/beanflow/staging/deployment.env \
  -f compose.portfolio.yml -f compose.staging.yml \
  logs --tail=200 api frontend keycloak postgres
```

- `postgres` unhealthy: secret·volume 권한과 init log 확인
- `keycloak` unhealthy: DB 연결, `/auth/health/ready`, realm import 확인
- `api` unhealthy: Vault Proxy/Transit, JWK, AIStor bucket, Flyway 확인
- Sophos 502: 서버 DMZ IP:8080 도달성과 frontend health 확인
- 결제 오류: 반드시 `test_ck_` / `test_sk_`인지 확인. live key로 바꾸지 않는다

관련 결정: [ADR-119](../adr/ADR-119-portfolio-deployment-runtime.md),
[Vault Transit Runbook](personal-data-vault-transit-runbook.md).
