import { ArrowRight, ChevronRight, LocateFixed, MapPin, Search, ShoppingBag, Store } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { coordinatesOf, useBrowserLocation } from "../../features/discovery/useBrowserLocation";
import { useResource } from "../../features/shared/useResource";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshMobileTopbar, RefreshStoreCard } from "./RefreshShared";
import { Button, ButtonLink, SearchField } from "../../design-system";

type CustomerOrderSummary = components["schemas"]["CustomerOrderSummary"];
type StoreRecommendation = components["schemas"]["StoreRecommendation"];
type StoreSearchPage = components["schemas"]["StoreSearchPage"];
type NearbyStorePage = components["schemas"]["NearbyStorePage"];

const recommendationLabels: Record<StoreRecommendation["reason"], string> = {
  FAVORITE: "자주 찾는 매장",
  RECENT: "최근 주문한 매장",
  NEARBY: "가까운 매장",
};

export function RefreshCustomerHomePage() {
  const { state: location, locate } = useBrowserLocation();
  const coordinates = coordinatesOf(location);
  const activeOrders = useResource<CustomerOrderSummary[]>(useCallback(async () => unwrap(await customerApi.GET("/me/orders", { params: { query: { status: "ACTIVE", limit: 3 } } })).items, []));
  const recommendations = useResource<StoreRecommendation[]>(useCallback(async () => unwrap(await customerApi.GET("/me/store-recommendations", { params: { query: { limit: 6, ...(coordinates ?? {}) } } })).items, [coordinates]));

  return (
    <div className="bfr-page bfr-home">
      <section className="bfr-home-hero">
        <h1>지금 마실 커피를<br />찾아보세요</h1>
        <Link to="/app/stores"><span>매장, 지역, 메뉴 검색</span><span className="bfr-home-search-icon"><Search size={25} /></span></Link>
      </section>

      <section className="bfr-home-order" aria-label="진행 중인 주문">
        {activeOrders.state.status === "loading" ? <RefreshLoading label="진행 중인 주문을 확인하는 중" /> : null}
        {activeOrders.state.status === "failed" ? <RefreshError error={activeOrders.state.error} retry={activeOrders.reload} /> : null}
        {activeOrders.state.status === "ready" && activeOrders.state.value.length === 0 ? <RefreshEmpty title="진행 중인 주문이 없어요" description="새 주문을 시작하면 픽업 번호와 준비 상태가 여기에 표시됩니다." /> : null}
        {activeOrders.state.status === "ready" ? <div className="bfr-active-orders">{activeOrders.state.value.slice(0, 1).map((order) => <Link key={order.orderReference} to={`/app/orders/${order.orderReference}`}><span className="bfr-pickup-symbol" aria-hidden="true"><ShoppingBag size={22} /></span><strong>{order.pickupNumber} {order.status === "READY" ? "준비 완료" : order.itemSummary}</strong><span>· {order.storeName}</span><ChevronRight size={22} aria-hidden="true" /></Link>)}</div> : null}
      </section>

      <section className="bfr-home-stores">
        <h2>주문 가능한 매장</h2>
        <div className="bfr-home-store-list">
          {recommendations.state.status === "ready" ? recommendations.state.value.slice(0, 2).map((item, index) => <Link key={item.store.storeId} to={`/app/stores/${item.store.storeId}`}><span className="bfr-store-symbol"><Store size={26} /></span><span><strong>{item.store.name}</strong><small>{index === 0 ? "최근 주문한 매장" : "가까운 매장"}</small></span><b>{item.store.orderingAvailable ? "주문 가능" : "준비 중"}</b><ChevronRight size={20} /></Link>) : null}
          <button type="button" onClick={locate} disabled={location.status === "locating"}><span className="bfr-store-symbol"><MapPin size={27} /></span><span><strong>{location.status === "locating" ? "현재 위치 확인 중" : "현재 위치로 찾기"}</strong><small>내 주변 매장 보기</small></span><ChevronRight size={20} /></button>
        </div>
        {location.status === "denied" ? <p className="bfr-inline-status" role="status">위치 권한이 꺼져 있어 자주 가는 매장과 최근 매장만 보여드려요.</p> : null}
        {location.status === "unavailable" ? <p className="bfr-inline-status" role="status">현재 위치를 확인할 수 없어 저장된 이용 기록을 기준으로 보여드려요.</p> : null}
        {recommendations.state.status === "loading" ? <RefreshLoading label="추천 매장을 불러오는 중" /> : null}
        {recommendations.state.status === "failed" ? <RefreshError error={recommendations.state.error} retry={recommendations.reload} /> : null}
        {recommendations.state.status === "ready" && recommendations.state.value.length === 0 ? <RefreshEmpty title="추천할 매장이 아직 없어요" description="매장 이름이나 메뉴로 직접 찾아볼 수 있어요." action={<ButtonLink variant="brand" to="/app/stores">매장 찾기</ButtonLink>} /> : null}
      </section>
      {recommendations.state.status === "ready" && recommendations.state.value.length ? <section className="bfr-home-recommendations"><header><h2>추천 매장</h2><Link to="/app/stores">전체 보기 <ChevronRight size={17} /></Link></header><div>{recommendations.state.value.slice(0, 4).map((item) => <Link key={item.store.storeId} to={`/app/stores/${item.store.storeId}`}><span className="bfr-store-symbol"><Store size={24} /></span><span><strong>{item.store.name}</strong><small>{recommendationLabels[item.reason]}</small></span></Link>)}</div></section> : null}
    </div>
  );
}

const queryHelpers = ["라떼", "디저트", "성수", "강남"];
const MIN_QUERY_LENGTH = 2;

export function RefreshStoreSearchPage() {
  const [params, setParams] = useSearchParams();
  const query = params.get("query") ?? "";
  const [draft, setDraft] = useState(query);
  const { state: location, locate } = useBrowserLocation();
  const coordinates = coordinatesOf(location);

  function search(value: string) {
    const next = new URLSearchParams(params);
    const normalized = value.trim();
    if (normalized) next.set("query", normalized); else next.delete("query");
    setDraft(normalized);
    setParams(next, { replace: true });
  }
  function submit(event: FormEvent) { event.preventDefault(); if (draft.trim().length >= MIN_QUERY_LENGTH) search(draft); }

  return (
    <div className="bfr-page bfr-search-page bfr-has-page-topbar">
      <RefreshMobileTopbar title="매장 검색" backTo="/app" />
      <form className="bfr-search-form" role="search" onSubmit={submit}>
        <SearchField label="매장과 메뉴 검색" value={draft} placeholder="예: 성수 라떼" onChange={(event) => setDraft(event.target.value)} onClear={() => search("")} />
        <button className="bfr-search-submit" type="submit" aria-label="검색" disabled={draft.trim().length < MIN_QUERY_LENGTH}><Search size={20} aria-hidden="true" /></button>
      </form>
      <div className="bfr-query-helpers" aria-label="빠른 검색어">
        {queryHelpers.map((helper) => <button type="button" key={helper} onClick={() => search(helper)}>{helper}</button>)}
        <button className="bfr-location-chip" aria-label="현재 위치로 가까운 매장 찾기" type="button" onClick={locate} disabled={location.status === "locating"}><LocateFixed size={14} />{location.status === "locating" ? "위치 확인 중" : "현재 위치"}</button>
      </div>
      {location.status === "denied" ? <p className="bfr-inline-status" role="status">위치 권한이 꺼져 있어 거리 대신 검색어로만 찾을 수 있어요.</p> : null}
      {location.status === "unavailable" ? <p className="bfr-inline-status" role="status">현재 위치를 확인하지 못했어요. 검색어로 매장을 찾아 주세요.</p> : null}
      {query.length >= MIN_QUERY_LENGTH ? <RefreshSearchResults query={query} coordinates={coordinates} /> : coordinates ? <RefreshNearbyResults coordinates={coordinates} /> : <RefreshEmpty title="찾고 싶은 매장을 알려주세요" description="검색어를 입력하거나 현재 위치로 가까운 매장을 찾을 수 있어요." />}
    </div>
  );
}

function RefreshSearchResults({ query, coordinates }: { query: string; coordinates: { latitude: number; longitude: number } | null }) {
  const [page, setPage] = useState<StoreSearchPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const load = useCallback(async (cursor?: string, append = false) => {
    append ? setLoadingMore(true) : setPage(null); setError(null);
    try {
      const next = unwrap(await customerApi.GET("/stores/search", { params: { query: { query, limit: 20, cursor, ...(coordinates ? { ...coordinates, sort: "distance" as const } : {}) } } }));
      setPage((current) => append && current ? { ...next, items: [...current.items, ...next.items] } : next);
    } catch (failure) { setError(failure); } finally { setLoadingMore(false); }
  }, [coordinates, query]);
  useEffect(() => { void load(); }, [load]);
  if (!page && !error) return <RefreshLoading label="매장을 찾는 중" />;
  if (!page) return <RefreshError error={error} retry={() => void load()} />;
  if (page.items.length === 0) return <RefreshEmpty title={`'${query}' 검색 결과가 없어요`} description="다른 매장, 지역 또는 메뉴 이름으로 찾아보세요." />;
  return <section className="bfr-search-results" aria-label="검색 결과"><h2>검색 결과</h2><div className="bfr-store-list">{page.items.map((store) => <RefreshStoreCard key={store.storeId} store={store} />)}</div>{error ? <RefreshError error={error} retry={() => void load(page.page.nextCursor, true)} /> : null}{page.page.nextCursor ? <Button block variant="secondary" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>매장 더 보기</Button> : null}</section>;
}

function RefreshNearbyResults({ coordinates }: { coordinates: { latitude: number; longitude: number } }) {
  const [page, setPage] = useState<NearbyStorePage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const load = useCallback(async (cursor?: string, append = false) => {
    append ? setLoadingMore(true) : setPage(null); setError(null);
    try {
      const next = unwrap(await customerApi.GET("/stores/nearby", { params: { query: { ...coordinates, radiusMeters: 10_000, limit: 20, cursor } } }));
      setPage((current) => append && current ? { ...next, items: [...current.items, ...next.items] } : next);
    } catch (failure) { setError(failure); } finally { setLoadingMore(false); }
  }, [coordinates]);
  useEffect(() => { void load(); }, [load]);
  if (!page && !error) return <RefreshLoading label="가까운 매장을 찾는 중" />;
  if (!page) return <RefreshError error={error} retry={() => void load()} />;
  if (!page.items.length) return <RefreshEmpty title="가까운 매장이 없어요" description="반경 10km 안에 픽업 가능한 매장이 없습니다." />;
  return <section className="bfr-search-results" aria-label="가까운 매장"><h2>가까운 매장</h2><div className="bfr-store-list">{page.items.map((store) => <RefreshStoreCard key={store.storeId} store={store} />)}</div>{error ? <RefreshError error={error} retry={() => void load(page.page.nextCursor, true)} /> : null}{page.page.nextCursor ? <Button block variant="secondary" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>매장 더 보기</Button> : null}</section>;
}
