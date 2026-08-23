# 매장·메뉴 이미지 업로드와 고객 조회 제공

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

점주와 운영자가 매장·메뉴의 현재 이미지를 업로드·교체·삭제하고, 고객 탐색 API가 비공개 AIStor의
512×512 thumbnail을 15분 presigned URL로 제공한다. 이미지가 없는 매장은 frontend placeholder를,
이미지가 없는 메뉴는 이미지 영역 부재를 표현할 수 있도록 backend는 optional `image`만 제공한다.

## Current State

- latest `main`/`origin/main`은 2026-08-24 확인한 `470f755`이고 마지막 Flyway는 V64다.
- Store/Menu에는 이미지 컬럼과 쓰기 API가 없고 discovery 응답에도 이미지 계약이 없다.
- AIStor/MinIO SDK와 multipart 이미지 normalizer가 없다.
- repository task/worktree/open PR inventory에 실행 중인 migration-writing branch/PR이 없음을 확인했다.
  이 branch가 ADR-072 repository-wide migration-writer lease를 획득하고 V65/V66을 사용한다. 이 PR이
  main에 merge되기 전 다른 schema writer를 시작하지 않는다.
- 공식 AIStor release API가 반환한 stable container tag는
  `quay.io/minio/aistor/minio:RELEASE.2026-08-07T18-34-35Z`다. Free license secret은 현재 환경에 없다.

## Definitions

- **Normalized original:** EXIF 방향을 적용하고 metadata를 제거해 다시 인코딩한 원본 비율 이미지.
- **Thumbnail:** normalized original을 중앙 crop한 512×512 이미지.
- **Pointer:** Store/Menu row의 original key, thumbnail key, normalized SHA-256과 updatedAt 네 값.
- **Media availability:** AIStor가 PUT/GET/HEAD/DELETE를 수행할 수 있는 상태. 전체 application readiness와
  분리한다.

## Scope

### In Scope

- AIStor Free private bucket adapter, 필수 설정/startup validation, media metrics
- JPEG/PNG 5 MiB·dimension·pixel·signature 검증, EXIF 방향, metadata 제거, original/thumbnail 생성
- Store/Menu pointer migration과 all-null/all-non-null CHECK
- Merchant Store OWNER, Menu OWNER/STAFF PUT/DELETE API와 CSRF
- Operations mirror API, `STORE_MEDIA_MANAGE`, `X-Access-Reason`, Audit
- Store 검색/nearby/favorite/recent/recommendation/detail와 Menu 목록 optional image contract
- immutable key, same-hash no-op, PUT unclear HEAD 확인, persistent cleanup event와 bounded orphan sweep
- target/runtime OpenAPI와 generated TypeScript schema

### Non-goals

- frontend route/component/Storybook fixture와 placeholder UI
- 다중 이미지, gallery, moderation, editor와 crop 선택
- client direct upload, CDN, dynamic transform, public bucket
- 별도 Media Aggregate, upload command table, media recovery state machine
- AIStor Free HA, replication, lifecycle transition 또는 SLA 보장

## Business Rules and Invariants

- BR-48과 ADR-115를 canonical source로 사용한다.
- pointer 네 값은 전부 null 또는 전부 non-null이다.
- object key는 server-generated Store/Menu scope와 normalized SHA-256만 포함한다.
- 같은 current SHA-256은 새 PUT, pointer version 변경과 Audit를 만들지 않는다.
- Menu write는 URL store와 실제 Menu store가 같아야 하며 권한을 pointer commit 직전에 다시 확인한다.
- 이미지 없음은 정상 optional data지만 provider 장애를 없음으로 바꾸지 않는다.

## Architecture and Transaction Boundaries

`merchant :: api`는 image normalization/storage 준비와 Store/Menu pointer query/mutation port를 노출한다.
`identity` Merchant Application Service와 `operations` Operator Application Service가 actor별 권한과 Audit를
조정한다. `discovery`는 Merchant projection의 thumbnail key를 signing port로 바꿔 customer DTO를 만든다.

PUT은 사전 인가 transaction, 외부 normalize/upload, pointer/Audit/publication transaction 세 구간이다.
DELETE는 외부 호출 없이 pointer/Audit/publication transaction을 먼저 commit하고 cleanup listener가
AIStor DELETE를 수행한다. Controller는 Repository를 직접 호출하지 않는다.

## Alternatives Considered

ADR-115의 DB bytea, public bucket, client direct upload, Media Aggregate, thumbnail-only 대안을 검토하고
기존 Store/Menu pointer와 backend multipart를 선택했다.

## Failure Semantics

- 설정·credential·bucket 검증 실패: startup failure
- 이미지 검증 실패: 400 `INVALID_IMAGE`
- AIStor PUT/HEAD 불명: pointer 불변, 503 `DEPENDENCY_UNAVAILABLE`
- pointer/Audit commit 실패: 기존 pointer 유지, 503; 새 object는 orphan sweep 대상
- cleanup 실패: 새 pointer 유지, incomplete publication+metric
- image absent: `image` field omission
- runtime AIStor down: image PUT/direct GET만 실패; order/payment/store text/readiness 유지

## Data and Migration

- V65: Store image pointer 네 컬럼, Store CHECK, `STORE_MEDIA_MANAGE` closed permission vocabulary
- V66: Menu image pointer 네 컬럼과 Menu CHECK
- 기존 migration을 수정·renumber/checksum repair하지 않는다.
- V65/V66은 이 branch의 migration-writer lease에서 직렬 작성한다.

## API and Event Contracts

- Merchant `PUT|DELETE /api/v1/stores/{storeId}/image`
- Merchant `PUT|DELETE /api/v1/stores/{storeId}/menus/{menuId}/image`
- Operations `PUT|DELETE /api/v1/operations/stores/{storeId}/image`
- Operations `PUT|DELETE /api/v1/operations/stores/{storeId}/menus/{menuId}/image`
- PUT request는 `multipart/form-data`의 required `image`, response는 `200 ImageAccess`
- DELETE는 `204`
- 고객 Store/Menu response의 optional `image: { url, expiresAt }`
- `StorefrontImageObjectsCleanupRequestedV1`은 이전 original/thumbnail key만 포함하고 actor/reason을 담지 않는다.

## Milestones

1. BR-48, ADR-115, authorization/failure semantics와 이 ExecPlan을 확정한다.
2. Store normalizer/storage/pointer, merchant/operator Store API와 모든 Store read contract를 구현한다.
3. Menu pointer, merchant/operator Menu API와 Menu list contract를 구현한다.
4. 실제 AIStor 통합 또는 license blocker, 전체 build와 문서/API 검증을 기록하고 plan을 완료한다.

## Required Tests

- normalizer small tests: valid/invalid MIME/signature/size/dimension/EXIF/metadata/crop/hash
- PostgreSQL tests: migrations/CHECK/pointer lock/same-hash/Audit/publication
- MockMvc: actor/role/membership/CSRF/grant/reason/Menu ownership/error contract
- discovery regressions: every Store surface, menu optional image, matchedMenus unchanged, query-count ceiling
- AIStor integration: private unsigned GET, PUT/stat/presign/remove, unclear PUT, cleanup retry
- outage isolation: media PUT 503, order/payment/text store/readiness unaffected
- Modulith/ArchUnit, OpenAPI parity, generated TypeScript, docs verifier and full build

## Validation Commands

```bash
./gradlew test --tests '*StorefrontImageNormalizerTest' --stacktrace
./gradlew test --tests '*StoreImage*' --stacktrace
./gradlew test --tests '*MenuImage*' --stacktrace
./gradlew test --tests '*Store*Query*' --stacktrace
npm run generate:api
npx tsc --noEmit
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
./gradlew spotlessCheck
./gradlew build --stacktrace
git diff --check
```

## Observability

- `beanflow.media.operation.count{operation,outcome}`
- `beanflow.media.cleanup.publication.count{outcome}`
- `beanflow.media.orphan.count{outcome}`
- AIStor server `minio_cluster_config_license_status` alert

ID, key, hash, URL, credential와 access reason은 tag/log에 넣지 않는다. media probe는 global readiness
component로 등록하지 않는다.

## Documentation Updates

- `docs/product/business-policy-decisions.md` BR-48
- `docs/adr/ADR-115-store-and-menu-image-storage.md`, `docs/adr/README.md`
- `docs/security/authorization-matrix.md`
- `docs/architecture/failure-semantics.md`
- target/runtime OpenAPI와 generated schema
- 완료 시 이 ExecPlan의 progress/outcome과 active→completed 이동

## Progress

- [x] 2026-08-24 current main/PR/worktree/Flyway inventory 확인과 migration-writer lease 획득
- [x] 2026-08-24 BR-48/ADR-115/authorization/failure contract 작성
- [ ] Store image vertical slice
- [ ] Menu image vertical slice
- [ ] AIStor/전체 validation과 plan completion

## Surprises & Discoveries

- active `Writes-Migration=true` plan이 셋 있지만 실행 branch/PR lease evidence는 없었다. canonical metadata는
  후보 표시이지 lease reservation이 아니므로 이 실행이 lease를 획득했다.
- AIStor Free는 공식 문서상 단일 노드이고 replication/lifecycle transition/SLA가 없다. object cleanup은
  애플리케이션 persistent publication과 bounded sweep이 소유해야 한다.

## Decision Log

| Date | Status | Decision | Record |
|---|---|---|---|
| 2026-08-24 | Accepted | AIStor Free private bucket, backend multipart, 15분 thumbnail presign | BR-48, ADR-115 |
| 2026-08-24 | Accepted | Store OWNER, Menu OWNER/STAFF, Operator explicit media grant | BR-48, authorization matrix |
| 2026-08-24 | Accepted | runtime media outage를 거래·텍스트 조회 readiness에서 격리 | failure semantics, ADR-115 |
| 2026-08-24 | Accepted | Store/Menu nullable pointer를 사용하고 별도 Media Aggregate/state machine 제외 | ADR-115 |

## Outcomes & Retrospective

(완료 시 실제 validation과 남은 제약을 기록한다.)

## Revision Notes

- 2026-08-24: 최초 작성과 migration-writer lease evidence 기록.
