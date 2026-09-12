import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent } from "../../api/client";
import { Button, InlineNotice, LoadingState, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime, won } from "../../lib/format";

type Dispute = components["schemas"]["DisputeManagementResponse"];
type Operation = "REVIEW" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";
type Props = {
  audience: "owner" | "operations";
  load: () => Promise<Dispute>;
  command: (operation: Operation, body: components["schemas"]["DisputeManagementRequest"], key: string) => Promise<Dispute>;
  /** Owner-only filing UI is supplied by the merchant surface. */
  terminalAction?: (dispute: Dispute) => React.ReactNode;
};
const labels: Record<Operation, string> = { REVIEW: "검토 시작", ACCEPTED: "인정 판정", REJECTED: "기각 판정", WITHDRAWN: "이의제기 철회" };
/** Read and re-read the authoritative decision intent before presenting another command. */
export function DisputeManagementPanel({ audience, load, command, terminalAction }: Props) {
  const [record, setRecord] = useState<Dispute | null>(null);
  const [readError, setReadError] = useState<unknown>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  const [saved, setSaved] = useState(false);
  const generation = useRef(0);
  const intent = useRef(new SubmissionIntent());
  async function read() {
    const version = ++generation.current;
    setLoading(true); setRecord(null); setReadError(null);
    try { const result = await load(); if (version === generation.current) setRecord(result); }
    catch (error) { if (version === generation.current) setReadError(error); }
    finally { if (version === generation.current) setLoading(false); }
  }
  useEffect(() => { void read(); return () => { ++generation.current; }; }, [load]);
  async function submit(operation: Operation) {
    if (busy || !record || !reason.trim()) return;
    setBusy(true); setFailure(null); setSaved(false);
    const body = { expectedVersion: record.version, reason: reason.trim() };
    try {
      await command(operation, body, intent.current.keyFor(JSON.stringify({ disputeId: record.disputeId, operation, ...body })));
      intent.current.complete(); setSaved(true); setReason("");
    } catch (error) { setFailure(error); }
    finally { await read(); setBusy(false); }
  }
  let actions: Operation[] = [];
  if (record?.state === "FILED" && audience === "operations") actions = ["REVIEW"];
  if (record?.state === "UNDER_REVIEW") {
    if (record.pendingDecision === null) actions = audience === "owner" ? ["WITHDRAWN"] : ["ACCEPTED", "REJECTED"];
    else if (audience === "owner" && record.pendingDecision === "WITHDRAWN") actions = ["WITHDRAWN"];
    else if (audience === "operations" && (record.pendingDecision === "ACCEPTED" || record.pendingDecision === "REJECTED")) actions = [record.pendingDecision];
  }
  return <section className="management-workspace" aria-label="이의제기 상세">
    {saved ? <p role="status">처리 요청을 완료했습니다.</p> : null}
    {failure ? <ErrorState error={failure} /> : null}
    {loading ? <LoadingState label="현재 이의제기 상태를 확인하는 중" /> : readError ? <ErrorState error={readError} retry={() => void read()} /> : record ? <>
      <article className="surface-card management-card"><div className="panel-heading"><h2>이의제기 내용</h2><StatusText domain="dispute" state={record.state} /></div>
        <p className="support-case-reference">{record.disputeId}</p>
        <dl className="detail-list"><div><dt>정산 항목</dt><dd className="support-case-reference">{record.settlementItemId}</dd></div><div><dt>요청 금액</dt><dd>{won.format(record.expectedAdjustmentKrw)}</dd></div><div><dt>보류 금액</dt><dd>{won.format(record.heldAmountKrw)}</dd></div><div><dt>접수</dt><dd>{fullDateTime.format(new Date(record.filedAt))}</dd></div><div><dt>판정</dt><dd>{record.decidedAt ? fullDateTime.format(new Date(record.decidedAt)) : "진행 중"}</dd></div>{record.settlementAdjustmentId ? <div><dt>반영된 정산 조정</dt><dd className="support-case-reference">{record.settlementAdjustmentId}</dd></div> : null}</dl>
        <h3>신청 사유</h3><p>{record.reason}</p><h3>증빙 위치</h3><ul>{record.evidenceReferences.map((reference, index) => <li className="support-case-reference" key={`${index}:${reference}`}>{reference}</li>)}</ul>
      </article>
      {record.pendingDecision ? <InlineNotice title="판정 처리가 아직 끝나지 않았습니다" description={audience === "owner" && record.pendingDecision !== "WITHDRAWN" ? "운영자가 접수된 판정을 처리하고 있습니다. 판정이 끝난 뒤 현재 내용을 다시 확인해 주세요." : "이미 접수된 판정의 처리를 확인하고 있습니다. 다른 판정으로 바꾸지 말고 같은 처리를 재개해 주세요."} /> : null}
      {audience === "owner" && record.state === "FILED" ? <p>검토가 시작된 뒤 철회할 수 있습니다.</p> : null}
      {actions.length ? <section className="surface-card management-card"><h3>이의제기 처리</h3><TextField label="처리 사유" value={reason} onValueChange={setReason} maxLength={500} disabled={busy} required /><div className="button-row">{actions.map(operation => <Button key={operation} variant="secondary" disabled={busy || !reason.trim()} loading={busy} onClick={() => void submit(operation)}>{labels[operation]}{record.pendingDecision ? " 재개" : ""}</Button>)}</div></section> : null}
      <Button variant="ghost" disabled={busy} onClick={() => void read()}>현재 이의제기 다시 읽기</Button>
      {audience === "owner" && ["ACCEPTED", "REJECTED", "WITHDRAWN"].includes(record.state) ? terminalAction?.(record) : null}
    </> : null}
  </section>;
}
