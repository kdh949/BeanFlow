import { useSupportCommand } from "./useSupportCommand";
import { supportCaseTitle } from "./supportCaseLabels";
import {
  FilePlus2,
  Link2,
  Search,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { caseCategoryLabels, casePriorityLabels } from "./supportCaseLabels";
import { SupportCompensationWorkspace } from "./SupportCompensationWorkspace";
import { SupportVerificationPanel } from "./SupportVerificationPanel";
import { SupportDataAccessWorkspace } from "./SupportDataAccessWorkspace";
import { SupportTimelinePanel } from "./SupportTimelinePanel";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";

type SearchResult = components["schemas"]["SupportSubjectSearchResult"];
type Candidate = components["schemas"]["SupportSubjectSearchCandidate"];
type SupportCase = components["schemas"]["SupportCase"];
type VerificationSession = components["schemas"]["VerificationSessionResource"];
type Timeline = components["schemas"]["SupportTimelinePage"];

/**
 * One bounded Support workspace: exact masked search, Case binding, staged
 * verification, purpose-bound reveal, owner timeline and compensation entry.
 * Raw search criteria, challenge proofs and reveals never enter URL or storage.
 */
export function SupportWorkspacePage() {
  const [params] = useSearchParams();
  const initialCaseId = params.get("caseId")?.trim() ?? "";
  return <SupportWorkspace key={initialCaseId} initialCaseId={initialCaseId} />;
}

function SupportWorkspace({ initialCaseId }: { initialCaseId: string }) {
  const [criterionType, setCriterionType] = useState<"PHONE" | "EMAIL">("PHONE");
  const [criterion, setCriterion] = useState("");
  const [subjectType, setSubjectType] = useState<"CUSTOMER" | "STORE" | "RIDER">("CUSTOMER");
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<unknown>(null);
  const [caseCategory, setCaseCategory] = useState<components["schemas"]["SupportInquiryCategory"]>("ACCOUNT_RECOVERY");
  const [casePriority, setCasePriority] = useState<components["schemas"]["SupportCasePriority"]>("NORMAL");
  const linkIntent = useRef(new SubmissionIntent());

  const caseGeneration = useRef(0);
  useEffect(() => {
    if (initialCaseId) void openCase(initialCaseId);
    return () => { caseGeneration.current += 1; };
  }, [initialCaseId]);
  const [supportCase, setSupportCase] = useState<SupportCase | null>(null);
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [caseLoading, setCaseLoading] = useState(false);
  const [caseError, setCaseError] = useState<unknown>(null);
  const caseCommand = useSupportCommand(() => undefined);
  const creatingCase = caseCommand.busy;
  const [dataBusy, setDataBusy] = useState(false);
  const [compensationBusy, setCompensationBusy] = useState(false);
  const [verificationBusy, setVerificationBusy] = useState(false);
  const workLocked = dataBusy || compensationBusy || verificationBusy || caseCommand.busy || caseCommand.pending;

  const [verification, setVerification] = useState<VerificationSession | null>(null);
  const [securityGeneration, setSecurityGeneration] = useState(0);
  const terminal = supportCase?.state === "RESOLVED" || supportCase?.state === "CLOSED";
  function clearSensitiveState() { setVerification(null); setSecurityGeneration(value => value + 1); }

  async function searchSubjects() {
    if (workLocked || searching) return;
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
    if (dataBusy || compensationBusy || verificationBusy) return;
    const normalized = caseId.trim();
    if (!normalized) return;
    const generation = ++caseGeneration.current;
    setCaseLoading(true);
    setCaseError(null);
    setSupportCase(null);
    setTimeline(null);
    setVerification(null);
    clearSensitiveState();
    try {
      const [caseResponse, timelineResponse] = await Promise.all([
        operationsApi.GET("/support/cases/{caseId}", { params: { path: { caseId: normalized } } }),
        operationsApi.GET("/support/cases/{caseId}/timeline", {
          params: { path: { caseId: normalized }, query: { limit: 50 } },
        }),
      ]);
      const loadedCase = unwrap(caseResponse);
      const loadedTimeline = unwrap(timelineResponse);
      if (generation !== caseGeneration.current) return;
      setSupportCase(loadedCase);
      setTimeline(loadedTimeline);
    } catch (error) {
      if (generation === caseGeneration.current) setCaseError(error);
    } finally {
      if (generation === caseGeneration.current) setCaseLoading(false);
    }
  }

  function createCaseFor(candidate: Candidate) {
    if (workLocked) return;
    const body = {
      requesterType: candidate.subjectType === "STORE" ? "STORE_OWNER" as const : candidate.subjectType,
      requesterReference: candidate.subjectId,
      category: caseCategory,
      priority: casePriority,
      reason: "MASKED_EXACT_SEARCH_CASE_INTAKE",
    };
    const linkBody = {
      subjectType: candidate.subjectType === "RIDER" ? "DELIVERY" as const : candidate.subjectType,
      subjectId: candidate.subjectId,
      relationship: "REQUESTER" as const,
      reason: "MASKED_SEARCH_CANDIDATE_SELECTED",
    };
    setCaseError(null);
    caseCommand.submit(JSON.stringify({ body, linkBody }), async key => {
      let createdCaseId: string | undefined;
      try {
        const created = unwrap(await operationsApi.POST("/support/cases", {
          params: { header: { "Idempotency-Key": key } }, body,
        }));
        createdCaseId = created.caseId;
        await operationsApi.POST("/support/cases/{caseId}/subject-links", {
          params: { path: { caseId: created.caseId }, header: { "Idempotency-Key": linkIntent.current.keyFor(JSON.stringify({ caseId: created.caseId, linkBody })) } },
          body: linkBody,
        }).then(unwrap);
        await openCase(created.caseId);
      } catch (error) {
        if (createdCaseId) await openCase(createdCaseId);
        throw error;
      }
    }, () => linkIntent.current.complete());
  }

  return (
    <div className="console-page support-workspace">
      {caseCommand.failure ? <ErrorState error={caseCommand.failure} /> : null}
      {caseCommand.pending ? <InlineNotice tone="warning" title="상담 접수 또는 대상 연결 결과를 확인하지 못했습니다" description="같은 대상과 접수 내용으로 결과를 확인해 주세요." action={<Button loading={creatingCase} onClick={() => void caseCommand.retry()}>같은 상담 접수 결과 확인</Button>} /> : null}
      <PageHeading title="고객지원 콘솔" action={<ButtonLink variant="secondary" to="/support/cases">상담 목록</ButtonLink>} />
      {supportCase ? <>
          <section className="surface-card support-case-header">
            <div><span className="context-label">현재 상담 건</span><h2>{supportCaseTitle(supportCase)}</h2><p>접수 {shortDateTime.format(new Date(supportCase.openedAt))}</p><p>담당자 {supportCase.assigneeDisplay?.state === "AVAILABLE" ? supportCase.assigneeDisplay.loginName : "조직 로그인 이름 미등록"} · 버전 {supportCase.version}</p></div>
            <div><StatusText state={supportCase.state} /><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${encodeURIComponent(supportCase.caseId)}`}>상담 후속 업무</ButtonLink><ButtonLink variant="secondary" to={`/support/cases/${encodeURIComponent(supportCase.caseId)}`}>상담 상태·담당자 관리</ButtonLink></div>
          </section>
      </> : null}

      <section className="support-intake-grid">
        <form className="surface-card operation-form" onSubmit={(event) => { event.preventDefault(); void searchSubjects(); }}>
          <div className="operation-heading"><Search aria-hidden="true" /><div><strong>고객 정보 정확 검색</strong></div></div>
          <SelectField disabled={workLocked} label="검색 기준" id="support-criterion-type" value={criterionType} onValueChange={(value) => setCriterionType(value as "PHONE" | "EMAIL")}>
            <option value="PHONE">등록 전화번호</option><option value="EMAIL">등록 이메일</option>
          </SelectField>
          <SelectField disabled={workLocked} label="대상 유형" id="support-subject-type" value={subjectType} onValueChange={(value) => setSubjectType(value as typeof subjectType)}>
            <option value="CUSTOMER">고객</option><option value="STORE">매장</option><option value="RIDER">외부 배달원</option>
          </SelectField>
          <TextField disabled={workLocked} label="전화번호 또는 이메일" id="support-criterion" type={criterionType === "EMAIL" ? "email" : "tel"} value={criterion} required autoComplete="off" onValueChange={setCriterion} />
          <Button type="submit" loading={searching} disabled={workLocked || !criterion.trim()}><Search size={17} /> 정확 검색</Button>
          {searchError ? <ErrorState error={searchError} /> : null}
        </form>

        <section className="surface-card operation-form">
          <div className="operation-heading"><Link2 aria-hidden="true" /><strong>기존 상담 찾기</strong></div>
          <p>상담 목록에서 문의 분류, 담당자, 상태와 접수 시각을 확인하고 선택합니다.</p>
          <ButtonLink variant="secondary" to="/support/cases">상담 목록에서 선택</ButtonLink>
          {caseError && !supportCase ? <ErrorState error={caseError} retry={initialCaseId ? () => void openCase(initialCaseId) : undefined} /> : null}
        </section>
      </section>

      {searching ? <LoadingState label="보호 대상을 정확 검색하는 중" /> : null}
      {searchResult ? (
        <section className="surface-card support-search-results" aria-labelledby="support-search-title">
          <div className="panel-heading"><div><span className="context-label">마스킹 검색 결과</span><h2 id="support-search-title">마스킹 후보 {searchResult.matchedCount}건</h2></div>{searchResult.ambiguous ? <StatusText state="AMBIGUOUS" /> : null}</div>
          {searchResult.items.length === 0 ? <EmptyState title="일치하는 대상이 없습니다" description="전화번호나 이메일을 확인해 다시 검색해 주세요." /> : (
            <div className="support-candidate-list">
              {searchResult.items.map((candidate) => (
                <article key={`${candidate.subjectType}-${candidate.subjectId}`}>
                  <div><StatusText state={candidate.subjectType} /><strong>{candidate.maskedDisplayName}</strong><span>{candidate.maskedMatchedValue}</span></div>
                  <div className="candidate-case-options">
                    <SelectField disabled={workLocked} label="문의 분류" value={caseCategory} onValueChange={(value) => setCaseCategory(value as typeof caseCategory)}>{Object.entries(caseCategoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
                    <SelectField disabled={workLocked} label="우선순위" value={casePriority} onValueChange={(value) => setCasePriority(value as typeof casePriority)}>{Object.entries(casePriorityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
                    <Button loading={creatingCase} disabled={workLocked} onClick={() => createCaseFor(candidate)}><FilePlus2 size={16} /> 새 상담 건에 연결</Button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      ) : null}

      {caseLoading ? <LoadingState label="상담 건과 관련 이력을 불러오는 중" /> : null}
      {supportCase ? (
        <>
          {caseError ? <ErrorState error={caseError} retry={() => void openCase(supportCase.caseId)} /> : null}
          <fieldset className="catalog-fieldset management-workspace" disabled={caseCommand.busy || caseCommand.pending}><legend>현재 상담 처리</legend>
          <div className="support-control-grid">
            <div className="management-workspace">
              <SupportVerificationPanel key={`${supportCase.caseId}:${securityGeneration}`} caseId={supportCase.caseId} links={supportCase.subjectLinks} disabled={terminal} locked={dataBusy || compensationBusy || caseCommand.busy || caseCommand.pending} onBusyChange={setVerificationBusy} onChange={setVerification} />
              {!terminal ? <SupportDataAccessWorkspace key={`${supportCase.caseId}:${securityGeneration}`} session={verification} onBusyChange={setDataBusy} /> : null}
            </div>

            <SupportTimelinePanel timeline={timeline} />
            {timeline?.nextCursor ? <ButtonLink variant="secondary" to={`/support/follow-up?caseId=${encodeURIComponent(supportCase.caseId)}`}>이력 더 보기</ButtonLink> : null}
          </div>

          <SupportCompensationWorkspace key={`${supportCase.caseId}:${securityGeneration}`} supportCase={supportCase} verification={verification} onBusyChange={setCompensationBusy} />
          </fieldset>
        </>
      ) : null}
    </div>
  );
}
