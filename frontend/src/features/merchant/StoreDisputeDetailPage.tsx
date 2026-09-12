import { useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, ButtonLink, EmptyState, PageHeading } from "../../design-system";
import { DisputeManagementPanel } from "../shared/DisputeManagementPanel";
import { DisputeFilingPanel } from "./DisputeFilingPanel";

/** The list supplies the explicit store scope; server ownership is checked on every request. */
export function StoreDisputeDetailPage() {
  const { disputeId = "" } = useParams();
  const [search] = useSearchParams();
  const storeId = search.get("storeId") ?? "";
  const [refiling, setRefiling] = useState(false);
  const [filedId, setFiledId] = useState<string | null>(null);
  useEffect(() => { setRefiling(false); setFiledId(null); }, [storeId, disputeId]);
  const load = useCallback(async () => unwrap(await merchantApi.GET("/stores/{storeId}/disputes/{disputeId}", { params: { path: { storeId, disputeId } } })), [storeId, disputeId]);
  return <div className="console-page"><ButtonLink to="/store/disputes" variant="ghost">이의제기 목록</ButtonLink><PageHeading title="정산 이의제기 상세" />
    {!storeId || !disputeId ? <EmptyState title="목록에서 매장과 이의제기를 선택해 주세요" description="선택한 매장의 소유자 권한으로 상세 내용을 확인합니다." /> : <DisputeManagementPanel key={`${storeId}:${disputeId}`} audience="owner" load={load} command={async (_operation, body, key) => unwrap(await merchantApi.POST("/stores/{storeId}/disputes/{disputeId}/withdrawals", { params: { path: { storeId, disputeId }, header: { "Idempotency-Key": key, ...await merchantCsrfHeader() } }, body }))} terminalAction={record => <section className="surface-card management-card"><h3>새 증빙 재접수</h3><p>종결된 건은 접수 기간 안에 새 증빙으로 한 번 재접수할 수 있습니다. 이전 접수와 증빙에 따라 재접수가 제한될 수 있습니다.</p>{refiling ? <DisputeFilingPanel settlementItemId={record.settlementItemId} previousDisputeId={record.disputeId} initialExpectedAdjustmentKrw={record.expectedAdjustmentKrw} onFiled={result => setFiledId(result.disputeId)} onClose={() => setRefiling(false)} /> : <Button variant="secondary" onClick={() => setRefiling(true)}>새 증빙으로 재접수</Button>}{filedId ? <ButtonLink variant="secondary" to={`/store/disputes/${filedId}?storeId=${storeId}`}>새 이의제기 확인</ButtonLink> : null}</section>} />}
  </div>;
}
