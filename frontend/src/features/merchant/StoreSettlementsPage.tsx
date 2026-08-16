import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button } from "../../design-system";
import { compactId, shortDateTime, won } from "../../lib/format";
import { useResource } from "../shared/useResource";
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
  const load = useCallback(async (): Promise<SettlementBatchPage> => {
    if (!storeId) return { items: [], page: {} };
    return unwrap(await merchantApi.GET("/stores/{storeId}/settlements", { params: { path: { storeId } } }));
  }, [storeId]);
  const { state, reload: reloadBatches } = useResource<SettlementBatchPage>(load);

  if (storesState.status === "loading") return <LoadingState label="매장 목록을 불러오는 중" />;
  if (storesState.status === "failed") {
    return <div className="console-page"><ErrorState error={storesState.error} retry={reload} /></div>;
  }

  return (
    <div className="console-page">
      <PageTitle
        eyebrow="SETTLEMENT"
        title="정산 내역"
        description="확정된 정산과 주문별 명세를 확인하고, 금액이 다르면 이의를 제기합니다."
        action={<StoreSelector stores={stores} selected={selected} onSelect={(id) => { setOpenBatch(null); select(id); }} />}
      />

      {stores.length === 0 ? (
        <EmptyState
          title="정산을 볼 수 있는 매장이 없습니다"
          description="정산 내역은 매장 소유자 권한이 있는 계정만 조회할 수 있습니다."
        />
      ) : state.status === "loading" ? (
        <LoadingState label="정산 내역을 불러오는 중" />
      ) : state.status === "failed" ? (
        <ErrorState error={state.error} retry={reloadBatches} />
      ) : state.value.items.length === 0 ? (
        <EmptyState title="아직 확정된 정산이 없습니다" description="정산은 주문 완료일 기준으로 집계된 뒤 확정됩니다." />
      ) : (
        <section className="settlement-list">
          {state.value.items.map((batch) => (
            <article className="surface-card settlement-card" key={batch.settlementBatchId}>
              <header>
                <div>
                  <span className="eyebrow">{settlementDate.format(new Date(batch.settlementDate))}</span>
                  <strong className="bf-num">{won.format(batch.netSettlementKrw)}</strong>
                </div>
                <StatusBadge state={batch.state} />
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
      )}
    </div>
  );
}

function SettlementItems({ storeId, batch }: { storeId: string; batch: SettlementBatch }) {
  const load = useCallback(async (): Promise<SettlementItemPage> => unwrap(
    await merchantApi.GET("/stores/{storeId}/settlements/{settlementBatchId}/items", {
      params: { path: { storeId, settlementBatchId: batch.settlementBatchId } },
    }),
  ), [storeId, batch.settlementBatchId]);
  const { state, reload } = useResource<SettlementItemPage>(load);
  const [filing, setFiling] = useState<string | null>(null);

  if (state.status === "loading") return <LoadingState label="주문별 명세를 불러오는 중" />;
  if (state.status === "failed") return <ErrorState error={state.error} retry={reload} />;
  if (state.value.items.length === 0) {
    return <EmptyState title="명세가 비어 있습니다" description="이 정산에는 포함된 주문이 없습니다." />;
  }

  return (
    <div className="settlement-items">
      {state.value.items.map((item) => (
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
            <DisputeFilingPanel settlementItemId={item.settlementItemId} onFiled={() => setFiling(null)} />
          ) : null}
        </div>
      ))}
    </div>
  );
}
