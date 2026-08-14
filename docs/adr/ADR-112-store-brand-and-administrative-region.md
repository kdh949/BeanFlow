# ADR-112: 매장 브랜드 Aggregate와 행정구역 어휘

- **Status:** Accepted
- **Date:** 2026-08-15
- **Implementation owner:** [Customer store discovery](../exec-plans/active/productization-70-customer-store-discovery.md)

## Context

[ADR-103](ADR-103-store-search-strategy.md)의 2026-08-15 Amendment는 검색 대상에 브랜드명과
지역명을 추가한다. 그런데 현재 source에는 두 개념이 모두 없다.

- **브랜드:** `merchant_store`, `merchant_store_discovery_profile` 어디에도 브랜드 컬럼이 없고
  Brand Entity·테이블·API도 없다. `merchant` 모듈에서 `brand`라는 낱말이 나오는 유일한 곳은
  `V6__create_external_payment_reconciliation.sql`의 `card_brand`이며 무관하다.
- **지역:** `merchant_store_discovery_profile`은 `name`과 `location geography(Point,4326)`만
  가진다. 좌표는 있지만 주소·행정구역이 없어 `"강남구"`로 검색할 근거 데이터가 존재하지 않는다.

즉 검색 기능을 붙이기 전에 두 데이터의 **소유권, 어휘, 쓰기 주체, 필수성**을 먼저 정해야 한다.
이는 Bounded Context 소유권과 스키마·마이그레이션 변경이므로 `docs/decisions/README.md`의
ADR 후보 기준에 해당한다.

또한 `merchant` 모듈에는 현재 `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`이
하나도 없다. 매장과 메뉴는 `LocalDemoSeedCli`와 테스트 fixture로만 생성된다. 브랜드·지역은
BeanFlow에서 **처음으로 추가되는 Merchant 쓰기 경로**다.

## Decision

### 1. Brand는 `merchant`가 소유하는 독립 Aggregate다

```sql
CREATE TABLE merchant_brand (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    normalized_name varchar(120) NOT NULL CHECK (length(trim(normalized_name)) > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_merchant_brand_active_normalized_name
    ON merchant_brand (normalized_name) WHERE status = 'ACTIVE';

ALTER TABLE merchant_store ADD COLUMN brand_id uuid REFERENCES merchant_brand(id);
```

- 매장은 브랜드를 **ID로만 참조**한다([ADR-003](ADR-003-aggregate-reference-by-id.md)).
  JPA 객체 연관관계와 cascade를 만들지 않는다. Brand는 매장 목록을 소유하지 않는다.
- 브랜드명 정규화는 검색 색인과 동일한 함수를 쓴다(NFKC → 소문자 → 공백 축약 → trim).
  활성 브랜드의 정규화 이름은 유일하며 중복 등록은 `409`다.
- `brand_id`는 **nullable**이다. 브랜드 없는 개인 카페가 정상 상태이며, 그런 매장에
  1:1 브랜드를 만들도록 강제하면 브랜드 개념이 희석된다.

### 2. Region은 법정동 코드 기반 폐쇄 어휘다

```sql
CREATE TABLE merchant_region (
    code varchar(10) PRIMARY KEY,
    sido varchar(40) NOT NULL CHECK (length(trim(sido)) > 0),
    sigungu varchar(40) NOT NULL DEFAULT '',
    eupmyeondong varchar(40) NOT NULL DEFAULT '',
    full_name varchar(120) NOT NULL CHECK (length(trim(full_name)) > 0)
);

ALTER TABLE merchant_store_discovery_profile
    ADD COLUMN region_code varchar(10) REFERENCES merchant_region(code);
```

- 행정안전부 법정동 코드 10자리를 식별자로 쓰고 **폐지되지 않은 항목만** 시드한다.
- 매장주는 신규 생성 없이 기존 코드를 **선택만** 한다. 자유 텍스트 입력을 허용하지 않는다.
  `강남구`와 `강남 구`가 서로 다른 값으로 저장되면 지역 검색 정확도가 무너지고, 나중에 지역
  필터·집계로 재사용할 수 없기 때문이다.
- `sigungu`와 `eupmyeondong`은 `NOT NULL DEFAULT ''`다. 세종특별자치시처럼 시군구 계층이 없는
  행정구역이 존재하므로 `NOT NULL`에 빈 문자열을 허용하고 `NULL`은 쓰지 않는다.

### 3. `region_code`는 백필 뒤 NOT NULL로 승격한다

V33 → V34의 `merchant_store_discovery_profile` 커버리지 gate 선례를 그대로 따른다.

1. 컬럼을 nullable로 추가한다.
2. 운영자가 각 매장의 법정동을 채운다.
3. 별도 마이그레이션이 `SET NOT NULL`로 fail-closed 검증한다.

한 마이그레이션에서 컬럼 생성과 NOT NULL을 동시에 하면 값을 채워 넣을 순간이 없다. 지역이 빈
매장은 지역명 검색에서 조용히 사라지므로 nullable로 방치하지 않는다.

브랜드에는 커버리지 gate를 두지 않는다. 브랜드 없음이 정상 상태이기 때문이다.

### 4. 쓰기 주체를 분리한다

| 명령 | 주체 | 근거 |
|---|---|---|
| 브랜드 생성·수정·보관 | `PLATFORM_OPERATOR` | 브랜드는 여러 매장이 공유하는 자원이다. 매장주가 임의로 바꾸면 사칭과 상호 혼선이 생긴다 |
| 매장의 브랜드 지정·해제 | `PLATFORM_OPERATOR` | 위와 같다. 매장이 스스로 유명 브랜드에 편입될 수 없어야 한다 |
| 매장의 지역 지정 | 해당 매장의 `STORE_OWNER` | 매장 주소는 매장주가 가장 정확히 안다. 공유 자원이 아니므로 사칭 위험이 없다 |

`STORE_STAFF`는 지역을 바꿀 수 없다. 매장 식별 정보 변경은 소유자 권한이다.
모든 명령은 AuditRecord를 남긴다.

### 5. 검색 색인 갱신 인터페이스는 `shared/api`에 둔다

`merchant`의 브랜드·지역 커맨드는 색인을 갱신해야 하고, `discovery`의 검색은 매장 상태를
읽어야 한다. 두 방향을 그대로 두면 `merchant ↔ discovery` 순환 의존이 생겨 Spring Modulith
구조 검증이 깨진다.

색인 갱신 port를 `shared/api`에 선언하고 `discovery/internal`이 구현한다. `merchant`는
`shared` 인터페이스에만 의존하고 `discovery`를 모른다. 기존
`discovery → merchant`(`StoreDiscoveryQueryOperations`) 방향은 그대로 유지한다.

### 6. 브랜드명 변경의 fan-out에 상한을 둔다

브랜드명을 바꾸면 소속 매장의 `BRAND_NAME` 색인 term을 모두 갱신해야 한다. 같은 트랜잭션에서
처리하므로 소속 매장이 많으면 긴 잠금이 생긴다. 소속 매장 `1000`개를 상한으로 두고 초과 상태에서
이름 변경을 시도하면 `409 BRAND_FANOUT_LIMIT_EXCEEDED`로 **명시적으로 거절**한다.
비동기 큐로 우회하지 않는다. 상한에 도달하면 그때 배치 갱신 설계를 ADR로 다시 결정한다.

## Alternatives Considered

### 1. 매장 프로필에 `brand_name` 텍스트 컬럼만 추가

- 장점: 마이그레이션과 API가 가장 단순하고 검색은 당장 동작한다.
- 단점: `스타벅스`와 `스타벅스코리아`가 별개 브랜드가 되는 것을 막을 수 없다. 브랜드명을 바꾸려면
  모든 매장 행을 수정해야 하고, 나중에 진짜 Aggregate로 승격할 때 데이터 정제가 필요하다.
  브랜드 페이지·브랜드 단위 정산으로 확장할 근거도 남지 않는다.

### 2. 브랜드를 이번 범위에서 제외

- 장점: 범위가 줄어 매장명·메뉴명·지역명만으로 먼저 출시할 수 있다.
- 단점: 프랜차이즈 카페 검색이 제품 요구의 핵심이다. `스타벅스`를 쳐서 안 나오는 카페 앱은
  검색이 있다고 보기 어렵다.

### 3. 모든 매장에 브랜드를 필수화

- 장점: `brand_id`가 `NOT NULL`이라 조인과 색인이 단순하다.
- 단점: 개인 카페마다 1:1 브랜드 행이 생겨 브랜드 개념이 매장의 별칭으로 퇴화한다.

### 4. 지역을 자유 텍스트 3칸(시도·시군구·읍면동)으로

- 장점: 시드 데이터가 필요 없고 마이그레이션이 가볍다.
- 단점: 표기 흔들림을 막을 수 없다. 지역 필터·집계로 재사용할 수 없고, 나중에 코드 체계로
  옮기려면 전수 정제가 필요하다.

### 5. 좌표에서 역지오코딩으로 지역을 자동 산출

- 장점: 매장주 입력 부담이 없고 좌표와 지역이 항상 일치한다.
- 단점: 외부 Provider 의존이 생긴다. `AGENTS.md`가 요구하는 실패 정책·timeout·fallback 금지
  설계가 통째로 추가되며, Provider 장애 시 매장 등록이 막힌다. 입력 1회 비용 대비 운영 비용이 크다.

### 6. 운영자가 서비스하는 지역만 등록

- 장점: 시드가 수십 행으로 가벼워 즉시 시작할 수 있다.
- 단점: 신규 매장이 새 지역에 들어올 때마다 운영자 선행 작업이 병목이 된다. 매장 등록이 지역
  등록을 기다리는 구조는 운영 실패 모드를 하나 늘린다.

## Rationale

브랜드와 지역은 성격이 정반대다. **브랜드는 여러 매장이 공유하는 자원**이라 정체성과 권한 통제가
필요하고, **지역은 매장 하나에 붙는 사실**이라 소유자가 스스로 관리하는 것이 정확하다. 그래서
한쪽은 Aggregate로, 다른 쪽은 폐쇄 어휘 참조로 모델링하고 쓰기 주체도 분리했다.

두 데이터 모두 "나중에 바꾸기 비싼" 축에 속한다. 브랜드를 텍스트 컬럼으로 시작하면 중복
브랜드가 쌓인 뒤에 정제해야 하고, 지역을 자유 텍스트로 시작하면 표기가 갈린 뒤에 정제해야 한다.
초기 비용이 조금 더 들더라도 정규화된 형태로 시작하는 편이 총비용이 낮다.

## Consequences

- Flyway 마이그레이션 3개가 추가된다(어휘·브랜드 스키마, 색인 테이블, 지역 커버리지 gate).
  ADR-072의 migration-writer lease가 필요하다.
- 법정동 시드가 약 2만 행 추가된다. 생성 스크립트가 원본을 결정적으로 변환하며 시드 SQL은
  재실행 가능한 `INSERT ... ON CONFLICT DO NOTHING`이다.
- `merchant`에 **처음으로 쓰기 엔드포인트가 생긴다.** 운영자 브랜드 명령과 매장주 지역 명령이다.
- `shared/api`에 색인 갱신 port가 추가되고 `discovery/internal`이 구현한다.
- 매장·메뉴는 여전히 쓰기 API가 없으므로 시드나 직접 DML로 바뀐다. 그 변경은 재색인 커맨드로만
  색인에 반영되며, 이 한계는 커버리지 gauge와 runbook으로 관측 가능하게 남긴다.
- 브랜드 소속 매장 1000개 상한이 제품 제약으로 생긴다.

## Verification

- V33→V34 선례대로 `region_code`가 비어 있는 매장이 하나라도 있으면 커버리지 마이그레이션이
  실패하는지 검증한다.
- 활성 브랜드 정규화 이름 동시 등록에서 하나만 성공하고 다른 하나가 `409`인지 검증한다.
- 브랜드명 변경이 소속 매장 색인 term을 **같은 트랜잭션에서** 모두 갱신하고, 색인 갱신을 강제
  실패시키면 브랜드 변경도 롤백되는지 검증한다.
- 소속 매장 1000개 초과 상태의 이름 변경이 `409`이고 부분 갱신이 남지 않는지 검증한다.
- `STORE_STAFF`와 타 매장 소유자의 지역 변경이 `403`인지 검증한다.
- `STORE_OWNER`의 브랜드 생성이 `403`인지 검증한다.
- 세종특별자치시처럼 `sigungu`가 빈 문자열인 행정구역이 정상 저장·검색되는지 검증한다.
- Spring Modulith 구조 검증에서 `merchant ↔ discovery` 순환 의존이 없는지 확인한다.
- 법정동 시드가 재실행 가능하고 두 번 실행해도 행 수가 같은지 검증한다.

## Implementation evidence (2026-08-15)

Milestone 1이 이 결정의 스키마와 지역 어휘 부분을 실현했다. 브랜드·지역 **명령**과 색인 갱신은
아직 구현하지 않았으므로 위 Verification 중 명령·권한·fan-out·순환 의존 항목은 `Not run`이다.

- V57이 `merchant_region`, `merchant_brand`와 활성 브랜드 부분 unique index를 만들고
  `merchant_store.brand_id`, `merchant_store_discovery_profile.region_code`를 nullable로 추가한다.
  `StoreSearchVocabularyMigrationTest`가 두 컬럼이 nullable인 것을 고정한다. 지금 NOT NULL로 만들면
  기존 매장이 있는 환경에서 값을 채울 창이 없어 배포가 불가능하기 때문이다.
- V58이 폐지되지 않은 법정동 **20,560행**을 시드한다. 역삼동 `1168010100`이
  `서울특별시`/`강남구`/`역삼동`으로, 세종특별자치시 `3611010100`이 빈 `sigungu`로 저장되는 것을
  통합 테스트가 확인한다. 시드를 다시 실행해도 행 수가 같다.
- **보강:** 시도 행을 `<시도>00000000`으로 가정할 수 없다. 세종특별자치시에는 그 코드가 없고
  `3611000000`이 최상위라 151행이 통째로 누락된다. 2자리 접두사별 최소 코드를 시도로 삼는다.
  또 `"경기도 부천시 원미구"`처럼 시군구가 두 단어인 행이 있어 계층 분해는 공백이 아니라 법정동
  코드 자릿수로 해야 한다.
- **한계:** 시드 20,560행 중 15,209행이 리 단위다. 스키마에 리 열이 없고 term 종류도 셋뿐이라
  리 행은 상위 읍면동 이름을 `eupmyeondong`에 담고 리 이름은 `full_name`에만 남는다. 리는 선택
  가능한 코드로 존재하지만 리 이름으로는 검색되지 않는다. Revisit Conditions의 더 세밀한 상권
  단위와 같은 성격의 한계이며 결함으로 감추지 않는다.

## Metrics

- 브랜드 지정 매장 비율
- 지역 커버리지(색인된 매장 수 / 전체 매장 수)
- 브랜드·지역 명령의 결과별 수와 권한 거부 수
- 브랜드명 변경 1회당 갱신된 색인 term 수
- 색인 갱신 실패로 롤백된 명령 수

## Revisit Conditions

- 브랜드 소속 매장이 1000개 상한에 도달할 때
- 브랜드 단위 정산·브랜드 소유자 계정·브랜드 페이지가 요구될 때
- 매장·메뉴 쓰기 API가 생겨 색인 동기 갱신을 흡수할 수 있게 될 때
- 법정동보다 세밀한 상권 단위(`홍대`, `가로수길`) 검색이 실제 요구가 될 때
- 행정구역 개편으로 시드 갱신 주기가 운영 부담이 될 때

## Related Decisions

- [ADR-103](ADR-103-store-search-strategy.md) — 이 ADR의 데이터를 소비하는 검색 전략
- [ADR-003](ADR-003-aggregate-reference-by-id.md) — Aggregate 간 ID 참조
- [ADR-020](ADR-020-nearby-location-privacy.md) — 매장 프로필과 위치 privacy
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md) — migration-writer lease
- [BR-47](../product/business-policy-decisions.md) — 검색·브랜드·지역의 제품 정책 수치
