import { Archive, Plus, Settings2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button, FeedbackState } from "../../design-system";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";

type StoreOrderingPolicy = components["schemas"]["StoreOrderingPolicy"];
type MenuCatalogLifecycle = components["schemas"]["MenuCatalogLifecycle"];
type MenuCatalogSummary = components["schemas"]["MenuCatalogSummary"];
type MenuTradeContent = components["schemas"]["MenuTradeContent"];
type MenuTradeDefinition = components["schemas"]["MenuTradeDefinition"];

export function StoreCatalogPage() {
  const { state: storesState, stores, selected, select, reload } = useMerchantStores("ANY");
  const [policy, setPolicy] = useState<StoreOrderingPolicy | null>(null);
  const [acceptingOrders, setAcceptingOrders] = useState(false);
  const [pickupEnabled, setPickupEnabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);
  const intent = useRef(new SubmissionIntent());
  const storeId = selected?.storeId ?? null;
  const policyRequest = useRef(0);
  const activeStoreId = useRef(storeId);
  activeStoreId.current = storeId;

  const loadPolicy = useCallback(async () => {
    if (!storeId) return;
    const requestedStoreId = storeId;
    const requestId = ++policyRequest.current;
    setLoading(true);
    setLoadError(null);
    setSaveError(null);
    setSaved(false);
    try {
      const next = unwrap(await merchantApi.GET("/stores/{storeId}/ordering-policy", {
        params: { path: { storeId: requestedStoreId } },
      }));
      if (policyRequest.current !== requestId || activeStoreId.current !== requestedStoreId) return;
      setPolicy(next);
      setAcceptingOrders(next.acceptingOrders);
      setPickupEnabled(next.pickupEnabled);
      intent.current.rotate();
    } catch (failure) {
      if (policyRequest.current !== requestId || activeStoreId.current !== requestedStoreId) return;
      setPolicy(null);
      setLoadError(failure);
    } finally {
      if (policyRequest.current === requestId && activeStoreId.current === requestedStoreId) {
        setLoading(false);
      }
    }
  }, [storeId]);

  useEffect(() => {
    policyRequest.current += 1;
    setPolicy(null);
    if (storeId) void loadPolicy();
  }, [storeId, loadPolicy]);

  function updateDraft(update: () => void) {
    update();
    setSaved(false);
    setSaveError(null);
    intent.current.rotate();
  }

  async function save() {
    if (!storeId || !policy || policy.storeId !== storeId) return;
    const body = { acceptingOrders, pickupEnabled, expectedVersion: policy.version };
    const fingerprint = JSON.stringify({ storeId, ...body });
    setSaving(true);
    setSaved(false);
    setSaveError(null);
    try {
      const next = unwrap(await merchantApi.PUT("/stores/{storeId}/ordering-policy", {
        params: {
          path: { storeId },
          header: {
            "Idempotency-Key": intent.current.keyFor(fingerprint),
            ...(await merchantCsrfHeader()),
          },
        },
        body,
      }));
      setPolicy(next);
      setAcceptingOrders(next.acceptingOrders);
      setPickupEnabled(next.pickupEnabled);
      setSaved(true);
      intent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
        intent.current.rotate();
      }
      setSaveError(failure);
    } finally {
      setSaving(false);
    }
  }

  if (storesState.status === "loading") return <LoadingState label="매장 목록을 불러오는 중" />;
  if (storesState.status === "failed") {
    return <div className="console-page"><ErrorState error={storesState.error} retry={reload} /></div>;
  }

  const unchanged = policy
    ? policy.acceptingOrders === acceptingOrders && policy.pickupEnabled === pickupEnabled
    : true;
  const stale = saveError instanceof ApiRequestError && saveError.code === "MERCHANT_CONTENT_STALE";

  return (
    <div className="console-page">
      <PageTitle
        eyebrow="MENU & ORDERING"
        title="메뉴·가격"
        description="고객이 새 주문을 만들고 매장에서 픽업할 수 있는지 관리합니다."
        action={<StoreSelector stores={stores} selected={selected} onSelect={select} />}
      />

      {stores.length === 0 ? (
        <EmptyState
          title="관리할 수 있는 매장이 없습니다"
          description="활성 OWNER 또는 STAFF 멤버십이 있는 매장만 표시됩니다."
        />
      ) : loading ? (
        <LoadingState label="주문 정책을 불러오는 중" />
      ) : loadError ? (
        <ErrorState error={loadError} retry={() => void loadPolicy()} />
      ) : policy ? (
        <div className="console-detail-grid">
          <section className="surface-card" aria-labelledby="ordering-policy-title">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">ORDERING POLICY</span>
                <h2 id="ordering-policy-title">주문 접수 정책</h2>
              </div>
              <Settings2 aria-hidden="true" />
            </div>
            <p>두 설정은 함께 저장되며, 저장 시점에 매장 권한과 서버 버전을 다시 확인합니다.</p>
            <fieldset>
              <legend>고객 주문에 적용할 정책</legend>
              <label>
                <input
                  type="checkbox"
                  checked={acceptingOrders}
                  onChange={(event) => updateDraft(() => setAcceptingOrders(event.target.checked))}
                />
                <span><strong>새 주문 접수</strong><small>끄면 고객은 새 주문을 만들 수 없습니다.</small></span>
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={pickupEnabled}
                  onChange={(event) => updateDraft(() => setPickupEnabled(event.target.checked))}
                />
                <span><strong>매장 픽업</strong><small>끄면 픽업 주문을 받을 수 없습니다.</small></span>
              </label>
            </fieldset>
          </section>

          <aside className="surface-card action-panel" aria-labelledby="ordering-save-title">
            <div>
              <span className="eyebrow">VERSION {policy.version}</span>
              <h2 id="ordering-save-title">변경 저장</h2>
            </div>
            <p className="form-footnote">마지막 거래 정책 변경: {new Date(policy.updatedAt).toLocaleString("ko-KR")}</p>
            <Button type="button" block loading={saving} disabled={unchanged} onClick={() => void save()}>
              {saving ? "저장 중" : "정책 저장"}
            </Button>
            {saved ? <p className="form-success" role="status">주문 정책을 저장했습니다.</p> : null}
            {stale ? (
              <FeedbackState
                kind="error"
                title="다른 변경이 먼저 저장되었습니다"
                description="현재 입력을 자동으로 덮어쓰지 않습니다. 서버의 최신 값을 다시 불러와 검토해 주세요."
                reference={saveError.correlationId}
                action={<Button variant="secondary" onClick={() => void loadPolicy()}>서버 값 다시 불러오기</Button>}
              />
            ) : saveError ? <ErrorState error={saveError} retry={() => void save()} /> : null}
          </aside>
          <MenuCatalogWorkspace storeId={policy.storeId} />
        </div>
      ) : null}
    </div>
  );
}

function MenuCatalogWorkspace({ storeId }: { storeId: string }) {
  const [lifecycle, setLifecycle] = useState<MenuCatalogLifecycle>("ACTIVE");
  const [items, setItems] = useState<MenuCatalogSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [draft, setDraft] = useState<MenuTradeDefinition | null>(null);
  const [current, setCurrent] = useState<MenuTradeContent | null>(null);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<MenuCatalogSummary | null>(null);
  const archiveTrigger = useRef<HTMLButtonElement | null>(null);
  const archiveDialog = useRef<HTMLDivElement | null>(null);
  const intent = useRef(new SubmissionIntent());
  const listRequest = useRef(0);
  const activeListScope = useRef({ storeId, lifecycle });
  activeListScope.current = { storeId, lifecycle };

  const loadList = useCallback(async (cursor?: string) => {
    const requestedStoreId = storeId;
    const requestedLifecycle = lifecycle;
    const requestId = ++listRequest.current;
    if (cursor) setLoadingMore(true); else setLoading(true);
    setLoadError(null);
    try {
      const page = unwrap(await merchantApi.GET("/stores/{storeId}/menu-catalog", {
        params: { path: { storeId: requestedStoreId }, query: { lifecycle: requestedLifecycle, limit: 50, ...(cursor ? { cursor } : {}) } },
      }));
      if (listRequest.current !== requestId ||
        activeListScope.current.storeId !== requestedStoreId ||
        activeListScope.current.lifecycle !== requestedLifecycle) return;
      setItems((currentItems) => cursor ? [...currentItems, ...page.items] : page.items);
      setNextCursor(page.nextCursor ?? null);
    } catch (failure) {
      if (listRequest.current !== requestId ||
        activeListScope.current.storeId !== requestedStoreId ||
        activeListScope.current.lifecycle !== requestedLifecycle) return;
      if (!cursor) setItems([]);
      setLoadError(failure);
    } finally {
      if (listRequest.current === requestId &&
        activeListScope.current.storeId === requestedStoreId &&
        activeListScope.current.lifecycle === requestedLifecycle) {
        if (cursor) setLoadingMore(false); else setLoading(false);
      }
    }
  }, [lifecycle, storeId]);

  useEffect(() => {
    listRequest.current += 1;
    setDraft(null);
    setCurrent(null);
    setEditing(false);
    setItems([]);
    setNextCursor(null);
    setLoadingMore(false);
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (archiveTarget) archiveDialog.current?.querySelector("button")?.focus();
  }, [archiveTarget]);

  async function edit(item: MenuCatalogSummary) {
    await editById(item.menuId);
  }

  async function editById(menuId: string) {
    setSaveError(null);
    setSaved(false);
    try {
      const content = unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/trade-content", {
        params: { path: { storeId, menuId } },
      }));
      setCurrent(content);
      setDraft(toDefinition(content));
      setEditing(true);
      intent.current.rotate();
    } catch (failure) {
      setSaveError(failure);
    }
  }

  function createDraft() {
    setCurrent(null);
    setDraft({
      menuId: crypto.randomUUID(),
      name: "",
      basePriceKrw: 0,
      available: false,
      options: [],
      configurations: [],
    });
    setEditing(true);
    setSaved(false);
    setSaveError(null);
    intent.current.rotate();
  }

  function change(next: MenuTradeDefinition) {
    setDraft(next);
    setSaved(false);
    setSaveError(null);
    intent.current.rotate();
  }

  async function saveMenu() {
    if (!draft) return;
    const fingerprint = JSON.stringify({ storeId, currentVersion: current?.version ?? null, draft });
    setSaving(true);
    setSaved(false);
    setSaveError(null);
    try {
      const header = {
        "Idempotency-Key": intent.current.keyFor(fingerprint),
        ...(await merchantCsrfHeader()),
      };
      const next = current
        ? unwrap(await merchantApi.PUT("/stores/{storeId}/menus/{menuId}/trade-content", {
          params: { path: { storeId, menuId: current.menuId }, header },
          body: { expectedVersion: current.version, ...draft },
        }))
        : unwrap(await merchantApi.POST("/stores/{storeId}/menus", {
          params: { path: { storeId }, header },
          body: draft,
        }));
      setCurrent(next);
      setDraft(toDefinition(next));
      setSaved(true);
      intent.current.complete();
      await loadList();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setSaveError(failure);
    } finally {
      setSaving(false);
    }
  }

  async function confirmArchive() {
    if (!archiveTarget) return;
    const target = archiveTarget;
    setSaving(true);
    setSaveError(null);
    try {
      const fingerprint = JSON.stringify({ storeId, menuId: target.menuId, expectedVersion: target.version });
      await merchantApi.POST("/stores/{storeId}/menus/{menuId}/archive", {
        params: {
          path: { storeId, menuId: target.menuId },
          header: {
            "Idempotency-Key": intent.current.keyFor(fingerprint),
            ...(await merchantCsrfHeader()),
          },
        },
        body: { expectedVersion: target.version },
      }).then(unwrap);
      setArchiveTarget(null);
      setDraft(null);
      setCurrent(null);
      setEditing(false);
      intent.current.complete();
      await loadList();
      archiveTrigger.current?.focus();
    } catch (failure) {
      setSaveError(failure);
      setArchiveTarget(null);
      archiveTrigger.current?.focus();
    } finally {
      setSaving(false);
    }
  }

  const stale = saveError instanceof ApiRequestError && saveError.code === "MERCHANT_CONTENT_STALE";

  return (
    <section className="surface-card menu-catalog-workspace" aria-labelledby="menu-catalog-title">
      <div className="panel-heading menu-catalog-heading">
        <div>
          <span className="eyebrow">TRADE CATALOG</span>
          <h2 id="menu-catalog-title">메뉴 거래 내용</h2>
          <p>가격·판매 상태·옵션·재고 요구량을 한 번에 저장합니다.</p>
        </div>
        <Button type="button" variant="secondary" onClick={createDraft}><Plus aria-hidden="true" /> 새 메뉴</Button>
      </div>

      <div className="catalog-lifecycle-tabs" role="group" aria-label="메뉴 보관 상태">
        {(["ACTIVE", "ARCHIVED"] as const).map((value) => (
          <button key={value} type="button" aria-pressed={lifecycle === value} onClick={() => setLifecycle(value)}>
            {value === "ACTIVE" ? "판매 카탈로그" : "보관된 메뉴"}
          </button>
        ))}
      </div>

      {loading ? (
        <FeedbackState kind="loading" title="메뉴를 불러오는 중" description="거래 카탈로그의 최신 상태를 확인하고 있습니다." />
      ) : loadError ? (
        <FeedbackState kind="error" title="메뉴를 불러오지 못했습니다" description={failureMessage(loadError)} action={<Button variant="secondary" onClick={() => void loadList()}>다시 시도</Button>} />
      ) : items.length === 0 ? (
        <FeedbackState kind="empty" title={lifecycle === "ACTIVE" ? "등록된 메뉴가 없습니다" : "보관된 메뉴가 없습니다"} description={lifecycle === "ACTIVE" ? "새 메뉴를 draft로 만든 뒤 거래 내용을 저장해 주세요." : "보관한 메뉴는 복원하거나 물리 삭제할 수 없습니다."} />
      ) : (
        <ul className="menu-authoring-list">
          {items.map((item) => (
            <li key={item.menuId}>
              {item.lifecycle === "ACTIVE" ? (
                <button type="button" className="menu-authoring-summary" onClick={() => void edit(item)}>
                  <MenuCatalogItemSummary item={item} />
                </button>
              ) : (
                <div className="menu-authoring-summary" aria-label={`${item.name} 보관 요약`}>
                  <MenuCatalogItemSummary item={item} />
                </div>
              )}
              {item.lifecycle === "ACTIVE" ? (
                <Button type="button" variant="danger" size="sm" onClick={() => { archiveTrigger.current = document.activeElement as HTMLButtonElement; setArchiveTarget(item); }}>
                  <Archive aria-hidden="true" /> 보관
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {!loading && !loadError && nextCursor ? (
        <Button type="button" variant="secondary" loading={loadingMore} onClick={() => void loadList(nextCursor)}>
          {loadingMore ? "불러오는 중" : "메뉴 더 보기"}
        </Button>
      ) : null}

      {editing && draft ? (
        <MenuTradeEditor
          draft={draft}
          current={current}
          saving={saving}
          saved={saved}
          error={saveError}
          stale={stale}
          onChange={change}
          onSave={() => void saveMenu()}
          onReload={current ? () => void editById(current.menuId) : undefined}
          onClose={() => { setEditing(false); setDraft(null); setCurrent(null); }}
        />
      ) : null}

      {archiveTarget ? (
        <div className="catalog-dialog-backdrop">
          <div ref={archiveDialog} role="dialog" aria-modal="true" aria-labelledby="archive-menu-title" className="surface-card catalog-dialog">
            <h3 id="archive-menu-title">‘{archiveTarget.name}’ 메뉴를 보관할까요?</h3>
            <p>고객 메뉴와 검색에서 즉시 제외됩니다. 이 버전에서는 복원이나 물리 삭제를 제공하지 않습니다.</p>
            <div className="button-row">
              <Button variant="secondary" onClick={() => { setArchiveTarget(null); archiveTrigger.current?.focus(); }}>취소</Button>
              <Button variant="danger" loading={saving} onClick={() => void confirmArchive()}>메뉴 보관</Button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function MenuCatalogItemSummary({ item }: { item: MenuCatalogSummary }) {
  return (
    <>
      <span><strong>{item.name}</strong><small>{item.basePriceKrw.toLocaleString("ko-KR")}원 · 옵션 {item.optionCount} · 구성 {item.configurationCount}</small></span>
      <span>{item.available ? "판매 가능" : "판매 중지"}</span>
    </>
  );
}

function MenuTradeEditor({
  draft, current, saving, saved, error, stale, onChange, onSave, onReload, onClose,
}: {
  draft: MenuTradeDefinition;
  current: MenuTradeContent | null;
  saving: boolean;
  saved: boolean;
  error: unknown;
  stale: boolean;
  onChange: (draft: MenuTradeDefinition) => void;
  onSave: () => void;
  onReload?: () => void;
  onClose: () => void;
}) {
  function addOption() {
    onChange({ ...draft, options: [...draft.options, { optionId: crypto.randomUUID(), name: "", additionalPriceKrw: 0, available: true }] });
  }
  function addConfiguration() {
    onChange({
      ...draft,
      configurations: [...draft.configurations, {
        configurationId: crypto.randomUUID(), selectedOptionIds: [], available: true,
        requirements: [{ sellableUnitId: crypto.randomUUID(), quantityPerLineUnit: 1 }],
      }],
    });
  }
  function updateRequirement(configurationIndex: number, requirementIndex: number, update: Partial<MenuTradeDefinition["configurations"][number]["requirements"][number]>) {
    onChange({
      ...draft,
      configurations: draft.configurations.map((configuration, index) => index === configurationIndex ? {
        ...configuration,
        requirements: configuration.requirements.map((requirement, index) => index === requirementIndex ? { ...requirement, ...update } : requirement),
      } : configuration),
    });
  }
  function addRequirement(configurationIndex: number) {
    onChange({
      ...draft,
      configurations: draft.configurations.map((configuration, index) => index === configurationIndex ? {
        ...configuration,
        requirements: [...configuration.requirements, { sellableUnitId: crypto.randomUUID(), quantityPerLineUnit: 1 }],
      } : configuration),
    });
  }
  function removeRequirement(configurationIndex: number, requirementIndex: number) {
    onChange({
      ...draft,
      configurations: draft.configurations.map((configuration, index) => index === configurationIndex ? {
        ...configuration,
        requirements: configuration.requirements.filter((_, index) => index !== requirementIndex),
      } : configuration),
    });
  }

  return (
    <form className="menu-trade-editor" onSubmit={(event) => { event.preventDefault(); onSave(); }}>
      <div className="panel-heading">
        <div><span className="eyebrow">{current ? `VERSION ${current.version}` : "NEW DRAFT"}</span><h3>{current ? "거래 내용 편집" : "새 메뉴 만들기"}</h3></div>
        <Button type="button" variant="ghost" onClick={onClose}>편집 닫기</Button>
      </div>
      <div className="form-grid">
        <label>메뉴 이름<input required maxLength={200} value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} /></label>
        <label>기본 가격(KRW)<input required min={0} type="number" value={draft.basePriceKrw} onChange={(event) => onChange({ ...draft, basePriceKrw: Number(event.target.value) })} /></label>
      </div>
      <label className="toggle-row"><input type="checkbox" checked={draft.available} onChange={(event) => onChange({ ...draft, available: event.target.checked })} /><span><strong>고객에게 판매 가능</strong><small>켜려면 하나 이상의 판매 구성이 필요합니다.</small></span></label>

      <fieldset className="catalog-fieldset">
        <legend>옵션 ({draft.options.length}/100)</legend>
        {draft.options.map((option, index) => (
          <div className="catalog-child-row" key={option.optionId}>
            <label>옵션 이름<input required value={option.name} onChange={(event) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, name: event.target.value } : item) })} /></label>
            <label>추가 금액<input type="number" min={0} value={option.additionalPriceKrw} onChange={(event) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, additionalPriceKrw: Number(event.target.value) } : item) })} /></label>
            <label className="compact-check"><input type="checkbox" checked={option.available} onChange={(event) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, available: event.target.checked } : item) })} /> 판매 가능</label>
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange({ ...draft, options: draft.options.filter((_, itemIndex) => itemIndex !== index), configurations: draft.configurations.map((configuration) => ({ ...configuration, selectedOptionIds: configuration.selectedOptionIds.filter((id) => id !== option.optionId) })) })}>옵션 제거</Button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" disabled={draft.options.length >= 100} onClick={addOption}>옵션 추가</Button>
      </fieldset>

      <fieldset className="catalog-fieldset">
        <legend>판매 구성 ({draft.configurations.length}/500)</legend>
        {draft.configurations.map((configuration, index) => (
          <div className="catalog-configuration" key={configuration.configurationId}>
            <fieldset><legend>선택 옵션</legend>{draft.options.length === 0 ? <p>옵션 없는 기본 구성입니다.</p> : draft.options.map((option) => <label key={option.optionId} className="compact-check"><input type="checkbox" checked={configuration.selectedOptionIds.includes(option.optionId)} onChange={(event) => onChange({ ...draft, configurations: draft.configurations.map((item, itemIndex) => itemIndex === index ? { ...item, selectedOptionIds: event.target.checked ? [...item.selectedOptionIds, option.optionId] : item.selectedOptionIds.filter((id) => id !== option.optionId) } : item) })} /> {option.name || "이름 없는 옵션"}</label>)}</fieldset>
            <div className="catalog-requirements">
              <strong>재고 요구량 ({configuration.requirements.length}/50)</strong>
              {configuration.requirements.map((requirement, requirementIndex) => (
                <div className="catalog-requirement-row" key={`${configuration.configurationId}-${requirementIndex}`}>
                  <label>재고 단위 ID<input required aria-describedby={`unit-help-${index}-${requirementIndex}`} value={requirement.sellableUnitId} onChange={(event) => updateRequirement(index, requirementIndex, { sellableUnitId: event.target.value })} /></label>
                  <small id={`unit-help-${index}-${requirementIndex}`}>판매 시 차감할 sellable-unit UUID입니다.</small>
                  <label>메뉴 1개당 수량<input required type="number" min={1} value={requirement.quantityPerLineUnit} onChange={(event) => updateRequirement(index, requirementIndex, { quantityPerLineUnit: Number(event.target.value) })} /></label>
                  <Button type="button" variant="ghost" size="sm" disabled={configuration.requirements.length === 1} onClick={() => removeRequirement(index, requirementIndex)}>요구량 제거</Button>
                </div>
              ))}
              <Button type="button" variant="secondary" size="sm" disabled={configuration.requirements.length >= 50} onClick={() => addRequirement(index)}>요구량 추가</Button>
            </div>
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange({ ...draft, configurations: draft.configurations.filter((_, itemIndex) => itemIndex !== index) })}>구성 제거</Button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" disabled={draft.configurations.length >= 500} onClick={addConfiguration}>판매 구성 추가</Button>
      </fieldset>

      <div className="button-row"><Button type="submit" loading={saving}>{saving ? "저장 중" : current ? "거래 내용 저장" : "메뉴 생성"}</Button><Button type="button" variant="secondary" onClick={onClose}>취소</Button></div>
      {saved ? <p className="form-success" role="status">메뉴 거래 내용을 저장했습니다.</p> : null}
      {stale ? <FeedbackState kind="error" title="다른 변경이 먼저 저장되었습니다" description="자동으로 덮어쓰지 않습니다. 서버의 최신 거래 내용을 다시 불러와 검토해 주세요." reference={error instanceof ApiRequestError ? error.correlationId : undefined} action={onReload ? <Button variant="secondary" onClick={onReload}>서버 값 다시 불러오기</Button> : undefined} /> : error ? <ErrorState error={error} retry={onSave} /> : null}
    </form>
  );
}

function toDefinition(content: MenuTradeContent): MenuTradeDefinition {
  return {
    menuId: content.menuId,
    name: content.name,
    basePriceKrw: content.basePriceKrw,
    available: content.available,
    options: content.options,
    configurations: content.configurations,
  };
}

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : "필수 저장소를 사용할 수 없습니다.";
}
