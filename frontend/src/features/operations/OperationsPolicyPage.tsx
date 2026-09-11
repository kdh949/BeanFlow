import { useSearchParams } from "react-router";
import { PointCostIssuerPicker, currentPolicyIssuer, pointIssuerTypeLabels, type PointCostIssuerSelection } from "./PointCostIssuerPicker";
import { PlatformCostOwnerWorkspace } from "./PlatformCostOwnerWorkspace";
import { useSupportCommand } from "../support/useSupportCommand";
import { Gift, RefreshCw, SearchCheck, Settings2, Tags } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, PageHeading, SelectField, Tab, TabList, TabPanel, Tabs, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime, shortDateTime } from "../../lib/format";
import { PointPolicyHistory } from "./PointPolicyHistory";
import { StorePointPolicyDirectory } from "./StorePointPolicyDirectory";

type PolicyVersion = components["schemas"]["OrdinaryPointAccrualPolicyVersion"];
type PointPolicy = PolicyVersion & Required<Pick<PolicyVersion, "accrualRateBps" | "roundingMode" | "issuerType" | "issuerReference" | "expiryRule" | "validityDays">>;
function completeGlobalPolicy(policy: PolicyVersion): PointPolicy {
  if (policy.scopeType !== "GLOBAL" || policy.state !== "OVERRIDE" || policy.accrualRateBps === undefined || !policy.roundingMode || !policy.issuerType || !policy.issuerReference || !policy.expiryRule || policy.validityDays === undefined) {
    throw new ApiRequestError(502, "POLICY_DATA_INCOMPLETE", "Global policy fields are incomplete");
  }
  return policy as PointPolicy;
}
type RestorationPolicy = components["schemas"]["ExpiredBenefitRestorationPolicy"];
type Brand = components["schemas"]["Brand"];
type SearchResult = components["schemas"]["SearchIndexRebuildResponse"];
type Workspace = "points" | "store-points" | "restoration" | "brands" | "search" | "cost-owners";

const workspaceItems: Array<{ id: Workspace; label: string; icon: typeof Settings2 }> = [
  { id: "points", label: "포인트 적립", icon: Settings2 },
  { id: "store-points", label: "매장별 포인트", icon: Settings2 },
  { id: "cost-owners", label: "포인트 비용 주체", icon: Tags },
  { id: "restoration", label: "만료 혜택 복원", icon: Gift },
  { id: "brands", label: "브랜드", icon: Tags },
  { id: "search", label: "검색 색인", icon: SearchCheck },
];

function mutationError(error: unknown, intent: SubmissionIntent) {
  if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.rotate();
}

export function OperationsPolicyPage() {
  const [params, setParams] = useSearchParams();
  const requestedWorkspace = params.get("workspace");
  const workspace = workspaceItems.some(item => item.id === requestedWorkspace) ? requestedWorkspace! : "points";
  const [locked, setLocked] = useState(false);
  return (
    <div className="console-page operations-policy-page">
      <PageHeading title="운영 정책 관리" />
      <Tabs value={workspace} onValueChange={value => { if (!locked) setParams({ workspace: value }); }}>
        <TabList label="운영 정책 업무 선택">{workspaceItems.map(({ id, label, icon: Icon }) => <Tab key={id} value={id} disabled={locked}><Icon size={17} aria-hidden="true" /> {label}</Tab>)}</TabList>
        <TabPanel value="points"><PointPolicyWorkspace onBusyChange={setLocked} /><PointPolicyHistory /></TabPanel>
        <TabPanel value="store-points"><StorePointPolicyDirectory onBusyChange={setLocked} /></TabPanel>
        <TabPanel value="restoration"><RestorationPolicyWorkspace /></TabPanel>
        <TabPanel value="cost-owners"><PlatformCostOwnerWorkspace onBusyChange={setLocked} /></TabPanel>
        <TabPanel value="brands"><BrandWorkspace /></TabPanel>
        <TabPanel value="search"><SearchIndexWorkspace /></TabPanel>
      </Tabs>
    </div>
  );
}

function PointPolicyWorkspace({ onBusyChange }: { onBusyChange: (busy: boolean) => void }) {
  const [accessReason, setAccessReason] = useState("");
  const [policy, setPolicy] = useState<PointPolicy | null>(null);
  const [rate, setRate] = useState("0");
  const [roundingMode, setRoundingMode] = useState<"FLOOR" | "HALF_UP">("FLOOR");
  const [issuer, setIssuer] = useState<PointCostIssuerSelection | null>(null);
  const [expiryRule, setExpiryRule] = useState<"EXACT_DURATION_FROM_COMPLETION" | "SEOUL_CALENDAR_DAYS_FROM_COMPLETION">("SEOUL_CALENDAR_DAYS_FROM_COMPLETION");
  const [validityDays, setValidityDays] = useState("365");
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);

  const [error, setError] = useState<unknown>(null);
  const command = useSupportCommand(() => undefined);
  const saving = command.busy, locked = command.busy || command.pending;
  const saveError = command.failure;
  useEffect(() => { onBusyChange(locked); return () => onBusyChange(false); }, [locked, onBusyChange]);

  function fillForm(next: PointPolicy) {
    setRate((next.accrualRateBps / 100).toString());
    setRoundingMode(next.roundingMode);
    setIssuer(currentPolicyIssuer(next));
    setExpiryRule(next.expiryRule);
    setValidityDays(String(next.validityDays));
  }

  async function load() {
    if (locked || loading) return;
    setLoading(true);
    setError(null);
    try {
      const next = completeGlobalPolicy(unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/global", {
        params: { header: { "X-Access-Reason": accessReason } },
      })));
      setPolicy(next);
      fillForm(next);
    } catch (nextError) {
      setError(nextError);
      setPolicy(null);
    } finally {
      setLoading(false);
    }
  }

  function save() {
    if (!policy || !issuer || locked || !reason.trim()) return;
    const body = { state: "OVERRIDE" as const, expectedPolicyVersionId: policy.policyVersionId,
      accrualRateBps: Math.round(Number(rate) * 100), roundingMode, issuerType: issuer.issuerType,
      issuerReference: issuer.issuerReference, expiryRule, validityDays: Number(validityDays), reason: reason.trim() };
    command.submit(JSON.stringify(body), async key => {
      const next = completeGlobalPolicy(unwrap(await operationsApi.PATCH("/operations/policies/ordinary-point-accrual/global", { params: { header: { "Idempotency-Key": key } }, body })));
      setPolicy(next); fillForm(next); setReason("");
    }, () => undefined);
  }

  return (
    <section className="policy-workspace" aria-labelledby="point-policy-title">
      <div className="surface-card policy-intro-card">
        <div><span className="context-label">공통 기본값</span><h2 id="point-policy-title">공통 포인트 적립 정책</h2></div>
        <div className="policy-audit-read">
          <SelectField label="정책 조회 사유" id="point-policy-access-reason" value={accessReason} onValueChange={setAccessReason} required>
            <option value="">업무 사유 선택</option>
            <option value="POLICY_CHANGE_REVIEW">정책 변경 전 현재값 확인</option>
            <option value="POLICY_AUDIT_REVIEW">정책 감사 검토</option>
          </SelectField>
          <Button variant="secondary" disabled={locked || !accessReason} loading={loading} onClick={() => void load()}>현재 적립 정책 조회</Button>
        </div>
      </div>
      {loading ? <LoadingState label="현재 적립 정책을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {!loading && !error && !policy ? <EmptyState title="조회 전입니다" description="감사 사유를 선택한 뒤 현재 정책을 조회해야 변경할 수 있습니다." /> : null}
      {policy ? (
        <div className="console-detail-grid policy-detail-grid">
          <section className="surface-card order-panel">
            <div className="panel-heading"><div><span className="context-label">현재 버전</span><h2>버전 {policy.policyVersionId} 적용 중</h2></div><StatusText state={policy.state} /></div>
            <dl className="detail-list">
              <div><dt>적립률</dt><dd>{(policy.accrualRateBps / 100).toFixed(2)}%</dd></div>
              <div><dt>반올림</dt><dd>{policy.roundingMode === "FLOOR" ? "버림" : "반올림"}</dd></div>
              <div><dt>비용 주체</dt><dd>{pointIssuerTypeLabels[policy.issuerType]} · 정책 버전 {policy.policyVersionId}의 비용 주체</dd></div>
              <div><dt>유효기간</dt><dd>{policy.validityDays}일</dd></div>
              <div><dt>적용 시각</dt><dd>{fullDateTime.format(new Date(policy.effectiveAt))}</dd></div>
            </dl>
          </section>
          <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <fieldset className="catalog-fieldset" disabled={locked}><legend>새 정책 버전</legend>
            <div className="field-grid">
              <TextField label="적립률(%)" type="number" min="0" max="100" step="0.01" value={rate} onValueChange={setRate} required />
              <SelectField label="반올림 방식" value={roundingMode} onValueChange={(value) => setRoundingMode(value as typeof roundingMode)}><option value="FLOOR">버림</option><option value="HALF_UP">반올림</option></SelectField>
              <PointCostIssuerPicker key={policy.policyVersionId} purpose="POLICY" value={issuer} onValueChange={setIssuer} disabled={locked} />
              <SelectField label="만료 계산" value={expiryRule} onValueChange={(value) => setExpiryRule(value as typeof expiryRule)}><option value="SEOUL_CALENDAR_DAYS_FROM_COMPLETION">서울 달력일</option><option value="EXACT_DURATION_FROM_COMPLETION">정확한 시간</option></SelectField>
              <TextField label="유효일수" type="number" min="1" max="3650" value={validityDays} onValueChange={setValidityDays} required />
            </div>
            <TextAreaField label="변경 사유" value={reason} maxLength={500} onValueChange={setReason} required />
            <Button type="submit" loading={saving} disabled={!reason.trim() || !issuer}>새 적립 정책 적용</Button>
            </fieldset>
            {saveError ? <ErrorState error={saveError} /> : null}
            {command.pending ? <><p role="status">정책 변경 결과를 확인하지 못했습니다. 선택한 비용 주체와 변경 내용을 유지합니다.</p><Button variant="secondary" loading={saving} onClick={() => void command.retry()}>같은 공통 정책 변경 결과 확인</Button></> : null}
          </form>
        </div>
      ) : null}
    </section>
  );
}

function RestorationPolicyWorkspace() {
  const [accessReason, setAccessReason] = useState("");
  const [policies, setPolicies] = useState<RestorationPolicy[]>([]);
  const [selected, setSelected] = useState<RestorationPolicy | null>(null);
  const [mode, setMode] = useState<RestorationPolicy["mode"]>("COMPENSATE_WITH_NEW_ISSUANCE");
  const [validityDays, setValidityDays] = useState("30");
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setPolicies(unwrap(await operationsApi.GET("/operations/policies/expired-benefit-restoration", {
        params: { header: { "X-Access-Reason": accessReason } },
      })));
    } catch (nextError) {
      setError(nextError);
      setPolicies([]);
    } finally {
      setLoading(false);
    }
  }

  function edit(policy: RestorationPolicy) {
    setSelected(policy);
    setMode(policy.mode);
    setValidityDays(String(policy.compensationValidityDays));
    setReason("");
    setSaveError(null);
    intent.current.rotate();
  }

  async function save() {
    if (!selected) return;
    const body = { expectedPolicyVersionId: selected.policyVersionId, mode, compensationValidityDays: Number(validityDays), reason: reason.trim() };
    const fingerprint = JSON.stringify({ trigger: selected.trigger, benefitType: selected.benefitType, ...body });
    setSaving(true);
    setSaveError(null);
    try {
      const next = unwrap(await operationsApi.PATCH("/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}", {
        params: {
          path: { trigger: selected.trigger, benefitType: selected.benefitType },
          header: { "Idempotency-Key": intent.current.keyFor(fingerprint) },
        },
        body,
      }));
      setPolicies((current) => current.map((item) => item.trigger === next.trigger && item.benefitType === next.benefitType ? next : item));
      setSelected(next);
      setReason("");
      intent.current.complete();
    } catch (nextError) {
      mutationError(nextError, intent.current);
      setSaveError(nextError);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="policy-workspace" aria-labelledby="restoration-title">
      <div className="surface-card policy-intro-card">
        <div><span className="context-label">만료 혜택</span><h2 id="restoration-title">만료 혜택 복원 정책</h2></div>
        <div className="policy-audit-read">
          <SelectField label="복원 정책 조회 사유" id="restoration-access-reason" value={accessReason} onValueChange={setAccessReason} required>
            <option value="">업무 사유 선택</option><option value="BENEFIT_POLICY_REVIEW">혜택 복원 정책 검토</option><option value="BENEFIT_POLICY_AUDIT">혜택 정책 감사</option>
          </SelectField>
          <Button variant="secondary" disabled={!accessReason} loading={loading} onClick={() => void load()}>복원 정책 조회</Button>
        </div>
      </div>
      {loading ? <LoadingState label="복원 정책을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {!loading && !error && policies.length === 0 ? <EmptyState title="조회 전입니다" description="감사 사유를 선택한 뒤 정책 다섯 개를 조회해 주세요." /> : null}
      {policies.length > 0 ? (
        <div className="console-detail-grid policy-detail-grid">
          <section className="policy-card-list" aria-label="만료 혜택 복원 정책 목록">
            {policies.map((policy) => (
              <article className={selected?.trigger === policy.trigger && selected?.benefitType === policy.benefitType ? "surface-card compact-policy-card is-selected" : "surface-card compact-policy-card"} key={`${policy.trigger}-${policy.benefitType}`}>
                <div><span className="context-label">{policy.trigger}</span><h3>{policy.benefitType === "COUPON" ? "쿠폰" : "포인트"}</h3></div>
                <StatusText state={`v${policy.policyVersionId}`} />
                <p>{policy.mode === "COMPENSATE_WITH_NEW_ISSUANCE" ? "신규 혜택 발급" : "원래 만료일 유지"}</p>
                <small>{policy.compensationValidityDays}일 · {policy.reason}</small>
                <Button size="sm" variant="secondary" onClick={() => edit(policy)}>정책 변경</Button>
              </article>
            ))}
          </section>
          {selected ? (
            <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
              <div className="panel-heading"><div><span className="context-label">{selected.trigger} · {selected.benefitType}</span><h3>버전 {selected.policyVersionId} 적용 중</h3></div><StatusText state={`v${selected.policyVersionId}`} /></div>
              <SelectField label="복원 방식" value={mode} onValueChange={(value) => setMode(value as typeof mode)}><option value="COMPENSATE_WITH_NEW_ISSUANCE">신규 혜택 발급</option><option value="PRESERVE_ORIGINAL_EXPIRY">원래 만료일 유지</option></SelectField>
              <TextField label="보상 유효일수" type="number" min="1" max="365" value={validityDays} onValueChange={setValidityDays} required />
              <TextAreaField label="복원 정책 변경 사유" value={reason} maxLength={500} onValueChange={(value) => { setReason(value); setSaveError(null); intent.current.rotate(); }} required />
              <Button type="submit" loading={saving} disabled={!reason.trim()}>새 복원 정책 적용</Button>
              {saveError ? <ErrorState error={saveError} /> : null}
            </form>
          ) : <EmptyState title="변경할 정책을 선택하세요" description="목록의 정책 변경 버튼을 선택하면 현재 버전을 기준으로 새 버전을 만들 수 있습니다." />}
        </div>
      ) : null}
    </section>
  );
}

function BrandWorkspace() {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const readGeneration = useRef(0);
  const [brands, setBrands] = useState<Brand[]>([]);
  const [selected, setSelected] = useState<Brand | null>(null);
  const [notice, setNotice] = useState("");
  const [newName, setNewName] = useState("");
  const [createReason, setCreateReason] = useState("");
  const [editName, setEditName] = useState("");
  const [editStatus, setEditStatus] = useState<Brand["status"]>("ACTIVE");
  const [editReason, setEditReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [mutationFailure, setMutationFailure] = useState<unknown>(null);
  const createIntent = useRef(new SubmissionIntent());
  const editIntent = useRef(new SubmissionIntent());

  async function load(pageCursor = cursor) {
    const generation = ++readGeneration.current;
    setBrands([]); setSelected(null); setNextCursor(null);
    setLoading(true);
    setError(null);
    try {
      const page = unwrap(await operationsApi.GET("/operations/brands", { params: { query: { limit: 20, cursor: pageCursor } } }));
      if (generation !== readGeneration.current) return;
      setBrands(page.items); setNextCursor(page.page.nextCursor);
    } catch (nextError) {
      if (generation === readGeneration.current) { setError(nextError); setBrands([]); }
    } finally {
      if (generation === readGeneration.current) setLoading(false);
    }
  }
  useEffect(() => { void load(cursor); return () => { ++readGeneration.current; }; }, [cursor]);

  async function create() {
    if (creating || saving) return;
    setNotice("");
    const body = { name: newName.trim(), reason: createReason.trim() };
    const fingerprint = JSON.stringify(body);
    setCreating(true);
    setMutationFailure(null);
    try {
      const created = unwrap(await operationsApi.POST("/operations/brands", {
        params: { header: { "Idempotency-Key": createIntent.current.keyFor(fingerprint) } },
        body,
      }));
      setNotice(`브랜드를 등록했습니다: ${created.name}`);
      if (cursor) setCursors([undefined]); else await load(undefined);
      setNewName("");
      setCreateReason("");
      createIntent.current.complete();
    } catch (nextError) {
      mutationError(nextError, createIntent.current);
      setMutationFailure(nextError);
    } finally {
      setCreating(false);
    }
  }

  function edit(brand: Brand) {
    setSelected(brand);
    setEditName(brand.name);
    setEditStatus(brand.status);
    setEditReason("");
    setMutationFailure(null);
    editIntent.current.rotate();
  }

  async function save() {
    if (!selected || saving || creating) return;
    setNotice("");
    const body = { name: editName.trim(), status: editStatus, expectedVersion: selected.version, reason: editReason.trim() };
    const fingerprint = JSON.stringify({ brandId: selected.brandId, ...body });
    setSaving(true);
    setMutationFailure(null);
    try {
      const next = unwrap(await operationsApi.PATCH("/operations/brands/{brandId}", {
        params: { path: { brandId: selected.brandId }, header: { "Idempotency-Key": editIntent.current.keyFor(fingerprint) } },
        body,
      }));
      setNotice(`브랜드를 변경했습니다: ${next.name}`);
      if (cursor) setCursors([undefined]); else await load(undefined);
      setEditReason("");
      editIntent.current.complete();
    } catch (nextError) {
      mutationError(nextError, editIntent.current);
      setMutationFailure(nextError);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="policy-workspace" aria-labelledby="brand-title">
      <div className="surface-card policy-intro-card"><div><span className="context-label">브랜드 목록</span><h2 id="brand-title">브랜드 관리</h2><p>활성 브랜드 이름은 중복될 수 없습니다. 소속 매장이 있는 브랜드는 이름을 바꿀 수 있지만 보관할 수 없습니다.</p></div><Button variant="secondary" disabled={creating || saving || loading} onClick={() => void load()}><RefreshCw size={16} /> 목록 새로고침</Button></div>
      <form className="surface-card inline-policy-form" onSubmit={(event) => { event.preventDefault(); void create(); }}>
        <TextField label="새 브랜드 이름" value={newName} maxLength={120} onValueChange={(value) => { setNewName(value); createIntent.current.rotate(); }} required />
        <TextField label="브랜드 등록 사유" value={createReason} maxLength={200} onValueChange={(value) => { setCreateReason(value); createIntent.current.rotate(); }} required />
        <Button type="submit" loading={creating} disabled={!newName.trim() || !createReason.trim()}>브랜드 등록</Button>
      </form>
      {notice ? <p role="status">{notice}</p> : null}
      {loading ? <LoadingState label="브랜드 목록을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {!loading && !error && brands.length === 0 ? <EmptyState title="등록된 브랜드가 없습니다" description="첫 브랜드를 등록하면 여기에 표시됩니다." /> : null}
      {brands.length > 0 ? (
        <div className="console-detail-grid policy-detail-grid">
          <section className="policy-card-list" aria-label="브랜드 목록">
            {brands.map((brand) => (
              <article className={selected?.brandId === brand.brandId ? "surface-card compact-policy-card is-selected" : "surface-card compact-policy-card"} key={brand.brandId}>
                <div><span className="context-label">브랜드</span><h3>{brand.name}</h3></div><StatusText domain="brand" state={brand.status} />
                <p>소속 매장 {brand.assignedStoreCount}개</p><small>버전 {brand.version}</small>
                <Button size="sm" variant="secondary" disabled={saving || creating} onClick={() => edit(brand)}>브랜드 편집</Button>
              </article>
            ))}
          </section>
          {selected ? (
            <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
              <h3>브랜드 정보 변경</h3>
              <TextField label="브랜드 이름" value={editName} maxLength={120} onValueChange={setEditName} required />
              <SelectField label="운영 상태" value={editStatus} onValueChange={(value) => setEditStatus(value as Brand["status"])}><option value="ACTIVE">활성</option><option value="ARCHIVED" disabled={selected.assignedStoreCount > 0}>보관</option></SelectField>
              {selected.assignedStoreCount > 0 ? <p className="policy-caution">소속 매장이 남아 있어 보관할 수 없습니다.</p> : null}
              <TextAreaField label="브랜드 변경 사유" value={editReason} maxLength={200} onValueChange={(value) => { setEditReason(value); setMutationFailure(null); editIntent.current.rotate(); }} required />
              <Button type="submit" loading={saving} disabled={!editReason.trim()}>브랜드 변경 적용</Button>
            </form>
          ) : <EmptyState title="편집할 브랜드를 선택하세요" description="이름 변경과 보관은 현재 버전·소속 매장 수를 기준으로 검증됩니다." />}
        </div>
      ) : null}
      <div className="button-row"><Button variant="ghost" disabled={loading || saving || creating || cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 브랜드 목록</Button><Button variant="secondary" disabled={loading || saving || creating || !nextCursor} onClick={() => { if (nextCursor) setCursors(value => [...value, nextCursor]); }}>다음 브랜드 목록</Button></div>
      {mutationFailure ? <ErrorState error={mutationFailure} /> : null}
    </section>
  );
}

function SearchIndexWorkspace() {
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());

  async function rebuild() {
    const body = { reason: reason.trim() };
    const fingerprint = JSON.stringify(body);
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const next = unwrap(await operationsApi.POST("/operations/search-index/rebuild", {
        params: { header: { "Idempotency-Key": intent.current.keyFor(fingerprint) } },
        body,
      }));
      setResult(next);
      intent.current.complete();
    } catch (nextError) {
      mutationError(nextError, intent.current);
      setError(nextError);
    } finally {
      setRunning(false);
    }
  }

  return (
    <section className="policy-workspace" aria-labelledby="search-index-title">
      <div className="surface-card policy-intro-card"><div><span className="context-label">검색 복구</span><h2 id="search-index-title">매장 검색 색인 재생성</h2></div></div>
      <form className="surface-card policy-form search-index-form" onSubmit={(event) => { event.preventDefault(); void rebuild(); }}>
        <TextAreaField label="재생성 사유" value={reason} maxLength={500} onValueChange={(value) => { setReason(value); setError(null); setResult(null); intent.current.rotate(); }} required />
        <Button type="submit" loading={running} disabled={!reason.trim()}><RefreshCw size={17} /> 검색 색인 재생성</Button>
      </form>
      {running ? <LoadingState label="검색 색인을 매장별로 재생성하는 중" /> : null}
      {error ? <ErrorState error={error} /> : null}
      {result ? (
        <section className={result.complete ? "surface-card index-result is-complete" : "surface-card index-result is-partial"} aria-live="polite">
          <div className="panel-heading"><div><span className="context-label">저장된 실행 결과</span><h2>{result.complete ? "대상 매장 재생성 완료" : "일부 매장 완료 · 추가 확인 필요"}</h2></div><StatusText state={result.complete ? "COMPLETE" : "RECONCILING"} /></div>
          <dl className="detail-list">
            <div><dt>색인 반영</dt><dd>{result.indexedStoreCount}개</dd></div>
            <div><dt>건너뜀</dt><dd>{result.skippedStoreCount}개</dd></div>
            <div><dt>실패 매장</dt><dd>실패 매장 {result.failedStoreIds.length}개</dd></div>
          </dl>
          {result.failedStoreIds.length > 0 ? <ul className="failed-store-list">{result.failedStoreIds.map((storeId) => <li key={storeId}><code>{storeId}</code></li>)}</ul> : null}
        </section>
      ) : null}
    </section>
  );
}
