import { useCallback, useState } from "react";
import { type MerchantStore, requestMerchantStores } from "../auth/merchant/merchantSession";
import { useResource } from "../shared/useResource";

const SELECTION_KEY = "beanflow.merchant.selected-store";

export type MerchantStoreScope = "ANY" | "OWNER";

/**
 * Loads the stores the server says this actor currently belongs to and keeps
 * one selection for the session. The role shown here is a convenience: every
 * endpoint re-checks membership, so a screen never treats it as permission.
 */
export function useMerchantStores(scope: MerchantStoreScope = "ANY") {
  const load = useCallback(async () => {
    const stores = await requestMerchantStores();
    return scope === "OWNER" ? stores.filter((store) => store.membershipRole === "OWNER") : stores;
  }, [scope]);
  const { state, reload } = useResource<MerchantStore[]>(load);
  const [requested, setRequested] = useState<string | null>(() => sessionStorage.getItem(SELECTION_KEY));

  const stores = state.status === "ready" ? state.value : [];
  const selected = stores.find((store) => store.storeId === requested) ?? stores[0] ?? null;

  function select(storeId: string) {
    sessionStorage.setItem(SELECTION_KEY, storeId);
    setRequested(storeId);
  }

  return { state, stores, selected, select, reload };
}
