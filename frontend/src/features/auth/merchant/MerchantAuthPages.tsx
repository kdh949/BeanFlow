import { type FormEvent, useEffect, useState } from "react";
import { Navigate, useSearchParams } from "react-router";
import { ApiRequestError } from "../../../api/client";
import { PageHeading } from "../../../design-system";
import { Button } from "../../../design-system";
import { merchantSession, sanitizeStoreReturnPath, useMerchantSession } from "./merchantSession";

const PASSWORD_MIN_LENGTH = 15;

function messageFor(failure: unknown, fallback: string): string {
  if (failure instanceof ApiRequestError) return failure.message || fallback;
  return fallback;
}

function codeOf(failure: unknown): string | null {
  return failure instanceof ApiRequestError ? failure.code : null;
}

/**
 * Store operators do not sign themselves up. Operations issues the account, so
 * this screen only signs in and never offers registration.
 */
export function MerchantLoginPage() {
  const session = useMerchantSession();
  const [searchParams] = useSearchParams();
  const returnPath = sanitizeStoreReturnPath(searchParams.get("next"));
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (session.status === "loading") void merchantSession.refresh();
  }, [session.status]);

  if (session.status === "initialPassword") return <Navigate replace to="/store/password" />;
  if (session.status === "authenticated") return <Navigate replace to={returnPath} />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);
    try {
      await merchantSession.logIn({ loginId: loginId.trim().toLowerCase(), password });
    } catch (error) {
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  const code = codeOf(failure);
  return (
    <div className="console-auth">
      <PageHeading title="매장 로그인" description="운영팀이 발급한 매장 계정으로 로그인합니다." />
      <form className="surface-card auth-form" onSubmit={(event) => void submit(event)} noValidate>
        <label htmlFor="merchant-login-id">아이디</label>
        <input
          id="merchant-login-id"
          name="loginId"
          value={loginId}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          required
          aria-invalid={failure !== null}
          aria-describedby={failure ? "merchant-login-error" : undefined}
          onChange={(event) => setLoginId(event.target.value)}
        />
        <label htmlFor="merchant-login-password">비밀번호</label>
        <input
          id="merchant-login-password"
          name="password"
          type="password"
          value={password}
          autoComplete="current-password"
          required
          aria-invalid={failure !== null}
          aria-describedby={failure ? "merchant-login-error" : undefined}
          onChange={(event) => setPassword(event.target.value)}
        />
        {failure ? (
          <p className="form-error" id="merchant-login-error" role="alert">
            {code === "AUTHENTICATION_RATE_LIMITED"
              ? messageFor(failure, "로그인 시도가 너무 많습니다. 잠시 뒤 다시 시도해 주세요.")
              : code === "AUTHENTICATION_FAILED"
                ? "아이디 또는 비밀번호를 확인해 주세요."
                : messageFor(failure, "로그인을 완료하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")}
          </p>
        ) : null}
        <Button size="xl" block type="submit" loading={submitting} disabled={!loginId.trim() || !password}>
          {submitting ? "로그인 중" : "로그인"}
        </Button>
      </form>
      <p className="auth-switch">계정 발급과 비밀번호 초기화는 운영팀이 처리합니다.</p>
    </div>
  );
}

/**
 * The initial password must be replaced before any store screen opens. The
 * server enforces this too, so the screen never treats its own state as the gate.
 */
export function MerchantPasswordChangePage() {
  const session = useMerchantSession();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (session.status === "loading") void merchantSession.refresh();
  }, [session.status]);

  if (session.status === "unauthenticated") return <Navigate replace to="/store/login" />;
  if (session.status === "authenticated") return <Navigate replace to="/store" />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);
    try {
      await merchantSession.changePassword({ currentPassword, newPassword });
    } catch (error) {
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  const code = codeOf(failure);
  const tooShort = newPassword.length > 0 && newPassword.length < PASSWORD_MIN_LENGTH;
  return (
    <div className="console-auth">
      <PageHeading

        title="비밀번호 변경"
        description="임시 비밀번호를 바꾸기 전에는 매장 화면을 사용할 수 없습니다."
      />
      <form className="surface-card auth-form" onSubmit={(event) => void submit(event)} noValidate>
        <label htmlFor="merchant-current-password">임시 비밀번호</label>
        <input
          id="merchant-current-password"
          name="currentPassword"
          type="password"
          value={currentPassword}
          autoComplete="current-password"
          required
          onChange={(event) => setCurrentPassword(event.target.value)}
        />
        <label htmlFor="merchant-new-password">새 비밀번호</label>
        <input
          id="merchant-new-password"
          name="newPassword"
          type="password"
          value={newPassword}
          autoComplete="new-password"
          required
          aria-invalid={tooShort}
          aria-describedby="merchant-new-password-hint"
          onChange={(event) => setNewPassword(event.target.value)}
        />
        <small id="merchant-new-password-hint">{PASSWORD_MIN_LENGTH}자 이상으로 만들어 주세요.</small>
        {failure ? (
          <p className="form-error" id="merchant-password-error" role="alert">
            {code === "PASSWORD_POLICY_VIOLATION"
              ? messageFor(failure, "비밀번호 규칙을 확인해 주세요.")
              : code === "AUTHENTICATION_FAILED"
                ? "현재 비밀번호를 확인해 주세요."
                : messageFor(failure, "비밀번호를 변경하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")}
          </p>
        ) : null}
        <Button
          size="xl"
          block
          type="submit"
          loading={submitting}
          disabled={!currentPassword || newPassword.length < PASSWORD_MIN_LENGTH}
        >
          {submitting ? "변경 중" : "비밀번호 변경"}
        </Button>
      </form>
    </div>
  );
}
