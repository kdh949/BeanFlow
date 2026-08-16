# 핸드오프: PR #74에 origin/main 병합하고 충돌 해소

## 배경

- 저장소: `BeanFlow` / 브랜치: `feature/productization-70-operator-brand-commands`
- PR: https://github.com/kdh949/BeanFlow/pull/74 (base `main`, 20 커밋, 77 파일, `CONFLICTING`)
- 이 브랜치는 ExecPlan `productization-70-customer-store-discovery`의 **Milestone 6~12**다.
  M1~M5는 PR #71로 이미 main에 들어갔다.
- 브랜치 base 이후 main이 **15 커밋** 나아갔다.
  - `#71` 머지 — 그 안에 **`b9c5586 fix: 검색 색인 정합성 및 관측 보강`**이 들어 있다. ★가장 중요
  - `#72` Storybook 디자인 시스템
  - `#73` Scalar API 문서 도입 + OpenAPI 스펙 부분 한국어화 **및 YAML 전체 재직렬화**
- 병합 방향은 **`git merge origin/main`** (rebase 아님). 이 저장소는 일반 merge를 허용한다.

## 목표

`origin/main`을 브랜치에 병합해 PR #74을 `MERGEABLE`로 만든다. **공개 HTTP 계약과 M6~M12의
동작은 바꾸지 않는다.** 충돌 해소로 인해 동작이 바뀌어야 한다면 그것은 아래 "판단이 필요한
지점"에 해당하니 임의로 정하지 말고 보고한다.

---

## 충돌: 4파일 9훅

### 1. `openapi/beanflow-v1.yaml` — 3훅

**먼저 알아야 할 것: #73은 단순 번역이 아니다.** YAML을 전체 재직렬화했다.

- `"400":` → `'400':` (큰따옴표 → 작은따옴표)
- `$ref: "#/..."` → `$ref: '#/...'`
- `tags: [Operations, Discovery]` → 블록 시퀀스(`tags:\n  - Operations`)

**재적용하는 모든 내용은 main의 이 스타일을 따라야 한다.** HEAD 쪽 스타일을 그대로 가져오면
diff가 지저분해지고 이후 재직렬화에서 또 충돌한다.

**또 하나: 한국어화는 부분적이다.** `/stores/nearby`, `/stores/{storeId}/menus` 등 runtime
스펙에 실린 endpoint만 한국어이고, `/stores/search`와 `/me/*`는 **여전히 영문**이다. 전부
번역하려 들지 말고 각 훅에서 main이 택한 언어를 따른다.

| 훅 | 위치 | 해소 방법 |
|---|---|---|
| 1 | `/stores/nearby`의 `description` | **합친다.** main의 한국어 본문을 base로 삼고, HEAD가 추가한 두 번째 문단(pickupAvailable이 7일 창 안 예약 가능 슬롯을 요구한다 / `/stores/search`와 같은 의미다 / 필터가 공간 질의 뒤에 적용돼 page가 짧아도 마지막 검사 candidate에 앵커된 nextCursor가 나온다)을 **한국어로 추가**한다. ⚠️ 아래 "함정 2" 필독 |
| 2 | `/operations/search-index/rebuild` path 전체 | **HEAD 쪽을 살린다.** main에는 이 path가 없다(순수 추가인데 인접 블록이 재직렬화돼 충돌났다). main의 인용부호·블록 스타일로 바꿔서 넣는다 |
| 3 | `SearchIndexRebuildRequest` / `SearchIndexRebuildResponse` 스키마 | **양쪽 다 살린다.** main 쪽 훅에는 기존 `ClearStoreBrandRequest`의 `description`/`example`이 들어 있다. 그걸 지우면 안 된다. HEAD의 두 스키마를 그 뒤에 추가한다 |

추가로 `components/responses`에 `SearchIndexRebuildConflict`가 필요하다(자동 병합으로 들어갔는지
확인할 것). `RadiusMeters` 파라미터의 `default: 3000`도 HEAD 변경이니 살아남았는지 확인한다.

### 2. `src/main/kotlin/.../discovery/internal/StoreSearchIndexRebuildService.kt` — 2훅

**이게 유일하게 설계가 갈린 충돌이다. 여기서 실수하면 리뷰에서 이미 고친 것이 되돌아간다.**

main의 `b9c5586`은 리뷰 지적을 반영한 **의도적 재설계**다. ExecPlan Decision Log에 이렇게
적혀 있다: *"재색인 대상은 UUID keyset이 아니라 시작 ID snapshot. 완료는 snapshot 범위에 한정"*.

| | HEAD (우리 M10) | origin/main (`b9c5586`, 더 최신 결정) |
|---|---|---|
| 대상 열거 | `findStoreIdsAfter(...)` live keyset 순회 | `findRebuildTargetStoreIds()` 시작 snapshot |
| chunk 크기 | `@Value("...chunk-size:100")` | `StoreSearchIndexRebuildProperties` 주입 |
| 결과 타입 | `shared/api`의 `StoreSearchIndexRebuildResult` | 이 파일 안 `internal data class`, `targetStoreCount` + `completeSnapshot` 보유 |
| 인터페이스 | `: StoreSearchIndexRebuildOperations` 구현 | 아무것도 구현 안 함 |

**해소 규칙: main의 재색인 알고리즘(snapshot + `properties.chunkSize`)을 택하고, 그 위에
HEAD의 운영자 명령 계층(`StoreSearchIndexRebuildOperations` 구현)을 다시 얹는다.**

즉 결과적으로:
- `rebuildAll()`은 main의 snapshot 방식 본문을 쓴다 (충돌 아래쪽 본문은 이미 main 것으로 자동
  병합돼 있으니, 위쪽 선언부만 main에 맞추면 대개 맞아떨어진다)
- 클래스는 `StoreSearchIndexRebuildOperations`를 계속 구현하고 `override fun rebuildAll()`이다
- 결과 data class는 **`shared/api`에 있어야 한다.** 파일 로컬로 되돌리면 `operations` →
  `discovery` 역방향 의존이 생겨 Modulith 순환이 되고, 그게 바로 MD-2026-028이 막은 것이다.
  main이 이 파일에 넣어둔 `internal data class StoreSearchIndexRebuildResult` 선언은 삭제하고,
  main이 그 클래스에 달아둔 **KDoc의 의도("부분 재색인이 완전한 것으로 읽히지 않도록 실패를
  개수가 아니라 store id로 보고한다")는 `shared/api` 쪽으로 옮겨 보존한다.**

### 3. `docs/exec-plans/completed/productization-70-customer-store-discovery.md` — 3훅

HEAD가 `active/` → `completed/`로 옮겨서 rename 충돌로 뜬다.

**경로는 `completed/`를 택한다**(M12까지 끝났으므로). 내용은 **어느 한쪽을 고르지 말고 합집합**을
만든다. main 쪽에만 있는 다음 기록이 실재하는 사실이라 지우면 안 된다:

- Progress: `Milestone 2 리뷰 보강`(V57/V59 FK·cascade, 재색인 snapshot 전환, gauge 분리),
  `CI timeout 보강`(GitHub Actions run `31893493864` 등), `CI job 분리`
- Decision Log: 2026-08-16자 5행 (snapshot 전환, FK cascade, freshness 분리, test worker 2개,
  CI job 분리)
- Outcomes & Retrospective: 위 3건에 대응하는 3문단

세 훅 모두 "HEAD 내용 뒤에 main 항목을 시간순으로 끼워 넣기"로 해소된다.
`> **Status:** COMPLETED` 헤더와 `Completed-At: 2026-08-16`은 HEAD 것을 유지한다.

### 4. `docs/architecture/store-search-technology-selection.md` — 1훅

파일 앞쪽 인용 블록의 "현재 상태" 문장 하나다. main은 *"Milestone 1·1-B·2 구현 완료, 검색·
즐겨찾기 API는 후속 단계"*라고 적혀 있는데 이 브랜치 기준으로는 낡았다.

**HEAD 문장을 택한다**(M12 증빙까지 완료 + 미측정 항목 경고). main 쪽에만 있는 사실은 없다.

---

## ⚠️ 충돌 마커가 안 붙는 함정 3건 — 이게 진짜 위험하다

### 함정 1. `findStoreIdsAfter`가 조용히 사라진다

`merchant/api/StoreSearchTermSourceQuery.kt`는 **충돌 없이 자동 병합되는데, 그 결과가 main
버전이다.** 우리 M10이 넣은 `findStoreIdsAfter(...)`가 인터페이스에서 사라지고
`findRebuildTargetStoreIds()` / `findAllSearchTermSources()` / `findSearchTermSource()` 세 개만
남는다. 구현체 `StoreSearchTermSourceQueryService.kt`도 마찬가지다.

main의 설계를 택하기로 했으니 **이게 올바른 결과다.** 다만 아무 경고 없이 일어나므로,
`findStoreIdsAfter`를 호출하던 코드와 테스트가 남아 있으면 컴파일이 깨진다.
`grep -rn "findStoreIdsAfter" src/` 로 잔재를 반드시 확인한다.

### 함정 2. `scripts/verify-docs.sh`가 스펙에서 **영문 문자열**을 찾는다

우리 M6이 `verify-docs.sh`에 다음 4개 영문 조각을 스펙 description에서 찾는 단언을 넣었다:

- `/stores/nearby` description: `'reservable slot inside the'`, `'same meaning as on GET /stores/search'`, `'last examined candidate'`
- `/stores/search` description: `'pickupAvailable additionally requires a reservable slot'`

`/stores/search`는 main에서도 영문이라 통과한다. 문제는 **`/stores/nearby`는 main이
한국어화했다**는 것. 충돌 훅 1에서 nearby description을 한국어로 쓰면 이 세 단언이 깨진다.

**해결: #73이 이미 쓰고 있는 관행을 따른다.** main의 nearby description을 보면
`canonical micrometer distance tuple(정규 마이크로미터 단위 거리 튜플)`처럼 **단언 대상 영문
조각을 그대로 남기고 괄호로 한국어 뜻을 붙였다.** 같은 방식으로 세 조각을 인라인 보존한다.
단언 쪽을 한국어로 바꾸는 것도 가능하지만, 그러면 #73의 관행과 갈라지므로 권하지 않는다.

`verify-docs.sh`는 main이 건드리지 않았으므로 이 파일 자체는 충돌하지 않는다.

### 함정 3. 병합 후 측정치는 전부 무효다

PR #74 본문의 `1,269건 / 실패 0 / 40m 20.5s`, `runtime 143 paths / 152 operations` 같은 수치는
**병합 이전** 상태의 것이다. 병합 후 반드시 재측정하고, PR 본문의 검증 결과 절과 ExecPlan
Progress를 새 수치로 갱신한다. 재측정 전에는 옛 수치를 근거로 "통과한다"고 쓰지 않는다.

---

## 판단이 필요한 지점 (임의로 정하지 말 것)

`StoreSearchIndexRebuildResult`의 필드가 양쪽에서 다르다.

- main: `targetStoreCount`, `indexedStoreCount`, `skippedStoreCount`, `failedStoreIds`, `completeSnapshot`
- HEAD + **공개 OpenAPI 스키마**: `indexedStoreCount`, `skippedStoreCount`, `failedStoreIds`, `complete`

즉 main의 내부 결과에는 `targetStoreCount`가 있는데 공개 응답 계약에는 없다.

**권장안:** 공개 응답 계약(`SearchIndexRebuildResponse`)은 **그대로 두고**, main의 풍부한 결과를
`shared/api`로 옮긴 뒤 controller에서 응답으로 매핑할 때 `targetStoreCount`를 떨어뜨린다.
`completeSnapshot` → 공개 `complete`로 이름만 맞춘다. 계약을 넓히는 것은 별건이다.

다르게 가야 한다고 판단되면 **코드만 고치지 말고** ADR-103 / MD-2026-028 / OpenAPI를 먼저
갱신하고 그 근거를 보고한다.

---

## 검증 절차

순서대로 전부 돌린다. 하나라도 못 돌렸으면 결과에 `Not run`이라고 명시한다.

```bash
grep -rn "findStoreIdsAfter" src/          # 함정 1: 잔재 0건이어야 함
grep -rn "<<<<<<<\|>>>>>>>" src/ docs/ openapi/ scripts/   # 마커 잔재 0건
./gradlew spotlessApply
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
./gradlew build --stacktrace
```

`./gradlew build`는 40분 이상 걸린다. **다른 Gradle 명령과 절대 동시에 돌리지 말고, 빌드 중에
소스를 수정하지 않는다** (그러면 결과가 무의미해진다).

테스트 총계는 `build/test-results/test/TEST-*.xml` 파싱으로는 안 나온다(Gradle 9.6.1).
`build/reports/tests/test/index.html`의 `<div class="counter">` 값(순서대로 tests / failures /
ignored / duration)이 유일한 근거다.

프런트엔드도 확인한다:

```bash
cd frontend && npm run generate:api && npx tsc --noEmit && npm test
```

마지막으로 `ModularityTests`가 통과하는지 별도로 확인한다 (함정 1·2와 직결).

```bash
./gradlew test --tests "io.github.kdh949.beanflow.architecture.*"
```

## 마무리

1. 병합 커밋 하나로 정리한다. 메시지 예: `merge: origin/main 병합과 재색인 snapshot 설계 정합화`
2. 위 검증 결과(실측 수치)로 **PR #74 본문의 "검증 결과" 절과 "머지 전 처리 필요" 절을 갱신**한다.
   충돌이 해소됐으면 후자는 지운다.
3. ExecPlan `Progress`에 병합과 재검증 결과를 한 항목으로 추가한다.
4. push는 하되 **merge 버튼은 누르지 않는다.**

## 지켜야 할 저장소 규칙

- 코드·테스트·OpenAPI·Business Policy·ADR·ExecPlan이 충돌하면 임의로 하나를 고르지 않는다.
  충돌 파일·상충 내용·영향 범위·추천 해결안을 보고한다. 기존 결정을 바꾸려면 관련 ADR 또는
  Business Policy를 **먼저** 갱신한다. Accepted ADR을 소스 코드만으로 우회하지 않는다.
- 실패한 의존성을 local·in-memory·fake·mock·cached·stale·no-op 구현으로 자동 대체하지 않는다.
- 실행하지 않은 검증은 `Not run`으로 표시한다. 실패한 검증을 숨기지 않는다. 비교 가능한 측정
  없이 성능 향상을 주장하지 않는다.
- BR-28 / ADR-020 / Invariant 17: 검색어·토큰·정밀 좌표를 DB, application log, metric tag,
  trace, 이벤트에 남기지 않는다.
- 명시적 요청 없이 commit 외의 push·PR 생성·원격 변경을 하지 않는다(이 작업은 push까지 허용).
