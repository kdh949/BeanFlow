import { LocateFixed, RefreshCw, Search } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { StoreCard } from "./StoreCards";
import { coordinatesOf, useBrowserLocation } from "./useBrowserLocation";
import { Button } from "../../design-system";

type StoreSearchPage = components["schemas"]["StoreSearchPage"];
type NearbyStorePage = components["schemas"]["NearbyStorePage"];

const MIN_QUERY_LENGTH = 2;

export function StoreSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("query") ?? "";
  const [draft, setDraft] = useState(query);
  const { state: location, locate } = useBrowserLocation();
  const coordinates = coordinatesOf(location);

  function submit(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams(searchParams);
    const trimmed = draft.trim();
    if (trimmed) next.set("query", trimmed);
    else next.delete("query");
    setSearchParams(next, { replace: true });
  }

  return (
    <div className="customer-page store-search-page">
      <PageTitle eyebrow="FIND A STORE" title="매장 찾기" description="매장, 브랜드, 지역 또는 메뉴 이름으로 찾을 수 있어요." />
      <form className="store-search-bar" onSubmit={submit} role="search">
        <label htmlFor="store-search-query">검색어</label>
        <div>
          <Search size={18} aria-hidden="true" />
          <input
            id="store-search-query"
            value={draft}
            placeholder="예: 성수 라떼"
            autoComplete="off"
            onChange={(event) => setDraft(event.target.value)}
          />
          <Button type="submit" disabled={draft.trim().length < MIN_QUERY_LENGTH}>찾기</Button>
        </div>
        <small>{MIN_QUERY_LENGTH}글자 이상 입력해 주세요.</small>
      </form>

      <button className="location-pill" type="button" onClick={locate} disabled={location.status === "locating"}>
        <LocateFixed size={17} /> {location.status === "locating" ? "위치 확인 중" : coordinates ? "현재 위치 다시 찾기" : "현재 위치로 찾기"}
      </button>
      {location.status === "denied" ? (
        <p className="inline-note" role="status">위치 권한이 꺼져 있어 거리 대신 검색어로만 찾을 수 있어요. 브라우저 설정에서 위치 권한을 허용하면 가까운 매장을 볼 수 있어요.</p>
      ) : null}
      {location.status === "unavailable" ? (
        <p className="inline-note" role="status">현재 위치를 확인하지 못했어요. 검색어로 매장을 찾아 주세요.</p>
      ) : null}

      {query.length >= MIN_QUERY_LENGTH
        ? <SearchResults query={query} coordinates={coordinates} />
        : coordinates
          ? <NearbyResults coordinates={coordinates} />
          : <EmptyState title="찾고 싶은 매장을 알려주세요" description="검색어를 입력하거나 현재 위치로 가까운 매장을 찾을 수 있어요." />}
    </div>
  );
}

function SearchResults({ query, coordinates }: { query: string; coordinates: { latitude: number; longitude: number } | null }) {
  const [page, setPage] = useState<StoreSearchPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    try {
      const next = unwrap(
        await customerApi.GET("/stores/search", {
          params: {
            query: {
              query,
              limit: 20,
              cursor,
              ...(coordinates ? { ...coordinates, sort: "distance" as const } : {}),
            },
          },
        }),
      );
      setPage((current) => (append && current ? { ...next, items: [...current.items, ...next.items] } : next));
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMore(false);
    }
  }, [coordinates, query]);

  useEffect(() => { void load(); }, [load]);

  if (!page && !error) return <LoadingState label="매장을 찾는 중" />;
  if (!page) return <ErrorState error={error} retry={() => void load()} />;
  if (page.items.length === 0) {
    return <EmptyState title={`'${query}' 검색 결과가 없어요`} description="다른 매장, 브랜드, 지역 또는 메뉴 이름으로 찾아보세요." />;
  }
  return (
    <>
      <section className="store-result-list" aria-label="검색 결과">
        {page.items.map((store) => (
          <StoreCard
            key={store.storeId}
            store={{
              storeId: store.storeId,
              name: store.name,
              open: store.open,
              pickupAvailable: store.pickupAvailable,
              distanceMeters: store.distanceMeters,
              caption: store.matchedMenus.length ? store.matchedMenus.map((menu) => menu.name).join(" · ") : store.brandName ?? store.regionName,
              image: store.image,
            }}
          />
        ))}
      </section>
      {error ? <ErrorState error={error} retry={() => void load(page.page.nextCursor, true)} /> : null}
      {page.page.nextCursor ? (
        <Button block variant="secondary" type="button" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          <RefreshCw size={16} className={loadingMore ? "spin" : undefined} /> {loadingMore ? "더 불러오는 중" : "매장 더 보기"}
        </Button>
      ) : null}
    </>
  );
}

function NearbyResults({ coordinates }: { coordinates: { latitude: number; longitude: number } }) {
  const [page, setPage] = useState<NearbyStorePage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    try {
      const next = unwrap(
        await customerApi.GET("/stores/nearby", {
          params: { query: { ...coordinates, radiusMeters: 10_000, limit: 20, cursor } },
        }),
      );
      setPage((current) => (append && current ? { ...next, items: [...current.items, ...next.items] } : next));
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMore(false);
    }
  }, [coordinates]);

  useEffect(() => { void load(); }, [load]);

  if (!page && !error) return <LoadingState label="가까운 매장을 찾는 중" />;
  if (!page) return <ErrorState error={error} retry={() => void load()} />;
  if (page.items.length === 0) {
    return <EmptyState title="가까운 매장이 없어요" description="반경 10km 안에 픽업 가능한 매장이 없습니다." />;
  }
  return (
    <>
      <section className="store-result-list" aria-label="가까운 매장">
        {page.items.map((store) => <StoreCard key={store.storeId} store={store} />)}
      </section>
      {error ? <ErrorState error={error} retry={() => void load(page.page.nextCursor, true)} /> : null}
      {page.page.nextCursor ? (
        <Button block variant="secondary" type="button" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          <RefreshCw size={16} className={loadingMore ? "spin" : undefined} /> {loadingMore ? "더 불러오는 중" : "매장 더 보기"}
        </Button>
      ) : null}
    </>
  );
}
