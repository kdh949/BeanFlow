SET LOCAL lock_timeout = '5s';

-- 검색 색인. 한 행은 "이 매장은 이 문자열로 찾을 수 있다"는 사실 하나다(ADR-103 A1, A7).
--
-- display_text는 원본 그대로라 가장 긴 원본인 매장명·메뉴명의 varchar(200)을 따른다. 계획서
-- 초안의 varchar(120)은 브랜드명·법정동명 상한이라 200자 매장명을 담지 못한다.
-- term_normalized는 NFKC 정규화 결과라 원본보다 길어질 수 있어(U+FDFD 한 글자가 18자가 된다)
-- 원본 상한의 두 배를 준다. 이 상한을 넘는 이름은 잘라서 담지 않고 색인 쓰기를 실패시킨다.
-- 잘라 담으면 뒷부분으로는 검색되지 않는 매장이 조용히 생긴다.
--
-- 이 migration은 스키마만 만들고 행을 넣지 않는다. term_normalized는 Kotlin
-- SearchTextNormalizer가 만든 값이어야 하는데(MD-2026-015, 구현 불변식 13) SQL의
-- lower()는 같은 결과를 내지 못한다. 측정된 차이는 MD-2026-018에 있다. 기존 매장·메뉴는
-- StoreSearchIndexRebuildService가 같은 함수로 채운다.
CREATE TABLE discovery_store_search_term (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id) ON DELETE CASCADE,
    term_kind varchar(24) NOT NULL CHECK (term_kind IN (
        'STORE_NAME', 'BRAND_NAME', 'REGION_SIDO', 'REGION_SIGUNGU',
        'REGION_EUPMYEONDONG', 'REGION_RI', 'MENU_NAME'
    )),
    -- 메뉴 term만 출처 행을 가진다. matchedMenus가 매칭된 메뉴를 되짚으려면 필요하고,
    -- 나머지 종류는 매장당 한 행이라 출처가 store_id로 충분하다.
    source_id uuid,
    term_normalized varchar(400) NOT NULL CHECK (length(trim(term_normalized)) > 0),
    display_text varchar(200) NOT NULL,
    weight numeric(3,2) NOT NULL CHECK (weight > 0 AND weight <= 1),
    CONSTRAINT ck_search_term_menu_source
        CHECK ((term_kind = 'MENU_NAME') = (source_id IS NOT NULL))
);

-- source_id가 nullable이라 PK에 넣을 수 없다. 대리 키 id와 COALESCE 식 unique 인덱스로
-- 정체성을 보장한다. 같은 매장에 같은 종류·출처·문자열이 두 번 들어가지 않는다.
CREATE UNIQUE INDEX uq_search_term_identity
    ON discovery_store_search_term (
        store_id, term_kind,
        COALESCE(source_id, '00000000-0000-0000-0000-000000000000'::uuid),
        term_normalized
    );

-- 토큰 후보 탐색은 매장과 무관한 전체 term 스캔이다. ADR-103 2026-08-15 Amendment로
-- trigram 인덱스가 매장명·메뉴명 컬럼이 아니라 이 색인 테이블로 옮겨졌다.
CREATE INDEX ix_search_term_trgm
    ON discovery_store_search_term USING gin (term_normalized gin_trgm_ops);

-- 종류 단위 term 교체(브랜드명 변경, 지역 변경, 재색인)가 매장별로 좁게 지워야 한다.
CREATE INDEX ix_search_term_store_kind
    ON discovery_store_search_term (store_id, term_kind);
