import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, Checkbox, EmptyState, InlineNotice, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";
import { seoulInputValue, seoulInstant } from "../../lib/seoulDateTime";
import { useResource } from "../shared/useResource";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";

type Display = components["schemas"]["StoreCustomerDisplayAuthoring"];
type OperatingDay = components["schemas"]["StoreOperatingDay"];
type Slot = components["schemas"]["ManagedPickupSlot"];
const days: [OperatingDay["dayOfWeek"], string][] = [["MONDAY", "월요일"], ["TUESDAY", "화요일"], ["WEDNESDAY", "수요일"], ["THURSDAY", "목요일"], ["FRIDAY", "금요일"], ["SATURDAY", "토요일"], ["SUNDAY", "일요일"]];

export function StoreSchedulingPage() {
  const stores = useMerchantStores();
  if (stores.state.status === "loading") return <LoadingState label="매장을 불러오는 중" />;
  if (stores.state.status === "failed") return <ErrorState error={stores.state.error} retry={stores.reload} />;
  return <div className="management-workspace">
    <StoreSelector stores={stores.stores} selected={stores.selected} onSelect={stores.select} />
    {stores.selected ? <div key={stores.selected.storeId}>
      {stores.selected.membershipRole === "OWNER" ? <DisplayEditor storeId={stores.selected.storeId} /> : <InlineNotice tone="info" title="고객 공개 정보는 점주가 변경할 수 있습니다" description="직원은 픽업 시간과 정원을 관리할 수 있습니다." />}
      <PickupSlots storeId={stores.selected.storeId} />
    </div> : <EmptyState title="관리할 매장이 없습니다" description="소속 매장과 권한을 확인해 주세요." />}
  </div>;
}

function DisplayEditor({ storeId }: { storeId: string }) {
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/customer-display", { params: { path: { storeId } } })), [storeId]));
  const [saved, setSaved] = useState(false);
  return <section className="surface-card management-card"><h2>고객 공개 정보와 주간 영업시간</h2>
    {saved ? <p role="status">공개 정보를 저장했습니다.</p> : null}
    {resource.state.status === "loading" ? <LoadingState label="공개 정보를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <DisplayForm key={resource.state.value.version} storeId={storeId} current={resource.state.value} onSaved={() => { setSaved(true); resource.reload(); }} onRefresh={() => { setSaved(false); resource.reload(); }} />}
  </section>;
}
function DisplayForm({ storeId, current, onSaved, onRefresh }: { storeId: string; current: Display; onSaved: () => void; onRefresh: () => void }) {
  const [address, setAddress] = useState(current.addressLine ?? "");
  const [directions, setDirections] = useState(current.directionsHint ?? "");
  const [showHours, setShowHours] = useState(!!current.operatingHours);
  const [schedule, setSchedule] = useState<OperatingDay[]>(days.map(([dayOfWeek]) => current.operatingHours?.days.find(day => day.dayOfWeek === dayOfWeek) ?? { dayOfWeek, closed: true }));
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  function update(index: number, value: Partial<OperatingDay>) { setSchedule(items => items.map((day, i) => i === index ? { ...day, ...value } : day)); }
  async function save() {
    if (saving) return;
    setSaving(true); setFailure(null);
    try {
      const operatingHours = showHours ? { timezone: "Asia/Seoul" as const, days: schedule.map(day => day.closed ? { dayOfWeek: day.dayOfWeek, closed: true } : day) } : null;
      if (operatingHours?.days.some(day => !day.closed && (!("opensAt" in day) || !day.opensAt || !("closesAt" in day) || !day.closesAt || day.opensAt >= day.closesAt))) throw new Error("영업 종료는 시작보다 늦어야 합니다.");
      unwrap(await merchantApi.PUT("/stores/{storeId}/customer-display", { params: { path: { storeId }, header: await merchantCsrfHeader() }, body: { expectedVersion: current.version, addressLine: address.trim() || null, directionsHint: directions.trim() || null, operatingHours } }));
      onSaved();
    } catch (error) { setFailure(error); } finally { setSaving(false); }
  }
  return <form onSubmit={event => { event.preventDefault(); void save(); }}>
    <fieldset disabled={saving} className="catalog-fieldset">
      <TextField label="고객에게 표시할 주소" value={address} onValueChange={setAddress} maxLength={300} />
      <TextAreaField label="길찾기 안내" value={directions} onValueChange={setDirections} maxLength={200} />
      <Checkbox label="주간 영업시간 표시" description="해제 후 저장하면 고객에게 표시되는 주간 영업시간을 지웁니다. 주문 접수 설정과는 별개입니다." checked={showHours} onCheckedChange={setShowHours} />
      {showHours ? <div className="management-card-grid">{days.map(([key, label], index) => { const day = schedule[index]!; return <fieldset key={key} className="catalog-fieldset"><legend>{label}</legend>
        <Checkbox label={`${label} 휴무`} checked={day.closed} onCheckedChange={closed => update(index, { closed })} />
        {!day.closed ? <><TextField label={`${label} 시작`} type="time" required value={day.opensAt ?? ""} onValueChange={opensAt => update(index, { opensAt })} /><TextField label={`${label} 종료`} type="time" required value={day.closesAt ?? ""} onValueChange={closesAt => update(index, { closesAt })} /></> : null}
      </fieldset>; })}</div> : null}
      <p>영업시간은 한국 시간 기준입니다. 픽업 주문은 별도로 등록한 시간과 주문 접수 설정을 따릅니다.</p>
      {failure ? <ErrorState error={failure} /> : null}
      <div className="form-actions"><Button type="submit" loading={saving}>공개 정보 저장</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 공개 정보 다시 읽기</Button></div>
    </fieldset>
  </form>;
}

function PickupSlots({ storeId }: { storeId: string }) {
  const [from, setFrom] = useState(() => seoulInputValue(Date.now()));
  const [to, setTo] = useState(() => seoulInputValue(Date.now() + 7 * 86400000));
  const [query, setQuery] = useState(() => ({ from: seoulInstant(from), to: seoulInstant(to), cursor: undefined as string | undefined }));
  const [selected, setSelected] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [filterError, setFilterError] = useState<unknown>(null);
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/pickup-slot-management", { params: { path: { storeId }, query: { ...query, limit: 20 } } })), [storeId, query]));
  return <section className="surface-card management-card"><h2>픽업 시간과 정원</h2>
    <form className="management-card-grid" onSubmit={event => { event.preventDefault(); try { const start = seoulInstant(from); const end = seoulInstant(to); if (start >= end) throw new Error("조회 종료는 시작보다 늦어야 합니다."); setFilterError(null); setQuery({ from: start, to: end, cursor: undefined }); } catch (error) { setFilterError(error); } }}>
      <TextField label="조회 시작 (한국 시간)" type="datetime-local" required value={from} onValueChange={setFrom} />
      <TextField label="조회 종료 (한국 시간)" type="datetime-local" required value={to} onValueChange={setTo} /><Button type="submit" variant="secondary">픽업 목록 조회</Button>
    </form>
    {filterError ? <ErrorState error={filterError} /> : null}
    <Button variant="secondary" onClick={() => { setSelected("new"); setSaved(false); }}>새 픽업 시간</Button>
    {saved ? <p role="status">픽업 시간을 저장했습니다.</p> : null}
    {selected === "new" ? <SlotForm key="new" storeId={storeId} onSaved={() => { setSelected(null); setSaved(true); resource.reload(); }} onClose={() => setSelected(null)} /> : selected ? <SlotEditor key={selected} storeId={storeId} slotId={selected} onSaved={() => { setSelected(null); setSaved(true); resource.reload(); }} onClose={() => setSelected(null)} /> : null}
    {resource.state.status === "loading" ? <LoadingState label="픽업 시간을 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>
      {resource.state.value.items.length === 0 ? <EmptyState title="이 구간에 픽업 시간이 없습니다" description="조회 기간을 바꾸거나 새 픽업 시간을 등록해 주세요." /> : <div className="management-card-grid">{resource.state.value.items.map(slot => <article className="surface-card management-card" key={slot.slotId}>
        <h3>{shortDateTime.format(new Date(slot.startsAt))}–{shortDateTime.format(new Date(slot.endsAt))}</h3>
        <dl className="detail-list"><div><dt>정원</dt><dd>{slot.capacity}</dd></div><div><dt>예약</dt><dd>{slot.reservedCount}</dd></div><div><dt>확정</dt><dd>{slot.confirmedCount}</dd></div></dl>
        <Button variant="secondary" disabled={Date.parse(slot.startsAt) <= Date.now()} aria-label={`${shortDateTime.format(new Date(slot.startsAt))} 픽업 시간 수정`} onClick={() => { setSelected(slot.slotId); setSaved(false); }}>수정</Button>
      </article>)}</div>}
      <div className="form-actions"><Button variant="ghost" disabled={!query.cursor} onClick={() => setQuery({ ...query, cursor: undefined })}>처음 목록</Button><Button variant="secondary" disabled={!resource.state.value.nextCursor} onClick={() => { if (resource.state.status === "ready" && resource.state.value.nextCursor) setQuery({ ...query, cursor: resource.state.value.nextCursor }); }}>다음 픽업 목록</Button></div>
    </>}
  </section>;
}
function SlotEditor({ storeId, slotId, onSaved, onClose }: { storeId: string; slotId: string; onSaved: () => void; onClose: () => void }) {
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/pickup-slot-management/{slotId}", { params: { path: { storeId, slotId } } })), [storeId, slotId]));
  if (resource.state.status === "loading") return <LoadingState label="현재 픽업 정보를 확인하는 중" />;
  if (resource.state.status === "failed") return <ErrorState error={resource.state.error} retry={resource.reload} />;
  return <SlotForm current={resource.state.value} storeId={storeId} onSaved={onSaved} onClose={onClose} onRefresh={resource.reload} />;
}
function SlotForm({ current, storeId, onSaved, onClose, onRefresh }: { current?: Slot; storeId: string; onSaved: () => void; onClose: () => void; onRefresh?: () => void }) {
  const [start, setStart] = useState(current ? seoulInputValue(current.startsAt) : "");
  const [end, setEnd] = useState(current ? seoulInputValue(current.endsAt) : "");
  const [capacity, setCapacity] = useState(current ? String(current.capacity) : "");
  const [reason, setReason] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const intent = useRef(new SubmissionIntent());
  const used = (current?.reservedCount ?? 0) + (current?.confirmedCount ?? 0);
  const started = !!current && Date.parse(current.startsAt) <= Date.now();
  async function save() {
    if (saving) return;
    setSaving(true); setFailure(null);
    try {
      // Preserve exact stored instants when reservations lock the time range.
      const body = { startsAt: current && start === seoulInputValue(current.startsAt) ? current.startsAt : seoulInstant(start), endsAt: current && end === seoulInputValue(current.endsAt) ? current.endsAt : seoulInstant(end), capacity: Number(capacity), reason: reason.trim() };
      if (!capacity || !Number.isSafeInteger(body.capacity) || body.capacity < used || body.startsAt >= body.endsAt || Date.parse(body.startsAt) <= Date.now()) throw new Error("미래 픽업 시간과 예약·확정 수량 이상의 정원을 입력해 주세요.");
      const fingerprint = JSON.stringify({ storeId, slotId: current?.slotId, version: current?.version, ...body });
      const header = { "Idempotency-Key": intent.current.keyFor(fingerprint), ...(await merchantCsrfHeader()) };
      if (current) unwrap(await merchantApi.PUT("/stores/{storeId}/pickup-slot-management/{slotId}", { params: { path: { storeId, slotId: current.slotId }, header }, body: { ...body, expectedVersion: current.version } }));
      else unwrap(await merchantApi.POST("/stores/{storeId}/pickup-slot-management", { params: { path: { storeId }, header }, body }));
      intent.current.complete(); onSaved();
    } catch (error) { setFailure(error); } finally { setSaving(false); }
  }
  return <form onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={saving || started}>
    <legend>{current ? "픽업 시간 수정" : "픽업 시간 등록"}</legend>
    <p>한국 시간 기준입니다. 예약·확정된 주문이 있으면 시각을 바꿀 수 없습니다.</p>
    <div className="management-card-grid"><TextField label="픽업 시작" type="datetime-local" required disabled={used > 0} value={start} onValueChange={setStart} /><TextField label="픽업 종료" type="datetime-local" required disabled={used > 0} value={end} onValueChange={setEnd} /><TextField label="정원" type="number" min={used} step={1} required value={capacity} onValueChange={setCapacity} /></div>
    <TextAreaField label="픽업 변경 사유" required maxLength={500} value={reason} onValueChange={setReason} />
    {failure ? <ErrorState error={failure} /> : null}
    <Button type="submit" loading={saving}>픽업 시간 저장</Button>
  </fieldset><div className="form-actions">{onRefresh ? <Button type="button" variant="secondary" disabled={saving} onClick={onRefresh}>현재 픽업 정보 다시 읽기</Button> : null}<Button type="button" variant="ghost" disabled={saving} onClick={onClose}>편집 닫기</Button></div></form>;
}
