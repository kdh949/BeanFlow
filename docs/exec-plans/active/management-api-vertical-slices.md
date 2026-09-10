# 매장 운영과 정산·장애 복구 관리 API 완성

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 기능별 변경은 조회·명령·권한·감사·멱등성·
DB 제약·테스트·OpenAPI를 함께 제공하는 수직 슬라이스로 검토한다.

## Purpose / Big Picture

현재 모델 또는 내부 서비스만 존재하는 다음 관리 기능을 HTTP API로 제공한다.

1. 정산 이의제기 검토·승인·기각·철회
2. 매장 개설 및 검색용 매장명·좌표 관리
3. 픽업 슬롯 생성·조회·시간·정원 관리
4. 매장 수수료 계약 버전 등록·조회
5. 기존 점주·직원의 매장 소속 추가·역할 변경·철회
6. 일반 알림 및 이벤트 publication 수동 복구

기능별 commit과 선형 Stacked PR을 작성한다. PR은 선행 PR 대비 증분을 검토하며, API 구현 완료와
원격 CI 통과·PR 병합·배포 완료를 구분한다. UI 구현은 이 요청의 범위에 포함하지 않는다.

## Current State

- 조사 checkout은 `main`의 `70733ce`였으며 미커밋 변경이 있다. 원래 checkout은 보존한다.
- 작업용 worktree는 `/private/tmp/beanflow-management-api-20260910`, 최초 branch는
  `feature/management-api-plan`, 시작 commit은 fetch로 확인한 `origin/main` `7ba84ea`다.
- `7ba84ea`는 PR #149의 event publication 자동 복구·수동 검토 격리를 포함한다. 원래 checkout의
  미커밋 변경을 새 기능의 구현물로 복사하거나 재커밋하지 않는다.
- `SettlementDisputeDecisionService`에는 검토·승인·기각·철회가 있으나 판정 HTTP entry point가 없다.
  승인 Adjustment는 `REQUIRES_NEW`로 먼저 commit하므로 후속 판정 실패와 반대 판정의 경합을
  해결하지 않은 단순 Controller wrapper는 허용하지 않는다.
- Store, 검색 profile, PickupSlot, 수수료 계약, membership에는 사용자 authoring 경로가 부분적으로 없다.
- 원격 PR #150의 head `8e77826a7e85635988752306c807af9f84ec8d69`는 Inventory module을 제거하고
  메뉴·옵션·구성의 수동 판매 가능 상태로 전환한다. V1 등 초기 DDL 변경과 V72/V73을 포함하며,
  해당 ExecPlan이 migration writer를 소유한다고 기록한다. 원격 CI는 조사 시 진행 중이었다.
- 사용자는 재고를 제외하고 #150 뒤의 순차 stack 구현을 승인했다. 정확한 시작점은 위 #150 head이며
  첫 branch는 `feature/dispute-management-api`다. ADR-124가 이 수동 stack의 순서와 writer 경계를 고정한다.

### 최종 통합의 부모 갱신 (2026-09-10)

최초 부모 이후 #150이 `88828060a2a762a479cebdcf2dbdebc1ca1d63be`로 전진했다. 해당 head의 backend,
6개 test shard, frontend, CodeQL 및 집계 build가 모두 성공한 것을 확인했다. ADR-124에 갱신 검토를
기록하고 첫 PR부터 정상 merge로 전파한다. V74~V80 번호 충돌은 없으며 새 부모 위에서 전체 backend
검증을 다시 실행한다. 앞선 부모의 실패 관측은 당시 head의 기록이며 최종 head의 상태를 뜻하지 않는다.

## Definitions

- **관리 명령:** 인증된 사람이 명시적 사유와 멱등 키로 요청한 상태 변경이다.
- **계약 버전:** 주문 생성 시 정확히 하나가 선택되는 매장별 수수료율과 적용 시간 구간이다.
- **복구 접수:** 원래 작업의 source/provider key를 보존해 실행을 재개할 수 있도록 내구 상태를 저장한다.
  접수 응답은 Provider 처리 또는 listener 성공을 뜻하지 않는다.
- **수직 슬라이스:** 해당 기능의 정상·실패·중복 요청을 HTTP부터 DB까지 검증할 수 있는 PR 단위다.

## Scope

### In Scope

- 사용자 조작에 필요한 bounded 목록·상세와 typed command
- actor 유형과 ACTIVE membership 또는 전용 Operations grant
- optimistic version, source uniqueness, 목적별 command replay 원장 및 보존 처리
- 동일 transaction의 business state와 actor/reason을 포함하는 Audit
- target/runtime OpenAPI, 인증 경로 registry, 오류 및 권한 문서
- 실제 PostgreSQL·API·동시성·장애·Modulith 검증
- 기능별 상세 PR 설명과 부모/자식 SHA 검증

### Non-goals

- 전체 운영 장애 큐·감사 로그·정산 대사 10개 API 프로그램의 대체 구현
- API 사용자 인터페이스, 기존 PR #125/#150의 임의 병합·close·base 변경
- 실제 계좌 지급, 과거 주문·확정 정산 스냅샷 재작성, Provider 결과 수동 성공 처리
- 재고/픽업/수수료의 fake source 또는 default 값으로 새 매장을 주문 가능하게 만드는 동작
- 새로운 production dependency

## Business Rules and Invariants

사용자가 승인한 권한 결정을 BR-54와 ADR-124에 기록했다.

- 픽업 슬롯: ACTIVE same-store OWNER와 STAFF. 재고 관련 구현은 제외한다.
- 매장 개설·수수료 계약·membership 관리·이의 판정·수동 복구: 전용 grant가 있는 Platform Operator.
- 이의 철회: ACTIVE same-store OWNER. 판정은 고객이나 점주에게 허용하지 않는다.
- 조회 grant와 mutation grant를 구분하고 JWT role만으로 privileged command를 허용하지 않는다.

기존 정책에서 파생되는 불변식은 다음과 같다.

1. BR-22~24의 접수 window, 하나의 active dispute, 한 번의 재이의와 held 의미를 유지한다.
2. 승인 Adjustment commit 뒤 판정 저장이 실패해도 반대 판정으로 해당 Adjustment를 숨기지 않는다.
3. Store와 검색 profile 및 검색어는 같은 owner transaction으로 저장한다. 좌표는 범위와 SRID를 검증한다.
4. 새 Store는 기본 주문 차단 상태로 생성한다. 계약·카탈로그·픽업 조건이 없는데 정상 주문 가능 상태를
   추정하지 않는다. 실제 값을 받지 않은 수수료·재고·픽업 슬롯을 생성하지 않는다.
6. PickupSlot 정원은 `reservedCount + confirmedCount`보다 작아질 수 없다. 예약/확정이 있는 슬롯의
   시간 변경은 거절하고, 슬롯 변경과 신규 예약은 같은 slot row lock으로 직렬화한다.
7. 수수료 계약 구간은 store별 중첩되지 않는다. 새 버전은 과거 OrderSettlementInputSnapshot을 바꾸지 않는다.
   ADR-071과 V18에 따라 기존 구간은 수정하지 않는다. 새 구간은 Store lock 아래 중첩 없이 추가한다.
   종료일이 없는 계약과 겹치는 등록은 409이며 계약 종료/정정 정책은 별도 결정이 필요하다.
8. membership의 `(actorId,storeId)` 유일성과 현재 상태 검증을 유지한다. 철회와 authoring의 lock 순서를
   고정하여 이미 철회된 권한으로 새 명령이 commit되지 않도록 한다.
9. 수동 복구는 동일 owner source만 재개하며 source payload·정산액·Provider key를 새로 추정하지 않는다.
   결과불명 외부 발송을 새 delivery로 복제하지 않는다. 미지원 recovery target은 명시적으로 거절한다.
10. 동일 actor/operation/key와 payload는 최초 결과를 재생하고 다른 payload는 409다. 권한 검증은 replay
    전에 수행한다. business state, response ledger, Audit 실패는 함께 rollback한다.

## Architecture and Transaction Boundaries

Controller는 DTO·입력 검증·actor·correlation을 Application Service에 전달한다. Repository를 직접 호출하지
않는다. Operations는 owner Context의 공개 typed port만 호출한다.

- Merchant: Store/profile/검색어, 수수료 계약의 owner command와 read projection
- Identity: StoreMembership의 행 잠금과 lifecycle; Merchant Session의 매 요청 권한 확인
- Fulfillment: PickupSlot의 시간·정원 관리와 예약 수량 guard
- Dispute: 판정 intent/actor/reason/replay 및 판정 상태; Settlement는 Adjustment만 소유
- Notification: 수동 복구 대상 delivery 상태와 동일 Provider key의 내구 재개
- Ordering: persistent publication의 target/source 검증, bounded retry budget과 수동 재개
- Operations: 전용 grant, 감사 및 각 owner에 대한 관리 facade

외부 Provider와 event listener 실행은 요청 DB transaction 밖에서 수행한다. 재개 요청은 내구 acceptance를
저장한 뒤 202를 반환하며 원본 owner의 실제 상태를 별도 조회한다.

## Alternatives Considered

- 내부 함수에 Controller만 추가: 판정 intent, grant, Audit actor, retry·부분 commit의 검증이 빠져 기각한다.
- 모든 기능을 공용 CRUD controller와 JSON patch로 구현: owner 불변식과 권한을 우회하므로 기각한다.
- 기능별 typed service와 목적별 replay persistence: 구현량은 증가하지만 실패/동시성/감사를 검증할 수 있어 추천한다.
- main에서 독립 schema writer 실행: #150의 활성 writer와 충돌하므로 현재 실행하지 않는다.
- #150 이후 여섯 기능을 하나의 migration lane/stack에서 순차 구현: 사용자 승인으로 선택했다.

## Failure Semantics

- 400: 범위·수량·시간·enum·cursor 등 invalid input
- 401/403: actor·Session·CSRF·grant·membership 실패; 조회 장애는 권한 없음으로 숨기지 않고 503
- 404: 접근 범위 내 target 부재; cross-store 노출 의미는 기존 인증 계약에 맞춰 고정
- 409: stale version, 다른 payload의 key 재사용, 중첩 계약, 사용량보다 작은 정원, 양립 불가능한 판정
- 503: source/DB/Audit/ledger 장애; 빈 목록·0·성공으로 변환하지 않는다
- 202: 내구 저장된 수동 복구 접수. worker 결과는 실제 owner 상태로만 확인한다

## Data and Migration

- 현재 baseline은 #150의 V73까지다. 이 작업은 V74부터 증분 DDL을 순차 작성한다.
- #150 head 이후 동일 worktree에서 한 기능씩 작성하며 독립 schema writer를 실행하지 않는다.
- 새 migration은 시작 시 최신 combined inventory를 읽고 순차 번호를 선택한다. 기존 applied migration을
  수정하거나 checksum repair하지 않는다.
- 각 기능의 command replay와 필요한 version/index/constraint를 같은 기능 PR에 둔다.
- 새 FK와 원장이 추가되면 PostgreSQL test cleanup 및 migration fixture를 함께 갱신한다.
- 완료 command의 retention은 기존 BR-26과 일치시킨다. 진행/결과불명 복구는 retention으로 제거하지 않는다.

## API and Event Contracts

아래는 endpoint 설계 후보이며 아직 runtime 계약으로 게시하지 않는다. 권한·정책과 기능별 원자성 설계가
닫힌 뒤 해당 slice에서 target/runtime OpenAPI를 함께 추가한다.

| Slice | 후보 HTTP surface | 검토해야 할 핵심 |
|---|---|---|
| Dispute | Operations 목록/상세/검토/판정, same-store OWNER 철회 | 승인 intent와 별도 Adjustment commit, 동일 source 재시도 |
| Store | Operations 매장 목록/상세/개설/식별 정보 교체 | profile 필수 필드, 좌표, 검색 동기화, 초기 주문 차단 |
| Pickup | Merchant 관리 전용 슬롯 목록/상세/생성/교체 | 고객 조회 경로와 인증 충돌 방지, 시간·정원 guard |
| Terms | Operations 매장별 계약 목록/상세/새 버전 | 기간 중첩 DB guard, Order snapshot 및 final quote 직렬화 |
| Membership | Operations 소속 목록/추가/역할 변경/철회 | ACTIVE account, actor-store unique, revoke 경쟁 |
| Recovery | Operations 알림/publication 상세/재개 | exact target, bounded retry, 원래 key, 원본 결과·Audit |

## Milestones

0. 완료: 재고 제외·추천 권한 승인, #150 exact head 확인 및 BR-54/ADR-124 기록.
1. 정산 이의제기 사용자 종결 슬라이스 구현·검증·commit·첫 PR.
2. 매장 개설·식별 정보 슬라이스 구현·검증·commit·child PR.
3. 픽업 슬롯 관리 슬라이스 구현·검증·commit·child PR.
4. 수수료 계약 관리 슬라이스 구현·검증·commit·child PR.
5. membership 관리 슬라이스 구현·검증·commit·child PR.
6. 일반 알림 복구와 event publication 복구를 각각 수직 슬라이스 commit·child PR로 구현·검증한다.
8. combined regression, PR별 exact base/head ancestry·원격 CI 확인 및 completion 기록.

## Required Tests

- 각 endpoint의 정상·actor mismatch·CSRF·grant/membership 부재 및 revoke·cross-store·invalid input
- 동일 key/payload replay, changed payload conflict, 다른 actor/operation의 key 격리와 실제 concurrent 요청
- stale expected version 및 동일 target의 두 writer 경쟁
- Audit/response ledger/source 저장 실패의 전체 rollback
- Dispute 승인 Adjustment commit 후 판정 transaction 실패, 같은 승인 replay, 반대 판정 거절
- Store/profile/search 원자성 및 초기 주문 불가, 좌표 validation
- Pickup 소비 중 정원 감소·시간 변경 거절과 예약/관리 명령 동시 실행
- Terms 시간 경계·구간 중첩·다중 writer·기존 주문 snapshot 불변 및 final Order 경합
- Membership create/regrant/revoke/role-change와 authoring 경쟁
- Recovery 이미 완료된 work·잘못된 target·중복 접수·결과불명 발송·listener 실패·retry 소진
- RuntimeOpenApiParity, AuthenticationPathCoverage, Modulith/ArchUnit, 문서 및 migration 검증

## Validation Commands

기능별 exact test class는 실제 구현과 함께 고정하고 실행 결과를 아래 Progress에 기록한다.

```sh
./gradlew test --tests '*RuntimeOpenApiParityTest' --tests '*AuthenticationPath*'
./gradlew spotlessCheck bootJar
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

환경: Docker 29.7.2 응답 확인. production 테스트·빌드·runtime HTTP 실행은 아직 Not run이다.
문서 verifier는 최초 metadata 형식 오류를 수정한 뒤 통과했다: verifier 단위 테스트 18개,
target 199/runtime 189 operation, 377 schema, Business Policy 53개, ADR 123개, Markdown 350개,
ExecPlan 97개 검증. 이 결과는 새 API 구현이나 runtime 성공의 증거가 아니다.

## Observability

기존 correlation ID와 Audit에 실제 actor, operation, target, reason, 이전/이후 상태를 기록한다.
재처리 접수·실행·완료는 별도 상태이며 오류와 작업 ID를 API에서 안전하게 연결한다. raw payload,
Provider credential 또는 개인 정보를 metric label/log에 추가하지 않는다.

## Documentation Updates

- `docs/product/business-policy-decisions.md`, 관련 ADR와 ADR index
- `docs/security/authorization-matrix.md`, `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- owner transaction/invariant 문서와 운영 복구 runbook
- 이 plan의 실제 결과 및 migration lane 증거

각 PR 본문에는 문제와 전후 사용자 동작, endpoint/권한 표, 부모 PR과 리뷰 범위, transaction·lock 순서,
중복/실패/외부 결과불명 처리, migration 영향, 실행한 명령과 Passed/Failed/Not run 결과,
운영 주의사항과 중점 검토 파일을 구체적으로 기록한다.

## Progress

### Slice 3 — 픽업 슬롯

- 선행 commit `c8b5b68`, PR #152 뒤의 `feature/pickup-slot-management-api`에서 작업.
- V76, 관리 목록/상세/생성/교체 4개 endpoint. Identity authoring shared lock과 PickupSlot row lock을 결합.
- 시작한 슬롯 변경, 소비 중 시간 이동, 사용량 미만 정원을 차단한다. 예약/확정 count를 DTO로 받지 않는다.
- signed interval 목록, microsecond 시각 정규화, 멱등 response·Audit 및 90일 bounded retention 추가.
- Passed: 관리 6 + 예약 회귀 11 + API parity/인증 경로/Modulith 3 = 20 tests, spotlessCheck, bootJar.
- 기존 테스트는 consumed slot 시간을 변경하는 대신 테스트 Clock을 이동한다. confirm source fixture도 원본 reserve source로 수정.
- opaque digest의 숫자열을 raw PII로 오인하지 않도록 byte-separated SHA-256 표현과 회귀 assertion 보강.
- 최종 보강 및 부모 `c8b5b68` 반영 뒤 동일 20 tests, spotlessCheck, bootJar 재실행 Passed.
- 문서 verifier Passed: target 221/runtime 211 operations, 401 schemas.

### Slice 2 — 매장 개설·식별 정보

- 선행 commit `0bfcc6f`, PR #151 뒤의 `feature/store-identity-management-api`에서 작업.
- V75, 개설/식별 정보 교체/목록/상세/지역 어휘 조회 구현. 초기 주문 차단과 확인된 지역 필수.
- Merchant public port가 매장·profile·검색어·원장을 소유하고 Operations가 grant/Audit를 같은 transaction에 조정.
- 원장 90일 bounded retention, identity 전용 version 및 actor/filter-bound signed cursor 사용.
- Passed: 매장 통합 7 tests + runtime parity/Modulith 2 tests, spotlessCheck, bootJar.
- Passed: 문서 verifier 18 tests, target 217/runtime 207 operations, 397 schemas.
- 기존 Audit raw PII 검사에 따라 이름·좌표·지역 값은 감사에 복사하지 않고 version/profile digest로 기록.
- 최초 포맷/감사 payload 실패를 수정하고 최종 suite 통과. 실패 중 남은 concurrent test task는 종료 대기를 추가.
- #151의 원격 preflight는 V74 끝 빈 줄을 발견하여 해당 branch에 `0bfcc6f`로 수정 후 child에 fast-forward.
  최종 PR compare 범위의 `git diff --check`를 staged/committed 변경까지 확인한다.

### Slice 1 — 이의 판정

- V74, 6개 endpoint, 현재 grant/OWNER 권한, signed 목록, durable 판정 intent와 response 원장 구현.
- 기존 V28 transition trigger는 intent 최초 기록만 허용하도록 확장하고 intent·terminal·접수 근거 불변성을 유지.
- Passed: 이의 통합·runtime parity·인증 경로·Modulith·인증 ArchUnit·테스트 격리 22 tests, spotlessCheck, bootJar.
- Passed: 문서 verifier 18 tests, target 212/runtime 202 operations, 391 schemas, 54 policies, 124 ADRs.
- 수정한 실패: 최초 테스트 컴파일 helper 이름, YAML 중복 anchor, DB transition trigger의 intent 금지,
  기존 csrf() mock의 token repository 교체에 따른 실제 Session 테스트 간섭. 최종 관련 suite는 재실행 통과.
- 선행 #150 exact head의 remote backend/6 shards/CodeQL Passed, frontend 및 집계 build Failed를 확인.
  후속 PR의 remote CI와 배포는 별도이며 아직 Not run이다.


- [x] 현재 main과 원래 미커밋 변경 조사; 별도 clean worktree 생성
- [x] 원격 main/열린 PR 갱신 및 Inventory 정책·migration lane 충돌 확인
- [x] 일곱 기능의 owner 경계·불변식·API/검증·PR 분할 초안 작성
- [x] 계획 metadata 보정 뒤 `scripts/verify-docs.sh` 통과
- [x] 권한 승인 및 Inventory 제외 결정
- [x] #150 exact head에서 사용자 승인 sequential writer 시작
- [ ] 각 수직 슬라이스 구현·검증·commit·PR
- [ ] combined validation 및 exact remote gates

## Surprises & Discoveries

- 조사 시점의 로컬 main보다 원격 main이 앞서 있었다. #149가 원래 checkout의 event recovery 변경을
  이미 통합했으므로 격리 worktree는 최신 원격을 사용한다.
- #150은 단순 카탈로그 API 추가를 넘어 Inventory 제거 및 초기 DDL 변경을 포함한다. 현재 요청의
  재고 관리 기능과 공존 여부를 임의로 결정하지 않는다.

## Decision Log

- 2026-09-10: 기능별 수직 슬라이스 commit 및 Stacked PR 작성 범위가 승인됐다. merge/deployment는 별도다.
- 2026-09-10: 추천 권한과 #150 후속 stack을 승인받았으며 재고는 명시적으로 제외한다.

## Outcomes & Retrospective

구현 진행 중이다. 아래 검증 결과와 PR은 각 수직 슬라이스 완료 시 기록한다.

## Revision Notes

- 2026-09-10: 일곱 관리 기능 조사 결과를 구현 계획으로 정리하고 최신 #150과의 충돌을 기록했다.

### 수수료 계약 슬라이스 검증 (2026-09-10)

- 선행 픽업 PR #153 head `523bb7c`에서 구현. PR #151의 packaged migration 최종 버전 검증 수정을
  #152와 #153에 merge하고 ancestry를 유지했다. 새로운 version은 V77이다.
- 기존 ADR-071/V18의 update/delete 금지와 무기한 계약을 보존하며 미래의 비중첩 구간만 추가한다.
- Passed: 계약 관리 5, 기존 계약 repository 3, 기존 주문 정산 입력 snapshot 8, Runtime API parity 1,
  Modulith 1, Flyway smoke 1 = 19 tests. SpotlessCheck, bootJar, 문서 검증도 통과했다.
- 문서 검증: target 199 paths/224 operations, runtime 189 paths/214 operations, 405 schemas.
- 동시 writer 1회 성공, Store shared lock과 등록 직렬화, Audit rollback, 현재 grant와 signed cursor,
  immutable version/과거 snapshot을 검증했다. 최초 포맷 실패를 수정한 후 최종 suite가 통과했다.
- 전체 backend suite 및 원격 CI는 별도 gate다. 재고와 UI 구현은 포함하지 않았다.

### 소속 관리 슬라이스 검증 (2026-09-10)

- 선행 계약 PR #154 head `310b587`에서 Identity 소속 목록·상세·추가·역할/상태 교체와 V78을 구현했다.
- Passed: 새 소속 관리 6, 기존 계정 발급 9, 픽업 관리 6, 이의제기 통합 15, Runtime API parity 1,
  Modulith 1, Flyway smoke 1 = 39 tests. SpotlessCheck, bootJar, 문서 검증도 통과했다.
- 문서 검증: target 201 paths/228 operations, runtime 191 paths/218 operations, 409 schemas.
- 역할/철회/재활성화의 실제 권한 반영, 회원권과 credential 분리, 동시 추가 및 authoring shared lock 대기,
  Audit rollback, 현재 운영 grant와 signed cursor를 확인했다. 테스트 정리 누락과 만료 계정 시각 fixture를
  수정한 후 최종 전체 대상 suite가 통과했다.
- 전체 backend suite와 원격 CI는 별도 gate이며 재고 및 UI 변경은 없다.

### 복구 PR 분할 (2026-09-10)

일반 복구 기능은 Notification Provider 호출과 Modulith publication 실행의 실패 모델이 달라 리뷰 범위를
알림 복구(V79)와 publication 복구(V80)의 두 PR로 나눈다. 여섯 기능의 전체 범위는 유지하며 총 일곱 PR이다.
Notification 소유 실행 변경과 공통 Case lifecycle port를 먼저 제공하고, 다음 PR에서 Ordering 실행 원장과
publication 후보 선택·결과 대사를 추가한다. 각 PR은 자체 Runtime API parity와 독립 마이그레이션을 검증한다.

### 알림 수동 복구 슬라이스 검증 (2026-09-10)

- 소속 PR #155 head `10bcff3` 위에 알림 복구를 분리하고 V79를 작성했다. 선행 픽업 PR의 고객 주문
  스냅샷 회귀 테스트 수정은 #154/#155를 거쳐 반영했다. 해당 수정은 고객 취소 21 + 픽업 관리 6 tests를 통과했다.
- Passed: 알림 관리 5, 기존 알림 repository 12, 상태 4, 기존 publication 복구 9, Runtime API parity 1,
  Modulith 1, Flyway smoke 1 = 33 tests. SpotlessCheck, bootJar, 문서 검증도 통과했다.
- 문서 검증: target 204 paths/231 operations, runtime 194 paths/221 operations, 414 schemas.
- 실제 Notification worker에서 동일 Provider key/payload, 추가 시도 한 번, 재실패/lease 소진 후 수동 검토,
  실제 성공 후 Case 해결, Audit rollback과 동시 접수 및 권한/cursor를 확인했다.
- publication 복구 구현은 다음 V80 PR에서 제공한다. 전체 backend suite와 원격 CI는 별도 gate다.

### 픽업 목록 인덱스 중복 제거 (2026-09-10)

최종 stack 전체 검증에서 V35 조회 계획 회귀가 실패했다. V76의 목록 index는 V35 covering index와
동일한 `(store_id, starts_at, id)` key를 중복 생성했다. 새 관리 query의 store/시간 범위/keyset 정렬은
기존 V35 index로 지원되므로 아직 미병합인 V76에서 중복 DDL만 제거한다. 기존 V35와 데이터·상태
제약 및 조회 계획 테스트 기준은 변경하지 않는다. 픽업 기능/기존 조회 계획 및 전체 검증을 다시 수행한다.

- Passed: StoreCatalogQueryMigrationTest 1 + PickupSlotManagementIntegrationTest 6 + FlywayMigrationSmokeTest 1
  + RuntimeOpenApiParityTest 1 = 9 tests, spotlessCheck, bootJar. 기존 조회 계획 테스트 기준은 유지했다.
