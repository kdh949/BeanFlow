import { useCallback, useState } from "react";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, EmptyState, LoadingState, SelectField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { OperatorStorefrontImageEditor } from "./OperatorStorefrontImageEditor";
import { useResource } from "../shared/useResource";

/** Uses the operator's media grant to select current or archived same-store menus. */
export function StoreMediaWorkspace({ storeId }: { storeId: string }) {
  const [lifecycle, setLifecycle] = useState<"ACTIVE" | "ARCHIVED">("ACTIVE");
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [selected, setSelected] = useState<{ menuId: string; name: string } | null>(null);
  const cursor = cursors.at(-1);
  const menus = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/media-menus", { params: { path: { storeId }, header: { "X-Access-Reason": "STORE_MEDIA_REVIEW" }, query: { lifecycle, cursor } } })), [storeId, lifecycle, cursor]));
  return <div className="management-workspace">
    <OperatorStorefrontImageEditor storeId={storeId} label="매장 대표 이미지" />
    <section className="surface-card management-card management-workspace"><h3>메뉴 이미지 선택</h3>
      <SelectField label="메뉴 범위" value={lifecycle} onValueChange={value => { setLifecycle(value as "ACTIVE" | "ARCHIVED"); setCursors([undefined]); setSelected(null); }}><option value="ACTIVE">현재 메뉴</option><option value="ARCHIVED">보관 메뉴</option></SelectField>
      {menus.state.status === "loading" ? <LoadingState label="메뉴 목록을 불러오는 중" /> : menus.state.status === "failed" ? <ErrorState error={menus.state.error} retry={menus.reload} /> : <>
        {menus.state.value.items.length ? <ul className="menu-authoring-list">{menus.state.value.items.map(menu => <li key={menu.menuId}><div><strong>{menu.name}</strong><p>{menu.lifecycle === "ARCHIVED" ? "보관됨" : "현재 메뉴"}</p></div><Button variant="secondary" aria-label={`${menu.name} 이미지 관리`} onClick={() => setSelected(menu)}>이미지 관리</Button></li>)}</ul> : <EmptyState title="해당 범위의 메뉴가 없습니다" description="다른 메뉴 범위를 선택해 주세요." />}
        <div className="button-row"><Button variant="secondary" disabled={cursors.length === 1} onClick={() => { setCursors(cursors.slice(0, -1)); setSelected(null); }}>이전 메뉴</Button><Button variant="secondary" disabled={!menus.state.value.nextCursor} onClick={() => { if (menus.state.status === "ready" && menus.state.value.nextCursor) { setCursors([...cursors, menus.state.value.nextCursor]); setSelected(null); } }}>다음 메뉴</Button><Button variant="ghost" onClick={() => { setSelected(null); menus.reload(); }}>메뉴 목록 새로고침</Button></div>
      </>}
    </section>
    {selected ? <OperatorStorefrontImageEditor key={selected.menuId} storeId={storeId} menuId={selected.menuId} label={`${selected.name} 이미지`} /> : null}
  </div>;
}
