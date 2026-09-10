import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, PageHeading, Tab, TabList, TabPanel, Tabs, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { StoreTermsWorkspace } from "./StoreTermsWorkspace";
import { StoreMembershipsWorkspace } from "./StoreMembershipsWorkspace";

type StoreIdentity = components["schemas"]["StoreIdentitySnapshot"];
type Region = components["schemas"]["OperatorStoreRegion"];
type Brand = components["schemas"]["Brand"];

/** Store selection is shared by the operator's identity and store-scoped management work. */
export function OperationsStoresPage() {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState("");
  const cursor = cursors.at(-1);
  const stores = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores", { params: { query: { query: query || undefined, cursor, limit: 20 } } })), [query, cursor]));
  return <div className="console-page">
    <PageHeading title="매장 관리" action={<Button onClick={() => { setCreating(true); setSelected(null); setNotice(""); }}>새 매장 등록</Button>} />
    <form className="button-row" onSubmit={event => { event.preventDefault(); if (query === search.trim() && !cursor) stores.reload(); else { setQuery(search.trim()); setCursors([undefined]); } }}>
      <TextField label="매장 이름 검색" value={search} onValueChange={setSearch} maxLength={200} />
      <Button type="submit" variant="secondary">매장 검색</Button>
    </form>
    {notice ? <p role="status">{notice}</p> : null}
    {stores.state.status === "loading" ? <LoadingState label="매장 목록을 불러오는 중" /> : stores.state.status === "failed" ? <ErrorState error={stores.state.error} retry={stores.reload} /> : <section className="surface-card management-card">
      {stores.state.value.items.length ? <ul className="menu-authoring-list">{stores.state.value.items.map(store => <li key={store.storeId}><div><strong>{store.name}</strong><p className="support-case-reference">{store.storeId}</p><p>주문 {store.acceptingOrders ? "접수 중" : "접수 중지"} · 픽업 {store.pickupEnabled ? "사용" : "중지"}</p></div><Button variant="secondary" aria-label={`${store.name} 관리`} onClick={() => { setSelected(store.storeId); setCreating(false); setNotice(""); }}>관리</Button></li>)}</ul> : <EmptyState title="검색 결과가 없습니다." description="매장 이름을 확인하거나 새 매장을 등록해 주세요." />}
      <div className="button-row"><Button variant="ghost" disabled={cursors.length === 1} onClick={() => setCursors(value => value.slice(0, -1))}>이전 매장 목록</Button><Button variant="secondary" disabled={!stores.state.value.nextCursor} onClick={() => { if (stores.state.status === "ready" && stores.state.value.nextCursor) { const next = stores.state.value.nextCursor; setCursors(value => [...value, next]); } }}>다음 매장 목록</Button></div>
    </section>}
    {creating ? <section className="surface-card management-card"><h2>새 매장</h2><IdentityForm onSaved={store => { setCreating(false); setSelected(store.storeId); setNotice("매장을 등록했습니다. 주문 접수와 픽업은 중지 상태입니다."); stores.reload(); }} /><Button variant="ghost" onClick={() => setCreating(false)}>등록 닫기</Button></section> : null}
    {selected ? <StoreWorkspace key={selected} storeId={selected} onChanged={stores.reload} /> : null}
  </div>;
}

function StoreWorkspace({ storeId, onChanged }: { storeId: string; onChanged: () => void }) {
  const [workspace, setWorkspace] = useState("identity");
  const identity = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/identity", { params: { path: { storeId } } })), [storeId]));
  const [saved, setSaved] = useState(false);
  return <section className="management-workspace"><h2>선택한 매장</h2><p className="support-case-reference">{storeId}</p>
    <Tabs value={workspace} onValueChange={setWorkspace}><TabList label="선택한 매장 업무"><Tab value="identity">식별정보·브랜드</Tab><Tab value="terms">정산 계약</Tab><Tab value="memberships">점주·직원 소속</Tab></TabList>
    <TabPanel value="identity">
    {saved ? <p role="status">식별정보를 저장했습니다.</p> : null}
    {identity.state.status === "loading" ? <LoadingState label="현재 식별정보를 불러오는 중" /> : identity.state.status === "failed" ? <ErrorState error={identity.state.error} retry={identity.reload} /> : <div className="surface-card management-card"><IdentityForm key={identity.state.value.version} current={identity.state.value} onSaved={() => { setSaved(true); identity.reload(); onChanged(); }} onRefresh={() => { setSaved(false); identity.reload(); }} /></div>}
    <StoreBrandEditor storeId={storeId} />
    </TabPanel><TabPanel value="terms"><StoreTermsWorkspace storeId={storeId} /></TabPanel><TabPanel value="memberships"><StoreMembershipsWorkspace storeId={storeId} /></TabPanel></Tabs>
  </section>;
}

function IdentityForm({ current, onSaved, onRefresh }: { current?: StoreIdentity; onSaved: (store: StoreIdentity) => void; onRefresh?: () => void }) {
  const [name, setName] = useState(current?.name ?? "");
  const [latitude, setLatitude] = useState(current ? String(current.latitude) : "");
  const [longitude, setLongitude] = useState(current ? String(current.longitude) : "");
  const [region, setRegion] = useState<Region | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());
  async function save() {
    if (busy || (!current && !region)) return;
    const fields = { name: name.trim(), latitude: Number(latitude), longitude: Number(longitude), reason: reason.trim() };
    const body = current ? { ...fields, expectedVersion: current.version } : { ...fields, regionCode: region!.code };
    const header = { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ storeId: current?.storeId, ...body })) };
    setBusy(true); setFailure(null);
    try {
      const store = current
        ? unwrap(await operationsApi.PUT("/operations/stores/{storeId}/identity", { params: { path: { storeId: current.storeId }, header }, body: { ...fields, expectedVersion: current.version } }))
        : unwrap(await operationsApi.POST("/operations/stores", { params: { header }, body: { ...fields, regionCode: region!.code } }));
      intent.current.complete(); onSaved(store);
    } catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  return <form onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={busy}>
    <h3>{current ? "매장 식별정보" : "등록 정보"}</h3>
    <TextField label="매장 이름" value={name} onValueChange={setName} maxLength={200} required />
    <div className="management-card-grid"><TextField label="위도" type="number" min="-90" max="90" step="any" value={latitude} onValueChange={setLatitude} required /><TextField label="경도" type="number" min="-180" max="180" step="any" value={longitude} onValueChange={setLongitude} required /></div>
    {current ? <p>지역 코드: {current.regionCode} · 지역 변경은 해당 매장 점주가 매장 설정에서 진행합니다.</p> : <RegionPicker value={region} onChange={setRegion} />}
    <TextAreaField label={current ? "식별정보 변경 사유" : "매장 등록 사유"} value={reason} onValueChange={setReason} maxLength={500} required />
    {failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy} disabled={!name.trim() || !reason.trim() || (!current && !region)}>{current ? "식별정보 저장" : "매장 생성"}</Button>{current ? <Button type="button" variant="secondary" onClick={onRefresh}>현재 식별정보 다시 읽기</Button> : null}</div>
  </fieldset></form>;
}

function RegionPicker({ value, onChange }: { value: Region | null; onChange: (region: Region) => void }) {
  const [search, setSearch] = useState("");
  const [request, setRequest] = useState<{ query: string; cursor?: string } | null>(null);
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const regions = useResource(useCallback(async () => request ? unwrap(await operationsApi.GET("/operations/store-regions", { params: { query: { ...request, limit: 20 } } })) : null, [request]));
  return <section><h4>등록 지역 선택</h4><div className="button-row"><TextField label="법정동 이름" value={search} onValueChange={setSearch} maxLength={200} /><Button type="button" variant="secondary" onClick={() => { setCursors([undefined]); setRequest({ query: search.trim() }); }}>지역 검색</Button></div>
    {value ? <p role="status">선택 지역: {value.fullName}</p> : <p>등록된 법정동 검색 결과에서 지역을 선택해 주세요.</p>}
    {regions.state.status === "loading" ? <LoadingState label="지역을 찾는 중" /> : regions.state.status === "failed" ? <ErrorState error={regions.state.error} retry={regions.reload} /> : regions.state.value ? <><ul className="menu-authoring-list">{regions.state.value.items.map(region => <li key={region.code}><span>{region.fullName}</span><Button type="button" variant="ghost" aria-label={`${region.fullName} 선택`} onClick={() => onChange(region)}>선택</Button></li>)}</ul>{!regions.state.value.items.length ? <p>일치하는 지역이 없습니다.</p> : null}<div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => { const next = cursors.slice(0, -1); setCursors(next); setRequest({ query: request!.query, cursor: next.at(-1) }); }}>이전 지역</Button><Button type="button" variant="secondary" disabled={!regions.state.value.nextCursor} onClick={() => { if (regions.state.status === "ready" && regions.state.value?.nextCursor) { const cursor = regions.state.value.nextCursor; setCursors(value => [...value, cursor]); setRequest({ query: request!.query, cursor }); } }}>다음 지역</Button></div></> : null}
  </section>;
}

function StoreBrandEditor({ storeId }: { storeId: string }) {
  const assignment = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/brand", { params: { path: { storeId } } })), [storeId]));
  const [request, setRequest] = useState<{ cursor?: string } | null>(null);
  const brands = useResource(useCallback(async () => request ? unwrap(await operationsApi.GET("/operations/brands", { params: { query: { ...request, limit: 20 } } })) : null, [request]));
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [selected, setSelected] = useState<Brand | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const intent = useRef(new SubmissionIntent());
  async function change(clear: boolean) {
    if (busy || assignment.state.status !== "ready" || !reason.trim() || (!clear && !selected)) return;
    const body = clear ? { reason: reason.trim() } : { reason: reason.trim(), brandId: selected!.brandId };
    const header = { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ storeId, clear, ...body })) };
    setBusy(true); setFailure(null); setNotice("");
    try {
      if (clear) unwrap(await operationsApi.DELETE("/operations/stores/{storeId}/brand", { params: { path: { storeId }, header }, body }));
      else unwrap(await operationsApi.PUT("/operations/stores/{storeId}/brand", { params: { path: { storeId }, header }, body: { brandId: selected!.brandId, reason: reason.trim() } }));
      intent.current.complete(); setReason(""); setSelected(null); setNotice(clear ? "브랜드 소속을 해제했습니다." : "브랜드를 지정했습니다.");
    } catch (error) { setFailure(error); } finally { setBusy(false); assignment.reload(); }
  }
  return <section className="surface-card management-card"><h3>브랜드 소속</h3>
    {assignment.state.status === "loading" ? <LoadingState label="현재 브랜드를 불러오는 중" /> : assignment.state.status === "failed" ? <ErrorState error={assignment.state.error} retry={assignment.reload} /> : <p>{assignment.state.value.brandId ? `현재 브랜드: ${assignment.state.value.brandName}` : "현재 소속 브랜드가 없습니다."}</p>}
    <fieldset className="catalog-fieldset" disabled={busy || assignment.state.status !== "ready"}>
      <Button variant="secondary" onClick={() => { setCursors([undefined]); setRequest({}); }}>브랜드 목록 조회</Button>
      {brands.state.status === "loading" ? <LoadingState label="브랜드 목록을 불러오는 중" /> : brands.state.status === "failed" ? <ErrorState error={brands.state.error} retry={brands.reload} /> : brands.state.value ? <><ul className="menu-authoring-list">{brands.state.value.items.map(brand => <li key={brand.brandId}><span>{brand.name} · {brand.status === "ACTIVE" ? "활성" : "보관"}</span><Button variant="ghost" disabled={brand.status !== "ACTIVE"} aria-label={`${brand.name} 선택`} onClick={() => setSelected(brand)}>선택</Button></li>)}</ul>{!brands.state.value.items.length ? <p>등록된 브랜드가 없습니다.</p> : null}<div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => { const next = cursors.slice(0, -1); setCursors(next); setRequest({ cursor: next.at(-1) }); }}>이전 브랜드</Button><Button variant="secondary" disabled={!brands.state.value.page.nextCursor} onClick={() => { if (brands.state.status === "ready" && brands.state.value?.page.nextCursor) { const cursor = brands.state.value.page.nextCursor; setCursors(value => [...value, cursor]); setRequest({ cursor }); } }}>다음 브랜드</Button></div></> : null}
      {selected ? <p>선택한 브랜드: {selected.name}</p> : null}
      <TextAreaField label="브랜드 변경 사유" value={reason} onValueChange={setReason} maxLength={200} required />
      <div className="button-row"><Button disabled={!selected || !reason.trim()} loading={busy} onClick={() => void change(false)}>선택한 브랜드로 지정</Button><Button variant="danger" disabled={assignment.state.status !== "ready" || !assignment.state.value.brandId || !reason.trim()} onClick={() => void change(true)}>현재 브랜드 해제</Button><Button variant="ghost" onClick={assignment.reload}>현재 브랜드 다시 읽기</Button></div>
    </fieldset>
    {notice ? <p role="status">{notice}</p> : null}{failure ? <ErrorState error={failure} /> : null}
  </section>;
}
