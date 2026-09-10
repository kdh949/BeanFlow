import { useCallback } from "react";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { Button, ButtonLink, EmptyState, LoadingState, PageHeading } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { StoreCard } from "../discovery/StoreCards";
import { useResource } from "../shared/useResource";

/** Displays the server's BR-40 order and visibility decisions, without deriving order history locally. */
export function RecentStoresPage() {
  const recent = useResource(useCallback(async () => unwrap(await customerApi.GET("/me/recent-stores", { params: { query: { limit: 20 } } })).items, []));
  return <div className="customer-page favorite-stores-page">
    <PageHeading title="최근 주문한 매장" />
    <p>최근 주문 순으로 최대 20개 매장을 보여드려요.</p>
    {recent.state.status === "loading" ? <LoadingState label="최근 주문한 매장을 불러오는 중" /> : recent.state.status === "failed" ? <ErrorState error={recent.state.error} retry={recent.reload} /> : recent.state.value.length ? <section className="favorite-store-list" aria-label="최근 주문 매장">{recent.state.value.map(store => <StoreCard key={store.storeId} store={store} />)}</section> : <EmptyState title="최근 주문한 매장이 없어요" description="진행 중이거나 완료된 주문의 매장을 다시 찾을 수 있어요." action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>} />}
    <Button variant="secondary" disabled={recent.state.status === "loading"} onClick={recent.reload}>최근 매장 새로고침</Button>
  </div>;
}
