# Merchant catalogue authoring runbook

이 문서는 점주 Store 주문 정책과 Menu 거래 카탈로그 command의 운영 확인·장애 분류·안전한 재시도
절차다. 일반 점주 작업은 `/store/catalog` 화면과 runtime API를 사용한다. DB 직접 수정, command ledger
삭제, stale payload 자동 overwrite 또는 검색 실패를 성공으로 처리하는 절차가 아니다.

관련 결정: [BR-52](../product/business-policy-decisions.md),
[ADR-118](../adr/ADR-118-merchant-transactional-catalog-lifecycle.md),
[Transaction boundaries](../architecture/transaction-boundaries.md),
[Authorization matrix](../security/authorization-matrix.md),
[Quality evidence](../quality/merchant-transactional-catalog-evidence.md).

## 1. 권한과 command 계약

- ACTIVE same-store `OWNER | STAFF`만 주문 정책과 Menu 거래 내용을 조회·변경한다.
- mutation은 Merchant Session, `X-BEANFLOW-CSRF`, 8~128자 `Idempotency-Key`를 요구한다.
- Store 정책과 Menu writer는 membership `FOR SHARE` 뒤 Store `FOR UPDATE` 순서로 잠근다. final Order는
  같은 Store를 `FOR SHARE`로 잠근다. 운영 중 이 순서를 우회하는 별도 SQL writer를 추가하지 않는다.
- Menu mutation은 root·Option·Configuration·requirement, `MENU_NAME` 검색 term, Audit와 terminal command
  response를 한 transaction에 반영한다. 검색 갱신 실패는 503이고 owner 변경도 rollback이다.
- 외부 Provider 호출은 이 transaction에 없고 cache/fake/stale fallback도 없다.

## 2. API와 정상 결과

```text
GET /api/v1/stores/{storeId}/ordering-policy
PUT /api/v1/stores/{storeId}/ordering-policy
GET /api/v1/stores/{storeId}/menu-catalog?lifecycle=ACTIVE|ARCHIVED&cursor=&limit=
GET /api/v1/stores/{storeId}/menus/{menuId}/trade-content
POST /api/v1/stores/{storeId}/menus
PUT /api/v1/stores/{storeId}/menus/{menuId}/trade-content
POST /api/v1/stores/{storeId}/menus/{menuId}/archive
```

동일 actor·key·canonical payload의 완료 command는 최초 status/body를 exact replay한다. replay는 owner
version, `updatedAt`, Audit와 검색 term을 다시 바꾸지 않는다. no-op replace도 version을 올리지 않는다.
다른 payload에 같은 key를 쓰면 409이고 새 command로 취급하지 않는다.

Menu 목록은 ACTIVE가 기본이고 `(name, menuId)` signed keyset cursor를 사용한다. cursor는
actor/store/lifecycle/limit에 묶여 있어 다른 탭·매장·사용자에 재사용하면 400이다. 한 응답으로 active와
archived 전체를 합치거나 cursor 오류를 첫 page로 대체하지 않는다.

## 3. 실패 분류와 안전한 다음 행동

| HTTP / code | 의미 | 안전한 다음 행동 |
|---|---|---|
| 400 `INVALID_REQUEST` | 필드, 중복 ID, cursor/filter/limit 또는 100/500/50 요청 경계 위반 | server current를 읽고 payload를 고친 뒤 새 key 사용 |
| 401 | Merchant Session 부재·만료 | 재로그인. 고객 Session/Bearer로 대체하지 않음 |
| 403 | membership inactive/revoked 또는 역할 부족 | 화면의 store state를 지우고 `/merchant/me/stores` 재조회 |
| 404 | membership/store/menu scope가 보이지 않음 | ID 존재를 추측하지 말고 접근 가능한 Store부터 재선택 |
| 409 `MERCHANT_CONTENT_STALE` | `expectedVersion`이 current 거래 version과 다름 | current 전체 representation을 다시 읽고 사용자 검토 후 새 key로 재제출. 자동 overwrite 금지 |
| 409 `IDEMPOTENCY_KEY_REUSED` | 같은 key가 다른 canonical payload에 귀속 | 원 요청 결과를 확인하고 새 key 사용. ledger 삭제 금지 |
| 409 `RESOURCE_STATE_CONFLICT` | archived target, child ID 충돌 또는 참조 불변식 위반 | current aggregate와 lifecycle을 확인하고 새 의도를 작성 |
| 503 `DEPENDENCY_UNAVAILABLE` | DB·검색 색인 갱신 결과를 확정할 수 없음 | 성공으로 표시하지 말고 같은 key의 terminal response/owner/search/Audit를 함께 조사 |

Menu 보관은 terminal v1 전이다. hard delete나 restore SQL로 되돌리지 않는다. 잘못 보관한 경우 제품
결정 없이 row 상태를 직접 바꾸지 말고 새 Menu 생성 또는 별도 restore 정책 결정을 검토한다.

## 4. 운영 확인

아래 조회는 incident에서 Store/Menu reference와 version/상태를 확인하는 최소 예다. raw payload,
Idempotency-Key, 전체 가격 목록을 log·metric·Audit에 복제하지 않는다.

```sql
SELECT id, accepting_orders, pickup_enabled, ordering_policy_version, ordering_policy_updated_at
  FROM merchant_store
 WHERE id = :store_id;

SELECT id, store_id, lifecycle, trade_version, updated_at, archived_at
  FROM merchant_menu
 WHERE store_id = :store_id
 ORDER BY name, id;

SELECT term_kind, count(*)
  FROM discovery_store_search_term
 WHERE store_id = :store_id
 GROUP BY term_kind
 ORDER BY term_kind;

SELECT action, actor_type, target_type, target_id, occurred_at
  FROM operations_audit_record
 WHERE target_id IN (:store_id, :menu_id)
 ORDER BY occurred_at DESC
 LIMIT 50;
```

command ledger의 stored response와 payload hash는 replay 판정 근거다. incident 정리를 위해 row를
update/delete하거나 raw key를 ticket/log에 복사하지 않는다. owner row만 바뀌고 검색·Audit·ledger가
없거나 그 반대라면 정상 부분 성공이 아니라 transaction 경계 위반으로 escalation한다.

## 5. 검색 freshness와 복구

production Menu create/replace/archive는 `MENU_NAME` term을 같은 transaction에서 갱신한다. 정상 API가
200/201을 반환했는데 term이 다르면 운영자 rebuild로 조용히 덮기 전에 owner/command/Audit transaction
증거를 보존하고 장애로 분류한다.

검증된 seed·직접 DML 또는 과거 데이터 복구 뒤에는
[Store keyword search runbook](store-keyword-search-runbook.md)의 rebuild와 coverage 절차를 사용한다.
rebuild의 `complete=false`는 HTTP 200이어도 성공이 아니며 stale 검색을 빈 결과로 대체하지 않는다.

## 6. rollout과 rollback

- V69와 V70, runtime application, OpenAPI/generated client, `/store/catalog` 소비자를 같은 stack 순서로
  배포한다. migration 없이 새 application을 시작하지 않는다.
- migration 적용 뒤에는 command traffic을 열기 전에 Flyway version 70, authentication path parity,
  runtime OpenAPI parity와 representative OWNER/STAFF read를 확인한다.
- 장애 시 새 mutation traffic을 중단하고 기존 committed 주문·Menu snapshot을 보존한다. migration을
  임의 down하거나 ledger/Audit/search row를 부분 삭제하지 않는다.
- Provider sandbox와 production deployment는 이 로컬 evidence 범위가 아니다. 실행하지 않았다면
  `Not run`으로 남긴다.
