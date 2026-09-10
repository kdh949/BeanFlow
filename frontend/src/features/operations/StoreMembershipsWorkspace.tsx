import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";

type Membership = components["schemas"]["ManagedStoreMembership"];
type Account = components["schemas"]["MerchantAccountView"];
/** Membership commands never change an account's credential or password state. */
export function StoreMembershipsWorkspace({ storeId }: { storeId: string }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]); const cursor = cursors.at(-1);
  const list = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/memberships", { params: { path: { storeId }, query: { cursor, limit: 20 } } })), [storeId, cursor]));
  const [selected, setSelected] = useState<string | null>(null); const [adding, setAdding] = useState(false); const [notice, setNotice] = useState("");
  return <section className="management-workspace"><h3>점주·직원 소속</h3>
    <p>계정 인증 상태는 소속과 별개입니다. 소속을 추가해도 비밀번호 미설정·만료 상태의 계정은 로그인할 수 없습니다.</p>
    {notice ? <p role="status">{notice}</p> : null}
    {list.state.status === "loading" ? <LoadingState label="매장 소속을 불러오는 중" /> : list.state.status === "failed" ? <ErrorState error={list.state.error} retry={list.reload} /> : <>
      <Button variant="secondary" onClick={() => { setAdding(true); setSelected(null); setNotice(""); }}>기존 계정에 소속 추가</Button>
      {adding ? <AccountMembershipForm storeId={storeId} onSaved={() => { setAdding(false); setNotice("기존 계정에 매장 소속을 추가했습니다."); list.reload(); }} /> : null}
      {list.state.value.items.length ? <ul className="menu-authoring-list">{list.state.value.items.map(item => <li key={item.membershipId}><div><p className="support-case-reference">{item.accountId}</p><p>{item.role === "OWNER" ? "점주" : "직원"}</p><p>{item.status === "ACTIVE" ? "활성" : "철회됨"}</p></div><Button variant="secondary" aria-label={`${item.accountId} 소속 변경`} onClick={() => { setSelected(item.accountId); setAdding(false); setNotice(""); }}>변경</Button></li>)}</ul> : <EmptyState title="등록된 소속이 없습니다" description="기존 계정을 조회해 점주 또는 직원 소속을 추가할 수 있습니다." />}
      <div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>이전 소속 목록</Button><Button variant="secondary" disabled={!list.state.value.nextCursor} onClick={() => { if (list.state.status === "ready" && list.state.value.nextCursor) { const next = list.state.value.nextCursor; setCursors(value => [...value, next]); } }}>다음 소속 목록</Button></div>
    </>}
    {selected ? <MembershipEditor key={selected} storeId={storeId} accountId={selected} onSaved={() => { setSelected(null); setNotice("소속을 변경했습니다."); list.reload(); }} /> : null}
  </section>;
}
function MembershipEditor({ storeId, accountId, onSaved }: { storeId: string; accountId: string; onSaved: () => void }) {
  const current = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/stores/{storeId}/memberships/{accountId}", { params: { path: { storeId, accountId } } })), [storeId, accountId]));
  if (current.state.status === "loading") return <LoadingState label="현재 소속을 확인하는 중" />;
  if (current.state.status === "failed") return <ErrorState error={current.state.error} retry={current.reload} />;
  return <MembershipForm key={current.state.value.version} current={current.state.value} storeId={storeId} accountId={accountId} onSaved={onSaved} onRefresh={current.reload} />;
}
function AccountMembershipForm({ storeId, onSaved }: { storeId: string; onSaved: () => void }) {
  const [method, setMethod] = useState("lookup"); const [knownId, setKnownId] = useState("");
  const [loginId, setLoginId] = useState(""); const [reason, setReason] = useState(""); const [account, setAccount] = useState<Account | null>(null);
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  async function lookup() {
    if (busy) return; setBusy(true); setFailure(null); setAccount(null);
    try { setAccount(unwrap(await operationsApi.GET("/operations/merchant-accounts", { params: { query: { loginId: loginId.trim() }, header: { "X-Access-Reason": reason.trim() } } }))); }
    catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  return <section className="surface-card management-card"><h4>소속을 추가할 기존 계정</h4>
    <SelectField label="소속 대상 선택 방식" value={method} disabled={busy} onValueChange={value => { setMethod(value); setAccount(null); setFailure(null); }}><option value="lookup">로그인 ID로 계정 조회</option><option value="id">확인한 계정 ID 입력</option></SelectField>
    {method === "lookup" ? <>
    <form onSubmit={event => { event.preventDefault(); void lookup(); }}><fieldset disabled={busy} className="catalog-fieldset"><TextField label="추가할 계정 로그인 ID" value={loginId} onValueChange={value => { setLoginId(value); setAccount(null); }} required maxLength={64} /><SelectField label="계정 조회 사유" value={reason} onValueChange={setReason}><option value="">조회 목적 선택</option><option value="STORE_MEMBERSHIP_ASSIGNMENT_REVIEW">매장 소속 부여 전 계정 확인</option></SelectField><Button type="submit" loading={busy} disabled={!loginId.trim() || !reason.trim()}>소속 추가 대상 조회</Button></fieldset></form>
    {failure ? <ErrorState error={failure} /> : null}
    {account ? <><p>{account.displayName} · {account.loginId}</p><p className="support-case-reference">{account.merchantAccountId}</p><MembershipForm key={account.merchantAccountId} storeId={storeId} accountId={account.merchantAccountId} onSaved={onSaved} /></> : null}
    </> : <><TextField label="기존 계정 ID" value={knownId} onValueChange={setKnownId} maxLength={36} description="계정 조회 권한이 없다면 업무에서 확인한 기존 계정 ID를 입력할 수 있습니다." />{/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(knownId) ? <MembershipForm key={knownId} storeId={storeId} accountId={knownId} onSaved={onSaved} /> : null}</>}
  </section>;
}
function MembershipForm({ current, storeId, accountId, onSaved, onRefresh }: { current?: Membership; storeId: string; accountId: string; onSaved: () => void; onRefresh?: () => void }) {
  const [role, setRole] = useState<Membership["role"]>(current?.role ?? "STAFF"); const [status, setStatus] = useState<Membership["status"]>(current?.status ?? "ACTIVE"); const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<unknown>(null); const intent = useRef(new SubmissionIntent());
  const nextRole = current && status === "REVOKED" ? current.role : role;
  const unchanged = !!current && current.role === nextRole && current.status === status;
  async function save() {
    if (busy || unchanged) return;
    const body = current ? { role: nextRole, status, expectedVersion: current.version, reason: reason.trim() } : { accountId, role, reason: reason.trim() };
    const header = { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ storeId, accountId, ...body })) };
    setBusy(true); setFailure(null);
    try {
      if (current) unwrap(await operationsApi.PUT("/operations/stores/{storeId}/memberships/{accountId}", { params: { path: { storeId, accountId }, header }, body: { role: nextRole, status, expectedVersion: current.version, reason: reason.trim() } }));
      else unwrap(await operationsApi.POST("/operations/stores/{storeId}/memberships", { params: { path: { storeId }, header }, body: { accountId, role, reason: reason.trim() } }));
      intent.current.complete(); onSaved();
    } catch (error) { setFailure(error); } finally { setBusy(false); }
  }
  return <form className="surface-card management-card" onSubmit={event => { event.preventDefault(); void save(); }}><fieldset className="catalog-fieldset" disabled={busy}><legend>{current ? "소속 변경" : "소속 추가"}</legend>
    {current ? <SelectField label="소속 상태" value={status} onValueChange={value => setStatus(value as Membership["status"])}><option value="ACTIVE">활성</option><option value="REVOKED">철회</option></SelectField> : null}
    <SelectField label="매장 역할" value={nextRole} disabled={status === "REVOKED"} onValueChange={value => setRole(value as Membership["role"])}><option value="OWNER">점주</option><option value="STAFF">직원</option></SelectField>
    {status === "REVOKED" ? <p>철회 후 이 계정의 새로운 매장 접근은 거절됩니다. 마지막 점주를 철회해도 다른 계정으로 권한을 자동 이전하지 않습니다.</p> : null}
    <TextAreaField label={current ? "소속 변경 사유" : "소속 추가 사유"} value={reason} onValueChange={setReason} maxLength={500} required />
    {failure ? <ErrorState error={failure} /> : null}
    <div className="button-row"><Button type="submit" loading={busy} disabled={!reason.trim() || unchanged}>{current ? "소속 변경 저장" : "매장 소속 추가"}</Button>{onRefresh ? <Button type="button" variant="secondary" onClick={onRefresh}>현재 소속 다시 읽기</Button> : null}</div>
  </fieldset></form>;
}
