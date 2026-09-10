import { useCallback } from "react";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { StorefrontImageEditorView } from "../shared/StorefrontImageEditorView";

/** Merchant image authoring uses only the session client and merchant CSRF. */
export function StorefrontImageEditor({ storeId, menuId, label }: { storeId: string; menuId?: string; label: string }) {
  const loadImage = useCallback(async () => menuId
    ? unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId } } }))
    : unwrap(await merchantApi.GET("/stores/{storeId}/image", { params: { path: { storeId } } })), [storeId, menuId]);
  const changeImage = useCallback(async (file: File | null) => {
    const header = await merchantCsrfHeader();
    if (!file) {
      const result = menuId
        ? await merchantApi.DELETE("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header } })
        : await merchantApi.DELETE("/stores/{storeId}/image", { params: { path: { storeId }, header } });
      if (!result.response.ok) unwrap(result);
    } else {
      const form = new FormData(); form.set("image", file);
      const body = { image: file.name };
      if (menuId) unwrap(await merchantApi.PUT("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header }, body, bodySerializer: () => form }));
      else unwrap(await merchantApi.PUT("/stores/{storeId}/image", { params: { path: { storeId }, header }, body, bodySerializer: () => form }));
    }
  }, [storeId, menuId]);
  return <StorefrontImageEditorView label={label} loadImage={loadImage} changeImage={changeImage} />;
}
