# Store Keyword Search Runbook

`POST /api/v1/operations/search-index/rebuild`로 매장 통합 검색 색인을 초기 적재하거나,
검증된 seed·직접 DML 뒤의 매장명/판매 중 메뉴명 변경을 반영하는 절차다. 검색어·고객 좌표를
조사하거나, 매장·메뉴 source를 임의 값으로 보정하는 절차는 아니다.

관련 결정: [BR-47](../product/business-policy-decisions.md),
[ADR-103](../adr/ADR-103-store-search-strategy.md),
[ADR-112](../adr/ADR-112-store-brand-and-administrative-region.md),
[MD-2026-018](../decisions/minor-decisions.md),
[MD-2026-028](../decisions/minor-decisions.md),
[ExecPlan](../exec-plans/completed/productization-70-customer-store-discovery.md).

## 1. 범위와 결과 의미

재색인은 `STORE_NAME`과 `MENU_NAME` term만 교체한다. `BRAND_NAME`과 `REGION_*` term은
각각 브랜드·지역 소유 커맨드가 동기 갱신하므로 이 명령으로 지우거나 복구하지 않는다.

한 HTTP 요청은 전체 색인을 하나의 transaction으로 잠그지 않는다. 매장 하나씩 별도 transaction으로
처리하므로 앞선 매장이 commit된 뒤 다음 매장에서 실패할 수 있다. 따라서 아래 응답의
`complete=false`는 HTTP 200이어도 완료 성공이 아니다.

```json
{
  "indexedStoreCount": 42,
  "skippedStoreCount": 0,
  "failedStoreIds": [],
  "complete": true
}
```

- `indexedStoreCount`: 현재 source에서 `STORE_NAME`·판매 중 `MENU_NAME` term을 교체한 매장 수다.
- `skippedStoreCount`: keyset walk 뒤 해당 Store가 사라져 읽지 않은 수다. 정상적인 zero가 아닌 경우
  즉시 coverage를 다시 확인한다.
- `failedStoreIds`: profile 누락·정규화 실패·DB 오류 등으로 해당 매장 transaction이 실패한 ID다.
  다른 매장 처리는 계속되지만 이 목록이 비어 있지 않으면 결과는 불완전하다.
- `complete`: `failedStoreIds`가 비어 있을 때만 `true`다. 카운트를 합산해 성공 여부를 추정하지 않는다.

재색인 중에는 seed나 직접 DML로 매장·메뉴 source를 바꾸지 않는 통제 창을 사용한다. 이 작업은 전역
snapshot이 아니므로 새 source 변경이 요청의 앞·뒤 어느 쪽에 반영됐는지 추정하지 않는다. 통제 창 밖
변경은 다음 검증된 재색인으로 반영한다.

## 2. 권한과 요청

Operations Bearer JWT의 `PLATFORM_OPERATOR` 역할과 활성 `STORE_BRAND_MANAGE` grant가 **둘 다**
필요하다. Operations chain에는 CSRF를 붙이지 않는다. `Idempotency-Key`는 8~128자의 제어 문자·양끝
공백 없는 값이고, `reason`은 trim 후 1~200자여야 한다. reason에는 검색어, 고객 정보, 정밀 좌표,
secret 또는 raw Idempotency-Key를 넣지 않는다.

```bash
curl -i -X POST "$BASE_URL/api/v1/operations/search-index/rebuild" \
  -H 'Authorization: Bearer <operator-token>' \
  -H 'Idempotency-Key: <new-8-to-128-character-key>' \
  -H 'Content-Type: application/json' \
  --data '{"reason":"verified store source refresh"}'
```

명령을 시작하기 전에 `pg_trgm`과 V57~V64가 적용됐는지 확인한다. 하나라도 다르면 배포 또는 재색인을
진행하지 않는다.

```sql
SELECT extversion
  FROM pg_extension
 WHERE extname = 'pg_trgm';

SELECT version, description, success
  FROM flyway_schema_history
 WHERE version IN ('57', '58', '59', '60', '61', '62', '63', '64')
 ORDER BY installed_rank;

SELECT audit_category
  FROM operations_audit_action_category
 WHERE action = 'STORE_SEARCH_INDEX_REBUILD_REQUESTED';
```

V64의 command ledger와 audit action이 없으면 새 application을 시작하거나 endpoint를 호출하지
않는다. extension 부재·source/DB 장애는 빈 검색 결과나 순차 검색으로 바꾸지 않고 503으로 드러나야
한다.

## 3. 멱등성, 재생과 재시도

같은 actor·Idempotency-Key·정규화 reason의 완료 요청은 저장된 **동일 결과**를 재생한다. 재생은
source를 다시 읽거나 AuditRecord를 하나 더 만들지 않는다. 같은 key에 다른 reason을 보내면
`409 IDEMPOTENCY_KEY_REUSED`다.

| 응답 또는 상태 | 의미 | 안전한 다음 행동 |
|---|---|---|
| `200`, `complete=true` | 완료 결과가 90일 동안 재생 가능하게 저장됨 | coverage가 `1.0`인지 확인하고 evidence만 남긴다 |
| `200`, `complete=false` | 일부 Store는 실패했으며 전체 성공이 아님 | `failedStoreIds`의 owner source를 고친 뒤 **새 key**와 새 reason으로 다시 실행한다. 기존 key는 같은 불완전 결과만 재생한다 |
| `409 IDEMPOTENCY_KEY_REUSED` | key가 다른 reason에 이미 귀속됨 | 새 key를 사용한다. 기존 row를 수정하거나 삭제하지 않는다 |
| `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` + `Retry-After: 2` | 같은 key의 command가 `RUNNING` | 2초는 polling/재시도 pace이지 완료 예상 시간이 아니다. 원 요청 상태를 확인하고 6절을 따른다 |
| `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED` | command가 `UNKNOWN` 또는 `MANUAL_REVIEW` | 자동 재시도하지 않는다. 6절로 간다. 해소했으면 **새 key**로 실행한다 |
| `503 DEPENDENCY_UNAVAILABLE` | source·DB 또는 결과 저장이 확정되지 않음 | 같은 key를 즉시 반복하지 않는다. 먼저 ledger 상태를 확인한다 |

### 실패한 command는 삭제되지 않는다

실패해도 원장 row는 남는다. 지우면 `(actor_id, idempotency_key)`에 묶인 `payload_hash`가 사라져서
같은 key에 다른 reason을 보낸 요청이 `IDEMPOTENCY_KEY_REUSED` 대신 **새 command로 통과**한다.
그래서 실패는 삭제가 아니라 상태로 남긴다.

| state | 뜻 | 같은 key·같은 reason 재요청 |
|---|---|---|
| `RUNNING` | 실행 중이거나 process loss 후 미확정 | `Retry-After`와 함께 409. 자동 재실행 없음 |
| `COMPLETED` | 결과 확정 | 저장된 응답을 90일간 재생 |
| `FAILED_RETRYABLE` | 실패했고 반복이 안전함 | 같은 row에서 `attempt_count`를 올려 다시 실행 |
| `UNKNOWN` | 결과 저장을 확인하지 못함 | 409 manual review. 자동 재실행 없음 |
| `MANUAL_REVIEW` | 재시도 상한(5회) 초과 | 409 manual review. 자동 재실행 없음 |

매장별 재색인은 그 매장의 term을 통째로 교체하므로 반복이 안전하다. `FAILED_RETRYABLE`의 자동
재실행은 그 성질에만 근거한다. 반면 `UNKNOWN`은 결과 row가 commit됐는지 자체를 모르는 상태이므로
같은 근거가 성립하지 않는다.

`RUNNING`에서 결과 저장이 확정되지 않으면 서버가 `UNKNOWN`으로 표시한다. 이 표시는 row가 아직
`RUNNING`일 때만 적용되므로, 실제로 `COMPLETED`가 commit됐다면 그 결과가 이긴다. 호출자가 응답을
받지 못했거나 상태를 확인할 수 없으면 성공·실패 어느 쪽으로도 단정하지 않고 6절의 unknown 처리로
들어간다.

감사 기록의 `source_reference`는 재사용 가능한 Idempotency-Key가 아니라 `command id:attempt`다.
따라서 재시도마다 별도 AuditRecord가 남고, key를 다시 쓰더라도 이전 기록 때문에 새 실행의 감사가
누락되지 않는다.

## 4. Coverage와 source 점검

`beanflow.discovery.search.index.coverage`는 `STORE_NAME` term을 가진 distinct Store 수를
`merchant_store` 전체 수로 나눈 값이다. 성공한 refresh 전에는 `NaN`이라 metric이 노출되지 않고,
refresh 자체가 DB 오류로 실패해도 오래된 비율을 유지하지 않고 metric이 사라진다. `1.0`만 완전
coverage이며 `1.0`보다 큰 값도 clamp하지 않는 데이터 무결성 신호다. metric에는 Store ID, 이름,
검색어가 tag로 들어가지 않는다.

```sql
SELECT
  (SELECT count(*) FROM merchant_store) AS total_store_count,
  (
    SELECT count(DISTINCT store_id)
      FROM discovery_store_search_term
     WHERE term_kind = 'STORE_NAME'
  ) AS indexed_store_count;

SELECT store.id
  FROM merchant_store store
  LEFT JOIN (
    SELECT DISTINCT store_id
      FROM discovery_store_search_term
     WHERE term_kind = 'STORE_NAME'
  ) indexed ON indexed.store_id = store.id
 WHERE indexed.store_id IS NULL
 ORDER BY store.id
 LIMIT 100;

SELECT
  (SELECT count(*) FROM merchant_menu WHERE available) AS available_menu_count,
  (
    SELECT count(*)
      FROM discovery_store_search_term
     WHERE term_kind = 'MENU_NAME'
  ) AS indexed_available_menu_term_count;

SELECT menu.id AS menu_id, menu.store_id
  FROM merchant_menu menu
  LEFT JOIN discovery_store_search_term term
    ON term.source_id = menu.id
   AND term.term_kind = 'MENU_NAME'
 WHERE menu.available
   AND term.id IS NULL
 ORDER BY menu.store_id, menu.id
 LIMIT 100;
```

profile 누락은 재색인에서 skip으로 숨겨지지 않고 해당 Store를 실패로 만든다. 아래 결과가 있으면
source owner가 profile을 복구할 때까지 재색인을 성공으로 선언하지 않는다.

```sql
SELECT store.id
  FROM merchant_store store
  LEFT JOIN merchant_store_discovery_profile profile ON profile.store_id = store.id
 WHERE profile.store_id IS NULL
 ORDER BY store.id
 LIMIT 100;
```

`beanflow.discovery.search.index.update{trigger=REBUILD,outcome=SUCCEEDED|SKIPPED|FAILED}`와
coverage를 같은 시간 범위에서 본다. `FAILED`나 coverage 미보고를 0건/정상 결과로 바꾸지 않는다.

## 5. `RUNNING`과 프로세스 손실

`operations_search_index_rebuild_command.state = 'RUNNING'`은 아직 실행 중이거나 process loss 뒤
결과가 unknown인 상태다. 이 구현은 오래된 `RUNNING`을 자동으로 재실행·완료·삭제하지 않는다.
`COMPLETED`만 90일 뒤 cleanup 대상이다.

승인된 제한 DB session에서 필요한 범위만 확인한다. raw key와 reason을 일반 log, metric tag, ticket
제목이나 chat에 복사하지 않는다.

```sql
SELECT id, actor_id, state, created_at, completed_at, retention_expires_at,
       (response_json IS NOT NULL) AS has_response
  FROM operations_search_index_rebuild_command
 WHERE state = 'RUNNING'
 ORDER BY created_at ASC, id ASC;

SELECT source_reference, count(*) AS audit_count
  FROM operations_audit_record
 WHERE action = 'STORE_SEARCH_INDEX_REBUILD_REQUESTED'
 GROUP BY source_reference
HAVING count(*) > 1;
```

1. 같은 요청을 처리 중인 application instance가 아직 살아 있는지, DB connection/pool 장애가 있었는지
   먼저 확인한다.
2. instance가 종료됐거나 request 결과를 확정할 수 없으면 `RUNNING`을 explicit unknown으로 보존한다.
   row를 `COMPLETED`로 바꾸거나 직접 삭제하고 같은 key를 재사용하지 않는다.
3. committed Store별 transaction이 이미 일부 존재할 수 있으므로 자동 재색인을 시작하지 않는다.
   source와 coverage evidence, `RUNNING` row ID·시간, audit source reference를 포함해 owner에게
   escalation한다. 지원되는 reconciliation/closure command가 생기기 전에는 수동 DB 조작이 없다.

이 정책은 stale row를 정상처럼 지우는 것보다 현재 결과를 모른다는 사실을 드러낸다. 새 자동 recovery나
global lock이 필요해지면 BR-47과 이 runbook을 함께 다시 결정한다.

## 6. 보존과 금지 사항

`COMPLETED`와 `FAILED_RETRYABLE` row는 `created_at + 90일`까지 보존되고, worker는 매 시간 최대
100행을 `FOR UPDATE SKIP LOCKED`로 정리한다. `RUNNING`·`UNKNOWN`·`MANUAL_REVIEW` row는 retention
worker가 삭제하지 않는다. 이 셋은 운영자가 결론을 내려야 하는 상태이고, 자동 정리가 조사 근거를
지워 버리면 안 되기 때문이다. cleanup backlog는 다음 질의로만 확인하며, broad delete나 retention
시간 변경으로 우회하지 않는다.

```sql
SELECT state, count(*) AS command_count, min(created_at) AS oldest_created_at
  FROM operations_search_index_rebuild_command
 GROUP BY state
 ORDER BY state;

SELECT count(*) AS due_count
  FROM operations_search_index_rebuild_command
 WHERE state IN ('COMPLETED', 'FAILED_RETRYABLE')
   AND retention_expires_at <= now();

-- 운영자 확인이 필요한 command
SELECT id, actor_id, state, attempt_count, created_at, last_failure_at
  FROM operations_search_index_rebuild_command
 WHERE state IN ('UNKNOWN', 'MANUAL_REVIEW')
 ORDER BY last_failure_at;
```

- `discovery_store_search_term`을 직접 insert/delete/update하여 coverage를 맞추지 않는다.
- 실패한 Store를 빈 term, placeholder 이름 또는 비판매 메뉴로 색인하지 않는다.
- `RUNNING`·`UNKNOWN`·`MANUAL_REVIEW` ledger row와 AuditRecord를 삭제·수정해 재실행을 강제하지
  않는다. 특히 실패 row를 지워 같은 key를 다시 쓰게 만들지 않는다. 그 삭제가 곧 payload binding을
  없애 다른 reason의 요청을 통과시킨다.
- 검색어·token·고객 좌표를 DB, AuditRecord, log, trace, metric tag 또는 incident evidence에 기록하지
  않는다. DB 조사 결과도 필요한 Store/source 범위만 보관한다.
- Postgres/`pg_trgm` 장애에서 in-memory index, cache, stale search result, 애플리케이션 순차 검색으로
  대체하지 않는다.
