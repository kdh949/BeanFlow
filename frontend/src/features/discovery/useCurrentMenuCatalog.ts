import { useCallback, useEffect } from "react";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { useResource } from "../shared/useResource";

/** Signed media belongs to a current catalog read, never to persisted cart state. */
export function useCurrentMenuCatalog(storeId: string) {
  const catalog = useResource(useCallback(async () => {
    const menus = unwrap(await customerApi.GET("/stores/{storeId}/menus", { params: { path: { storeId } } })).items;
    if (menus.some((menu) => menu.image && Date.parse(menu.image.expiresAt) <= Date.now())) {
      throw new Error("메뉴 이미지 주소가 만료되었습니다. 다시 조회해 주세요.");
    }
    return menus;
  }, [storeId]));
  const { state, reload } = catalog;
  useEffect(() => {
    if (state.status !== "ready") return;
    const expirations = state.value.flatMap((menu) => menu.image ? [Date.parse(menu.image.expiresAt)] : []);
    if (!expirations.length) return;
    // Cap long fixture lifetimes at one day to stay within browser timer bounds.
    const timer = window.setTimeout(reload, Math.min(86_400_000, Math.max(0, Math.min(...expirations) - Date.now())));
    return () => window.clearTimeout(timer);
  }, [state, reload]);
  return catalog;
}
