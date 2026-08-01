# ADR-074: 전역 기본값과 매장별 일반 적립 정책 우선순위

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owner:** [ordinary accrual policy/snapshot foundation](../exec-plans/completed/ordinary-point-accrual-policy-management.md)

## Context

ADR-073은 Order 생성 시 일반 적립 정책과 계산 결과를 immutable snapshot으로 고정하지만,
어떤 current policy를 선택하는지는 정하지 않았다. BeanFlow는 여러 매장의 주문을 처리하고
Order는 `storeId`를 가진다. 반면 현재 Merchant에는 Brand Aggregate나 Store→Brand 관계가 없고,
일반 적립 policy head도 없다.

전역 policy 하나만 두면 구현은 작지만 특정 매장에 다른 적립 정책을 적용할 수 없다. 매장별
policy만 두면 모든 Store에 head를 미리 만들어야 하고 누락 policy를 어떤 값으로 보완할지라는
fallback 문제가 생긴다. live policy를 완료 시점에 다시 선택하면 정책 변경 뒤 과거 주문의
적립·환불 회수 결과가 바뀐다.

## Decision

### Scope key와 선택 순서

- Operations는 일반 적립 policy의 append-only version과 current head를 소유한다.
- 적용 scope는 `GLOBAL`과 `STORE`다. `GLOBAL` head는 정확히 하나가 필수이고 `STORE` head는
  `storeId`별 선택적 override다.
- Order 생성 transaction은 자신의 `storeId`와 일치하는 사용 가능한 STORE override가 있으면
  그 current version을 선택하고, 없으면 GLOBAL current version을 선택한다.
- `BRAND` scope와 Store→Brand lookup은 현재 모델에 없으므로 이번 결정에 포함하지 않는다.
- STORE override 누락은 오류가 아니라 명시된 GLOBAL fallback 조건이다. GLOBAL head/version
  누락, head와 version key 불일치 또는 persistence failure는 주문 생성 전체를 실패시키며
  hard-coded 설정, 0 bps, PLATFORM issuer 또는 stale/cache policy로 대체하지 않는다.

### STORE override lifecycle

- STORE policy version의 state는 `OVERRIDE` 또는 `INHERIT_GLOBAL`이다.
- `OVERRIDE` version은 rate, rounding, issuer와 expiry를 모두 가지며 그 값을 사용하는 current
  store policy다.
- `INHERIT_GLOBAL` version은 rate, rounding, issuer와 expiry를 복제하지 않는다. current STORE
  head가 이 state를 가리키면 Order 생성은 그 transaction 시점의 GLOBAL current version을
  선택한다.
- STORE head가 아직 없을 때와 `INHERIT_GLOBAL`일 때의 결과는 GLOBAL fallback으로 같지만,
  후자는 운영자의 명시적 복귀 명령, actor, reason과 Audit 이력을 보존한다.
- override 시작, 변경, `INHERIT_GLOBAL` 전환과 재활성화는 모두 새 version insert와 head CAS로
  수행한다. 과거 version과 head 변경 전 Order snapshot을 수정하거나 삭제하지 않는다.

### Immutable version과 issuer 의미

- 각 version은 scope key, 적립률 bps, 반올림 방식, issuer type/reference, 만료 규칙·기간,
  effective time, updated actor, 변경 reason과 idempotency source를 저장한다.
- issuer type/reference는 scope에서 추론하지 않는 literal cost-owner snapshot이다. STORE scope가
  선택됐다고 issuer type 또는 reference를 주문의 storeId로 자동 보정하지 않는다.
- 선택한 policy version ID와 모든 policy input·계산 결과는 ADR-073의
  `OrderPointAccrualSnapshot`에 저장한다. 완료와 환불 처리자는 current head를 다시 읽지 않는다.

### Closed calculation vocabulary

- `accrualRateBps`는 `0..10_000`이다. `0`은 명시적인 적립 중지 version이며 missing policy나
  dependency failure의 fallback이 아니다.
- rounding은 `FLOOR`와 `HALF_UP`만 허용한다. `finalPayableKrw × accrualRateBps / 10_000`의
  gross에 한 번 적용하고 ADR-073 unit allocation은 그 결과를 배분한다.
- expiry rule은 `EXACT_DURATION_FROM_COMPLETION`과
  `SEOUL_CALENDAR_DAYS_FROM_COMPLETION`이고 `validityDays`는 `1..3650`이다.
- exact rule은 `completedAt + validityDays × 24h`다. 서울 달력일 rule은
  `completedAt`의 `Asia/Seoul` 현지 날짜를 첫 유효일로 세고
  `completedLocalDate.plusDays(validityDays)` 00:00의 Instant를 exclusive expiry로 사용한다.
- issuer type은 `PLATFORM|BRAND|STORE`, reference는 trim 뒤 1..240자다. 현재 authoritative
  issuer registry가 없으므로 policy command는 reference 존재를 추측하지 않고, scope 또는
  Order store에서 다른 값으로 보정하지 않는다.
- gross가 0이어도 snapshot과 source 처리 결과는 남긴다. Loyalty는 0원 PointLot 또는 0원
  `ACCRUAL` transaction을 만들지 않되 같은 completion replay가 새 side effect를 만들지 않도록
  terminal source를 보존한다.

### 변경 선형화

- policy update와 Order 생성은 같은 store-scope advisory transaction key를 먼저 사용한다. Order
  selection은 shared mode, policy update는 exclusive mode로 최초 STORE head 생성과 head 부재 조회를
  선형화하면서 동시 Order selection은 허용한다. 기존 STORE head도 같은 read/write lock mode로 읽는다.
- current STORE state가 `OVERRIDE`면 그 version을 선택한다. head가 없거나
  `INHERIT_GLOBAL`이면 GLOBAL head를 잠그고 current GLOBAL version을 선택한다. GLOBAL update도
  같은 GLOBAL head를 잠근다.
- Order snapshot transaction이 먼저 commit하면 기존 version을, policy update가 먼저 commit하면
  새 version을 사용한다. 실패하거나 rollback한 update는 어떤 Order에도 적용된 것으로 보지 않는다.
- 이미 생성된 Order snapshot, 완료 적립 PointLot/원장, 부분 환불 `RECOVERY`와
  PointRecoveryPending은 head 변경으로 수정하거나 backfill하지 않는다.

### 최초 GLOBAL bootstrap과 startup gate

- 최초 GLOBAL version/head는 migration seed나 HTTP endpoint가 아니라 controlled deployment job의
  별도 `ordinary-accrual-policy-bootstrap` command로 만든다.
- command는 ADR-069 permission bootstrap과 같은 verified short-lived OIDC workload identity trust를
  사용한다. raw token은 read-only mounted file에서만 읽고 token, file path, 자유 입력 reason과
  evidence body를 저장·로그하지 않는다.
- input은 closed vocabulary의 완전한 policy 값, non-blank reason, immutable evidence reference와
  correlation ID다. verified principal 확인 뒤 GLOBAL version, head와 target AuditRecord를 같은
  local transaction에 insert한다.
- GLOBAL head가 이미 있으면 overwrite, idempotent success 또는 새 initial version을 만들지 않고
  `POLICY_ALREADY_INITIALIZED` state conflict로 non-zero 종료한다. DB/lock/Audit 실패도 no partial
  state와 non-zero 결과다.
- 정상 HTTP application은 Flyway 완료 뒤 정확히 한 GLOBAL head가 완전한 GLOBAL policy version을
  가리키는지 검사한다. 누락·key mismatch·불완전 값·DB 장애는 startup failure다. bootstrap용
  non-web context와 명시적 test profile만 최초 생성 전 precheck를 실행하지 않는다.
- 특정 initial rate, issuer와 validity 값은 deployment input이며 source constant가 아니다. 이후
  변경은 offline command가 아니라 audited operator API만 사용한다.
- policy/snapshot migration 전에 존재한 Order는 initial GLOBAL policy 대상이 아니다. migration은
  ADR-073의 `LEGACY_NOT_APPLICABLE` source만 backfill하고 policy result를 역산하지 않는다. 정상
  server가 시작된 뒤 새 Order만 GLOBAL/STORE selection을 수행한다.

### Online permission boundary

- current/history 조회는 별도 `POINT_ACCRUAL_POLICY_READ`, 생성·변경·상속 전환은
  `POINT_ACCRUAL_POLICY_WRITE` active grant를 요구한다. 기존
  `EXPIRED_BENEFIT_POLICY_READ|WRITE`를 재사용하지 않는다.
- read는 required `X-Access-Reason`, write는 `Idempotency-Key`, expected head version과 body reason을
  요구하고 projection/version 또는 변경 결과와 AuditRecord가 같은 Operations transaction에서
  commit된 경우에만 성공한다.
- ADR-069의 offline permission bootstrap은 forward migration으로 확장된 closed vocabulary에서 두
  grant를 부여·회수할 수 있다. role 또는 JWT permission claim은 fallback이 아니다.

## Alternatives Considered

### 전역 singleton만 사용

head와 lookup이 가장 단순하지만 매장별 미래 정책을 표현할 수 없다.

### 매장별 head만 사용

모든 선택이 명시적이지만 신규·누락 Store에 대한 초기화와 장애 정책이 필요하고, 전역 운영 변경을
모든 head에 원자적으로 반영하기 어렵다.

### 전역과 매장 override

GLOBAL은 완전한 기본 정책을 보장하고 STORE는 필요한 곳만 차등 운영할 수 있다. 대신 override
lifecycle, 조회 화면과 precedence 검증이 추가된다. 이 대안을 선택한다.

### Brand scope도 함께 도입

향후 브랜드 단위 운영에는 유용하지만 현재 Store→Brand authoritative relation이 없어 free-form
reference나 추측 lookup을 만들게 된다.

## Rationale

Order가 이미 가진 `storeId`만으로 결정적 exact-match를 수행하고, 일치하지 않을 때 하나의 필수
GLOBAL head로 수렴하면 wildcard·브랜드 추론 없이도 다점포 차등 정책을 지원할 수 있다. 선택 결과를
Order 생성 시 고정하면 운영 변경과 완료·환불의 시간 차이에도 결과가 변하지 않는다.

## Consequences

- Operations policy API는 GLOBAL current와 STORE별 override를 구분해 조회·변경해야 한다.
- Ordering은 Operations의 typed policy boundary를 호출해 snapshot을 같은 Order 생성 transaction에
  저장한다.
- STORE override를 만들거나 되돌리는 command에는 append-only history, 권한, Audit와 동시성 계약이
  필요하다.
- STORE update와 Order lookup은 head 부재도 선형화하는 같은 store-scope advisory lock name을
  exclusive/shared mode로 사용해야 한다.
- 배포 순서는 policy schema/permission vocabulary migration을 적용하는 offline bootstrap,
  read/write grant bootstrap, 정상 HTTP application 시작이다.
- migration은 ADR-072의 migration-writer lease 아래에서만 추가한다.

## Verification

- override가 없는 Store는 GLOBAL version을 선택한다.
- override가 있는 Store만 STORE version을 선택하고 다른 Store는 GLOBAL을 유지한다.
- `INHERIT_GLOBAL` 전환 뒤 미래 Order는 당시 GLOBAL current를 선택하고 전환 전 Order snapshot은
  기존 STORE version을 유지한다.
- 최초 override 생성과 동시 Order 생성은 commit 순서에 맞춰 STORE 또는 GLOBAL 중 하나를 선택한다.
- GLOBAL/STORE update와 Order 생성 경쟁은 commit 순서에 맞는 완전한 한 version만 snapshot한다.
- policy 변경 뒤 기존 Order snapshot, PointLot/원장, recovery/pending row는 변하지 않는다.
- missing GLOBAL, head/version mismatch와 persistence failure는 주문 전체 rollback이며 fallback이 없다.
- bps 경계, FLOOR/HALF_UP 차이, 두 expiry rule의 완료 시각·서울 자정 경계와 0 bps no-lot source
  replay를 검증한다.
- invalid workload identity, invalid input, first apply, repeated bootstrap, DB/Audit failure와 정상
  startup precheck의 missing/mismatch/dependency failure를 검증한다.
- migration 기존 Order의 exact marker cardinality, policy 값 backfill 부재와 신규 Order의 mandatory
  snapshot/source를 검증한다.

## Revisit Conditions

Brand Aggregate와 Store→Brand 관계가 생기거나, 고객 segment·campaign·시간 예약 발효 같은 새
precedence 차원이 필요하거나, store override 수가 전역 변경 운영을 어렵게 만들 때

## Related Decisions

- BR-10, BR-20
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [ADR-073](ADR-073-order-point-accrual-snapshot.md)
