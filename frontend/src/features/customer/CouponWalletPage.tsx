import { ArrowLeft, Check, TicketPercent } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, LoadingState } from "../../design-system";
import { PageHeading } from "../../design-system";
import { Button, ButtonLink } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";
import { couponSelection, useCouponSelection } from "./couponSelection";

type WalletPage = components["schemas"]["CustomerCouponWalletPage"];
type WalletItem = components["schemas"]["CustomerCouponWalletItem"];
type CustomerStore = components["schemas"]["CustomerStore"];

type WalletState = {
  store: CustomerStore;
  page: WalletPage;
};

export function couponBenefitLabel(item: Pick<WalletItem, "benefit">): string {
  if (item.benefit.discountType === "FIXED_KRW") {
    return `${won.format(item.benefit.fixedAmountKrw ?? 0)} 할인`;
  }
  const rate = (item.benefit.rateBps ?? 0) / 100;
  return `${rate}% 할인 · 최대 ${won.format(item.benefit.maximumDiscountKrw ?? 0)}`;
}

export function CouponWalletPage() {
  const [searchParams] = useSearchParams();
  const storeId = searchParams.get("storeId") ?? "";
  const selected = useCouponSelection(storeId);
  const [wallet, setWallet] = useState<WalletState | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (!storeId) return;
    if (append) setLoadingMore(true);
    else setWallet(null);
    setError(null);
    try {
      const [storeResult, walletResult] = await Promise.all([
        customerApi.GET("/stores/{storeId}", { params: { path: { storeId } } }),
        customerApi.GET("/me/coupons", { params: { query: { storeId, cursor, limit: 20 } } }),
      ]);
      const store = unwrap(storeResult);
      const page = unwrap(walletResult);
      setWallet((current) => append && current
        ? { store, page: { items: [...current.page.items, ...page.items], page: page.page } }
        : { store, page });
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMore(false);
    }
  }, [storeId]);

  useEffect(() => { void load(); }, [load]);

  if (!storeId) {
    return (
      <div className="customer-page coupon-wallet-page">
        <PageHeading title="쿠폰" />
        <EmptyState
          title="쿠폰을 사용할 매장을 골라주세요"
          description="매장별 적용 범위가 달라서 매장을 선택해야 사용할 수 있는 쿠폰을 정확히 보여드릴 수 있어요."
          action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>}
        />
      </div>
    );
  }

  if (!wallet && !error) return <LoadingState label="이 매장에서 사용할 쿠폰을 확인하는 중" />;
  if (!wallet) return <ErrorState error={error} retry={() => void load()} />;

  return (
    <div className="customer-page coupon-wallet-page">
      <Link className="back-link" to={`/app/stores/${storeId}`}><ArrowLeft size={17} /> {wallet.store.name}</Link>
      <PageHeading
        title={`${wallet.store.name} 쿠폰`}
      />
      {error ? <ErrorState error={error} retry={() => void load(wallet.page.page.nextCursor, true)} /> : null}
      {wallet.page.items.length === 0 ? (
        <EmptyState
          title="사용할 수 있는 쿠폰이 없어요"
          description="새 쿠폰이 발급되면 이 매장에서 사용할 수 있는지 여기에 표시됩니다."
          action={<ButtonLink to={`/app/stores/${storeId}`}>메뉴 보기</ButtonLink>}
        />
      ) : (
        <section className="coupon-list" aria-label="보유 쿠폰">
          {wallet.page.items.map((coupon) => {
            const label = couponBenefitLabel(coupon);
            const isSelected = selected?.couponIssuanceId === coupon.couponIssuanceId;
            return (
              <article className={`surface-card coupon-card ${coupon.applicable ? "" : "is-unavailable"}`} key={coupon.couponIssuanceId}>
                <span className="coupon-icon"><TicketPercent size={22} /></span>
                <div className="coupon-copy">
                  <strong>{label}</strong>
                  <span>{won.format(coupon.minimumOrderKrw)} 이상 주문 · {shortDateTime.format(new Date(coupon.couponExpiresAt))}까지</span>
                  {!coupon.applicable ? <small>이 매장에서는 사용할 수 없어요.</small> : null}
                </div>
                <Button
                  variant={isSelected ? "secondary" : "brand"}
                  disabled={!coupon.applicable}
                  aria-pressed={isSelected}
                  aria-label={!coupon.applicable
                    ? `${label} 이 매장에서는 사용할 수 없음`
                    : isSelected ? `${label} 선택됨` : `${label} 쿠폰 선택`}
                  onClick={() => isSelected
                    ? couponSelection.clear(storeId)
                    : couponSelection.select({ storeId, couponIssuanceId: coupon.couponIssuanceId, label })}
                >
                  {isSelected ? <><Check size={16} /> 선택됨</> : coupon.applicable ? "선택" : "사용 불가"}
                </Button>
              </article>
            );
          })}
        </section>
      )}
      {wallet.page.page.nextCursor ? (
        <Button block variant="secondary" loading={loadingMore} onClick={() => void load(wallet.page.page.nextCursor, true)}>
          {loadingMore ? "쿠폰을 더 불러오는 중" : "쿠폰 더 보기"}
        </Button>
      ) : null}
      {selected ? (
        <div className="coupon-selection-summary surface-card" role="status">
          <div><Check size={18} /><span><strong>{selected.label}</strong> 쿠폰을 주문에 적용할 예정입니다.</span></div>
          <ButtonLink to="/app/cart">장바구니 보기</ButtonLink>
        </div>
      ) : null}
    </div>
  );
}
