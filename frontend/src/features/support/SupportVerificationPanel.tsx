import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, InlineNotice, LoadingState } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { SupportWorkPicker } from "./SupportWorkPicker";
import { verificationPurposeLabels } from "./supportSecurityLabels";
type Session = components["schemas"]["VerificationSessionResource"];
/** Historical verification records only; these records never authorize new support work. */
export function SupportVerificationPanel({ caseId, locked = false, onChange }: { caseId: string; links: components["schemas"]["SupportSubjectLink"][]; disabled: boolean; locked?: boolean; initialActionScope?: Session["actionScope"]; onBusyChange?: (busy: boolean) => void; onChange: (session: Session | null) => void }) {
  const [sessionId, setSessionId] = useState("");
  useEffect(() => onChange(null), [onChange]);
  const read = useResource(useCallback(async () => {
    if (!sessionId) return null;
    const result = unwrap(await operationsApi.GET("/support/verification-sessions/{sessionId}", { params: { path: { sessionId } } }));
    if (result.caseId !== caseId) throw new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "현재 상담의 본인확인 기록이 아닙니다");
    return result;
  }, [caseId, sessionId]));
  const current = read.state.status === "ready" ? read.state.value : null;
  return <section className="management-workspace"><h2>과거 본인확인 기록</h2>
    <InlineNotice title="본인확인 절차가 종료되었습니다" description="새 업무는 현재 상담의 대상 연결로 처리합니다. 과거의 실제 본인확인 기록만 조회할 수 있습니다." />
    <SupportWorkPicker kind="VERIFICATION" caseId={caseId} disabled={locked} onSelect={item => setSessionId(item.requestId)} />
    {read.state.status === "loading" && sessionId ? <LoadingState label="과거 본인확인 기록을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : current ? <article className="surface-card management-card"><h3>본인확인 이력</h3><p className="support-case-reference">본인확인 ID {current.sessionId}</p><p>{verificationPurposeLabels[current.purpose]}</p><StatusText state={current.state} /><p>요청 수준 {current.requestedLevel} · 만료 {fullDateTime.format(new Date(current.expiresAt))}</p><Button variant="ghost" onClick={read.reload}>기록 새로고침</Button></article> : null}
  </section>;
}
