import { useSyncExternalStore } from "react";

export type SelectedCoupon = {
  storeId: string;
  couponIssuanceId: string;
  label: string;
};

let selected: SelectedCoupon | null = null;
const listeners = new Set<() => void>();

function publish() {
  for (const listener of listeners) listener();
}

/**
 * Ephemeral selection only. The server revalidates the issuance, store scope,
 * minimum order and final discount when it creates the order.
 */
export const couponSelection = {
  get: () => selected,
  forStore: (storeId: string) => selected?.storeId === storeId ? selected : null,
  select: (coupon: SelectedCoupon) => {
    selected = coupon;
    publish();
  },
  clear: (storeId?: string) => {
    if (storeId && selected?.storeId !== storeId) return;
    selected = null;
    publish();
  },
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

export function useCouponSelection(storeId?: string) {
  const snapshot = useSyncExternalStore(couponSelection.subscribe, couponSelection.get, couponSelection.get);
  return storeId && snapshot?.storeId === storeId ? snapshot : null;
}
