import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { StorefrontImageEditor } from "./StorefrontImageEditor";

type Content = components["schemas"]["MenuDisplayContentAuthoring"];
/** Category and description are distinct from prices and the registered selling configuration. */
export function MenuPresentationEditor({ storeId, menuId, name, onChanged }: { storeId: string; menuId: string; name: string; onChanged?: () => void }) {
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/display-content", { params: { path: { storeId, menuId } } })), [storeId, menuId]));
  const [saved, setSaved] = useState(false);
  return <section className="management-workspace"><h3>{name} 고객 표시 정보</h3>
    {saved ? <p role="status">메뉴 표시 정보를 저장했습니다.</p> : null}
    {resource.state.status === "loading" ? <LoadingState label="메뉴 표시 정보를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <ContentForm key={resource.state.value.version} current={resource.state.value} storeId={storeId} menuId={menuId} onSaved={() => { setSaved(true); resource.reload(); onChanged?.(); }} onRefresh={() => { setSaved(false); resource.reload(); }} />}
    <StorefrontImageEditor storeId={storeId} menuId={menuId} label={`${name} 메뉴 이미지`} />
  </section>;
}
function ContentForm({ current, storeId, menuId, onSaved, onRefresh }: { current: Content; storeId: string; menuId: string; onSaved: () => void; onRefresh: () => void }) {
  const [category, setCategory] = useState(current.displayCategory ?? ""); const [description, setDescription] = useState(current.description ?? "");
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  async function save() {
    if (busy) return; setBusy(true); setFailure(null);
    try { unwrap(await merchantApi.PUT("/stores/{storeId}/menus/{menuId}/display-content", { params: { path: { storeId, menuId }, header: await merchantCsrfHeader() }, body: { expectedVersion: current.version, displayCategory: category.trim() || null, description: description.trim() || null } })); onSaved(); }
    catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  return <form onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={busy}>
    <TextField label="메뉴 분류" value={category} onValueChange={setCategory} maxLength={50} /><TextAreaField label="메뉴 설명" value={description} onValueChange={setDescription} maxLength={500} />
    <p>비워서 저장하면 해당 표시 정보를 지웁니다. 가격과 판매 구성은 바뀌지 않습니다.</p>
    {failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy}>메뉴 표시 정보 저장</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 메뉴 표시 정보 다시 읽기</Button></div>
  </fieldset></form>;
}
