import { Heart, LifeBuoy, LogOut, ReceiptText, Sparkles, TicketCheck, TicketPercent } from "lucide-react";
import { useCallback, useState } from "react";
import { Link, useNavigate } from "react-router";
import { ErrorState } from "../../../presentation/shared";
import type { components } from "../../../api/schema";
import { unwrap } from "../../../api/client";
import { customerApi } from "../../../api/customerClient";
import { useResource } from "../../shared/useResource";
import { useCart } from "../../ordering/cart";
import { FeedbackState, ButtonLink, PageHeading } from "../../../design-system";
import { customerSession, useCustomerSession } from "./customerSession";
import { Button } from "../../../design-system";

export function CustomerMyPage() {
  const session = useCustomerSession();
  const navigate = useNavigate();
  const [failure, setFailure] = useState<unknown>(null);
  const [signingOut, setSigningOut] = useState(false);

  if (session.status !== "authenticated") return null;

  async function signOut() {
    setSigningOut(true);
    setFailure(null);
    try {
      await customerSession.logOut();
      navigate("/app/login", { replace: true });
    } catch (error) {
      setFailure(error);
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <div className="customer-page my-page">
      <PageHeading title="내 정보" />
      <section className="surface-card my-identity">
        <strong>{session.actor.displayName}</strong>
      </section>
      <MyBenefits />
      <nav className="my-links" aria-label="내 정보 바로가기">
        <Link className="surface-card my-link" to="/app/orders"><ReceiptText size={19} /><span>주문 내역</span></Link>
        <Link className="surface-card my-link" to="/app/points"><Sparkles size={19} /><span>포인트</span></Link>
        <Link className="surface-card my-link" to="/app/coupons"><TicketPercent size={19} /><span>쿠폰</span></Link>
        <Link className="surface-card my-link" to="/app/coupon-claims"><TicketCheck size={19} /><span>쿠폰 받기</span></Link>
        <Link className="surface-card my-link" to="/app/favorites"><Heart size={19} /><span>즐겨찾기 매장</span></Link>
        <Link className="surface-card my-link" to="/app/help"><LifeBuoy size={19} /><span>도움말</span></Link>
      </nav>
      {failure ? <ErrorState error={failure} /> : null}
      <Button variant="secondary" block loading={signingOut} onClick={() => void signOut()}>
        <LogOut size={17} /> {signingOut ? "로그아웃 중" : "로그아웃"}
      </Button>
      <p className="form-footnote">로그아웃하면 이 기기에 담아 둔 장바구니도 함께 비워져요.</p>
    </div>
  );
}

function MyBenefits() {
  const points = useResource<components["schemas"]["CustomerPointSummary"]>(useCallback(async () => unwrap(await customerApi.GET("/me/points")), []));
  const cart = useCart();
  return <section className="bfr-my-benefits" aria-label="내 혜택">
    <div className="surface-card"><h2>사용 가능 포인트</h2>{points.state.status === "loading" ? <FeedbackState kind="loading" title="포인트 확인 중" description="잠시만 기다려 주세요." /> : points.state.status === "failed" ? <ErrorState error={points.state.error} retry={points.reload} /> : <><strong className="bfr-benefit-amount">{points.state.value.availablePointsKrw.toLocaleString("ko-KR")}P</strong>{points.state.value.expiring.length ? <p>소멸 예정 포인트가 있어요. 내역에서 확인해 주세요.</p> : null}</>}<ButtonLink variant="ghost" to="/app/points">포인트 내역</ButtonLink></div>
    {cart.status === "ready" ? <MyStoreCoupons key={cart.cart.storeId} storeId={cart.cart.storeId} storeName={cart.cart.storeName} /> : <div className="surface-card"><h2>쿠폰</h2><p>매장을 고르면 쿠폰을 확인할 수 있어요.</p><ButtonLink variant="ghost" to="/app/stores">매장 찾기</ButtonLink></div>}
  </section>;
}

function MyStoreCoupons({ storeId, storeName }: { storeId: string; storeName: string }) {
  const wallet = useResource<components["schemas"]["CustomerCouponWalletPage"]>(useCallback(async () => unwrap(await customerApi.GET("/me/coupons", { params: { query: { storeId, limit: 20 } } })), [storeId]));
  return <div className="surface-card"><h2>{storeName} 쿠폰</h2>{wallet.state.status === "loading" ? <FeedbackState kind="loading" title="쿠폰 확인 중" description="잠시만 기다려 주세요." /> : wallet.state.status === "failed" ? <ErrorState error={wallet.state.error} retry={wallet.reload} /> : <><strong className="bfr-benefit-amount">{wallet.state.value.items.filter((item) => item.applicable).length}개{wallet.state.value.page.nextCursor ? " 이상" : ""}</strong><p>이 매장에 적용되는 쿠폰입니다. 최소 주문 금액 등 사용 조건을 확인해 주세요.</p></>}<ButtonLink variant="ghost" to={`/app/coupons?storeId=${encodeURIComponent(storeId)}`}>쿠폰 확인</ButtonLink></div>;
}
