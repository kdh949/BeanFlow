import { ArrowRight, LocateFixed, Search } from "lucide-react";
import { useCallback } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { useCustomerSession } from "../auth/customer/customerSession";
import { useResource } from "../shared/useResource";
import { StoreCard, recommendationReasonLabel } from "./StoreCards";
import { coordinatesOf, useBrowserLocation } from "./useBrowserLocation";
import { ButtonLink } from "../../design-system";

type CustomerOrderSummary = components["schemas"]["CustomerOrderSummary"];
type StoreRecommendation = components["schemas"]["StoreRecommendation"];

export function CustomerHomePage() {
  const session = useCustomerSession();
  const { state: location, locate } = useBrowserLocation();
  const coordinates = coordinatesOf(location);

  const loadActiveOrders = useCallback(
    async () => unwrap(await customerApi.GET("/me/orders", { params: { query: { status: "ACTIVE", limit: 3 } } })).items,
    [],
  );
  const loadRecommendations = useCallback(
    async () =>
      unwrap(
        await customerApi.GET("/me/store-recommendations", {
          params: { query: { limit: 6, ...(coordinates ?? {}) } },
        }),
      ).items,
    [coordinates],
  );

  const activeOrders = useResource<CustomerOrderSummary[]>(loadActiveOrders);
  const recommendations = useResource<StoreRecommendation[]>(loadRecommendations);
  const displayName = session.status === "authenticated" ? session.actor.displayName : null;

  return (
    <div className="customer-page home-page">
      <section className="home-hero">
        <span className="eyebrow">PICKUP, WITHOUT THE WAIT</span>
        <h1>{displayName ? <>{displayName}님,<br />오늘도 기다리지 마세요.</> : <>좋은 커피는<br />기다리지 않아도 돼요.</>}</h1>
        <p>가까운 매장을 찾고, 도착 시간에 맞춰 픽업하세요.</p>
        <Link className="location-pill" to="/app/stores">
          <Search size={17} /> 매장이나 메뉴로 찾기
        </Link>
      </section>

      <section className="home-section">
        <div className="section-heading">
          <div><span className="eyebrow">IN PROGRESS</span><h2>진행 중인 주문</h2></div>
          <Link className="muted-label" to="/app/orders">전체 보기</Link>
        </div>
        {activeOrders.state.status === "loading" ? <LoadingState label="진행 중인 주문을 확인하는 중" /> : null}
        {activeOrders.state.status === "failed" ? <ErrorState error={activeOrders.state.error} retry={activeOrders.reload} /> : null}
        {activeOrders.state.status === "ready" && activeOrders.state.value.length === 0 ? (
          <EmptyState title="진행 중인 주문이 없어요" description="주문을 시작하면 픽업 번호와 준비 상태가 여기에 표시됩니다." />
        ) : null}
        {activeOrders.state.status === "ready"
          ? activeOrders.state.value.map((order) => (
            <Link className="surface-card active-order-summary" key={order.orderReference} to={`/app/orders/${order.orderReference}`}>
              <div><StatusBadge state={order.status} /><span>{order.storeName}</span></div>
              <strong className="pickup-number">{order.pickupNumber}</strong>
              <div><span>{order.itemSummary}</span><ArrowRight size={18} aria-hidden="true" /></div>
            </Link>
          ))
          : null}
      </section>

      <section className="home-section">
        <div className="section-heading">
          <div><span className="eyebrow">FOR YOU</span><h2>추천 매장</h2></div>
          <button className="muted-label link-button" type="button" onClick={locate} disabled={location.status === "locating"}>
            <LocateFixed size={15} /> {location.status === "locating" ? "위치 확인 중" : coordinates ? "위치 다시 찾기" : "가까운 순 보기"}
          </button>
        </div>
        {location.status === "denied" ? (
          <p className="inline-note" role="status">위치 권한이 꺼져 있어 자주 가는 매장과 최근 매장만 보여드려요.</p>
        ) : null}
        {location.status === "unavailable" ? (
          <p className="inline-note" role="status">현재 위치를 확인할 수 없어 자주 가는 매장과 최근 매장만 보여드려요.</p>
        ) : null}
        {recommendations.state.status === "loading" ? <LoadingState label="추천 매장을 불러오는 중" /> : null}
        {recommendations.state.status === "failed" ? <ErrorState error={recommendations.state.error} retry={recommendations.reload} /> : null}
        {recommendations.state.status === "ready" && recommendations.state.value.length === 0 ? (
          <EmptyState
            title="추천할 매장이 아직 없어요"
            description="매장 이름이나 메뉴로 직접 찾아볼 수 있어요."
            action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>}
          />
        ) : null}
        {recommendations.state.status === "ready"
          ? recommendations.state.value.map((recommendation) => (
            <StoreCard
              key={recommendation.store.storeId}
              store={{ ...recommendation.store, caption: recommendationReasonLabel[recommendation.reason] ?? null }}
            />
          ))
          : null}
      </section>
    </div>
  );
}
