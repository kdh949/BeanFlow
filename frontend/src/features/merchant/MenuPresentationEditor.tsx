import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { StorefrontImageEditor } from "./StorefrontImageEditor";

type Content = components["schemas"]["MenuDisplayContentAuthoring"];
/** Category and description are distinct from prices and the registered selling configuration. */
export function MenuPresentationEditor({ storeId, menuId, name, onChanged, onBusyChange }: { storeId: string; menuId: string; name: string; onChanged?: () => void; /** Report unsaved content and image work to the parent. */ onBusyChange?: (busy: boolean) => void }) {
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/display-content", { params: { path: { storeId, menuId } } })), [storeId, menuId]));
  const [saved, setSaved] = useState(false);
  const [contentBusy, setContentBusy] = useState(false);
  const [imageBusy, setImageBusy] = useState(false);
  const locked = contentBusy || imageBusy;
  useEffect(() => { onBusyChange?.(locked); return () => onBusyChange?.(false); }, [locked, onBusyChange]);
  return <section className="management-workspace"><h3>{name} 고객 표시 정보</h3>
    {saved ? <p role="status">메뉴 표시 정보를 저장했습니다.</p> : null}
    {resource.state.status === "loading" ? <LoadingState label="메뉴 표시 정보를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <ContentForm key={resource.state.value.version} current={resource.state.value} storeId={storeId} menuId={menuId} onBusyChange={setContentBusy} onEdit={() => setSaved(false)} onSaved={() => { setSaved(true); resource.reload(); onChanged?.(); }} onRefresh={() => { setSaved(false); resource.reload(); }} />}
    <StorefrontImageEditor storeId={storeId} menuId={menuId} label={`${name} 메뉴 이미지`} onBusyChange={setImageBusy} />
  </section>;
}
function ContentForm({ current, storeId, menuId, onSaved, onRefresh, onEdit, onBusyChange }: { current: Content; storeId: string; menuId: string; onSaved: () => void; onRefresh: () => void; onEdit: () => void; onBusyChange: (busy: boolean) => void }) {
  const [category, setCategory] = useState(current.displayCategory ?? ""); const [description, setDescription] = useState(current.description ?? "");
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  const locked = busy || category !== (current.displayCategory ?? "") || description !== (current.description ?? "");
  useEffect(() => { onBusyChange(locked); return () => onBusyChange(false); }, [locked, onBusyChange]);
  async function save() {
    if (busy) return; setBusy(true); setFailure(null);
    try { unwrap(await merchantApi.PUT("/stores/{storeId}/menus/{menuId}/display-content", { params: { path: { storeId, menuId }, header: await merchantCsrfHeader() }, body: { expectedVersion: current.version, displayCategory: category.trim() || null, description: description.trim() || null } })); onSaved(); }
    catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  return <form onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={busy}>
    <TextField label="메뉴 분류" value={category} onValueChange={value => { setCategory(value); onEdit(); }} maxLength={50} /><TextAreaField label="메뉴 설명" value={description} onValueChange={value => { setDescription(value); onEdit(); }} maxLength={500} />
    <p>비워서 저장하면 해당 표시 정보를 지웁니다. 가격과 판매 구성은 바뀌지 않습니다.</p>
    {failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy}>메뉴 표시 정보 저장</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 메뉴 표시 정보 다시 읽기</Button></div>
  </fieldset></form>;
}
