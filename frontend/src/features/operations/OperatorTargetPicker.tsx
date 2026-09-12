import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, SearchField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
export type OperatorSelection = components["schemas"]["OperatorCandidate"];
/** Selects an observed organization login with current purpose-specific grants; commands still authorize independently. */
export function OperatorTargetPicker({ label, purpose, value, onSelect, disabled = false }: { label: string; purpose: components["schemas"]["OperatorSelectionPurpose"]; value: OperatorSelection | null; onSelect: (value: OperatorSelection | null) => void; disabled?: boolean }) {
  const [input, setInput] = useState("");
  const [query, setQuery] = useState<{ query?: string; cursor?: string }>({});
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/operator-directory", { params: { query: { ...query, purpose, limit: 20 } } })), [purpose, query]));
  function search() { if (!disabled) setQuery({ query: input.trim() || undefined }); }
  return <fieldset className="catalog-fieldset" disabled={disabled}><legend>{label}</legend>
    <div className="button-row"><SearchField label={`${label} 검색`} value={input} onChange={event => setInput(event.target.value)} placeholder="조직 로그인 이름" maxLength={100} onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); search(); } }} /><Button type="button" variant="secondary" onClick={search}>담당자 검색</Button></div>
    {value ? <div className="button-row"><p>선택한 담당자: {value.loginName}</p><Button type="button" variant="ghost" onClick={() => onSelect(null)}>담당자 선택 해제</Button></div> : null}
    {resource.state.status === "loading" ? <LoadingState label="담당자를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>
      {resource.state.value.missingProfileCount > 0 ? <InlineNotice title={`이름 연결이 필요한 계정 ${resource.state.value.missingProfileCount}개`} description="해당 담당자가 조직 계정으로 로그인하면 이름이 연결됩니다. 로그인 후에도 보이지 않으면 관리자에게 계정 연결 확인을 요청해 주세요." /> : null}
      {resource.state.value.items.length === 0 ? <EmptyState title="조건에 맞는 담당자가 없습니다" description="조직 로그인 이름과 필요한 업무 권한을 확인해 주세요." /> : <div className="management-card-grid">{resource.state.value.items.map(item => <article className="surface-card management-card" key={item.operatorId}><p><strong>{item.loginName}</strong></p><p>로그인 정보 확인 {fullDateTime.format(new Date(item.observedAt))}</p><Button type="button" variant="secondary" aria-pressed={value?.operatorId === item.operatorId} onClick={() => onSelect(item)}>{item.loginName} 담당자 선택</Button></article>)}</div>}
      <div className="button-row">{query.cursor ? <Button type="button" variant="ghost" onClick={() => setQuery({ query: query.query })}>담당자 처음으로</Button> : null}{resource.state.value.nextCursor ? <Button type="button" variant="secondary" onClick={() => { if (resource.state.status === "ready") setQuery({ ...query, cursor: resource.state.value.nextCursor ?? undefined }); }}>다음 담당자</Button> : null}</div>
    </>}
  </fieldset>;
}
