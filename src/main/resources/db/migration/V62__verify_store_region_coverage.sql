SET LOCAL lock_timeout = '5s';

-- Fail-closed coverage gate for the store's administrative region (ADR-112 3절).
--
-- V33 -> V34 선례를 그대로 따른다. 컬럼은 V57이 nullable로 만들었고, 매장주가
-- PUT /stores/{storeId}/region으로 값을 채운 뒤 이 migration이 커버리지를 단언한다.
-- 한 migration에서 컬럼 생성과 NOT NULL을 함께 하면 값을 넣을 순간이 없고, 원장과 gate를
-- 한 migration에 담아도 마찬가지다. 그래서 명령 원장은 V61, gate는 여기다.
--
-- 기존 매장이 있는 환경의 배포는 두 단계다. target V61까지 올리고, 애플리케이션을 배포해
-- 매장주가 지역을 지정하게 한 뒤, 나머지 migration을 돌린다.
--
-- 지역이 빈 매장은 지역명 검색에서 조용히 사라진다. 그래서 좌표에서 역지오코딩한 값이나
-- 시도 기본값 같은 대체 출처로 채우지 않고, 미지정 행 하나만 있어도 배포를 멈춘다.
DO $$
DECLARE
    unresolved bigint;
BEGIN
    SELECT count(*)
      INTO unresolved
      FROM merchant_store_discovery_profile
     WHERE region_code IS NULL;

    IF unresolved <> 0 THEN
        RAISE EXCEPTION
            'Region coverage migration found % store discovery profile row(s) without a region_code',
            unresolved;
    END IF;
END
$$;

ALTER TABLE merchant_store_discovery_profile
    ALTER COLUMN region_code SET NOT NULL;
