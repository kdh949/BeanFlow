import { CalendarClock, Sparkles } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, LoadingState } from "../../design-system";
import { PageHeading } from "../../design-system";
import { shortDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { Button } from "../../design-system";
import { ErrorState } from "../../presentation/shared";

type CustomerPointSummary = components["schemas"]["CustomerPointSummary"];
type PointTransactionPage = components["schemas"]["PointTransactionPage"];
type PointTransaction = PointTransactionPage["items"][number];

const TYPE_LABELS: Record<string, string> = {
  ACCRUAL: "적립",
  USE: "사용",
  EXPIRATION: "만료",
  RECOVERY: "회수",
  RESTORE: "복구",
  RESTORE_SKIPPED_EXPIRED: "복구 불가(만료)",
  COMPENSATION: "보상",
  GOODWILL_COMPENSATION: "보상",
  ADJUSTMENT: "조정",
};

export function CustomerPointsPage() {
  const loadSummary = useCallback(async () => unwrap(await customerApi.GET("/me/points")), []);
  const summary = useResource<CustomerPointSummary>(loadSummary);

  return (
    <div className="customer-page points-page">
      <PageHeading title="포인트" description="적립과 사용 내역, 만료 예정 포인트를 확인하세요." />
      {summary.state.status === "loading" ? <LoadingState label="포인트를 불러오는 중" /> : null}
      {summary.state.status === "failed" ? <PointsFailure error={summary.state.error} retry={summary.reload} /> : null}
      {summary.state.status === "ready" ? <PointsSummary summary={summary.state.value} /> : null}
      {summary.state.status === "ready" ? <PointLedger /> : null}
    </div>
  );
}

/**
 * A missing PointAccount is an integrity failure the operator has to look at. It
 * is never displayed as a zero balance.
 */
function PointsFailure({ error, retry }: { error: unknown; retry: () => void }) {
  const integrity = error instanceof ApiRequestError && error.code === "POINT_ACCOUNT_INTEGRITY_FAILURE";
  return (
    <>
      <ErrorState error={error} retry={retry} />
      <p className="state-page-note">
        {integrity
          ? "포인트 계정을 확인하지 못했어요. 잔액이 0원이라는 뜻은 아니며, 문의 코드와 함께 알려주시면 확인해 드릴게요."
          : "포인트 잔액을 불러오지 못했어요. 화면에 보이는 값이 없더라도 0원으로 계산하지 마세요."}
      </p>
    </>
  );
}

function PointsSummary({ summary }: { summary: CustomerPointSummary }) {
  return (
    <>
      <section className="surface-card points-balance">
        <span className="context-label"><Sparkles size={15} /> 사용 가능</span>
        <strong>{summary.availablePointsKrw.toLocaleString("ko-KR")}P</strong>
        {summary.recoveryPendingKrw > 0 ? (
          <p role="status">환불로 회수 예정인 {summary.recoveryPendingKrw.toLocaleString("ko-KR")}P가 있어요.</p>
        ) : null}
      </section>

      <section className="surface-card points-expiring">
        <div className="card-kicker"><CalendarClock size={17} /> 만료 예정</div>
        {summary.expiring.length === 0 ? (
          <p>곧 만료되는 포인트가 없어요.</p>
        ) : (
          <dl>
            {summary.expiring.map((expiring) => (
              <div key={expiring.expiresAt}>
                <dt>{shortDateTime.format(new Date(expiring.expiresAt))}</dt>
                <dd>{expiring.amountKrw.toLocaleString("ko-KR")}P</dd>
              </div>
            ))}
          </dl>
        )}
        {summary.expiringHasMore ? (
          <p className="inline-note" role="status">이후에 만료되는 포인트가 더 있어요. 포인트 내역에서 전체 적립·사용 이력을 확인하세요.</p>
        ) : null}
      </section>
    </>
  );
}

function PointLedger() {
  const [page, setPage] = useState<PointTransactionPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    try {
      const next = unwrap(await customerApi.GET("/me/point-transactions", { params: { query: { cursor, limit: 20 } } }));
      setPage((current) => (append && current ? { items: [...current.items, ...next.items], page: next.page } : next));
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <section className="points-ledger" aria-label="포인트 내역">
      <h2>포인트 내역</h2>
      {!page && !error ? <LoadingState label="포인트 내역을 불러오는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {page?.items.length === 0 ? <EmptyState title="아직 포인트 내역이 없어요" description="주문하면 적립과 사용 내역이 여기에 쌓입니다." /> : null}
      {page?.items.length ? (
        <ul className="surface-card">
          {page.items.map((transaction) => <PointRow key={transaction.transactionId} transaction={transaction} />)}
        </ul>
      ) : null}
      {page?.page.nextCursor ? (
        <Button variant="secondary" block loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          {loadingMore ? "더 불러오는 중" : "내역 더 보기"}
        </Button>
      ) : null}
    </section>
  );
}

function PointRow({ transaction }: { transaction: PointTransaction }) {
  const positive = transaction.amountKrw > 0;
  return (
    <li>
      <div>
        <strong>{TYPE_LABELS[transaction.type] ?? transaction.type}</strong>
        <span>{shortDateTime.format(new Date(transaction.occurredAt))}</span>
      </div>
      <b className={positive ? "is-credit" : ""}>
        {positive ? "+" : ""}{transaction.amountKrw.toLocaleString("ko-KR")}P
      </b>
    </li>
  );
}
