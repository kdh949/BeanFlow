import { KeyRound, LockKeyholeOpen, Search, ShieldCheck, UserPlus } from "lucide-react";
import { useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button } from "../../design-system";
import { shortDateTime } from "../../lib/format";

type MerchantAccount = components["schemas"]["MerchantAccountView"];
type OneTimePassword = components["schemas"]["MerchantTemporaryPasswordResult"] | components["schemas"]["MerchantAccountCreationResult"];

/**
 * Exact account administration workspace. Reads are audited and every command
 * owns a separate submit intent; one-time passwords never leave route memory.
 */
export function MerchantAccountsPage() {
  const [mode, setMode] = useState<"lookup" | "create">("lookup");
  const [loginId, setLoginId] = useState("");
  const [accessReason, setAccessReason] = useState("");
  const [account, setAccount] = useState<MerchantAccount | null>(null);
  const [lookupError, setLookupError] = useState<unknown>(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [resetReason, setResetReason] = useState("");
  const [unlockReason, setUnlockReason] = useState("");
  const [passwordResult, setPasswordResult] = useState<OneTimePassword | null>(null);
  const [resetError, setResetError] = useState<unknown>(null);
  const [unlockError, setUnlockError] = useState<unknown>(null);
  const [resetting, setResetting] = useState(false);
  const [unlocking, setUnlocking] = useState(false);
  const [unlocked, setUnlocked] = useState(false);
  const resetIntent = useRef(new SubmissionIntent());
  const unlockIntent = useRef(new SubmissionIntent());

  const [newLoginId, setNewLoginId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [storeId, setStoreId] = useState("");
  const [membershipRole, setMembershipRole] = useState<"OWNER" | "STAFF">("OWNER");
  const [createReason, setCreateReason] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const createIntent = useRef(new SubmissionIntent());

  function clearSensitiveResult() {
    setPasswordResult(null);
  }

  async function lookup() {
    const normalizedLoginId = loginId.trim();
    setLookingUp(true);
    setLookupError(null);
    setAccount(null);
    clearSensitiveResult();
    setUnlocked(false);
    try {
      setAccount(unwrap(await operationsApi.GET("/operations/merchant-accounts", {
        params: {
          query: { loginId: normalizedLoginId },
          header: { "X-Access-Reason": accessReason.trim() },
        },
      })));
    } catch (error) {
      setLookupError(error);
    } finally {
      setLookingUp(false);
    }
  }

  async function resetTemporaryPassword() {
    if (!account || !resetReason.trim()) return;
    const body = { reason: resetReason.trim() };
    const fingerprint = JSON.stringify({ merchantAccountId: account.merchantAccountId, ...body });
    setResetting(true);
    setResetError(null);
    clearSensitiveResult();
    try {
      const result = unwrap(await operationsApi.POST(
        "/operations/merchant-accounts/{merchantAccountId}/temporary-password-resets",
        {
          params: {
            path: { merchantAccountId: account.merchantAccountId },
            header: { "Idempotency-Key": resetIntent.current.keyFor(fingerprint) },
          },
          body,
        },
      ));
      setPasswordResult(result);
      setAccount((current) => current ? {
        ...current,
        accountState: "INITIAL_PASSWORD",
        lockedUntil: undefined,
        temporaryPasswordExpiresAt: result.temporaryPasswordExpiresAt,
      } : current);
      resetIntent.current.complete();
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") resetIntent.current.rotate();
      setResetError(error);
    } finally {
      setResetting(false);
    }
  }

  async function releaseLock() {
    if (!account || !unlockReason.trim()) return;
    const body = { reason: unlockReason.trim() };
    const fingerprint = JSON.stringify({ merchantAccountId: account.merchantAccountId, ...body });
    setUnlocking(true);
    setUnlockError(null);
    setUnlocked(false);
    try {
      const response = await operationsApi.POST(
        "/operations/merchant-accounts/{merchantAccountId}/lock-releases",
        {
          params: {
            path: { merchantAccountId: account.merchantAccountId },
            header: { "Idempotency-Key": unlockIntent.current.keyFor(fingerprint) },
          },
          body,
        },
      );
      if (!response.response.ok) unwrap(response);
      setUnlocked(true);
      setAccount((current) => current ? { ...current, lockedUntil: undefined } : current);
      unlockIntent.current.complete();
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") unlockIntent.current.rotate();
      setUnlockError(error);
    } finally {
      setUnlocking(false);
    }
  }

  async function createAccount() {
    const body = {
      loginId: newLoginId.trim(),
      displayName: displayName.trim(),
      storeId: storeId.trim(),
      membershipRole,
      reason: createReason.trim(),
    };
    const fingerprint = JSON.stringify(body);
    setCreating(true);
    setCreateError(null);
    clearSensitiveResult();
    try {
      const result = unwrap(await operationsApi.POST("/operations/merchant-accounts", {
        params: { header: { "Idempotency-Key": createIntent.current.keyFor(fingerprint) } },
        body,
      }));
      setPasswordResult(result);
      createIntent.current.complete();
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") createIntent.current.rotate();
      setCreateError(error);
    } finally {
      setCreating(false);
    }
  }

  const notFound = lookupError instanceof ApiRequestError && lookupError.status === 404;

  return (
    <div className="console-page">
      <PageTitle
        eyebrow="MERCHANT CREDENTIAL"
        title="점주 계정 관리"
        description="로그인 ID exact 조회, 최초 계정 발급, 임시 비밀번호 재발급과 로그인 잠금 해제를 처리합니다."
      />
      <div className="filter-row" role="group" aria-label="점주 계정 업무 선택">
        <button type="button" className={mode === "lookup" ? "is-active" : ""} aria-pressed={mode === "lookup"} onClick={() => { setMode("lookup"); clearSensitiveResult(); }}>
          계정 조회·복구
        </button>
        <button type="button" className={mode === "create" ? "is-active" : ""} aria-pressed={mode === "create"} onClick={() => { setMode("create"); clearSensitiveResult(); }}>
          새 계정 발급
        </button>
      </div>

      {mode === "lookup" ? (
        <>
          <form className="surface-card merchant-account-lookup" onSubmit={(event) => { event.preventDefault(); void lookup(); }}>
            <div className="lookup-fields">
              <label htmlFor="merchant-login-id">점주 로그인 ID</label>
              <input id="merchant-login-id" value={loginId} minLength={5} maxLength={32} required onChange={(event) => setLoginId(event.target.value)} />
              <label htmlFor="merchant-access-reason">조회 사유</label>
              <select id="merchant-access-reason" value={accessReason} required onChange={(event) => setAccessReason(event.target.value)}>
                <option value="">업무 사유 선택</option>
                <option value="MERCHANT_ACCOUNT_RECOVERY">계정 복구 요청 확인</option>
                <option value="MERCHANT_ACCOUNT_EXISTENCE_CHECK">계정 유무 확인</option>
                <option value="MERCHANT_ACCOUNT_STATUS_REVIEW">계정 상태 확인</option>
              </select>
            </div>
            <Button type="submit" loading={lookingUp}><Search size={17} /> {lookingUp ? "조회 중" : "계정 조회"}</Button>
          </form>
          {lookingUp ? <LoadingState label="점주 계정을 조회하는 중" /> : null}
          {notFound ? <EmptyState title="일치하는 점주 계정이 없습니다" description="부분 검색은 제공하지 않습니다. canonical 로그인 ID를 확인해 주세요." /> : null}
          {lookupError && !notFound ? <ErrorState error={lookupError} retry={() => void lookup()} /> : null}
          {account ? (
            <div className="console-detail-grid merchant-account-workspace">
              <section className="surface-card order-panel merchant-account-summary">
                <div className="panel-heading">
                  <div><span className="eyebrow">EXACT ACCOUNT</span><h2>{account.displayName}</h2></div>
                  <StatusBadge state={account.accountState} />
                </div>
                <dl className="detail-list">
                  <div><dt>로그인 ID</dt><dd>{account.loginId}</dd></div>
                  <div><dt>로그인 잠금</dt><dd>{account.lockedUntil ? shortDateTime.format(new Date(account.lockedUntil)) : "잠금 없음"}</dd></div>
                  <div><dt>임시 비밀번호 만료</dt><dd>{account.temporaryPasswordExpiresAt ? shortDateTime.format(new Date(account.temporaryPasswordExpiresAt)) : "해당 없음"}</dd></div>
                </dl>
                <div className="merchant-memberships">
                  <h3>매장 권한</h3>
                  {account.memberships.map((membership) => (
                    <div key={`${membership.storeId}-${membership.role}`}><code>{membership.storeId}</code><StatusBadge state={membership.role} /></div>
                  ))}
                </div>
              </section>
              <aside className="merchant-credential-actions">
                <section className="surface-card action-panel">
                  <div className="operation-heading"><KeyRound aria-hidden="true" /><div><strong>임시 비밀번호 재발급</strong><small>성공 응답에서 한 번만 표시</small></div></div>
                  <label htmlFor="reset-password-reason">임시 비밀번호 재발급 사유</label>
                  <textarea id="reset-password-reason" value={resetReason} maxLength={200} required onChange={(event) => { setResetReason(event.target.value); setResetError(null); clearSensitiveResult(); resetIntent.current.rotate(); }} />
                  <Button block loading={resetting} disabled={!resetReason.trim()} onClick={() => void resetTemporaryPassword()}>
                    {resetting ? "재발급 중" : "임시 비밀번호 재발급"}
                  </Button>
                  {resetError ? <ErrorState error={resetError} /> : null}
                </section>
                <section className="surface-card action-panel">
                  <div className="operation-heading"><LockKeyholeOpen aria-hidden="true" /><div><strong>로그인 잠금 해제</strong><small>계정 잠금과 로그인 시도 차단을 함께 해제</small></div></div>
                  <label htmlFor="unlock-reason">잠금 해제 사유</label>
                  <textarea id="unlock-reason" value={unlockReason} maxLength={200} required onChange={(event) => { setUnlockReason(event.target.value); setUnlockError(null); setUnlocked(false); unlockIntent.current.rotate(); }} />
                  <Button variant="secondary" block loading={unlocking} disabled={!unlockReason.trim()} onClick={() => void releaseLock()}>
                    {unlocking ? "해제 중" : "로그인 잠금 해제"}
                  </Button>
                  {unlockError ? <ErrorState error={unlockError} /> : null}
                  {unlocked ? <p className="operation-success" role="status">로그인 잠금을 해제했습니다</p> : null}
                </section>
              </aside>
            </div>
          ) : null}
        </>
      ) : (
        <form className="surface-card operation-form merchant-account-create" onSubmit={(event) => { event.preventDefault(); void createAccount(); }}>
          <div className="operation-heading"><UserPlus aria-hidden="true" /><div><strong>점주 계정과 첫 매장 권한</strong><small>서버가 한 트랜잭션에서 함께 생성합니다.</small></div></div>
          <div className="account-create-grid">
            <label htmlFor="new-merchant-login">새 로그인 ID<input id="new-merchant-login" value={newLoginId} minLength={5} maxLength={32} required onChange={(event) => { setNewLoginId(event.target.value); clearSensitiveResult(); createIntent.current.rotate(); }} /></label>
            <label htmlFor="new-merchant-name">표시 이름<input id="new-merchant-name" value={displayName} maxLength={100} required onChange={(event) => { setDisplayName(event.target.value); clearSensitiveResult(); createIntent.current.rotate(); }} /></label>
            <label htmlFor="new-merchant-store">첫 매장 ID<input id="new-merchant-store" value={storeId} required onChange={(event) => { setStoreId(event.target.value); clearSensitiveResult(); createIntent.current.rotate(); }} /></label>
            <label htmlFor="new-merchant-role">첫 매장 역할<select id="new-merchant-role" value={membershipRole} onChange={(event) => { setMembershipRole(event.target.value as "OWNER" | "STAFF"); clearSensitiveResult(); createIntent.current.rotate(); }}><option value="OWNER">점주</option><option value="STAFF">직원</option></select></label>
          </div>
          <label htmlFor="create-merchant-reason">발급 사유</label>
          <textarea id="create-merchant-reason" value={createReason} maxLength={200} required onChange={(event) => { setCreateReason(event.target.value); clearSensitiveResult(); createIntent.current.rotate(); }} />
          <Button type="submit" loading={creating}>{creating ? "발급 중" : "점주 계정 발급"}</Button>
          {createError ? <ErrorState error={createError} /> : null}
        </form>
      )}

      {passwordResult ? <OneTimePasswordPanel result={passwordResult} onDismiss={clearSensitiveResult} /> : null}
    </div>
  );
}

function OneTimePasswordPanel({ result, onDismiss }: { result: OneTimePassword; onDismiss: () => void }) {
  return (
    <section className="surface-card one-time-password" aria-labelledby="one-time-password-title">
      <ShieldCheck aria-hidden="true" />
      <div>
        <span className="eyebrow">ONE-TIME SECRET</span>
        <h2 id="one-time-password-title">임시 비밀번호를 지금 전달하세요</h2>
        <p>이 값은 화면 이동·새로고침 후 복구되지 않습니다. 티켓·로그·브라우저 저장소에 남기지 마세요.</p>
        <code>{result.temporaryPassword}</code>
        <small>만료 {shortDateTime.format(new Date(result.temporaryPasswordExpiresAt))}</small>
      </div>
      <Button variant="ghost" onClick={onDismiss}>확인 후 지우기</Button>
    </section>
  );
}
