import { useCallback, useState } from "react";
import type { components, paths } from "../../api/schema";
import { unwrap } from "../../api/client";
import { merchantApi } from "../../api/merchantClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { compactId, shortDateTime, won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { useMerchantStores } from "./useMerchantStores";
import { StoreSelector } from "./StoreSelector";

type DisputePage = components["schemas"]["MerchantDisputePage"];
type DisputeState = NonNullable<
  NonNullable<paths["/stores/{storeId}/disputes"]["get"]["parameters"]["query"]>["state"]
>;

const STATE_FILTERS: Array<{ value: DisputeState | "ALL"; label: string }> = [
  { value: "ALL", label: "전체" },
  { value: "FILED", label: "접수" },
  { value: "UNDER_REVIEW", label: "검토 중" },
  { value: "ACCEPTED", label: "인정" },
  { value: "REJECTED", label: "기각" },
  { value: "WITHDRAWN", label: "철회" },
];

/** 이의제기 목록은 ACTIVE OWNER만 조회한다. 목록 자체가 상태의 source다. */
export function StoreDisputesPage() {
  const { state: storesState, stores, selected, select, reload } = useMerchantStores("OWNER");
  const [filter, setFilter] = useState<DisputeState | "ALL">("ALL");

  const storeId = selected?.storeId ?? null;
  const load = useCallback(async (): Promise<DisputePage> => {
    if (!storeId) return { items: [], page: {} };
    return unwrap(
      await merchantApi.GET("/stores/{storeId}/disputes", {
        params: { path: { storeId }, query: filter === "ALL" ? {} : { state: filter } },
      }),
    );
  }, [storeId, filter]);
  const { state, reload: reloadDisputes } = useResource<DisputePage>(load);

  if (storesState.status === "loading") return <LoadingState label="매장 목록을 불러오는 중" />;
  if (storesState.status === "failed") {
    return <div className="console-page"><ErrorState error={storesState.error} retry={reload} /></div>;
  }

  return (
    <div className="console-page">
      <PageTitle
        eyebrow="DISPUTE"
        title="정산 이의제기"
        description="접수한 이의제기의 진행 상태와 보류 금액을 확인합니다."
        action={<StoreSelector stores={stores} selected={selected} onSelect={select} />}
      />

      {stores.length === 0 ? (
        <EmptyState
          title="이의제기를 볼 수 있는 매장이 없습니다"
          description="이의제기는 매장 소유자 권한이 있는 계정만 접수하고 조회할 수 있습니다."
        />
      ) : (
        <>
          <div className="filter-row" role="group" aria-label="상태 필터">
            {STATE_FILTERS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={filter === option.value ? "is-active" : ""}
                aria-pressed={filter === option.value}
                onClick={() => setFilter(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>

          {state.status === "loading" ? (
            <LoadingState label="이의제기를 불러오는 중" />
          ) : state.status === "failed" ? (
            <ErrorState error={state.error} retry={reloadDisputes} />
          ) : state.value.items.length === 0 ? (
            <EmptyState
              title="접수한 이의제기가 없습니다"
              description="정산 명세에서 금액이 다른 주문을 찾아 이의를 제기할 수 있습니다."
            />
          ) : (
            <section className="dispute-list">
              {state.value.items.map((dispute) => (
                <article className="surface-card dispute-card" key={dispute.disputeId}>
                  <header>
                    <div>
                      <span className="eyebrow">명세 {compactId(dispute.settlementItemId)}</span>
                      <strong className="bf-num">{won.format(dispute.expectedAdjustmentKrw)}</strong>
                    </div>
                    <StatusBadge state={dispute.state} />
                  </header>
                  <dl className="detail-list">
                    <div><dt>보류 금액</dt><dd className="bf-num">{won.format(dispute.heldAmountKrw)}</dd></div>
                    <div><dt>접수</dt><dd>{shortDateTime.format(new Date(dispute.filedAt))}</dd></div>
                    <div>
                      <dt>판정</dt>
                      <dd>{dispute.decidedAt ? shortDateTime.format(new Date(dispute.decidedAt)) : "진행 중"}</dd>
                    </div>
                  </dl>
                </article>
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
