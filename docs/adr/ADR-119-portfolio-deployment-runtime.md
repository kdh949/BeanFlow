# ADR-119: 포트폴리오 배포 런타임과 sandbox 결제 경계

- **Status:** Accepted
- **Date:** 2026-09-02
- **Implementation owner:** [포트폴리오 배포 스택](../exec-plans/active/portfolio-deployment-stack.md)

## Context

BeanFlow에는 실제 Toss sandbox HTTP adapter와 local scripted 알림 adapter가 있지만, `prod` profile은
실제 결제수단·본인확인·알림 Provider가 없으면 시작을 거부한다. 이 저장소의 공개 배포 목적은 실제
자금을 다루는 상용 서비스가 아니라 포트폴리오 시연이다. 따라서 상용 Provider를 구현하거나 `prod`
guard를 약화하는 것은 목적과 맞지 않는다.

현재 비local 배포 패키지도 없다. 백엔드·프론트 이미지, 영구 PostGIS, OIDC 서버, Vault Proxy,
secret 주입, 외부 노출 경계와 버전 롤백 절차가 하나의 검증 가능한 계약으로 묶여 있지 않다.

## Decision

### 1. `portfolio`를 별도 공개 배포 profile로 둔다

- `portfolio`는 `local`, `toss-sandbox`, `vault-enforced`를 조합한다.
- Toss client/secret key는 기존 adapter의 `test_ck_`/`test_sk_` startup validation을 그대로 통과해야
  한다. live key와 실제 자금 이동은 허용하지 않는다.
- notification과 결제수단 lifecycle은 현재 local/scripted 또는 명시적 unavailable 결과를 사용한다.
  UI와 운영 문서는 이 환경을 상용 운영으로 표현하지 않는다.
- `prod` profile과 모든 production Provider guard는 변경하지 않는다. `portfolio`와 `prod`, `test`,
  `local-demo`의 동시 활성은 startup failure다.

### 2. 개인정보 암호화 경계는 낮추지 않는다

- `prod`와 `portfolio`는 모두 `vault-enforced`를 활성화한다.
- 애플리케이션은 같은 컨테이너의 loopback Vault Proxy만 호출한다.
- Vault Proxy는 AppRole auto-auth token을 강제 사용하고 외부 Vault의 Transit API만 전달한다.
- encryption key와 blind-index key는 서로 다른 Transit key다. AppRole role ID와 secret ID, CA는
  저장소 밖 파일로 주입한다.
- Vault 또는 Proxy가 준비되지 않았거나 Transit 계약이 틀리면 애플리케이션 시작은 실패한다.

### 3. 배포 토폴로지를 좁게 고정한다

```text
Internet/VPN -> Sophos WAF :443 -> server DMZ address :8080 -> frontend Nginx
                                                        |-> /api  -> BeanFlow API
                                                        `-> /auth -> Keycloak realm endpoints

private network: PostgreSQL 17 + PostGIS, Keycloak, BeanFlow API
external private dependencies: Vault server, licensed AIStor
```

- Compose는 frontend port만 사용자가 지정한 DMZ 주소에 publish한다.
- API, PostgreSQL과 Keycloak 관리 port는 host에 publish하지 않는다.
- Nginx는 query string을 access log에 남기지 않고 `/auth/admin`을 공개 proxy하지 않는다.
- Sophos와 frontend proxy CIDR만 authentication source-IP 계산의 trusted proxy로 설정한다.

### 4. secret과 버전은 배포 입력이다

- secret은 저장소 밖 디렉터리의 파일을 Compose secret/config tree로 주입한다.
- `.env`, private key, certificate key와 실제 secret 파일은 Git에서 차단한다.
- 애플리케이션 이미지는 `BEANFLOW_IMAGE_TAG`로 명시하며 `latest`를 배포 계약에 사용하지 않는다.
- rollback은 이전 image tag를 다시 선택하는 애플리케이션 rollback이다. Flyway migration은
  자동 downgrade하지 않고 forward-fix 원칙을 유지한다.

## Alternatives Considered

### `prod` guard를 느슨하게 해 sandbox Provider를 허용

상용 운영과 포트폴리오 시연의 의미가 하나의 profile에 섞이고, 이후 실제 Provider를 붙일 때 기존
배포가 조용히 sandbox를 계속 사용할 위험이 있다. 채택하지 않았다.

### local demo를 그대로 외부에 공개

tmpfs DB, ephemeral JWKS, test source seed와 reset 도구는 재시작 후 상태와 인증 신뢰를 보장하지 않는다.
채택하지 않았다.

### Vault와 AIStor까지 Compose에서 자동 bootstrap

Vault unseal/root-of-trust와 AIStor license/TLS/KMS를 애플리케이션 저장소가 소유하게 된다. 개인 서버의
외부 인프라 수명주기와 애플리케이션 배포를 분리하고, 최소 권한 AppRole·bucket credential만 주입한다.

### 모든 port를 localhost에 publish

Sophos가 별도 장비에서 DMZ 서버로 전달할 수 없고, 운영자가 임시 host port를 추가하게 될 가능성이
높다. frontend만 명시적 DMZ 주소에 publish하고 나머지는 Compose network에 둔다.

## Rationale

별도 profile은 상용 안전 guard를 보존하면서 포트폴리오 환경의 의도된 sandbox 동작을 명시한다.
Vault·secret·proxy 경계는 실제 개인정보 코드가 요구하는 fail-closed 조건을 유지하고, Compose는
외부 공격 표면을 단일 Nginx port로 줄인다.

## Consequences

- 공개 배포에서도 결제는 Toss sandbox이며 실제 결제수단 등록과 일부 외부 업무는 사용할 수 없다.
- Vault와 AIStor는 배포 전에 별도로 준비해야 한다.
- Keycloak realm은 자동 생성되지만 운영자 계정과 역할 부여는 관리자가 비공개 관리 경로에서 한다.
- 단일 호스트 Compose라 무중단 배포와 고가용성을 제공하지 않는다.

## Verification

- profile 조합과 Vault startup validator를 Spring context test로 검증한다.
- 배포 검증 script가 publish port, persistent volume, healthcheck, secret file, image tag와 sandbox profile
  불변식을 검사한다.
- 백엔드와 프론트 이미지를 실제로 build하고 Compose 최종 구성을 렌더링한다.
- Nginx routing, SPA fallback, query 없는 access log 형식을 정적 검증한다.

## Metrics

별도 Prometheus/Grafana/SLO 도입은 이 결정의 범위가 아니다. 기존 actuator health를 컨테이너
healthcheck와 배포 승인에 사용한다.

## Revisit Conditions

- 실제 자금을 처리하는 PG 계약을 맺을 때
- 실제 알림·본인확인·결제수단 Provider를 구현할 때
- 다중 호스트, 무중단 배포 또는 고가용성이 필요할 때
- Vault/AIStor를 애플리케이션과 같은 수명주기로 운영할 근거가 생길 때

## Related Decisions

- [ADR-080](ADR-080-toss-v2-one-time-payment-window.md)
- [ADR-083](ADR-083-personal-data-encryption-and-blind-index.md)
- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
