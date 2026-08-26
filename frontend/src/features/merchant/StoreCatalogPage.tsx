import { Settings2 } from "lucide-react";
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

  const loadPolicy = useCallback(async () => {
    if (!storeId) return;
    setLoading(true);
    setLoadError(null);
    setSaveError(null);
    setSaved(false);
    try {
      const next = unwrap(await merchantApi.GET("/stores/{storeId}/ordering-policy", {
        params: { path: { storeId } },
      }));
      setPolicy(next);
      setAcceptingOrders(next.acceptingOrders);
      setPickupEnabled(next.pickupEnabled);
      intent.current.rotate();
    } catch (failure) {
      setPolicy(null);
      setLoadError(failure);
    } finally {
      setLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
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
    if (!storeId || !policy) return;
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
        </div>
      ) : null}
    </div>
  );
}
