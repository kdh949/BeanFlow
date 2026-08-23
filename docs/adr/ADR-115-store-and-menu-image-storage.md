# ADR-115: 매장·메뉴 이미지의 AIStor 저장과 조회 경계

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation owner:** [Store and menu images](../exec-plans/completed/store-menu-images.md)

## Context

현재 `Store`와 `Menu`에는 이미지 식별자가 없고 고객 매장 탐색 응답도 텍스트·거리·가용성만 반환한다.
제품 요구는 매장 목록 왼쪽의 정사각형 이미지와 메뉴별 선택적 정사각형 이미지를 추가하는 것이다.
원본 fixture는 후속 frontend 작업에서 제공되므로 이번 결정은 backend 저장·인가·계약과 장애 경계만
소유한다.

이미지 업로드는 untrusted multipart body와 이미지 decoder를 새 trust boundary로 만들고, 비공개 object
storage는 DB transaction 밖의 외부 부수효과와 결과불명 구간을 만든다. 별도 Media Aggregate와 upload
상태 머신을 도입하면 단일 현재 이미지 요구보다 소유권·복구 상태가 커진다. 반대로 object key를 URL로
직접 저장하면 signing host와 credential 회전, 객체 교체와 조회 계약이 DB에 결합된다.

MinIO의 공식 AIStor Free 계약과 문서는 Free tier를 단일 compute resource의 standalone deployment로
제한하며 HA, replication, lifecycle transition과 SLA/SLO를 제공하지 않는다. 2026-08-24 확인한 공식 release
feed의 stable container tag는 `quay.io/minio/aistor/minio:RELEASE.2026-08-07T18-34-35Z`다.

## Decision

### 1. Store/Menu가 현재 이미지 pointer를 소유한다

`merchant_store`와 `merchant_menu`에 아래 네 nullable 값을 각각 둔다.

- `image_original_key`
- `image_thumbnail_key`
- `image_sha256`
- `image_updated_at`

CHECK constraint로 네 값이 전부 null이거나 전부 non-null인 상태만 허용한다. Store/Menu가 자기 현재
표현을 소유하므로 별도 image table, Media Aggregate, upload command table과 상태 머신을 만들지 않는다.
JPA 연관관계도 추가하지 않는다.

### 2. 원본과 thumbnail은 비공개 AIStor bucket에 immutable key로 저장한다

backend는 최대 5 MiB의 JPEG/PNG multipart file을 받고 선언 MIME, magic bytes, decoder 결과와 해상도
범위를 모두 검증한다. 방향 보정 전 각 변은 256..4096px이고 총 pixel은 16,777,216 이하다. JPEG EXIF
orientation을 적용한 뒤 metadata를 재인코딩으로 제거한다.

- `original`: 방향이 정규화된 원본 비율 이미지
- `thumbnail`: 중앙 crop한 512×512 이미지

정규화 원본 bytes의 SHA-256을 계산하고 Store/Menu ID, 서버 생성 upload generation과 조합해 client가
제어할 수 없는 immutable key를 만든다. generation은 `A → B → A` 재선택 뒤 늦게 처리된 첫 cleanup이
현재 A 객체를 삭제하는 경합을 막는다. 같은 현재 SHA-256 요청은 새 generation을 만들기 전에 no-op으로
종료해 pointer, Aggregate version, updatedAt과 Audit를 바꾸지 않는다.

bucket은 public policy를 갖지 않는다. 고객 응답은 thumbnail key를 15분 presigned GET URL과
`expiresAt`으로 바꿔 반환한다. object key, access key와 secret은 공개 응답·log·metric·Audit에 넣지 않는다.
presign은 명시적으로 설정한 region과 별도의 public base endpoint를 사용하는 local signature 연산이며 매장 조회마다 AIStor
availability probe를 호출하지 않는다.

### 3. 외부 호출과 pointer transaction을 분리한다

업로드 순서는 다음과 같다.

1. 짧은 transaction에서 actor 권한, Store/Menu 존재와 Menu 소속을 확인한다.
2. transaction 밖에서 이미지 검증·정규화와 original/thumbnail PUT을 수행한다.
3. PUT 응답이 불명확하면 같은 immutable key를 HEAD로 한 번 확인한다. 기대 object metadata를 확인하지
   못하면 DB를 바꾸지 않고 503을 반환한다.
4. 새 transaction에서 권한과 Menu 소속을 다시 검증하고 대상 row를 잠근다.
5. pointer, append-only Audit와 이전 key cleanup event를 함께 commit한다.

동시 교체는 row lock의 commit 순서대로 latest pointer가 된다. 늦게 commit한 요청은 바로 이전 pointer를
cleanup 대상으로 발행한다. 각 upload generation은 다시 pointer로 선택되지 않으므로 지연된 cleanup이
새 현재 객체와 key를 공유하지 않는다. metadata transaction이 실패해 새 객체가 참조되지 않으면 periodic
orphan sweep이 정리한다. 분산 transaction과 object rollback을 흉내 내지 않는다.

### 4. 기존 persistent publication으로 이전 객체를 정리한다

pointer 교체·삭제 transaction은 이전 original/thumbnail key를 담은 versioned cleanup event를 발행한다.
`@ApplicationModuleListener`가 AIStor DELETE를 실행하고 Spring Modulith JPA publication registry가 실패를
미완료로 유지한다. 새 pointer commit 뒤 cleanup 실패로 Store/Menu를 되돌리지 않는다.

Free tier에 lifecycle transition이 없으므로 현재 DB가 참조하지 않는 storefront prefix를 제한된 batch로
검사하는 periodic sweep을 보완한다. sweep은 현재 참조를 다시 확인한 뒤 삭제하며, list/delete 실패를
0건 성공으로 기록하지 않는다. 별도 media recovery state machine은 만들지 않는다.

### 5. actor별 Application Service가 인가와 Audit 경계를 소유한다

- Store image: ACTIVE StoreMembership `OWNER`
- Menu image: ACTIVE StoreMembership `OWNER | STAFF`
- Operations mirror: `PLATFORM_OPERATOR` + active `STORE_MEDIA_MANAGE` + `X-Access-Reason`

Merchant endpoint는 기존 Session Cookie와 CSRF를 사용한다. actor별 Controller는 Repository를 직접
호출하지 않고 Application Service가 권한 재검증과 pointer/Audit transaction을 조정한다. Operations
permission은 closed vocabulary에 추가하되 default grant나 role fallback으로 seed하지 않는다.

### 6. media 장애는 거래·텍스트 조회 readiness와 분리한다

endpoint, credential, bucket과 signing endpoint는 필수 설정이다. 누락·형식 오류 또는 AIStor가 명시적으로
거절한 credential·bucket은 startup probe에서 애플리케이션 시작을 실패시킨다. probe의 연결 실패·timeout·5xx는
media unavailable metric을 남기되 애플리케이션 시작과 전체 readiness를 막지 않는다. 운영 profile에서
filesystem, in-memory, fake 또는 public bucket fallback을 허용하지 않는다.

startup 이후 AIStor 장애는 다음에만 드러난다.

- 새 이미지 PUT은 `503 DEPENDENCY_UNAVAILABLE`
- 이미 발급된 presigned URL의 직접 GET은 AIStor 오류
- cleanup publication과 orphan sweep은 미완료/실패 관측 후 retry

presigned URL 생성은 local signature 연산이므로 주문·결제·매장 텍스트 API와 전체 readiness는 계속
서비스한다. 이미지가 원래 없으면 `image`를 생략하지만 장애를 이미지 없음, placeholder URL 또는 stale
URL로 바꾸지 않는다. 별도 `beanflow.media.*` metric과 alert를 사용한다.

### 7. 새 production dependency를 adapter 경계에 제한한다

- `io.minio:minio:9.0.3`: 공식 AIStor Java API가 제공하는 PUT, stat/HEAD, presigned GET과 remove를 사용한다.
- `com.drewnoakes:metadata-extractor:2.19.0`: JPEG EXIF orientation만 읽는다. 이미지 bytes 변경과
  metadata 제거는 JDK ImageIO/Java2D 재인코딩이 담당한다.

MinIO SDK를 직접 HTTP 서명 구현으로 대체하면 SigV4, retry와 오류 분류를 자체 유지해야 한다.
EXIF parser를 직접 작성하면 untrusted binary parser의 검증 범위가 커진다. 두 dependency는 Merchant의
media adapter/normalizer 내부에서만 사용하며 제거 시 같은 port 구현을 바꿀 수 있다.

## Alternatives Considered

### DB bytea에 이미지 저장

transaction은 단순하지만 고객 탐색 DB의 backup·I/O·connection 비용이 이미지 payload에 결합된다.

### public bucket URL 저장

조회는 단순하지만 접근 만료와 bucket 공개 범위를 통제할 수 없고 key 변경이 API 계약에 노출된다.

### client direct presigned upload

backend bandwidth는 줄지만 임시 upload identity, 완료 command, 미확정 upload cleanup과 client-side 상태가
추가된다. 5 MiB 단일 이미지는 backend multipart가 더 작은 경계다.

### 별도 Media Aggregate와 upload 상태 머신

다중 이미지, moderation과 편집 이력이 없는데도 pending/active/deleted 상태와 command table이 필요해진다.
현재 Store/Menu의 nullable current pointer로 충분하다.

### thumbnail만 저장

현재 화면은 충족하지만 향후 다른 crop을 만들 원천이 없어지고 fixture 재업로드가 필요하다. 정규화 원본과
단일 512 thumbnail까지만 보관한다.

## Rationale

현재 요구는 Store/Menu당 현재 이미지 하나다. Aggregate를 늘리지 않고 기존 소유 모델에 pointer만 추가하면
인가, 동시성, 조회 경계가 기존 Store/Menu와 일치한다. object storage I/O를 transaction 밖에 두고 immutable
key+HEAD 확인+orphan sweep을 사용하면 DB와 AIStor 사이의 분산 transaction 없이 기존 이미지를 보존한다.

## Consequences

- AIStor Free 단일 노드 장애 동안 새 업로드와 직접 이미지 GET이 불가능하고 HA/SLA를 주장할 수 없다.
- 원본과 thumbnail을 함께 저장해 이미지당 object가 두 개가 되고 thumbnail만 저장할 때보다 용량이 늘어난다.
- 조회 응답 URL이 15분마다 달라지므로 client는 object URL을 장기 식별자로 저장하면 안 된다.
- 이미지 decoder와 두 production dependency의 보안 업데이트를 추적해야 한다.
- periodic orphan sweep은 즉시 정리가 아니라 bounded eventual cleanup이다.

## Verification

- normalizer 단위 테스트로 MIME/signature/decoder/dimension/pixel/EXIF/metadata/crop/hash를 검증한다.
- PostgreSQL Testcontainers로 all-null/all-non-null CHECK, row lock 경쟁, pointer/Audit/publication 원자성을
  검증한다.
- 실제 AIStor Free에서 private unsigned GET 거부, original/thumbnail PUT, 15분 presign, stat 확인과 remove를
  검증한다. license secret이 없으면 `Blocked`로 기록하고 fake 결과를 실제 통합 성공으로 보고하지 않는다.
- MockMvc 계약 테스트로 actor별 권한, CSRF, grant/reason, Menu 소속, 오류 code와 optional field를 검증한다.
- AIStor 중단 중 이미지 PUT 503과 주문·결제·텍스트 매장 조회/readiness 정상 유지를 검증한다.
- Spring Modulith/ArchUnit으로 새 cycle과 internal package 접근이 없음을 검증한다.

## Metrics

- `beanflow.media.operation.count{operation,outcome}`
- `beanflow.media.cleanup.publication.count{outcome}`
- `beanflow.media.orphan.count{outcome}`
- `beanflow.media.startup.validation.count{outcome}`

actor ID, Store/Menu ID, object key, hash, URL, credential과 access reason은 metric tag나 log field에 넣지 않는다.

## Revisit Conditions

다중 이미지·moderation·편집 이력, 5 MiB를 넘는 direct upload, CDN/동적 변환, 측정된 signing/DB query 병목,
HA·replication·DR 또는 AIStor Free 제약을 넘는 운영 요구가 확정될 때 별도 Media Aggregate나 storage tier를
재검토한다.

## Related Decisions

- BR-30, BR-47, BR-48
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [ADR-103](ADR-103-store-search-strategy.md)
