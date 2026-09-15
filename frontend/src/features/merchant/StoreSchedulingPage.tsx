import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, Checkbox, EmptyState, InlineNotice, LoadingState, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { StorefrontImageEditor } from "./StorefrontImageEditor";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";

type Display = components["schemas"]["StoreCustomerDisplayAuthoring"];
type OperatingDay = components["schemas"]["StoreOperatingDay"];
const days: [OperatingDay["dayOfWeek"], string][] = [["MONDAY", "월요일"], ["TUESDAY", "화요일"], ["WEDNESDAY", "수요일"], ["THURSDAY", "목요일"], ["FRIDAY", "금요일"], ["SATURDAY", "토요일"], ["SUNDAY", "일요일"]];

export function StoreSchedulingPage({ onBusyChange }: { /** Report unsaved work before changing workspaces. */ onBusyChange?: (busy: boolean) => void }) {
  const stores = useMerchantStores();
  const [displayBusy, setDisplayBusy] = useState(false);
  const [imageBusy, setImageBusy] = useState(false);
  const locked = displayBusy || imageBusy;
  useEffect(() => { onBusyChange?.(locked); return () => onBusyChange?.(false); }, [locked, onBusyChange]);
  if (stores.state.status === "loading") return <LoadingState label="매장을 불러오는 중" />;
  if (stores.state.status === "failed") return <ErrorState error={stores.state.error} retry={stores.reload} />;
  return <div className="management-workspace">
    <StoreSelector stores={stores.stores} selected={stores.selected} onSelect={stores.select} disabled={locked} />
    {stores.selected ? <div key={stores.selected.storeId}>
      {stores.selected.membershipRole === "OWNER" ? <><DisplayEditor storeId={stores.selected.storeId} onBusyChange={setDisplayBusy} /><StorefrontImageEditor storeId={stores.selected.storeId} label="매장 대표 이미지" onBusyChange={setImageBusy} /></> : <InlineNotice tone="info" title="고객 공개 정보는 점주가 변경할 수 있습니다" description="요일별 영업시간과 매장 이미지는 점주 권한으로 관리합니다." />}
    </div> : <EmptyState title="관리할 매장이 없습니다" description="소속 매장과 권한을 확인해 주세요." />}
  </div>;
}

function DisplayEditor({ storeId, onBusyChange }: { storeId: string; onBusyChange: (busy: boolean) => void }) {
  const resource = useResource(useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/customer-display", { params: { path: { storeId } } })), [storeId]));
  const [saved, setSaved] = useState(false);
  return <section className="surface-card management-card"><h2>고객 공개 정보와 주간 영업시간</h2>
    {saved ? <p role="status">공개 정보를 저장했습니다.</p> : null}
    {resource.state.status === "loading" ? <LoadingState label="공개 정보를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <DisplayForm key={resource.state.value.version} storeId={storeId} current={resource.state.value} onBusyChange={onBusyChange} onEdit={() => setSaved(false)} onSaved={() => { setSaved(true); resource.reload(); }} onRefresh={() => { setSaved(false); resource.reload(); }} />}
  </section>;
}
function DisplayForm({ storeId, current, onSaved, onRefresh, onEdit, onBusyChange }: { storeId: string; current: Display; onSaved: () => void; onRefresh: () => void; onEdit: () => void; onBusyChange: (busy: boolean) => void }) {
  const [address, setAddress] = useState(current.addressLine ?? "");
  const [directions, setDirections] = useState(current.directionsHint ?? "");
  const [showHours, setShowHours] = useState(!!current.operatingHours);
  const [schedule, setSchedule] = useState<OperatingDay[]>(days.map(([dayOfWeek]) => current.operatingHours?.days.find(day => day.dayOfWeek === dayOfWeek) ?? { dayOfWeek, closed: true }));
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const originalSchedule = days.map(([dayOfWeek]) => current.operatingHours?.days.find(day => day.dayOfWeek === dayOfWeek) ?? { dayOfWeek, closed: true });
  const locked = saving || address !== (current.addressLine ?? "") || directions !== (current.directionsHint ?? "") || showHours !== !!current.operatingHours || JSON.stringify(schedule) !== JSON.stringify(originalSchedule);
  useEffect(() => { onBusyChange(locked); return () => onBusyChange(false); }, [locked, onBusyChange]);
  function update(index: number, value: Partial<OperatingDay>) { onEdit(); setSchedule(items => items.map((day, i) => i === index ? { ...day, ...value } : day)); }
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
      <TextField label="고객에게 표시할 주소" value={address} onValueChange={value => { setAddress(value); onEdit(); }} maxLength={300} />
      <TextAreaField label="길찾기 안내" value={directions} onValueChange={value => { setDirections(value); onEdit(); }} maxLength={200} />
      <Checkbox label="주간 영업시간 설정" description="설정하지 않으면 신규 결제가 차단됩니다. 고객에게도 같은 영업시간을 표시합니다." checked={showHours} onCheckedChange={value => { setShowHours(value); onEdit(); }} />
      {showHours ? <div className="management-card-grid">{days.map(([key, label], index) => { const day = schedule[index]!; return <fieldset key={key} className="catalog-fieldset"><legend>{label}</legend>
        <Checkbox label={`${label} 휴무`} checked={day.closed} onCheckedChange={closed => update(index, { closed })} />
        {!day.closed ? <><TextField label={`${label} 시작`} type="time" required value={day.opensAt ?? ""} onValueChange={opensAt => update(index, { opensAt })} /><TextField label={`${label} 종료`} type="time" required value={day.closesAt ?? ""} onValueChange={closesAt => update(index, { closesAt })} /></> : null}
      </fieldset>; })}</div> : null}
      <p>영업시간은 한국 시간 기준 같은 날 단일 구간이며, 시작 시각은 포함하고 종료 시각은 포함하지 않습니다. 신규 주문은 별도 픽업 슬롯 없이 이 시간 안에서 결제됩니다.</p>
      {failure ? <ErrorState error={failure} /> : null}
      <div className="button-row"><Button type="submit" loading={saving}>공개 정보 저장</Button><Button type="button" variant="secondary" onClick={onRefresh}>현재 공개 정보 다시 읽기</Button></div>
    </fieldset>
  </form>;
}
