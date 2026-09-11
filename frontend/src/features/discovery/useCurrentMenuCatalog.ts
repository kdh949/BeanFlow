import { useCallback, useEffect } from "react";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { useResource } from "../shared/useResource";

/** Signed media belongs to a current catalog read, never to persisted cart state. */
export function useCurrentMenuCatalog(storeId: string) {
  const catalog = useResource(useCallback(async () => {
    const menus = unwrap(await customerApi.GET("/stores/{storeId}/menus", { params: { path: { storeId } } })).items;
    return menus;
  }, [storeId]));
  const { state, refresh } = catalog;
  useEffect(() => {
    if (state.status !== "ready") return;
    const expirations = state.value.flatMap((menu) => menu.image ? [Date.parse(menu.image.expiresAt)] : []);
    if (!expirations.length) return;
    const remaining = Math.min(...expirations) - Date.now();
    // A past local deadline may be clock skew. The current server read remains authoritative;
    // use the hint once per minute rather than creating an immediate refresh loop.
    const timer = window.setTimeout(refresh, Math.min(86_400_000, remaining > 0 ? remaining : 60_000));
    return () => window.clearTimeout(timer);
  }, [state, refresh]);
  return catalog;
}
