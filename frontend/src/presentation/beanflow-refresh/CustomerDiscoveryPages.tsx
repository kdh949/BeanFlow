import { ChevronRight, LocateFixed, MapPin, Search, ShoppingBag, TicketPercent } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { coordinatesOf, type Coordinates, useBrowserLocation } from "../../features/discovery/useBrowserLocation";
import { newSearchVisit, searchOriginState, searchVisitForEntry, updateSearchVisit, type SearchVisit } from "../../features/discovery/storeSearchNavigation";
import { useAttentionRefresh } from "../../features/shared/useAttentionRefresh";
import { useResource } from "../../features/shared/useResource";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshMobileTopbar, RefreshStoreCard } from "./RefreshShared";
import { Button, ButtonLink, ChipButton, IconButton, SearchField, SelectField } from "../../design-system";

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
  const recentOrders = useResource<CustomerOrderSummary[]>(useCallback(async () => unwrap(await customerApi.GET("/me/orders", { params: { query: { status: "PAST", limit: 1 } } })).items, []));
  useAttentionRefresh(activeOrders.refresh, { intervalMs: 30_000 });
  useAttentionRefresh(recentOrders.refresh);
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
        {activeOrders.state.status === "ready" ? <div className="bfr-active-orders">{activeOrders.state.value.map((order) => <Link key={order.orderReference} to={`/app/orders/${order.orderReference}`}><span className="bfr-pickup-symbol" aria-hidden="true"><ShoppingBag size={22} /></span><strong>{order.pickupNumber} {order.status === "READY" ? "준비 완료" : order.itemSummary}</strong><span>· {order.storeName}</span><ChevronRight size={22} aria-hidden="true" /></Link>)}</div> : null}
      </section>

      <Link className="bfr-home-event-link" to="/app/events"><span><TicketPercent size={20} aria-hidden="true" /></span><strong>진행 중인 쿠폰 이벤트</strong><ChevronRight size={20} aria-hidden="true" /></Link>

      <section className="bfr-home-stores">
        <header className="bfr-section-heading"><h2>추천 매장</h2><Link to="/app/stores">전체 보기 <ChevronRight size={17} /></Link></header>
        <Button variant="secondary" block onClick={locate} disabled={location.status === "locating"}><MapPin size={20} />{location.status === "locating" ? "현재 위치 확인 중" : "현재 위치로 찾기"}</Button>
        {location.status === "denied" ? <p className="bfr-inline-status" role="status">위치 권한이 꺼져 있어 자주 가는 매장과 최근 매장만 보여드려요.</p> : null}
        {location.status === "unavailable" ? <p className="bfr-inline-status" role="status">현재 위치를 확인할 수 없어 저장된 이용 기록을 기준으로 보여드려요.</p> : null}
        {recommendations.state.status === "loading" ? <RefreshLoading label="추천 매장을 불러오는 중" /> : null}
        {recommendations.state.status === "failed" ? <RefreshError error={recommendations.state.error} retry={recommendations.reload} /> : null}
        {recommendations.state.status === "ready" && recommendations.state.value.length === 0 ? <RefreshEmpty title="추천할 매장이 아직 없어요" description="매장 이름이나 메뉴로 직접 찾아볼 수 있어요." action={<ButtonLink variant="brand" to="/app/stores">매장 찾기</ButtonLink>} /> : null}
        {recommendations.state.status === "ready" ? <div className="bfr-store-list">{recommendations.state.value.map((item) => <RefreshStoreCard key={item.store.storeId} store={item.store} caption={recommendationLabels[item.reason]} />)}</div> : null}
      </section>
      <section className="bfr-recent-orders" aria-label="최근 주문">
        <header className="bfr-section-heading"><h2>최근 주문</h2><Link to="/app/orders?status=PAST">주문 내역 <ChevronRight size={17} /></Link></header>
        {recentOrders.state.status === "loading" ? <RefreshLoading label="최근 주문을 확인하는 중" /> : null}
        {recentOrders.state.status === "failed" ? <RefreshError error={recentOrders.state.error} retry={recentOrders.reload} /> : null}
        {recentOrders.state.status === "ready" && !recentOrders.state.value.length ? <p>주문한 메뉴를 여기에서 다시 찾을 수 있어요.</p> : null}
        {recentOrders.state.status === "ready" ? recentOrders.state.value.map((order) => <article className="bfr-recent-order" key={order.orderReference}><Link to={`/app/orders/${order.orderReference}`}><strong>{order.storeName}</strong><span>{order.itemSummary}</span></Link>{order.allowedActions.includes("REORDER") ? <ButtonLink variant="secondary" to={`/app/orders/${order.orderReference}?reorder=1`}>다시 주문</ButtonLink> : null}</article>) : null}
      </section>
    </div>
  );
}

const queryHelpers = ["라떼", "디저트", "성수", "강남"];
const MIN_QUERY_LENGTH = 2;

export function RefreshStoreSearchPage() {
  const entry = useLocation();
  return <StoreSearchVisitPage key={entry.key} />;
}

function StoreSearchVisitPage() {
  const entry = useLocation();
  const [params, setParams] = useSearchParams();
  const query = params.get("query") ?? "";
  const sort = params.get("sort") === "distance" ? "distance" : "relevance";
  const openOnly = params.get("openOnly") === "true";
  const [visit] = useState(() => searchVisitForEntry(entry.key, `${entry.pathname}${entry.search}`, entry.state));
  const [draft, setDraft] = useState(query);
  const { state: location, locate } = useBrowserLocation(visit.coordinates);
  const coordinates = coordinatesOf(location);
  const needsLocation = sort === "distance" && !coordinates;

  useEffect(() => {
    if (coordinates?.latitude !== visit.coordinates?.latitude || coordinates?.longitude !== visit.coordinates?.longitude) {
      updateSearchVisit(visit, { coordinates, pageCount: 1, scrollY: 0 });
    }
  }, [coordinates, visit]);
  useEffect(() => {
    const save = () => updateSearchVisit(visit, { scrollY: window.scrollY });
    window.addEventListener("scroll", save, { passive: true });
    return () => window.removeEventListener("scroll", save);
  }, [visit]);

  function changeConditions(next: URLSearchParams) {
    const search = next.toString();
    const nextVisit = newSearchVisit(`/app/stores${search ? `?${search}` : ""}`, coordinates);
    updateSearchVisit(visit, { scrollY: window.scrollY });
    setParams(next, { state: { searchVisitId: nextVisit.id } });
  }
  function search(value: string) {
    const next = new URLSearchParams(params);
    const normalized = value.trim();
    if (normalized) next.set("query", normalized); else next.delete("query");
    changeConditions(next);
  }
  function submit(event: FormEvent) { event.preventDefault(); if (draft.trim().length >= MIN_QUERY_LENGTH) search(draft); }
  const sameCoordinates = coordinates?.latitude === visit.coordinates?.latitude && coordinates?.longitude === visit.coordinates?.longitude;

  return (
    <div className="bfr-page bfr-search-page bfr-has-page-topbar">
      <RefreshMobileTopbar title="매장 검색" backTo="/app" />
      <form className="bfr-search-form" role="search" onSubmit={submit}>
        <SearchField label="매장과 메뉴 검색" value={draft} placeholder="예: 성수 라떼" onChange={(event) => setDraft(event.target.value)} onClear={() => search("")} />
        <IconButton label="검색" type="submit" variant="ghost" disabled={draft.trim().length < MIN_QUERY_LENGTH}><Search size={20} aria-hidden="true" /></IconButton>
      </form>
      <div className="bfr-query-helpers" aria-label="빠른 검색어">
        {queryHelpers.map((helper) => <ChipButton key={helper} onClick={() => search(helper)}>{helper}</ChipButton>)}
        <ChipButton aria-label="현재 위치로 가까운 매장 찾기" onClick={locate} disabled={location.status === "locating"}><LocateFixed size={14} />{location.status === "locating" ? "위치 확인 중" : "현재 위치"}</ChipButton>
      </div>
      {location.status === "denied" ? <p className="bfr-inline-status" role="status">위치 권한이 꺼져 있어요. 관련도순으로 검색하거나 위치 권한을 켜 주세요.</p> : null}
      {location.status === "unavailable" ? <p className="bfr-inline-status" role="status">현재 위치를 확인하지 못했어요. 관련도순으로 검색하거나 다시 시도해 주세요.</p> : null}
      <p className="form-footnote">위치는 이 화면을 사용하는 동안만 기억해요. 새로고침 후에는 위치를 다시 확인해 주세요.</p>
      {query.length >= MIN_QUERY_LENGTH ? <div className="bfr-search-filters"><SelectField label="검색 정렬" value={sort} onValueChange={(value) => { const next = new URLSearchParams(params); next.set("sort", value); changeConditions(next); }}><option value="relevance">관련도순</option><option value="distance" disabled={!coordinates}>거리순{coordinates ? "" : " · 위치 필요"}</option></SelectField><ChipButton aria-pressed={openOnly} onClick={() => { const next = new URLSearchParams(params); next.set("openOnly", String(!openOnly)); changeConditions(next); }}>주문 가능한 매장만</ChipButton></div> : null}
      {needsLocation && query.length >= MIN_QUERY_LENGTH ? <RefreshEmpty title="거리순 검색에 위치가 필요해요" description="현재 위치 버튼으로 확인하거나 관련도순으로 바꿔 주세요." />
        : query.length >= MIN_QUERY_LENGTH || coordinates ? <RefreshStoreResults key={`${query}-${coordinates?.latitude}-${coordinates?.longitude}-${sort}-${openOnly}`} query={query.length >= MIN_QUERY_LENGTH ? query : undefined} coordinates={coordinates} sort={sort} openOnly={openOnly} visit={visit} restorePages={sameCoordinates ? visit.pageCount : 1} restoreScroll={sameCoordinates ? visit.scrollY : 0} />
          : <RefreshEmpty title="찾고 싶은 매장을 알려주세요" description="검색어를 입력하거나 현재 위치로 가까운 매장을 찾을 수 있어요." />}
    </div>
  );
}

type StoreResults = { items: Array<StoreSearchPage["items"][number] | NearbyStorePage["items"][number]>; page: { nextCursor?: string } };

/** Rebuild only this visit's visible pages from fresh server cursors; never replay cached results. */
function RefreshStoreResults({ query, coordinates, sort, openOnly, visit, restorePages, restoreScroll }: {
  query?: string; coordinates: Coordinates | null; sort: "relevance" | "distance"; openOnly: boolean;
  visit: SearchVisit; restorePages: number; restoreScroll: number;
}) {
  const [page, setPage] = useState<StoreResults | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [restore] = useState({ pages: restorePages, scroll: restoreScroll });
  const generation = useRef(0);
  const pagesLoaded = useRef(0);
  const restored = useRef(false);
  const load = useCallback(async (cursor?: string, append = false) => {
    const request = ++generation.current;
    append ? setLoadingMore(true) : setPage(null); setError(null);
    try {
      let next: StoreResults | undefined;
      let items: StoreResults["items"] = [];
      let count = 0;
      do {
        next = query ? unwrap(await customerApi.GET("/stores/search", { params: { query: { query, limit: 20, cursor, sort, openOnly, ...(coordinates ?? {}) } } }))
          : unwrap(await customerApi.GET("/stores/nearby", { params: { query: { ...coordinates!, radiusMeters: 10_000, pickupAvailable: true, limit: 20, cursor } } }));
        if (request !== generation.current) return;
        items = [...items, ...next.items]; count += 1; cursor = next.page.nextCursor;
      } while (!append && count < restore.pages && cursor);
      pagesLoaded.current = append ? pagesLoaded.current + count : count;
      updateSearchVisit(visit, { pageCount: pagesLoaded.current });
      const result = { items, page: next.page };
      setPage((current) => append && current ? { ...result, items: [...current.items, ...items] } : result);
    } catch (failure) { if (request === generation.current) setError(failure); }
    finally { if (request === generation.current) setLoadingMore(false); }
  }, [coordinates, query, sort, openOnly, restore.pages, visit]);
  useEffect(() => { void load(); return () => { ++generation.current; }; }, [load]);
  useEffect(() => {
    if (!page || restored.current) return;
    const frame = requestAnimationFrame(() => { window.scrollTo(0, restore.scroll); restored.current = true; });
    return () => cancelAnimationFrame(frame);
  }, [page, restore.scroll]);
  if (!page && !error) return <RefreshLoading label={query ? "매장을 찾는 중" : "가까운 매장을 찾는 중"} />;
  if (!page) return <RefreshError error={error} retry={() => void load()} />;
  if (!page.items.length) return <RefreshEmpty title={query ? `'${query}' 검색 결과가 없어요` : "가까운 매장이 없어요"} description={query ? "다른 매장, 지역 또는 메뉴 이름으로 찾아보세요." : "반경 10km 안에 현재 주문 가능한 매장이 없습니다."} />;
  return <section className="bfr-search-results" aria-label={query ? "검색 결과" : "가까운 매장"}><h2>{query ? "검색 결과" : "가까운 매장"}</h2><div className="bfr-store-list">{page.items.map((store) => <RefreshStoreCard key={store.storeId} store={store} navigationState={searchOriginState(visit)} />)}</div>{error ? <RefreshError error={error} retry={() => void load(page.page.nextCursor, true)} /> : null}{page.page.nextCursor ? <Button block variant="secondary" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>매장 더 보기</Button> : null}</section>;
}
