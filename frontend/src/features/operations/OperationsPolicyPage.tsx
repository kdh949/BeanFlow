import { Gift, RefreshCw, SearchCheck, Settings2, Tags } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button } from "../../design-system";
import { shortDateTime } from "../../lib/format";

type PointPolicy = components["schemas"]["OrdinaryPointAccrualPolicyVersion"];
type RestorationPolicy = components["schemas"]["ExpiredBenefitRestorationPolicy"];
type Brand = components["schemas"]["Brand"];
type SearchResult = components["schemas"]["SearchIndexRebuildResponse"];
type Workspace = "points" | "restoration" | "brands" | "search";

const workspaceItems: Array<{ id: Workspace; label: string; icon: typeof Settings2 }> = [
  { id: "points", label: "포인트 적립", icon: Settings2 },
  { id: "restoration", label: "만료 혜택 복원", icon: Gift },
  { id: "brands", label: "브랜드", icon: Tags },
  { id: "search", label: "검색 색인", icon: SearchCheck },
];

function mutationError(error: unknown, intent: SubmissionIntent) {
  if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.rotate();
}

export function OperationsPolicyPage() {
  const [workspace, setWorkspace] = useState<Workspace>("points");
  return (
    <div className="console-page operations-policy-page">
      <PageTitle
        eyebrow="PLATFORM POLICY"
        title="운영 정책 관리"
        description="포인트·만료 혜택 정책, 브랜드와 매장 검색 색인을 실제 서버 상태 기준으로 관리합니다."
      />
      <div className="filter-row policy-workspace-tabs" role="group" aria-label="운영 정책 업무 선택">
        {workspaceItems.map(({ id, label, icon: Icon }) => (
          <button key={id} type="button" className={workspace === id ? "is-active" : ""} aria-pressed={workspace === id} onClick={() => setWorkspace(id)}>
            <Icon size={17} aria-hidden="true" /> {label}
          </button>
        ))}
      </div>
      {workspace === "points" ? <PointPolicyWorkspace /> : null}
      {workspace === "restoration" ? <RestorationPolicyWorkspace /> : null}
      {workspace === "brands" ? <BrandWorkspace /> : null}
      {workspace === "search" ? <SearchIndexWorkspace /> : null}
    </div>
  );
}

function PointPolicyWorkspace() {
  const [accessReason, setAccessReason] = useState("");
  const [policy, setPolicy] = useState<PointPolicy | null>(null);
  const [rate, setRate] = useState("0");
  const [roundingMode, setRoundingMode] = useState<"FLOOR" | "HALF_UP">("FLOOR");
  const [issuerType, setIssuerType] = useState<"PLATFORM" | "BRAND" | "STORE">("PLATFORM");
  const [issuerReference, setIssuerReference] = useState("platform:beanflow");
  const [expiryRule, setExpiryRule] = useState<"EXACT_DURATION_FROM_COMPLETION" | "SEOUL_CALENDAR_DAYS_FROM_COMPLETION">("SEOUL_CALENDAR_DAYS_FROM_COMPLETION");
  const [validityDays, setValidityDays] = useState("365");
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());

  function fillForm(next: PointPolicy) {
    setRate(((next.accrualRateBps ?? 0) / 100).toString());
    setRoundingMode(next.roundingMode ?? "FLOOR");
    setIssuerType(next.issuerType ?? "PLATFORM");
    setIssuerReference(next.issuerReference ?? "platform:beanflow");
    setExpiryRule(next.expiryRule ?? "SEOUL_CALENDAR_DAYS_FROM_COMPLETION");
    setValidityDays(String(next.validityDays ?? 365));
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const next = unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/global", {
        params: { header: { "X-Access-Reason": accessReason } },
      }));
      setPolicy(next);
      fillForm(next);
    } catch (nextError) {
      setError(nextError);
      setPolicy(null);
    } finally {
      setLoading(false);
    }
  }

  async function save() {
    if (!policy) return;
    const body = {
      state: "OVERRIDE" as const,
      expectedPolicyVersionId: policy.policyVersionId,
      accrualRateBps: Math.round(Number(rate) * 100),
      roundingMode,
      issuerType,
      issuerReference: issuerReference.trim(),
      expiryRule,
      validityDays: Number(validityDays),
      reason: reason.trim(),
    };
    const fingerprint = JSON.stringify(body);
    setSaving(true);
    setSaveError(null);
    try {
      const next = unwrap(await operationsApi.PATCH("/operations/policies/ordinary-point-accrual/global", {
        params: { header: { "Idempotency-Key": intent.current.keyFor(fingerprint) } },
        body: body as never,
      }));
      setPolicy(next);
      fillForm(next);
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
    <section className="policy-workspace" aria-labelledby="point-policy-title">
      <div className="surface-card policy-intro-card">
        <div><span className="eyebrow">GLOBAL DEFAULT</span><h2 id="point-policy-title">공통 포인트 적립 정책</h2><p>현재 버전을 먼저 감사 조회한 뒤 새 버전으로 즉시 적용합니다.</p></div>
        <div className="policy-audit-read">
          <label htmlFor="point-policy-access-reason">정책 조회 사유</label>
          <select id="point-policy-access-reason" value={accessReason} onChange={(event) => setAccessReason(event.target.value)} required>
            <option value="">업무 사유 선택</option>
            <option value="POLICY_CHANGE_REVIEW">정책 변경 전 현재값 확인</option>
            <option value="POLICY_AUDIT_REVIEW">정책 감사 검토</option>
          </select>
          <Button variant="secondary" disabled={!accessReason} loading={loading} onClick={() => void load()}>현재 적립 정책 조회</Button>
        </div>
      </div>
      {loading ? <LoadingState label="현재 적립 정책을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {!loading && !error && !policy ? <EmptyState title="조회 전입니다" description="감사 사유를 선택한 뒤 현재 정책을 조회해야 변경할 수 있습니다." /> : null}
      {policy ? (
        <div className="console-detail-grid policy-detail-grid">
          <section className="surface-card order-panel">
            <div className="panel-heading"><div><span className="eyebrow">CURRENT VERSION</span><h2>버전 {policy.policyVersionId} 적용 중</h2></div><StatusBadge state={policy.state} /></div>
            <dl className="detail-list">
              <div><dt>적립률</dt><dd>{((policy.accrualRateBps ?? 0) / 100).toFixed(2)}%</dd></div>
              <div><dt>반올림</dt><dd>{policy.roundingMode === "FLOOR" ? "버림" : "반올림"}</dd></div>
              <div><dt>비용 주체</dt><dd>{policy.issuerType} · {policy.issuerReference}</dd></div>
              <div><dt>유효기간</dt><dd>{policy.validityDays}일</dd></div>
              <div><dt>적용 시각</dt><dd>{shortDateTime.format(new Date(policy.effectiveAt))}</dd></div>
            </dl>
          </section>
          <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <h3>새 정책 버전</h3>
            <div className="field-grid">
              <label>적립률(%)<input aria-label="적립률(%)" type="number" min="0" max="100" step="0.01" value={rate} onChange={(event) => { setRate(event.target.value); intent.current.rotate(); }} required /></label>
              <label>반올림 방식<select value={roundingMode} onChange={(event) => setRoundingMode(event.target.value as typeof roundingMode)}><option value="FLOOR">버림</option><option value="HALF_UP">반올림</option></select></label>
              <label>발행 주체<select value={issuerType} onChange={(event) => setIssuerType(event.target.value as typeof issuerType)}><option value="PLATFORM">플랫폼</option><option value="BRAND">브랜드</option><option value="STORE">매장</option></select></label>
              <label>발행 주체 식별값<input value={issuerReference} maxLength={240} onChange={(event) => setIssuerReference(event.target.value)} required /></label>
              <label>만료 계산<select value={expiryRule} onChange={(event) => setExpiryRule(event.target.value as typeof expiryRule)}><option value="SEOUL_CALENDAR_DAYS_FROM_COMPLETION">서울 달력일</option><option value="EXACT_DURATION_FROM_COMPLETION">정확한 시간</option></select></label>
              <label>유효일수<input type="number" min="1" max="3650" value={validityDays} onChange={(event) => setValidityDays(event.target.value)} required /></label>
            </div>
            <label>변경 사유<textarea value={reason} maxLength={500} onChange={(event) => { setReason(event.target.value); setSaveError(null); intent.current.rotate(); }} required /></label>
            <Button type="submit" loading={saving} disabled={!reason.trim()}>새 적립 정책 적용</Button>
            {saveError ? <ErrorState error={saveError} /> : null}
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
        <div><span className="eyebrow">EXPIRED BENEFIT</span><h2 id="restoration-title">만료 혜택 복원 정책</h2><p>다섯 가지 허용 조합을 현재 버전과 함께 관리합니다.</p></div>
        <div className="policy-audit-read">
          <label htmlFor="restoration-access-reason">복원 정책 조회 사유</label>
          <select id="restoration-access-reason" value={accessReason} onChange={(event) => setAccessReason(event.target.value)} required>
            <option value="">업무 사유 선택</option><option value="BENEFIT_POLICY_REVIEW">혜택 복원 정책 검토</option><option value="BENEFIT_POLICY_AUDIT">혜택 정책 감사</option>
          </select>
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
                <div><span className="eyebrow">{policy.trigger}</span><h3>{policy.benefitType === "COUPON" ? "쿠폰" : "포인트"}</h3></div>
                <StatusBadge state={`v${policy.policyVersionId}`} />
                <p>{policy.mode === "COMPENSATE_WITH_NEW_ISSUANCE" ? "신규 혜택 발급" : "원래 만료일 유지"}</p>
                <small>{policy.compensationValidityDays}일 · {policy.reason}</small>
                <Button size="sm" variant="secondary" onClick={() => edit(policy)}>정책 변경</Button>
              </article>
            ))}
          </section>
          {selected ? (
            <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
              <div className="panel-heading"><div><span className="eyebrow">{selected.trigger} · {selected.benefitType}</span><h3>버전 {selected.policyVersionId} 적용 중</h3></div><StatusBadge state={`v${selected.policyVersionId}`} /></div>
              <label>복원 방식<select value={mode} onChange={(event) => setMode(event.target.value as typeof mode)}><option value="COMPENSATE_WITH_NEW_ISSUANCE">신규 혜택 발급</option><option value="PRESERVE_ORIGINAL_EXPIRY">원래 만료일 유지</option></select></label>
              <label>보상 유효일수<input aria-label="보상 유효일수" type="number" min="1" max="365" value={validityDays} onChange={(event) => setValidityDays(event.target.value)} required /></label>
              <label>복원 정책 변경 사유<textarea value={reason} maxLength={500} onChange={(event) => { setReason(event.target.value); setSaveError(null); intent.current.rotate(); }} required /></label>
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
  const [brands, setBrands] = useState<Brand[]>([]);
  const [selected, setSelected] = useState<Brand | null>(null);
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

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const page = unwrap(await operationsApi.GET("/operations/brands", { params: { query: { limit: 100 } } }));
      setBrands(page.items);
    } catch (nextError) {
      setError(nextError);
      setBrands([]);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { void load(); }, []);

  async function create() {
    const body = { name: newName.trim(), reason: createReason.trim() };
    const fingerprint = JSON.stringify(body);
    setCreating(true);
    setMutationFailure(null);
    try {
      const created = unwrap(await operationsApi.POST("/operations/brands", {
        params: { header: { "Idempotency-Key": createIntent.current.keyFor(fingerprint) } },
        body,
      }));
      setBrands((current) => [...current.filter((item) => item.brandId !== created.brandId), created]);
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
    if (!selected) return;
    const body = { name: editName.trim(), status: editStatus, expectedVersion: selected.version, reason: editReason.trim() };
    const fingerprint = JSON.stringify({ brandId: selected.brandId, ...body });
    setSaving(true);
    setMutationFailure(null);
    try {
      const next = unwrap(await operationsApi.PATCH("/operations/brands/{brandId}", {
        params: { path: { brandId: selected.brandId }, header: { "Idempotency-Key": editIntent.current.keyFor(fingerprint) } },
        body,
      }));
      setBrands((current) => current.map((item) => item.brandId === next.brandId ? next : item));
      setSelected(next);
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
      <div className="surface-card policy-intro-card"><div><span className="eyebrow">BRAND CATALOG</span><h2 id="brand-title">브랜드 관리</h2><p>활성 이름 중복과 소속 매장 수·버전 충돌을 서버에서 다시 검증합니다.</p></div><Button variant="secondary" onClick={() => void load()}><RefreshCw size={16} /> 목록 새로고침</Button></div>
      <form className="surface-card inline-policy-form" onSubmit={(event) => { event.preventDefault(); void create(); }}>
        <label>새 브랜드 이름<input value={newName} maxLength={120} onChange={(event) => { setNewName(event.target.value); createIntent.current.rotate(); }} required /></label>
        <label>브랜드 등록 사유<input value={createReason} maxLength={500} onChange={(event) => { setCreateReason(event.target.value); createIntent.current.rotate(); }} required /></label>
        <Button type="submit" loading={creating} disabled={!newName.trim() || !createReason.trim()}>브랜드 등록</Button>
      </form>
      {loading ? <LoadingState label="브랜드 목록을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {!loading && !error && brands.length === 0 ? <EmptyState title="등록된 브랜드가 없습니다" description="첫 브랜드를 등록하면 여기에 표시됩니다." /> : null}
      {brands.length > 0 ? (
        <div className="console-detail-grid policy-detail-grid">
          <section className="policy-card-list" aria-label="브랜드 목록">
            {brands.map((brand) => (
              <article className={selected?.brandId === brand.brandId ? "surface-card compact-policy-card is-selected" : "surface-card compact-policy-card"} key={brand.brandId}>
                <div><span className="eyebrow">BRAND</span><h3>{brand.name}</h3></div><StatusBadge state={brand.status} />
                <p>소속 매장 {brand.assignedStoreCount}개</p><small>버전 {brand.version}</small>
                <Button size="sm" variant="secondary" onClick={() => edit(brand)}>브랜드 편집</Button>
              </article>
            ))}
          </section>
          {selected ? (
            <form className="surface-card policy-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
              <h3>브랜드 정보 변경</h3>
              <label>브랜드 이름<input value={editName} maxLength={120} onChange={(event) => setEditName(event.target.value)} required /></label>
              <label>운영 상태<select value={editStatus} onChange={(event) => setEditStatus(event.target.value as Brand["status"])}><option value="ACTIVE">활성</option><option value="ARCHIVED" disabled={selected.assignedStoreCount > 0}>보관</option></select></label>
              {selected.assignedStoreCount > 0 ? <p className="policy-caution">소속 매장이 남아 있어 보관할 수 없습니다.</p> : null}
              <label>브랜드 변경 사유<textarea value={editReason} maxLength={500} onChange={(event) => { setEditReason(event.target.value); setMutationFailure(null); editIntent.current.rotate(); }} required /></label>
              <Button type="submit" loading={saving} disabled={!editReason.trim()}>브랜드 변경 적용</Button>
            </form>
          ) : <EmptyState title="편집할 브랜드를 선택하세요" description="이름 변경과 보관은 현재 버전·소속 매장 수를 기준으로 검증됩니다." />}
        </div>
      ) : null}
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
      <div className="surface-card policy-intro-card"><div><span className="eyebrow">DISCOVERY RECOVERY</span><h2 id="search-index-title">매장 검색 색인 재생성</h2><p>실행 시작 전 대상을 고정하고 매장별 트랜잭션으로 갱신합니다. 부분 결과는 전체 성공이 아닙니다.</p></div></div>
      <form className="surface-card policy-form search-index-form" onSubmit={(event) => { event.preventDefault(); void rebuild(); }}>
        <label>재생성 사유<textarea value={reason} maxLength={500} onChange={(event) => { setReason(event.target.value); setError(null); setResult(null); intent.current.rotate(); }} required /></label>
        <Button type="submit" loading={running} disabled={!reason.trim()}><RefreshCw size={17} /> 검색 색인 재생성</Button>
        <p className="policy-caution">503 응답은 어떤 매장도 반영되지 않았다는 뜻이 아닙니다. 재시도 전 운영 Runbook을 확인하세요.</p>
      </form>
      {running ? <LoadingState label="검색 색인을 매장별로 재생성하는 중" /> : null}
      {error ? <ErrorState error={error} /> : null}
      {result ? (
        <section className={result.complete ? "surface-card index-result is-complete" : "surface-card index-result is-partial"} aria-live="polite">
          <div className="panel-heading"><div><span className="eyebrow">STORED PASS RESULT</span><h2>{result.complete ? "스냅샷 범위 재생성 완료" : "부분 완료 · 재조정 필요"}</h2></div><StatusBadge state={result.complete ? "COMPLETE" : "RECONCILING"} /></div>
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
