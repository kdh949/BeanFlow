import { useCallback } from "react";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { StorefrontImageEditorView } from "../shared/StorefrontImageEditorView";

/** Operator image authoring uses the Bearer client and an explicit audit reason. */
export function OperatorStorefrontImageEditor({ storeId, menuId, label }: { storeId: string; menuId?: string; label: string }) {
  const loadImage = useCallback(async (reason: string) => menuId
    ? unwrap(await operationsApi.GET("/operations/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header: { "X-Access-Reason": reason } } }))
    : unwrap(await operationsApi.GET("/operations/stores/{storeId}/image", { params: { path: { storeId }, header: { "X-Access-Reason": reason } } })), [storeId, menuId]);
  const changeImage = useCallback(async (file: File | null, reason: string) => {
    const header = { "X-Access-Reason": reason };
    if (!file) {
      const result = menuId
        ? await operationsApi.DELETE("/operations/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header } })
        : await operationsApi.DELETE("/operations/stores/{storeId}/image", { params: { path: { storeId }, header } });
      if (!result.response.ok) unwrap(result);
    } else {
      const form = new FormData(); form.set("image", file);
      const body = { image: file.name };
      if (menuId) unwrap(await operationsApi.PUT("/operations/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header }, body, bodySerializer: () => form }));
      else unwrap(await operationsApi.PUT("/operations/stores/{storeId}/image", { params: { path: { storeId }, header }, body, bodySerializer: () => form }));
    }
  }, [storeId, menuId]);
  return <StorefrontImageEditorView requireReason label={label} loadImage={loadImage} changeImage={changeImage} />;
}
