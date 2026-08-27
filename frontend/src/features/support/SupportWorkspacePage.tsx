import {
  Eye,
  EyeOff,
  FilePlus2,
  Link2,
  Search,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { PageHeading } from "../../design-system";
import { EmptyState, ErrorState, LoadingState, StatusText } from "../../design-system";
import { Button } from "../../design-system";
import { compactId, shortDateTime, won } from "../../lib/format";

type SearchResult = components["schemas"]["SupportSubjectSearchResult"];
type Candidate = components["schemas"]["SupportSubjectSearchCandidate"];
type SupportCase = components["schemas"]["SupportCase"];
type SubjectLink = components["schemas"]["SupportSubjectLink"];
type VerificationSession = components["schemas"]["VerificationSessionResource"];
type VerificationChallenge = components["schemas"]["VerificationChallengeResource"];
type Grant = components["schemas"]["DataAccessGrantResource"];
type Reveal = components["schemas"]["RevealedPersonalDataResource"];
type Timeline = components["schemas"]["SupportTimelinePage"];
type PersonalField = components["schemas"]["SupportPersonalDataField"];
type CompensationEvaluation = components["schemas"]["SupportCompensationEvaluationResource"];
type Compensation = components["schemas"]["SupportCompensationResource"];

const nextCaseState: Partial<Record<SupportCase["state"], SupportCase["state"]>> = {
  OPEN: "IN_PROGRESS",
  IN_PROGRESS: "RESOLVED",
  WAITING: "IN_PROGRESS",
  RESOLVED: "CLOSED",
};

const fieldBySubject: Record<SubjectLink["subjectType"], PersonalField | null> = {
  CUSTOMER: "CUSTOMER_PRIMARY_PHONE",
  STORE: "STORE_SUPPORT_PHONE",
  DELIVERY: "COURIER_RELAY_PHONE",
  ORDER: null,
};

/**
 * One bounded Support workspace: exact masked search, Case binding, staged
 * verification, purpose-bound reveal, owner timeline and compensation entry.
 * Raw search criteria, challenge proofs and reveals never enter URL or storage.
 */
export function SupportWorkspacePage() {
  const [criterionType, setCriterionType] = useState<"PHONE" | "EMAIL">("PHONE");
  const [criterion, setCriterion] = useState("");
  const [subjectType, setSubjectType] = useState<"CUSTOMER" | "STORE" | "RIDER">("CUSTOMER");
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<unknown>(null);
  const [caseCategory, setCaseCategory] = useState<components["schemas"]["SupportInquiryCategory"]>("ACCOUNT_RECOVERY");
  const [casePriority, setCasePriority] = useState<components["schemas"]["SupportCasePriority"]>("NORMAL");
  const caseIntent = useRef(new SubmissionIntent());
  const linkIntent = useRef(new SubmissionIntent());

  const [caseLookupId, setCaseLookupId] = useState("");
  const [supportCase, setSupportCase] = useState<SupportCase | null>(null);
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [caseLoading, setCaseLoading] = useState(false);
  const [caseError, setCaseError] = useState<unknown>(null);
  const [creatingCase, setCreatingCase] = useState(false);
  const [transitioning, setTransitioning] = useState(false);
  const transitionIntent = useRef(new SubmissionIntent());

  const [verification, setVerification] = useState<VerificationSession | null>(null);
  const [challenge, setChallenge] = useState<VerificationChallenge | null>(null);
  const [proof, setProof] = useState("");
  const [verificationBusy, setVerificationBusy] = useState(false);
  const [verificationError, setVerificationError] = useState<unknown>(null);
  const verificationIntent = useRef(new SubmissionIntent());
  const challengeIntent = useRef(new SubmissionIntent());
  const proofIntent = useRef(new SubmissionIntent());

  const [grant, setGrant] = useState<Grant | null>(null);
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const [grantBusy, setGrantBusy] = useState(false);
  const [grantError, setGrantError] = useState<unknown>(null);
  const grantIntent = useRef(new SubmissionIntent());
  const approvalIntent = useRef(new SubmissionIntent());
  const revealIntent = useRef(new SubmissionIntent());

  const activeLink = supportCase?.subjectLinks.find((link) => fieldBySubject[link.subjectType] !== null) ?? null;
  const revealField = activeLink ? fieldBySubject[activeLink.subjectType] : null;
  const terminal = supportCase?.state === "RESOLVED" || supportCase?.state === "CLOSED";

  useEffect(() => {
    if (!reveal) return;
    const timeout = window.setTimeout(() => setReveal(null), 60_000);
    return () => window.clearTimeout(timeout);
  }, [reveal]);

  function clearSensitiveState() {
    setProof("");
    setReveal(null);
  }

  async function searchSubjects() {
    const body = {
      criterion: { type: criterionType, value: criterion.trim() },
      subjectTypes: [subjectType],
      reasonCode: "CASE_INTAKE" as const,
    };
    setSearching(true);
    setSearchError(null);
    setSearchResult(null);
    clearSensitiveState();
    try {
      setSearchResult(unwrap(await operationsApi.POST("/support/searches", { body })));
    } catch (error) {
      setSearchError(error);
    } finally {
      setCriterion("");
      setSearching(false);
    }
  }

  async function openCase(caseId: string) {
    const normalized = caseId.trim();
    setCaseLoading(true);
    setCaseError(null);
    setSupportCase(null);
    setTimeline(null);
    setVerification(null);
    setChallenge(null);
    setGrant(null);
    clearSensitiveState();
    try {
      const [caseResponse, timelineResponse] = await Promise.all([
        operationsApi.GET("/support/cases/{caseId}", { params: { path: { caseId: normalized } } }),
        operationsApi.GET("/support/cases/{caseId}/timeline", {
          params: { path: { caseId: normalized }, query: { limit: 50 } },
        }),
      ]);
      setSupportCase(unwrap(caseResponse));
      setTimeline(unwrap(timelineResponse));
      setCaseLookupId(normalized);
    } catch (error) {
      setCaseError(error);
    } finally {
      setCaseLoading(false);
    }
  }

  async function createCaseFor(candidate: Candidate) {
    const body = {
      requesterType: candidate.subjectType === "STORE" ? "STORE_OWNER" as const : candidate.subjectType,
      requesterReference: candidate.subjectId,
      category: caseCategory,
      priority: casePriority,
      reason: "MASKED_EXACT_SEARCH_CASE_INTAKE",
    };
    setCreatingCase(true);
    setCaseError(null);
    try {
      const created = unwrap(await operationsApi.POST("/support/cases", {
        params: { header: { "Idempotency-Key": caseIntent.current.keyFor(JSON.stringify(body)) } },
        body,
      }));
      const linkBody = {
        subjectType: candidate.subjectType === "RIDER" ? "DELIVERY" as const : candidate.subjectType,
        subjectId: candidate.subjectId,
        relationship: "REQUESTER" as const,
        reason: "MASKED_SEARCH_CANDIDATE_SELECTED",
      };
      await operationsApi.POST("/support/cases/{caseId}/subject-links", {
        params: {
          path: { caseId: created.caseId },
          header: { "Idempotency-Key": linkIntent.current.keyFor(JSON.stringify(linkBody)) },
        },
        body: linkBody,
      }).then(unwrap);
      caseIntent.current.complete();
      linkIntent.current.complete();
      await openCase(created.caseId);
    } catch (error) {
      setCaseError(error);
    } finally {
      setCreatingCase(false);
    }
  }

  async function transitionCase() {
    if (!supportCase) return;
    const targetState = nextCaseState[supportCase.state];
    if (!targetState) return;
    const body = {
      targetState,
      expectedVersion: supportCase.version,
      reason: `SUPPORT_WORKSPACE_${supportCase.state}_TO_${targetState}`,
    };
    setTransitioning(true);
    setCaseError(null);
    clearSensitiveState();
    try {
      await operationsApi.POST("/support/cases/{caseId}/status-transitions", {
        params: {
          path: { caseId: supportCase.caseId },
          header: { "Idempotency-Key": transitionIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      }).then(unwrap);
      transitionIntent.current.complete();
      await openCase(supportCase.caseId);
    } catch (error) {
      setCaseError(error);
    } finally {
      setTransitioning(false);
    }
  }

  async function startVerification() {
    if (!supportCase || !activeLink || terminal) return;
    const body = {
      subjectLinkId: activeLink.linkId,
      requestedLevel: "ENHANCED" as const,
      purpose: "CONTACT_CONFIRMATION" as const,
      actionScope: "PERSONAL_DATA_REVEAL" as const,
    };
    setVerificationBusy(true);
    setVerificationError(null);
    setChallenge(null);
    setGrant(null);
    clearSensitiveState();
    try {
      setVerification(unwrap(await operationsApi.POST("/support/cases/{caseId}/verification-sessions", {
        params: {
          path: { caseId: supportCase.caseId },
          header: { "Idempotency-Key": verificationIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      verificationIntent.current.complete();
    } catch (error) {
      setVerificationError(error);
    } finally {
      setVerificationBusy(false);
    }
  }

  async function issueChallenge() {
    if (!verification) return;
    const body = { channel: "REGISTERED_PHONE" as const };
    setVerificationBusy(true);
    setVerificationError(null);
    clearSensitiveState();
    try {
      setChallenge(unwrap(await operationsApi.POST("/support/verification-sessions/{sessionId}/challenges", {
        params: {
          path: { sessionId: verification.sessionId },
          header: { "Idempotency-Key": challengeIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      challengeIntent.current.complete();
    } catch (error) {
      setVerificationError(error);
    } finally {
      setVerificationBusy(false);
    }
  }

  async function verifyProof() {
    if (!challenge || !verification) return;
    const body = { proof };
    setVerificationBusy(true);
    setVerificationError(null);
    try {
      const result = unwrap(await operationsApi.POST("/support/verification-challenges/{challengeId}/verifications", {
        params: {
          path: { challengeId: challenge.challengeId },
          header: { "Idempotency-Key": proofIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      }));
      setChallenge(result.challenge);
      setVerification({
        ...verification,
        state: result.sessionState,
        achievedLevel: result.achievedLevel,
        invalidAttempts: result.invalidAttempts,
        challenges: [result.challenge],
      });
      proofIntent.current.complete();
    } catch (error) {
      setVerificationError(error);
    } finally {
      setProof("");
      setVerificationBusy(false);
    }
  }

  async function requestGrant() {
    if (!supportCase || !verification || !revealField || terminal) return;
    const body = {
      verificationSessionId: verification.sessionId,
      purpose: "CONTACT_CONFIRMATION" as const,
      fields: [revealField],
      reasonCode: "CONTACT_CONFIRMATION" as const,
    };
    setGrantBusy(true);
    setGrantError(null);
    clearSensitiveState();
    try {
      setGrant(unwrap(await operationsApi.POST("/support/cases/{caseId}/data-access-grants", {
        params: {
          path: { caseId: supportCase.caseId },
          header: { "Idempotency-Key": grantIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      grantIntent.current.complete();
    } catch (error) {
      setGrantError(error);
    } finally {
      setGrantBusy(false);
    }
  }

  async function approveGrant() {
    if (!grant) return;
    const body = { decision: "APPROVE" as const, expectedVersion: grant.version, reasonCode: "CASE_HANDLING" as const };
    setGrantBusy(true);
    setGrantError(null);
    clearSensitiveState();
    try {
      setGrant(unwrap(await operationsApi.POST("/support/data-access-grants/{grantId}/approvals", {
        params: {
          path: { grantId: grant.grantId },
          header: { "Idempotency-Key": approvalIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      approvalIntent.current.complete();
    } catch (error) {
      setGrantError(error);
    } finally {
      setGrantBusy(false);
    }
  }

  async function revealPersonalData() {
    if (!grant || !revealField || grant.state !== "ACTIVE") return;
    const body = { fields: [revealField] };
    setGrantBusy(true);
    setGrantError(null);
    setReveal(null);
    try {
      setReveal(unwrap(await operationsApi.POST("/support/data-access-grants/{grantId}/reveals", {
        params: {
          path: { grantId: grant.grantId },
          header: { "Idempotency-Key": revealIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      revealIntent.current.complete();
    } catch (error) {
      setGrantError(error);
    } finally {
      setGrantBusy(false);
    }
  }

  return (
    <div className="console-page support-workspace">
      <PageHeading

        title="고객지원 콘솔"
        description="마스킹 검색부터 Case, 본인확인, 제한형 개인정보 열람, 타임라인과 보상 판단까지 하나의 감사 가능한 흐름으로 처리합니다."
      />

      <section className="support-intake-grid">
        <form className="surface-card operation-form" onSubmit={(event) => { event.preventDefault(); void searchSubjects(); }}>
          <div className="operation-heading"><Search aria-hidden="true" /><div><strong>보호 대상 정확 검색</strong><small>원문은 POST body에만 전송하고 즉시 지웁니다.</small></div></div>
          <label htmlFor="support-criterion-type">검색 기준</label>
          <select id="support-criterion-type" value={criterionType} onChange={(event) => setCriterionType(event.target.value as "PHONE" | "EMAIL")}>
            <option value="PHONE">등록 전화번호</option><option value="EMAIL">등록 이메일</option>
          </select>
          <label htmlFor="support-subject-type">대상 유형</label>
          <select id="support-subject-type" value={subjectType} onChange={(event) => setSubjectType(event.target.value as typeof subjectType)}>
            <option value="CUSTOMER">고객</option><option value="STORE">매장</option><option value="RIDER">외부 배달원</option>
          </select>
          <label htmlFor="support-criterion">전화번호 또는 이메일</label>
          <input id="support-criterion" type={criterionType === "EMAIL" ? "email" : "tel"} value={criterion} required autoComplete="off" onChange={(event) => setCriterion(event.target.value)} />
          <Button type="submit" loading={searching} disabled={!criterion.trim()}><Search size={17} /> 정확 검색</Button>
          {searchError ? <ErrorState error={searchError} /> : null}
        </form>

        <form className="surface-card operation-form" onSubmit={(event) => { event.preventDefault(); void openCase(caseLookupId); }}>
          <div className="operation-heading"><Link2 aria-hidden="true" /><div><strong>기존 Case 열기</strong><small>Case ID는 PII가 아닌 opaque 식별자입니다.</small></div></div>
          <label htmlFor="support-case-id">기존 Case ID</label>
          <input id="support-case-id" value={caseLookupId} required onChange={(event) => setCaseLookupId(event.target.value)} />
          <Button type="submit" variant="secondary" loading={caseLoading}>Case 열기</Button>
          {caseError && !supportCase ? <ErrorState error={caseError} retry={() => void openCase(caseLookupId)} /> : null}
        </form>
      </section>

      {searching ? <LoadingState label="보호 대상을 정확 검색하는 중" /> : null}
      {searchResult ? (
        <section className="surface-card support-search-results" aria-labelledby="support-search-title">
          <div className="panel-heading"><div><span className="context-label">마스킹 검색 결과</span><h2 id="support-search-title">마스킹 후보 {searchResult.matchedCount}건</h2></div>{searchResult.ambiguous ? <StatusText state="AMBIGUOUS" /> : null}</div>
          {searchResult.items.length === 0 ? <EmptyState title="일치하는 대상이 없습니다" description="필수 의존성과 감사 기록이 성공한 빈 결과입니다." /> : (
            <div className="support-candidate-list">
              {searchResult.items.map((candidate) => (
                <article key={`${candidate.subjectType}-${candidate.subjectId}`}>
                  <div><StatusText state={candidate.subjectType} /><strong>{candidate.maskedDisplayName}</strong><span>{candidate.maskedMatchedValue}</span><code>{candidate.subjectId}</code></div>
                  <div className="candidate-case-options">
                    <label>문의 분류<select value={caseCategory} onChange={(event) => setCaseCategory(event.target.value as typeof caseCategory)}><option value="ACCOUNT_RECOVERY">계정 복구</option><option value="PAYMENT_OR_REFUND">결제·환불</option><option value="PRIVACY">개인정보</option><option value="COMPENSATION">보상</option></select></label>
                    <label>우선순위<select value={casePriority} onChange={(event) => setCasePriority(event.target.value as typeof casePriority)}><option value="NORMAL">보통</option><option value="HIGH">높음</option><option value="URGENT">긴급</option></select></label>
                    <Button loading={creatingCase} onClick={() => void createCaseFor(candidate)}><FilePlus2 size={16} /> 새 Case에 연결</Button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      ) : null}

      {caseLoading ? <LoadingState label="Case와 타임라인을 함께 불러오는 중" /> : null}
      {supportCase ? (
        <>
          <section className="surface-card support-case-header">
            <div><span className="context-label">진행 중인 Case</span><h2>CASE {supportCase.caseId}</h2><p>담당자 {compactId(supportCase.assigneeId)} · 버전 {supportCase.version}</p></div>
            <div><StatusText state={supportCase.state} />{nextCaseState[supportCase.state] ? <Button variant="secondary" loading={transitioning} onClick={() => void transitionCase()}>다음 상태: {nextCaseState[supportCase.state]}</Button> : null}</div>
          </section>
          {caseError ? <ErrorState error={caseError} retry={() => void openCase(supportCase.caseId)} /> : null}
          <div className="support-control-grid">
            <section className="surface-card support-access-panel">
              <div className="operation-heading"><ShieldCheck aria-hidden="true" /><div><strong>본인확인과 제한형 열람</strong><small>Verification 성공이 Grant를 자동 부여하지 않습니다.</small></div></div>
              {activeLink ? <p className="support-subject-binding"><StatusText state={activeLink.subjectType} /><code>{activeLink.subjectId}</code><span>{revealField}</span></p> : <EmptyState title="본인확인 가능한 대상이 없습니다" description="고객, 매장 또는 배송 대상을 Case에 연결해 주세요." />}
              {terminal ? <p className="operation-warning">RESOLVED/CLOSED Case에서는 verification, grant와 reveal을 시작할 수 없습니다.</p> : null}
              {activeLink && !verification && !terminal ? <Button block loading={verificationBusy} onClick={() => void startVerification()}>ENHANCED 본인확인 시작</Button> : null}
              {verification ? (
                <div className="support-step-stack">
                  <div className="support-step-summary"><span>Verification</span><StatusText state={verification.state} /><strong>{verification.achievedLevel}</strong><small>만료 {shortDateTime.format(new Date(verification.expiresAt))}</small></div>
                  {!challenge && verification.state === "PENDING" ? <Button variant="secondary" block loading={verificationBusy} onClick={() => void issueChallenge()}>등록 전화로 challenge 발급</Button> : null}
                  {challenge ? <div className="challenge-proof"><p><StatusText state={challenge.state} /> Challenge {compactId(challenge.challengeId)}</p>{challenge.state === "ISSUED" ? <><label htmlFor="support-proof">일회성 proof</label><input id="support-proof" type="password" autoComplete="one-time-code" value={proof} onChange={(event) => setProof(event.target.value)} /><Button block loading={verificationBusy} disabled={!proof} onClick={() => void verifyProof()}>proof 검증</Button></> : null}</div> : null}
                  {verification.achievedLevel === "ENHANCED" && !grant ? <Button block loading={grantBusy} onClick={() => void requestGrant()}>전화번호 Grant 요청</Button> : null}
                </div>
              ) : null}
              {verificationError ? <ErrorState error={verificationError} /> : null}
              {grant ? (
                <div className="support-grant-card">
                  <div><span className="context-label">데이터 접근 승인</span><StatusText state={grant.state} /></div>
                  <code>{grant.grantId}</code><p>{grant.risk} · 사용 {grant.reservedReveals}/{grant.maxReveals}</p>
                  {grant.state === "APPROVAL_PENDING" ? <Button variant="secondary" block loading={grantBusy} onClick={() => void approveGrant()}>별도 승인자로 Grant 승인</Button> : null}
                  {grant.state === "ACTIVE" ? <Button block loading={grantBusy} onClick={() => void revealPersonalData()}><Eye size={16} /> 승인된 전화번호 열람</Button> : null}
                </div>
              ) : null}
              {grantError ? <ErrorState error={grantError} /> : null}
              {reveal ? <RevealPanel reveal={reveal} onClear={() => setReveal(null)} /> : null}
            </section>

            <TimelinePanel timeline={timeline} />
          </div>

          <SupportCompensationPanel caseId={supportCase.caseId} verificationSessionId={verification?.sessionId ?? ""} disabled={terminal} />
        </>
      ) : null}
    </div>
  );
}

function RevealPanel({ reveal, onClear }: { reveal: Reveal; onClear: () => void }) {
  return (
    <section className="support-reveal" aria-labelledby="support-reveal-title">
      <div><EyeOff aria-hidden="true" /><div><strong id="support-reveal-title">60초 뒤 자동 제거되는 원문</strong><small>복사·다운로드 기능과 브라우저 저장을 제공하지 않습니다.</small></div></div>
      {Object.entries(reveal.values).map(([field, value]) => <p key={field}><span>{field}</span><strong>{value}</strong></p>)}
      <Button variant="ghost" block onClick={onClear}>원문 즉시 지우기</Button>
    </section>
  );
}

function TimelinePanel({ timeline }: { timeline: Timeline | null }) {
  return (
    <section className="surface-card support-timeline-panel">
      <div className="operation-heading"><Sparkles aria-hidden="true" /><div><strong>교차 Context 타임라인</strong><small>서버가 마스킹한 owner fact만 시간순으로 표시합니다.</small></div></div>
      {!timeline || timeline.items.length === 0 ? <EmptyState title="표시할 이력이 없습니다" description="빈 결과를 장애 fallback으로 만들지 않습니다." /> : (
        <ol className="support-timeline">
          {timeline.items.map((item) => <li key={item.itemId}><span aria-hidden="true" /><div><small>{item.source} · {item.type}</small><strong>{item.summary}</strong><p><StatusText state={item.state} /> {shortDateTime.format(new Date(item.occurredAt))}{item.amountKrw !== null ? ` · ${won.format(item.amountKrw)}` : ""}</p></div></li>)}
        </ol>
      )}
    </section>
  );
}

function SupportCompensationPanel({ caseId, verificationSessionId, disabled }: { caseId: string; verificationSessionId: string; disabled: boolean }) {
  const [incidentId, setIncidentId] = useState("");
  const [orderId, setOrderId] = useState("");
  const [targetVersion, setTargetVersion] = useState("1");
  const [amountKrw, setAmountKrw] = useState("3000");
  const [evidenceDigest, setEvidenceDigest] = useState("");
  const [evaluation, setEvaluation] = useState<CompensationEvaluation | null>(null);
  const [compensation, setCompensation] = useState<Compensation | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const createIntent = useRef(new SubmissionIntent());

  const baseBody = {
    incidentId: incidentId.trim(),
    orderId: orderId.trim() || null,
    expectedTargetVersion: Number(targetVersion),
    benefitType: "POINT" as const,
    amountKrw: Number(amountKrw),
    couponTemplateId: null,
    responsibility: "PLATFORM" as const,
    evidenceBasis: null,
    costEvidenceDigest: null,
    platformShareBps: 10_000,
    storeShareBps: 0,
    verificationSessionId,
  };

  async function evaluate() {
    setBusy(true); setError(null); setEvaluation(null); setCompensation(null);
    try {
      setEvaluation(unwrap(await operationsApi.POST("/support/cases/{caseId}/compensation-evaluations", {
        params: { path: { caseId } }, body: baseBody,
      })));
    } catch (failure) { setError(failure); } finally { setBusy(false); }
  }

  async function create() {
    const body = { ...baseBody, evidenceDigest: evidenceDigest.trim() };
    setBusy(true); setError(null); setCompensation(null);
    try {
      setCompensation(unwrap(await operationsApi.POST("/support/cases/{caseId}/compensations", {
        params: {
          path: { caseId },
          header: { "Idempotency-Key": createIntent.current.keyFor(JSON.stringify(body)) },
        },
        body,
      })));
      createIntent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") createIntent.current.rotate();
      setError(failure);
    } finally { setBusy(false); }
  }

  return (
    <section className="surface-card support-compensation-panel">
      <div className="operation-heading"><Sparkles aria-hidden="true" /><div><strong>고객 불편 보상</strong><small>평가 결과와 실제 요청 상태를 분리합니다. 요청 접수는 지급 완료가 아닙니다.</small></div></div>
      <div className="support-compensation-grid">
        <label>사고 ID<input value={incidentId} required onChange={(event) => { setIncidentId(event.target.value); setEvaluation(null); }} /></label>
        <label>주문 ID (선택)<input value={orderId} onChange={(event) => { setOrderId(event.target.value); setEvaluation(null); }} /></label>
        <label>대상 버전<input type="number" min="0" value={targetVersion} onChange={(event) => { setTargetVersion(event.target.value); setEvaluation(null); }} /></label>
        <label>포인트 금액<input type="number" min="1" value={amountKrw} onChange={(event) => { setAmountKrw(event.target.value); setEvaluation(null); }} /></label>
      </div>
      {!verificationSessionId ? <p className="operation-warning">보상 평가는 완료된 본인확인 세션이 필요합니다.</p> : null}
      <Button variant="secondary" loading={busy} disabled={disabled || !incidentId.trim() || !verificationSessionId} onClick={() => void evaluate()}>보상 가능 여부 평가</Button>
      {evaluation ? <div className="support-compensation-result"><StatusText state={evaluation.decision} /><strong>{evaluation.band} · {evaluation.approvalRoute}</strong><span>{evaluation.executable ? "현재 평가상 실행 가능" : "승인·조사 또는 추가 조건 필요"}</span><small>{evaluation.reasonCodes.join(", ") || "정책 제한 사유 없음"}</small></div> : null}
      {evaluation && evaluation.decision !== "DENIED" ? <><label htmlFor="support-evidence-digest">증거 SHA-256 digest</label><input id="support-evidence-digest" value={evidenceDigest} pattern="[a-f0-9]{64}" placeholder="원문 대신 소문자 64자리 digest" onChange={(event) => setEvidenceDigest(event.target.value)} /><Button loading={busy} disabled={!/^[a-f0-9]{64}$/.test(evidenceDigest)} onClick={() => void create()}>보상 요청 생성</Button></> : null}
      {compensation ? <div className="support-compensation-result"><StatusText state={compensation.state} /><strong>{won.format(compensation.amountKrw)} 포인트 보상 요청</strong><code>{compensation.compensationRequestId}</code><small>서버 상태가 BENEFIT_ISSUED 또는 NOTIFICATION_ACCEPTED가 되기 전 완료로 표시하지 않습니다.</small></div> : null}
      {error ? <ErrorState error={error} /> : null}
    </section>
  );
}
