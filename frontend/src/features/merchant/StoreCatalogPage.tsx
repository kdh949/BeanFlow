import { Archive, Plus, Settings2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, Checkbox, ChipButton, EmptyState, FeedbackState, LoadingState, PageHeading, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { requestErrorPresentation } from "../../presentation/shared/requestErrorPresentation";
import { MenuPresentationEditor } from "./MenuPresentationEditor";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";

type StoreOrderingPolicy = components["schemas"]["StoreOrderingPolicy"];
type MenuCatalogLifecycle = components["schemas"]["MenuCatalogLifecycle"];
type MenuCatalogSummary = components["schemas"]["MenuCatalogSummary"];
type MenuTradeContent = components["schemas"]["MenuTradeContent"];
type MenuTradeDefinition = components["schemas"]["MenuTradeDefinition"];

export function StoreCatalogPage({ embedded = false }: { embedded?: boolean }) {
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
    setSaving(false);
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
    const requestId = policyRequest.current;
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
      if (policyRequest.current !== requestId || activeStoreId.current !== storeId) return;
      setPolicy(next);
      setAcceptingOrders(next.acceptingOrders);
      setPickupEnabled(next.pickupEnabled);
      setSaved(true);
      intent.current.complete();
    } catch (failure) {
      if (policyRequest.current !== requestId || activeStoreId.current !== storeId) return;
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
        intent.current.rotate();
      }
      setSaveError(failure);
    } finally {
      if (policyRequest.current === requestId && activeStoreId.current === storeId) setSaving(false);
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
      {embedded ? <div className="catalog-store-selector"><StoreSelector stores={stores} selected={selected} onSelect={select} /></div> : (
        <PageHeading title="메뉴·가격" action={<StoreSelector stores={stores} selected={selected} onSelect={select} />} />
      )}

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
          <MenuCatalogWorkspace key={policy.storeId} storeId={policy.storeId} />
          <section className="surface-card catalog-policy-panel" aria-labelledby="ordering-policy-title">
            <div className="panel-heading">
              <div>
                <h2 id="ordering-policy-title">주문 접수 정책</h2>
              </div>
              <Settings2 aria-hidden="true" />
            </div>
            <p>새 주문을 받을지 설정합니다. 이미 받은 주문은 그대로 유지됩니다.</p>
            <fieldset>
              <legend>고객 주문에 적용할 정책</legend>
              <Checkbox label="새 주문 접수" description="끄면 고객은 새 주문을 만들 수 없습니다." checked={acceptingOrders} disabled={saving} onCheckedChange={(next) => updateDraft(() => setAcceptingOrders(next))} />
              <Checkbox label="매장 픽업" description="끄면 픽업 주문을 받을 수 없습니다." checked={pickupEnabled} disabled={saving} onCheckedChange={(next) => updateDraft(() => setPickupEnabled(next))} />
            </fieldset>
          </section>

          <aside className="surface-card action-panel" aria-labelledby="ordering-save-title">
            <div>
              <span className="context-label">{policy.version}번째 저장</span>
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
                description="다른 사람이 저장한 최신 내용을 불러와 확인한 뒤 다시 수정해 주세요."
                reference={saveError.correlationId}
                action={<Button variant="secondary" onClick={() => void loadPolicy()}>최신 내용 불러오기</Button>}
              />
            ) : saveError ? <ErrorState error={saveError} retry={() => void save()} /> : null}
          </aside>
        </div>
      ) : null}
    </div>
  );
}

function MenuCatalogWorkspace({ storeId }: { storeId: string }) {
  const [displayTarget, setDisplayTarget] = useState<MenuCatalogSummary | null>(null);
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
  const editRequest = useRef(0);
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
    editRequest.current += 1;
    setDraft(null);
    setCurrent(null);
    setEditing(false);
    setDisplayTarget(null);
    setItems([]);
    setNextCursor(null);
    setLoadingMore(false);
    void loadList();
    return () => { editRequest.current += 1; listRequest.current += 1; };
  }, [loadList]);

  useEffect(() => {
    if (archiveTarget) archiveDialog.current?.querySelector("button")?.focus();
  }, [archiveTarget]);

  async function edit(item: MenuCatalogSummary) {
    setDisplayTarget(null);
    await editById(item.menuId);
  }

  async function editById(menuId: string) {
    if (saving) return;
    const requestId = ++editRequest.current;
    const requestedLifecycle = lifecycle;
    const isCurrent = () => editRequest.current === requestId &&
      activeListScope.current.storeId === storeId && activeListScope.current.lifecycle === requestedLifecycle;
    setSaveError(null);
    setSaved(false);
    try {
      const content = unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/trade-content", {
        params: { path: { storeId, menuId } },
      }));
      if (!isCurrent()) return;
      setCurrent(content);
      setDraft(toDefinition(content));
      setEditing(true);
      intent.current.rotate();
    } catch (failure) {
      if (isCurrent()) setSaveError(failure);
    }
  }

  function createDraft() {
    if (saving) return;
    editRequest.current += 1;
    setDisplayTarget(null);
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

  function closeEditor() {
    if (saving) return;
    editRequest.current += 1;
    setEditing(false);
    setDraft(null);
    setCurrent(null);
    setSaveError(null);
  }

  function change(next: MenuTradeDefinition) {
    if (saving) return;
    setDraft(next);
    setSaved(false);
    setSaveError(null);
    intent.current.rotate();
  }

  async function saveMenu() {
    if (!draft || saving) return;
    editRequest.current += 1;
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
      void loadList();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setSaveError(failure);
    } finally {
      setSaving(false);
    }
  }

  async function confirmArchive() {
    if (!archiveTarget || saving) return;
    editRequest.current += 1;
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
          <h2 id="menu-catalog-title">메뉴 거래 내용</h2>
          <p>가격·판매 상태·옵션·판매 구성을 한 번에 저장합니다.</p>
        </div>
        <Button type="button" variant="secondary" disabled={saving} onClick={createDraft}><Plus aria-hidden="true" /> 새 메뉴</Button>
      </div>

      <div className="catalog-lifecycle-tabs" role="group" aria-label="메뉴 보관 상태">
        {(["ACTIVE", "ARCHIVED"] as const).map((value) => (
          <ChipButton disabled={saving} key={value} aria-pressed={lifecycle === value} onClick={() => setLifecycle(value)}>
            {value === "ACTIVE" ? "판매 카탈로그" : "보관된 메뉴"}
          </ChipButton>
        ))}
      </div>

      {loading ? (
        <FeedbackState kind="loading" title="메뉴를 불러오는 중" description="거래 카탈로그의 최신 상태를 확인하고 있습니다." />
      ) : loadError ? (
        <FeedbackState kind="error" title="메뉴를 불러오지 못했습니다" description={failureMessage(loadError)} reference={requestErrorPresentation(loadError).reference} action={<Button variant="secondary" onClick={() => void loadList()}>다시 시도</Button>} />
      ) : items.length === 0 ? (
        <FeedbackState kind="empty" title={lifecycle === "ACTIVE" ? "등록된 메뉴가 없습니다" : "보관된 메뉴가 없습니다"} description={lifecycle === "ACTIVE" ? "새 메뉴를 추가하고 가격과 판매 여부를 저장해 주세요." : "보관한 메뉴는 다시 판매할 수 없습니다."} />
      ) : (
        <ul className="menu-authoring-list">
          {items.map((item) => (
            <li key={item.menuId}>
              {item.lifecycle === "ACTIVE" ? (
                <div className="menu-authoring-summary">
                  <MenuCatalogItemSummary item={item} />
                  <div className="button-row"><Button variant="secondary" disabled={saving} onClick={() => void edit(item)} aria-label={`${item.name} 편집`}>편집</Button><Button variant="ghost" disabled={saving || editing} aria-label={`${item.name} 표시 정보`} onClick={() => setDisplayTarget(item)}>표시 정보</Button></div>
                </div>
              ) : (
                <div className="menu-authoring-summary" aria-label={`${item.name} 보관 요약`}>
                  <MenuCatalogItemSummary item={item} />
                </div>
              )}
              {item.lifecycle === "ACTIVE" ? (
                <Button type="button" variant="danger" size="sm" disabled={saving} onClick={() => { editRequest.current += 1; archiveTrigger.current = document.activeElement as HTMLButtonElement; setArchiveTarget(item); }}>
                  <Archive aria-hidden="true" /> 보관
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {saveError && !editing ? <ErrorState error={saveError} retry={() => void loadList()} /> : null}

      {!loading && !loadError && nextCursor ? (
        <Button type="button" variant="secondary" loading={loadingMore} onClick={() => void loadList(nextCursor)}>
          {loadingMore ? "불러오는 중" : "메뉴 더 보기"}
        </Button>
      ) : null}

      {displayTarget ? <><MenuPresentationEditor key={displayTarget.menuId} storeId={storeId} menuId={displayTarget.menuId} name={displayTarget.name} onChanged={() => void loadList()} /><Button variant="ghost" onClick={() => setDisplayTarget(null)}>표시 정보 닫기</Button></> : null}

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
          onClose={closeEditor}
        />
      ) : null}

      {archiveTarget ? (
        <div className="catalog-dialog-backdrop">
          <div ref={archiveDialog} role="dialog" aria-modal="true" aria-labelledby="archive-menu-title" className="surface-card catalog-dialog">
            <h3 id="archive-menu-title">‘{archiveTarget.name}’ 메뉴를 보관할까요?</h3>
            <p>고객 메뉴와 검색에서 제외되며 다시 판매할 수 없습니다. 잠시 품절이라면 판매 가능 설정을 꺼 주세요.</p>
            <div className="button-row">
              <Button variant="secondary" disabled={saving} onClick={() => { setArchiveTarget(null); archiveTrigger.current?.focus(); }}>취소</Button>
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
      }],
    });
  }

  return (
    <form className="menu-trade-editor" onSubmit={(event) => { event.preventDefault(); onSave(); }}>
      <div className="panel-heading">
        <div><span className="context-label">{current ? `${current.version}번째 저장` : "새 메뉴"}</span><h3>{current ? "거래 내용 편집" : "새 메뉴 만들기"}</h3></div>
        <Button type="button" variant="ghost" disabled={saving} onClick={onClose}>편집 닫기</Button>
      </div>
      <div className="form-grid">
        <TextField disabled={saving} label="메뉴 이름" required maxLength={200} value={draft.name} onValueChange={(name) => onChange({ ...draft, name })} />
        <TextField disabled={saving} label="기본 가격(KRW)" required min={0} type="number" value={String(draft.basePriceKrw)} onValueChange={(value) => onChange({ ...draft, basePriceKrw: Number(value) })} />
      </div>
      <Checkbox disabled={saving} label="고객에게 판매 가능" description="품절이면 끄고, 다시 팔 수 있을 때 켜 주세요. 판매하려면 사용 가능한 구성이 하나 이상 필요합니다." checked={draft.available} onCheckedChange={(available) => onChange({ ...draft, available })} />

      <fieldset className="catalog-fieldset">
        <legend>옵션 ({draft.options.length}/100)</legend>
        {draft.options.map((option, index) => (
          <div className="catalog-child-row" key={option.optionId}>
            <TextField disabled={saving} label="옵션 이름" required maxLength={200} value={option.name} onValueChange={(name) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, name } : item) })} />
            <TextField disabled={saving} label="추가 금액" required type="number" min={0} value={String(option.additionalPriceKrw)} onValueChange={(value) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, additionalPriceKrw: Number(value) } : item) })} />
            <Checkbox disabled={saving} label="판매 가능" checked={option.available} onCheckedChange={(available) => onChange({ ...draft, options: draft.options.map((item, itemIndex) => itemIndex === index ? { ...item, available } : item) })} />
            <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={() => onChange({ ...draft, options: draft.options.filter((_, itemIndex) => itemIndex !== index), configurations: draft.configurations.map((configuration) => ({ ...configuration, selectedOptionIds: configuration.selectedOptionIds.filter((id) => id !== option.optionId) })) })}>옵션 제거</Button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" disabled={saving || draft.options.length >= 100} onClick={addOption}>옵션 추가</Button>
      </fieldset>

      <fieldset className="catalog-fieldset">
        <legend>판매 구성 ({draft.configurations.length}/500)</legend>
        {draft.configurations.map((configuration, index) => (
          <div className="catalog-configuration" key={configuration.configurationId}>
            <fieldset><legend>선택 옵션</legend>{draft.options.length === 0 ? <p>옵션 없는 기본 구성입니다.</p> : draft.options.map((option) => <Checkbox disabled={saving} key={option.optionId} label={option.name || "이름 없는 옵션"} checked={configuration.selectedOptionIds.includes(option.optionId)} onCheckedChange={(checked) => onChange({ ...draft, configurations: draft.configurations.map((item, itemIndex) => itemIndex === index ? { ...item, selectedOptionIds: checked ? [...item.selectedOptionIds, option.optionId] : item.selectedOptionIds.filter((id) => id !== option.optionId) } : item) })} />)}</fieldset>
            <Checkbox disabled={saving} label="이 구성 판매 가능" checked={configuration.available} onCheckedChange={(available) => onChange({ ...draft, configurations: draft.configurations.map((item, itemIndex) => itemIndex === index ? { ...item, available } : item) })} />
            <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={() => onChange({ ...draft, configurations: draft.configurations.filter((_, itemIndex) => itemIndex !== index) })}>구성 제거</Button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" disabled={saving || draft.configurations.length >= 500} onClick={addConfiguration}>판매 구성 추가</Button>
      </fieldset>

      <div className="button-row"><Button type="submit" loading={saving}>{saving ? "저장 중" : current ? "거래 내용 저장" : "메뉴 생성"}</Button><Button type="button" variant="secondary" disabled={saving} onClick={onClose}>취소</Button></div>
      {saved ? <p className="form-success" role="status">메뉴 거래 내용을 저장했습니다.</p> : null}
      {stale ? <FeedbackState kind="error" title="다른 변경이 먼저 저장되었습니다" description="다른 사람이 저장한 최신 내용을 불러와 확인한 뒤 다시 수정해 주세요." reference={error instanceof ApiRequestError ? error.correlationId : undefined} action={onReload ? <Button variant="secondary" onClick={onReload}>최신 내용 불러오기</Button> : undefined} /> : error ? <ErrorState error={error} retry={onSave} /> : null}
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
  return requestErrorPresentation(failure).description;
}
