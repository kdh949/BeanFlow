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
import { Button, ButtonLink, EmptyState, LoadingState, PageHeading, SelectField, TextField } from "../../design-system";
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
  const caseIntent = useRef(new SubmissionIntent());
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
  const [creatingCase, setCreatingCase] = useState(false);

  const [verification, setVerification] = useState<VerificationSession | null>(null);
  const [securityGeneration, setSecurityGeneration] = useState(0);
  const terminal = supportCase?.state === "RESOLVED" || supportCase?.state === "CLOSED";
  function clearSensitiveState() { setVerification(null); setSecurityGeneration(value => value + 1); }

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

  async function createCaseFor(candidate: Candidate) {
    const body = {
      requesterType: candidate.subjectType === "STORE" ? "STORE_OWNER" as const : candidate.subjectType,
      requesterReference: candidate.subjectId,
      category: caseCategory,
      priority: casePriority,
      reason: "MASKED_EXACT_SEARCH_CASE_INTAKE",
    };
    let createdCaseId: string | undefined;
    setCreatingCase(true);
    setCaseError(null);
    try {
      const created = unwrap(await operationsApi.POST("/support/cases", {
        params: { header: { "Idempotency-Key": caseIntent.current.keyFor(JSON.stringify(body)) } },
        body,
      }));
      createdCaseId = created.caseId;
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
      if (createdCaseId) await openCase(createdCaseId);
      setCaseError(error);
    } finally {
      setCreatingCase(false);
    }
  }

  return (
    <div className="console-page support-workspace">
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
          <SelectField label="검색 기준" id="support-criterion-type" value={criterionType} onValueChange={(value) => setCriterionType(value as "PHONE" | "EMAIL")}>
            <option value="PHONE">등록 전화번호</option><option value="EMAIL">등록 이메일</option>
          </SelectField>
          <SelectField label="대상 유형" id="support-subject-type" value={subjectType} onValueChange={(value) => setSubjectType(value as typeof subjectType)}>
            <option value="CUSTOMER">고객</option><option value="STORE">매장</option><option value="RIDER">외부 배달원</option>
          </SelectField>
          <TextField label="전화번호 또는 이메일" id="support-criterion" type={criterionType === "EMAIL" ? "email" : "tel"} value={criterion} required autoComplete="off" onValueChange={setCriterion} />
          <Button type="submit" loading={searching} disabled={!criterion.trim()}><Search size={17} /> 정확 검색</Button>
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
                    <SelectField label="문의 분류" value={caseCategory} onValueChange={(value) => setCaseCategory(value as typeof caseCategory)}>{Object.entries(caseCategoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
                    <SelectField label="우선순위" value={casePriority} onValueChange={(value) => setCasePriority(value as typeof casePriority)}>{Object.entries(casePriorityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
                    <Button loading={creatingCase} onClick={() => void createCaseFor(candidate)}><FilePlus2 size={16} /> 새 상담 건에 연결</Button>
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
          <div className="support-control-grid">
            <div className="management-workspace">
              <SupportVerificationPanel key={`${supportCase.caseId}:${securityGeneration}`} caseId={supportCase.caseId} links={supportCase.subjectLinks} disabled={terminal} onChange={setVerification} />
              {!terminal ? <SupportDataAccessWorkspace key={verification?.sessionId ?? "unverified"} session={verification} /> : null}
            </div>

            <SupportTimelinePanel timeline={timeline} />
            {timeline?.nextCursor ? <ButtonLink variant="secondary" to={`/support/follow-up?caseId=${encodeURIComponent(supportCase.caseId)}`}>이력 더 보기</ButtonLink> : null}
          </div>

          <SupportCompensationWorkspace key={`${supportCase.caseId}:${securityGeneration}`} supportCase={supportCase} verification={verification} />
        </>
      ) : null}
    </div>
  );
}
