export function couponWalletPath(storeId: string, returnTo: string): string {
  const params = new URLSearchParams({ storeId, returnTo });
  return `/app/coupons?${params.toString()}`;
}

export function couponReturnTarget(rawReturnTo: string | null, storeId: string, storeName: string): { to: string; label: string } {
  const fallback = { to: `/app/stores/${storeId}`, label: storeName };
  if (!rawReturnTo) return fallback;
  const base = "https://beanflow.local";
  let parsed: URL;
  try {
    parsed = new URL(rawReturnTo, base);
  } catch {
    return fallback;
  }
  const isCustomerPath = parsed.origin === base
    && (parsed.pathname === "/app" || parsed.pathname.startsWith("/app/"))
    && !parsed.pathname.startsWith("/app/coupons");
  if (!isCustomerPath) return fallback;
  const to = `${parsed.pathname}${parsed.search}${parsed.hash}`;
  if (parsed.pathname === "/app/cart") return { to, label: "장바구니" };
  if (parsed.pathname === "/app/orders" || parsed.pathname.startsWith("/app/orders/")) return { to, label: "주문" };
  if (parsed.pathname === "/app/events") return { to, label: "쿠폰 받기" };
  if (parsed.pathname === "/app/me") return { to, label: "내 정보" };
  if (parsed.pathname === "/app") return { to, label: "홈" };
  return { to, label: storeName };
}
