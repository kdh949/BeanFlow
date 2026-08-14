SET LOCAL lock_timeout = '5s';

-- 부분 일치가 실패한 토큰만 유사도로 구제한다(ADR-103 A2). 색인 테이블과 GIN trigram
-- 인덱스는 단계 2에서 만들고 여기서는 extension만 준비한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 법정동 코드 기반 폐쇄 어휘. 시드는 다음 migration이 넣는다.
-- 세종특별자치시처럼 시군구 계층이 없는 경우가 있어 sigungu는 NULL이 아니라 빈 문자열이다.
-- 리 단위 행은 상위 읍면동 이름을 eupmyeondong에 담고 full_name에만 리 이름이 남는다.
CREATE TABLE merchant_region (
    code varchar(10) PRIMARY KEY,
    sido varchar(40) NOT NULL CHECK (length(trim(sido)) > 0),
    sigungu varchar(40) NOT NULL DEFAULT '',
    eupmyeondong varchar(40) NOT NULL DEFAULT '',
    full_name varchar(120) NOT NULL CHECK (length(trim(full_name)) > 0)
);

CREATE TABLE merchant_brand (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    normalized_name varchar(120) NOT NULL CHECK (length(trim(normalized_name)) > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

-- 활성 브랜드끼리만 정규화 이름이 유일하다. 보관된 브랜드는 같은 이름을 다시 쓸 수 있다.
CREATE UNIQUE INDEX uq_merchant_brand_active_normalized_name
    ON merchant_brand (normalized_name) WHERE status = 'ACTIVE';

-- 브랜드는 없는 상태가 정상이므로 nullable이고 커버리지 gate를 두지 않는다.
ALTER TABLE merchant_store ADD COLUMN brand_id uuid REFERENCES merchant_brand(id);

CREATE INDEX ix_merchant_store_brand
    ON merchant_store (brand_id) WHERE brand_id IS NOT NULL;

-- 지역은 나중에 NOT NULL이 된다. V33 -> V34 선례대로 여기서는 nullable로 만들고
-- 운영자가 값을 채운 뒤 단계 3 migration이 SET NOT NULL로 커버리지를 단언한다.
ALTER TABLE merchant_store_discovery_profile
    ADD COLUMN region_code varchar(10) REFERENCES merchant_region(code);

CREATE TABLE discovery_customer_favorite_store (
    customer_id uuid NOT NULL,
    store_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (customer_id, store_id)
);

-- 즐겨찾기 목록은 고객별 최신순이다. store_id를 끝에 두어 동시각 항목의 순서를 고정한다.
CREATE INDEX ix_discovery_favorite_customer_created
    ON discovery_customer_favorite_store (customer_id, created_at DESC, store_id);
