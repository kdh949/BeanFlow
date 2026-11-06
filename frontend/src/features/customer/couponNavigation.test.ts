import { describe, expect, it } from "vitest";
import { couponEventPath, couponReturnTarget, couponWalletPath } from "./couponNavigation";

describe("store coupon event navigation", () => {
  it("preserves the same store and validated cart origin through events and wallet", () => {
    const events = new URL(couponEventPath("store-one", "/app/cart?source=coupon#summary"), "https://beanflow.local");
    expect(events.searchParams.get("storeId")).toBe("store-one");
    const origin = couponReturnTarget(events.searchParams.get("returnTo"), "store-one", "매장");
    const wallet = new URL(couponWalletPath("store-one", origin.to), "https://beanflow.local");
    expect(wallet.searchParams.get("storeId")).toBe("store-one");
    expect(wallet.searchParams.get("returnTo")).toBe("/app/cart?source=coupon#summary");
  });
  it.each(["https://external.test/app/cart", "//external.test/app/cart", "/app/coupons?storeId=other", "/ops"])("rejects unsafe or looping origin %s", raw => {
    expect(new URL(couponEventPath("store-one", raw), "https://beanflow.local").searchParams.get("returnTo")).toBe("/app/stores/store-one");
  });
});
