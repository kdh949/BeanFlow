import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { EmptyState, LoadingState } from "../../design-system";
import { PageHeading } from "../../design-system";
import { Button } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { compactId, shortDateTime, won } from "../../lib/format";
import { useMerchantStores } from "./useMerchantStores";
import { StoreSelector } from "./StoreSelector";
import { DisputeFilingPanel } from "./DisputeFilingPanel";

type SettlementBatch = components["schemas"]["SettlementBatch"];
type SettlementBatchPage = components["schemas"]["SettlementBatchPage"];
type SettlementItemPage = components["schemas"]["SettlementItemPage"];

const settlementDate = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "medium" });

/** 정산 명세는 ACTIVE OWNER만 조회한다. 서버가 다시 확인하므로 이 화면의 목록은 편의일 뿐이다. */
export function StoreSettlementsPage() {
  const { state: storesState, stores, selected, select, reload } = useMerchantStores("OWNER");
  const [openBatch, setOpenBatch] = useState<SettlementBatch | null>(null);

  const storeId = selected?.storeId ?? null;
  const [page, setPage] = useState<SettlementBatchPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  // A store change starts a new request before the previous one settles; this
  // discards a stale response instead of letting it overwrite the answer for
  // the store the operator has since switched to.
  const requestGeneration = useRef(0);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (!storeId) {
      setPage({ items: [], page: {} });
      return;
    }
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    const generation = ++requestGeneration.current;
    try {
      const next = unwrap(
        await merchantApi.GET("/stores/{storeId}/settlements", { params: { path: { storeId }, query: { cursor } } }),
      );
      if (generation !== requestGeneration.current) return;
      setPage((current) => (append && current ? { items: [...current.items, ...next.items], page: next.page } : next));
    } catch (failure) {
      if (generation === requestGeneration.current) setError(failure);
    } finally {
      if (generation === requestGeneration.current) setLoadingMore(false);
    }
  }, [storeId]);

  useEffect(() => { void load(); }, [load]);

  if (storesState.status === "loading") return <LoadingState label="매장 목록을 불러오는 중" />;
  if (storesState.status === "failed") {
    return <div className="console-page"><ErrorState error={storesState.error} retry={reload} /></div>;
  }

  return (
    <div className="console-page">
      <PageHeading

        title="정산 내역"
        description="확정된 정산과 주문별 명세를 확인하고, 금액이 다르면 이의를 제기합니다."
        action={<StoreSelector stores={stores} selected={selected} onSelect={(id) => { setOpenBatch(null); select(id); }} />}
      />

      {stores.length === 0 ? (
        <EmptyState
          title="정산을 볼 수 있는 매장이 없습니다"
          description="정산 내역은 매장 소유자 권한이 있는 계정만 조회할 수 있습니다."
        />
      ) : !page && !error ? (
        <LoadingState label="정산 내역을 불러오는 중" />
      ) : error ? (
        <ErrorState error={error} retry={() => void load()} />
      ) : page?.items.length === 0 ? (
        <EmptyState title="아직 확정된 정산이 없습니다" description="정산은 주문 완료일 기준으로 집계된 뒤 확정됩니다." />
      ) : (
        <>
          <section className="settlement-list">
            {page?.items.map((batch) => (
              <article className="surface-card settlement-card" key={batch.settlementBatchId}>
                <header>
                  <div>
                    <span className="context-label">{settlementDate.format(new Date(batch.settlementDate))}</span>
                    <strong className="bf-num">{won.format(batch.netSettlementKrw)}</strong>
                  </div>
                  <StatusText state={batch.state} />
                </header>
                <dl className="detail-list">
                  <div><dt>결제 총액</dt><dd className="bf-num">{won.format(batch.grossPaidKrw)}</dd></div>
                  <div><dt>수수료</dt><dd className="bf-num">{won.format(batch.feeKrw)}</dd></div>
                  <div><dt>혜택 비용</dt><dd className="bf-num">{won.format(batch.benefitCostKrw)}</dd></div>
                  <div><dt>조정</dt><dd className="bf-num">{won.format(batch.adjustmentKrw)}</dd></div>
                </dl>
                <Button
                  variant="secondary"
                  onClick={() => setOpenBatch(openBatch?.settlementBatchId === batch.settlementBatchId ? null : batch)}
                >
                  {openBatch?.settlementBatchId === batch.settlementBatchId ? "명세 닫기" : "주문별 명세"}
                </Button>
                {openBatch?.settlementBatchId === batch.settlementBatchId && storeId ? (
                  <SettlementItems storeId={storeId} batch={batch} />
                ) : null}
              </article>
            ))}
          </section>
          {page?.page.nextCursor ? (
            <Button variant="secondary" block loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
              {loadingMore ? "더 불러오는 중" : "정산 더 보기"}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}

function SettlementItems({ storeId, batch }: { storeId: string; batch: SettlementBatch }) {
  const [page, setPage] = useState<SettlementItemPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const requestGeneration = useRef(0);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    const generation = ++requestGeneration.current;
    try {
      const next = unwrap(
        await merchantApi.GET("/stores/{storeId}/settlements/{settlementBatchId}/items", {
          params: { path: { storeId, settlementBatchId: batch.settlementBatchId }, query: { cursor } },
        }),
      );
      if (generation !== requestGeneration.current) return;
      setPage((current) => (append && current ? { items: [...current.items, ...next.items], page: next.page } : next));
    } catch (failure) {
      if (generation === requestGeneration.current) setError(failure);
    } finally {
      if (generation === requestGeneration.current) setLoadingMore(false);
    }
  }, [storeId, batch.settlementBatchId]);

  useEffect(() => { void load(); }, [load]);

  const [filing, setFiling] = useState<string | null>(null);

  /**
   * A background refresh after filing, not a `load()` call: `load()` clears
   * `page` first and would flash the whole list back to its loading state,
   * unmounting the just-filed confirmation this panel stays open to show.
   */
  async function refreshQuietly() {
    try {
      const next = unwrap(
        await merchantApi.GET("/stores/{storeId}/settlements/{settlementBatchId}/items", {
          params: { path: { storeId, settlementBatchId: batch.settlementBatchId }, query: {} },
        }),
      );
      setPage(next);
    } catch {
      // Best-effort: a failed background refresh must not hide the confirmation above.
    }
  }

  if (!page && !error) return <LoadingState label="주문별 명세를 불러오는 중" />;
  if (error) return <ErrorState error={error} retry={() => void load()} />;
  if (page?.items.length === 0) {
    return <EmptyState title="명세가 비어 있습니다" description="이 정산에는 포함된 주문이 없습니다." />;
  }

  return (
    <div className="settlement-items">
      {page?.items.map((item) => (
        <div className="settlement-item" key={item.settlementItemId}>
          <div>
            <strong>{shortDateTime.format(new Date(item.completedAt))} 완료</strong>
            <small>명세 {compactId(item.settlementItemId)}</small>
          </div>
          <dl className="detail-list">
            <div><dt>결제</dt><dd className="bf-num">{won.format(item.grossPaidKrw)}</dd></div>
            <div><dt>수수료</dt><dd className="bf-num">{won.format(item.feeKrw)}</dd></div>
            <div><dt>혜택 비용</dt><dd className="bf-num">{won.format(item.benefitCostKrw)}</dd></div>
            <div><dt>정산액</dt><dd className="bf-num">{won.format(item.netSettlementKrw)}</dd></div>
          </dl>
          {batch.state === "CONFIRMED" ? (
            <Button
              variant="ghost"
              onClick={() => setFiling(filing === item.settlementItemId ? null : item.settlementItemId)}
            >
              {filing === item.settlementItemId ? "이의제기 취소" : "이의제기"}
            </Button>
          ) : (
            <p className="form-footnote">확정 전 정산에는 이의를 제기할 수 없습니다.</p>
          )}
          {filing === item.settlementItemId ? (
            <DisputeFilingPanel
              settlementItemId={item.settlementItemId}
              onFiled={() => void refreshQuietly()}
              onClose={() => setFiling(null)}
            />
          ) : null}
        </div>
      ))}
      {page?.page.nextCursor ? (
        <Button variant="secondary" block loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          {loadingMore ? "더 불러오는 중" : "명세 더 보기"}
        </Button>
      ) : null}
    </div>
  );
}
