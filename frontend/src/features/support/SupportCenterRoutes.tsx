import { useEffect, useRef, useState } from "react";
import { Navigate, useNavigate, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import {
  SupportApprovalsScreen,
  SupportCaseDetailScreen,
  SupportCaseIntakeScreen,
  SupportCompensationScreen,
  SupportOrderActionScreen,
  SupportProfileChangeScreen,
  SupportQueueScreen,
  SupportVerificationScreen,
  type ApprovalDetail,
  type ApprovalPage,
  type ApprovalTimeline,
  type CaseOverview,
  type CompensationPage,
  type OrderActionVisualState,
  type ProfileChangePage,
  type QueuePage,
  type QueueSummary,
  type ScreenStatus,
  type SearchResult,
  type VerificationVisualState,
} from "../../presentation/support-center";

type SupportCase = components["schemas"]["SupportCase"];
type VerificationSession = components["schemas"]["VerificationSessionResource"];
type VerificationChallenge = components["schemas"]["VerificationChallengeResource"];
type Grant = components["schemas"]["DataAccessGrantResource"];
type Reveal = components["schemas"]["RevealedPersonalDataResource"];
type Timeline = components["schemas"]["SupportTimelinePage"];
type PersonalField = components["schemas"]["SupportPersonalDataField"];
type SupportInquiryCategory = components["schemas"]["SupportInquiryCategory"];
type SupportCasePriority = components["schemas"]["SupportCasePriority"];
type VerificationChannel = components["schemas"]["VerificationChannel"];

const nextCaseState: Partial<Record<SupportCase["state"], SupportCase["state"]>> = {
  OPEN: "IN_PROGRESS",
  IN_PROGRESS: "RESOLVED",
  WAITING: "IN_PROGRESS",
  RESOLVED: "CLOSED",
};

function screenStatus(error: unknown, loading: boolean): ScreenStatus {
  if (loading) return "loading";
  if (error instanceof ApiRequestError && error.status === 403) return "permission";
  return error ? "error" : "ready";
}

export function SupportIndexRoute() {
  return <Navigate replace to="/support/queue" />;
}

export function SupportQueueRoute() {
  const [summary, setSummary] = useState<QueueSummary>();
  const [page, setPage] = useState<QueuePage>();
  const [filters, setFilters] = useState({ scope: "MINE", state: "", priority: "" });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [generation, setGeneration] = useState(0);
  useEffect(() => {
    let current = true;
    setLoading(true); setError(undefined);
    Promise.all([
      operationsApi.GET("/support/case-queue/summary"),
      operationsApi.GET("/support/case-queue", { params: { query: { scope: filters.scope as "MINE" | "ALL", state: (filters.state || undefined) as components["schemas"]["SupportCaseState"] | undefined, priority: (filters.priority || undefined) as components["schemas"]["SupportCasePriority"] | undefined, limit: 20 } } }),
    ]).then(([summaryResponse, queueResponse]) => {
      if (!current) return;
      setSummary(unwrap(summaryResponse)); setPage(unwrap(queueResponse));
    }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [filters, generation]);
  return <SupportQueueScreen status={screenStatus(error, loading)} summary={summary} page={page} filters={filters} onFilterChange={(key, value) => setFilters((current) => ({ ...current, [key]: value }))} onRefresh={() => setGeneration((value) => value + 1)} />;
}

export function SupportCaseIntakeRoute() {
  const navigate = useNavigate();
  const [criterionType, setCriterionType] = useState("PHONE");
  const [criterion, setCriterion] = useState("");
  const [category, setCategory] = useState<SupportInquiryCategory>("ACCOUNT_RECOVERY");
  const [priority, setPriority] = useState<SupportCasePriority>("NORMAL");
  const [result, setResult] = useState<SearchResult>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const caseIntent = useRef(new SubmissionIntent());
  const linkIntent = useRef(new SubmissionIntent());
  async function search() {
    const value = criterion.trim();
    if (!value) return;
    setBusy(true); setError(undefined); setResult(undefined);
    try {
      setResult(unwrap(await operationsApi.POST("/support/searches", { body: { criterion: { type: criterionType as "PHONE" | "EMAIL", value }, subjectTypes: ["CUSTOMER", "STORE", "RIDER"], reasonCode: "CASE_INTAKE" } })));
    } catch (failure) { setError(failure); } finally { setCriterion(""); setBusy(false); }
  }
  async function create(candidate: SearchResult["items"][number]) {
    const body = { requesterType: candidate.subjectType === "STORE" ? "STORE_OWNER" as const : candidate.subjectType, requesterReference: candidate.subjectId, category, priority, reason: "MASKED_EXACT_SEARCH_CASE_INTAKE" };
    setBusy(true); setError(undefined);
    try {
      const created = unwrap(await operationsApi.POST("/support/cases", { params: { header: { "Idempotency-Key": caseIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      const linkBody = { subjectType: candidate.subjectType === "RIDER" ? "DELIVERY" as const : candidate.subjectType, subjectId: candidate.subjectId, relationship: "REQUESTER" as const, reason: "MASKED_SEARCH_CANDIDATE_SELECTED" };
      unwrap(await operationsApi.POST("/support/cases/{caseId}/subject-links", { params: { path: { caseId: created.caseId }, header: { "Idempotency-Key": linkIntent.current.keyFor(JSON.stringify(linkBody)) } }, body: linkBody }));
      caseIntent.current.complete(); linkIntent.current.complete(); navigate(`/support/cases/${created.caseId}`);
    } catch (failure) { setError(failure); } finally { setBusy(false); }
  }
  return <SupportCaseIntakeScreen status={screenStatus(error, false)} criterionType={criterionType} criterion={criterion} category={category} priority={priority} result={result} busy={busy} onCriterionTypeChange={setCriterionType} onCriterionChange={setCriterion} onCategoryChange={(value) => setCategory(value as SupportInquiryCategory)} onPriorityChange={(value) => setPriority(value as SupportCasePriority)} onSearch={() => void search()} onCreate={(candidate) => void create(candidate)} />;
}

export function SupportCaseDetailRoute() {
  const { caseId = "" } = useParams();
  const [overview, setOverview] = useState<CaseOverview>();
  const [timeline, setTimeline] = useState<Timeline>();
  const [noteContent, setNoteContent] = useState("");
  const [interactionSummary, setInteractionSummary] = useState("");
  const [mutationMessage, setMutationMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [generation, setGeneration] = useState(0);
  const noteIntent = useRef(new SubmissionIntent());
  const interactionIntent = useRef(new SubmissionIntent());
  const transitionIntent = useRef(new SubmissionIntent());
  useEffect(() => {
    let current = true; setLoading(true); setError(undefined);
    Promise.all([
      operationsApi.GET("/support/cases/{caseId}/overview", { params: { path: { caseId } } }),
      operationsApi.GET("/support/cases/{caseId}/timeline", { params: { path: { caseId }, query: { limit: 50 } } }),
    ]).then(([overviewResponse, timelineResponse]) => { if (current) { setOverview(unwrap(overviewResponse)); setTimeline(unwrap(timelineResponse)); } }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [caseId, generation]);
  async function appendNote() {
    const content = noteContent.trim();
    if (!content) return;
    const body = { content, reason: "상담 진행 메모" };
    setBusy(true); setMutationMessage(undefined);
    try {
      unwrap(await operationsApi.POST("/support/cases/{caseId}/notes", { params: { path: { caseId }, header: { "Idempotency-Key": noteIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      noteIntent.current.complete(); setNoteContent(""); setMutationMessage("메모가 비식별 기록으로 저장되었습니다."); setGeneration((value) => value + 1);
    } catch (failure) { setMutationMessage(failure instanceof Error ? failure.message : "메모를 저장하지 못했습니다."); }
    finally { setBusy(false); }
  }
  async function appendInteraction() {
    const redactedSummary = interactionSummary.trim();
    if (!redactedSummary) return;
    const body = { channel: "PHONE" as const, direction: "INBOUND" as const, occurredAt: new Date().toISOString(), redactedSummary };
    setBusy(true); setMutationMessage(undefined);
    try {
      unwrap(await operationsApi.POST("/support/cases/{caseId}/interactions", { params: { path: { caseId }, header: { "Idempotency-Key": interactionIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      interactionIntent.current.complete(); setInteractionSummary(""); setMutationMessage("접촉 기록이 비식별 요약으로 저장되었습니다."); setGeneration((value) => value + 1);
    } catch (failure) { setMutationMessage(failure instanceof Error ? failure.message : "접촉 기록을 저장하지 못했습니다."); }
    finally { setBusy(false); }
  }
  async function transitionCase() {
    if (!overview) return;
    const targetState = nextCaseState[overview.case.state];
    if (!targetState) return;
    const body = { targetState, expectedVersion: overview.case.version, reason: `SUPPORT_CENTER_${overview.case.state}_TO_${targetState}` };
    setBusy(true); setMutationMessage(undefined);
    try {
      unwrap(await operationsApi.POST("/support/cases/{caseId}/status-transitions", { params: { path: { caseId }, header: { "Idempotency-Key": transitionIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      transitionIntent.current.complete(); setMutationMessage(`상담 상태가 ${targetState}(으)로 변경되었습니다.`); setGeneration((value) => value + 1);
    } catch (failure) { setMutationMessage(failure instanceof Error ? failure.message : "상담 상태를 변경하지 못했습니다."); }
    finally { setBusy(false); }
  }
  return <SupportCaseDetailScreen status={screenStatus(error, loading)} overview={overview} timeline={timeline?.items} noteContent={noteContent} interactionSummary={interactionSummary} mutationMessage={mutationMessage} busy={busy} nextState={overview ? nextCaseState[overview.case.state] : undefined} onNoteContentChange={setNoteContent} onInteractionSummaryChange={setInteractionSummary} onAppendNote={() => void appendNote()} onAppendInteraction={() => void appendInteraction()} onTransition={() => void transitionCase()} onRefresh={() => setGeneration((value) => value + 1)} />;
}

const fieldBySubject: Partial<Record<components["schemas"]["SupportSubjectType"], PersonalField>> = {
  CUSTOMER: "CUSTOMER_PRIMARY_PHONE", STORE: "STORE_SUPPORT_PHONE", DELIVERY: "COURIER_RELAY_PHONE",
};

export function SupportVerificationRoute() {
  const { caseId = "" } = useParams();
  const [supportCase, setSupportCase] = useState<SupportCase>();
  const [session, setSession] = useState<VerificationSession>();
  const [challenge, setChallenge] = useState<VerificationChallenge>();
  const [grant, setGrant] = useState<Grant>();
  const [reveal, setReveal] = useState<Reveal>();
  const [proof, setProof] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const sessionIntent = useRef(new SubmissionIntent());
  const challengeIntent = useRef(new SubmissionIntent());
  const proofIntent = useRef(new SubmissionIntent());
  const grantIntent = useRef(new SubmissionIntent());
  const revealIntent = useRef(new SubmissionIntent());
  useEffect(() => {
    let current = true; setLoading(true); setError(undefined);
    operationsApi.GET("/support/cases/{caseId}", { params: { path: { caseId } } }).then((response) => { if (current) setSupportCase(unwrap(response)); }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; setProof(""); setReveal(undefined); };
  }, [caseId]);
  useEffect(() => {
    if (!reveal) return;
    const timeout = window.setTimeout(() => setReveal(undefined), 60_000);
    return () => window.clearTimeout(timeout);
  }, [reveal]);
  const activeLink = supportCase?.subjectLinks.find((link) => fieldBySubject[link.subjectType]);
  const field = activeLink ? fieldBySubject[activeLink.subjectType] : undefined;
  async function issueChallenge(sessionId: string, channel: VerificationChannel) {
    const body = { channel };
    const issued = unwrap(await operationsApi.POST("/support/verification-sessions/{sessionId}/challenges", { params: { path: { sessionId }, header: { "Idempotency-Key": challengeIntent.current.keyFor(JSON.stringify(body)) } }, body }));
    challengeIntent.current.complete();
    return issued;
  }
  async function issue() {
    if (!activeLink) return;
    setBusy(true); setError(undefined);
    try {
      const body = { subjectLinkId: activeLink.linkId, requestedLevel: "ENHANCED" as const, purpose: "CONTACT_CONFIRMATION" as const, actionScope: "PERSONAL_DATA_REVEAL" as const };
      const created = unwrap(await operationsApi.POST("/support/cases/{caseId}/verification-sessions", { params: { path: { caseId }, header: { "Idempotency-Key": sessionIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      setSession(created); sessionIntent.current.complete();
      const issued = await issueChallenge(created.sessionId, "REGISTERED_PHONE");
      setChallenge(issued); setSession({ ...created, challenges: [...created.challenges, issued] });
    } catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  async function verify() {
    if (!challenge || !session || !field) return;
    setBusy(true); setError(undefined);
    try {
      const body = { proof };
      const verified = unwrap(await operationsApi.POST("/support/verification-challenges/{challengeId}/verifications", { params: { path: { challengeId: challenge.challengeId }, header: { "Idempotency-Key": proofIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      const verifiedChallenges = [...session.challenges.filter((item) => item.challengeId !== verified.challenge.challengeId), verified.challenge];
      setSession({ ...session, state: verified.sessionState, achievedLevel: verified.achievedLevel, invalidAttempts: verified.invalidAttempts, challenges: verifiedChallenges });
      setChallenge(verified.challenge); proofIntent.current.complete(); setProof("");
      if (verified.challenge.state === "VERIFIED" && verified.sessionState === "PENDING" && verified.challenge.channel === "REGISTERED_PHONE") {
        const emailChallenge = await issueChallenge(session.sessionId, "REGISTERED_EMAIL");
        setChallenge(emailChallenge);
        setSession({ ...session, state: "PENDING", achievedLevel: verified.achievedLevel, invalidAttempts: verified.invalidAttempts, challenges: [...verifiedChallenges, emailChallenge] });
      } else if (verified.sessionState === "VERIFIED") {
        const grantBody = { verificationSessionId: session.sessionId, purpose: "CONTACT_CONFIRMATION" as const, fields: [field], reasonCode: "CONTACT_CONFIRMATION" as const };
        setGrant(unwrap(await operationsApi.POST("/support/cases/{caseId}/data-access-grants", { params: { path: { caseId }, header: { "Idempotency-Key": grantIntent.current.keyFor(JSON.stringify(grantBody)) } }, body: grantBody })));
        grantIntent.current.complete();
      }
    } catch (failure) { setError(failure); setProof(""); }
    finally { setBusy(false); }
  }
  async function revealData() {
    if (!grant || grant.state !== "ACTIVE" || !field) return;
    const body = { fields: [field] };
    setError(undefined); setReveal(undefined);
    try { setReveal(unwrap(await operationsApi.POST("/support/data-access-grants/{grantId}/reveals", { params: { path: { grantId: grant.grantId }, header: { "Idempotency-Key": revealIntent.current.keyFor(JSON.stringify(body)) } }, body }))); revealIntent.current.complete(); } catch (failure) { setError(failure); }
  }
  const visualState: VerificationVisualState = challenge?.state === "EXPIRED" ? "expired" : challenge?.state === "INVALID" ? "invalid" : grant?.state === "ACTIVE" ? "active" : grant ? "grant-pending" : session?.state === "VERIFIED" ? "verified" : "pending";
  const verifiedChannelCount = session?.challenges.filter((item) => item.state === "VERIFIED").length ?? 0;
  return <SupportVerificationScreen status={screenStatus(error, loading)} state={visualState} verificationCode={proof} challengeChannel={challenge?.channel} verifiedChannelCount={verifiedChannelCount} busy={busy} revealedValue={reveal?.values[field ?? "CUSTOMER_PRIMARY_PHONE"]} onVerificationCodeChange={setProof} onIssue={() => void issue()} onVerify={() => void verify()} onReveal={() => void revealData()} onClear={() => setReveal(undefined)} />;
}

export function SupportOrderActionRoute() {
  const { caseId = "", orderId = "" } = useParams();
  const [overview, setOverview] = useState<components["schemas"]["SupportOrderOverview"]>();
  const [action, setAction] = useState<components["schemas"]["SupportActionType"]>("ORDER_CANCELLATION");
  const [verificationSessionId, setVerificationSessionId] = useState("");
  const [reason, setReason] = useState("");
  const [evidenceDigest, setEvidenceDigest] = useState("");
  const [actionPayloadDigest, setActionPayloadDigest] = useState("");
  const [evaluation, setEvaluation] = useState<components["schemas"]["SupportActionEvaluationResource"]>();
  const [request, setRequest] = useState<components["schemas"]["SupportActionRequestResource"]>();
  const [actionState, setActionState] = useState<OrderActionVisualState>("allowed");
  const [busy, setBusy] = useState(false);
  const requestIntent = useRef(new SubmissionIntent());
  const [loading, setLoading] = useState(true); const [error, setError] = useState<unknown>();
  useEffect(() => { let current = true; setLoading(true); operationsApi.GET("/support/orders/{orderId}/overview", { params: { path: { orderId }, query: { caseId } } }).then((response) => { if (current) setOverview(unwrap(response)); }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); }); return () => { current = false; }; }, [caseId, orderId]);
  useEffect(() => { if (overview?.state === "UNKNOWN") setActionState("unknown"); }, [overview?.state]);
  async function evaluate() {
    if (!overview || !verificationSessionId) return;
    setBusy(true); setEvaluation(undefined); setRequest(undefined);
    try {
      const result = unwrap(await operationsApi.POST("/support/cases/{caseId}/action-evaluations", { params: { path: { caseId } }, body: { action, orderId, expectedTargetVersion: overview.version, verificationSessionId } }));
      setEvaluation(result);
      setActionState(result.decision === "ALLOWED" ? "allowed" : result.decision === "APPROVAL_REQUIRED" ? "approval-required" : "denied");
    } catch (failure) {
      setActionState(failure instanceof ApiRequestError && failure.code === "STALE_TARGET_VERSION" ? "stale" : failure instanceof ApiRequestError && /UNKNOWN|RECONCIL/.test(failure.code) ? "reconciling" : "manual-review");
    } finally { setBusy(false); }
  }
  async function requestAction() {
    if (!overview || !evaluation) return;
    const body = { action, orderId, expectedTargetVersion: overview.version, verificationSessionId, actionPayloadDigest, reason, evidenceDigest };
    setBusy(true);
    try {
      const created = unwrap(await operationsApi.POST("/support/cases/{caseId}/action-requests", { params: { path: { caseId }, header: { "Idempotency-Key": requestIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      setRequest(created); requestIntent.current.complete();
      setActionState(created.state === "STALE" ? "stale" : created.state === "MANUAL_REVIEW" ? "manual-review" : created.state === "RESOLUTION_REQUIRED" ? "resolution-required" : "approval-required");
    } catch (failure) {
      setActionState(failure instanceof ApiRequestError && failure.code === "STALE_TARGET_VERSION" ? "stale" : "manual-review");
    } finally { setBusy(false); }
  }
  function changeAction(value: components["schemas"]["SupportActionType"]) { setAction(value); setEvaluation(undefined); setRequest(undefined); setActionState("allowed"); requestIntent.current.rotate(); }
  return <SupportOrderActionScreen status={screenStatus(error, loading)} overview={overview} actionState={actionState} action={action} verificationSessionId={verificationSessionId} reason={reason} evidenceDigest={evidenceDigest} actionPayloadDigest={actionPayloadDigest} evaluation={evaluation} requestState={request?.state} busy={busy} onActionChange={changeAction} onVerificationSessionIdChange={(value) => { setVerificationSessionId(value); setEvaluation(undefined); }} onReasonChange={setReason} onEvidenceDigestChange={setEvidenceDigest} onActionPayloadDigestChange={setActionPayloadDigest} onEvaluate={() => void evaluate()} onRequest={() => void requestAction()} />;
}

export function SupportCompensationRoute() {
  const { caseId } = useParams(); const [page, setPage] = useState<CompensationPage>(); const [loading, setLoading] = useState(true); const [error, setError] = useState<unknown>();
  useEffect(() => { let current = true; setLoading(true); operationsApi.GET("/support/compensations", { params: { query: { caseId, limit: 20 } } }).then((response) => { if (current) setPage(unwrap(response)); }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); }); return () => { current = false; }; }, [caseId]);
  return <SupportCompensationScreen status={screenStatus(error, loading)} page={page} />;
}

export function SupportProfileChangeRoute() {
  const { caseId } = useParams(); const [page, setPage] = useState<ProfileChangePage>(); const [loading, setLoading] = useState(true); const [error, setError] = useState<unknown>();
  useEffect(() => { let current = true; setLoading(true); operationsApi.GET("/support/profile-changes", { params: { query: { caseId, limit: 20 } } }).then((response) => { if (current) setPage(unwrap(response)); }).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); }); return () => { current = false; }; }, [caseId]);
  return <SupportProfileChangeScreen status={screenStatus(error, loading)} page={page} />;
}

export function SupportApprovalsRoute() {
  const { taskType, resourceId } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState<ApprovalPage>(); const [detail, setDetail] = useState<ApprovalDetail>(); const [timeline, setTimeline] = useState<ApprovalTimeline>(); const [loading, setLoading] = useState(true); const [error, setError] = useState<unknown>();
  const [stateFilter, setStateFilter] = useState("");
  const [actionMessage, setActionMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [generation, setGeneration] = useState(0);
  const decisionIntent = useRef(new SubmissionIntent());
  useEffect(() => {
    let current = true; setLoading(true); setError(undefined);
    const type = taskType as components["schemas"]["SupportApprovalTaskType"] | undefined;
    const requests: Promise<unknown>[] = [operationsApi.GET("/support/approval-tasks", { params: { query: { scope: "MINE", taskType: type, state: stateFilter || undefined, limit: 20 } } }).then((response) => { if (current) setPage(unwrap(response)); })];
    if (type && resourceId) {
      requests.push(operationsApi.GET("/support/approval-tasks/{taskType}/{resourceId}", { params: { path: { taskType: type, resourceId } } }).then((response) => { if (current) setDetail(unwrap(response)); }));
      requests.push(operationsApi.GET("/support/approval-tasks/{taskType}/{resourceId}/timeline", { params: { path: { taskType: type, resourceId }, query: { limit: 50 } } }).then((response) => { if (current) setTimeline(unwrap(response)); }));
    } else { setDetail(undefined); setTimeline(undefined); }
    Promise.all(requests).catch((failure) => { if (current) setError(failure); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [taskType, resourceId, stateFilter, generation]);
  async function decide(action: components["schemas"]["SupportApprovalAction"]) {
    if (!detail) return;
    if (detail.task.taskType !== "DATA_ACCESS_GRANT" || (action !== "APPROVE" && action !== "DENY")) {
      setActionMessage("이 작업은 해당 업무 전용 처리 화면에서 실행해야 합니다."); return;
    }
    const body = { decision: action, expectedVersion: detail.task.version, reasonCode: "CASE_HANDLING" as const };
    setBusy(true); setActionMessage(undefined);
    try {
      unwrap(await operationsApi.POST("/support/data-access-grants/{grantId}/approvals", { params: { path: { grantId: detail.task.resourceId }, header: { "Idempotency-Key": decisionIntent.current.keyFor(JSON.stringify(body)) } }, body }));
      decisionIntent.current.complete(); setActionMessage(action === "APPROVE" ? "열람 승인이 완료되었습니다." : "열람 요청을 거부했습니다."); setGeneration((value) => value + 1);
    } catch (failure) { setActionMessage(failure instanceof Error ? failure.message : "승인 결정을 저장하지 못했습니다."); }
    finally { setBusy(false); }
  }
  return <SupportApprovalsScreen status={screenStatus(error, loading)} page={page} detail={detail} timeline={timeline} taskType={taskType ?? ""} state={stateFilter} actionMessage={actionMessage} busy={busy} onTaskTypeChange={(value) => navigate(value ? `/support/approvals/${value}` : "/support/approvals")} onStateChange={setStateFilter} onAction={(action) => void decide(action)} />;
}
