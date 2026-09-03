import { type FormEvent, useEffect, useState } from "react";
import { Navigate, useSearchParams } from "react-router";
import { ApiRequestError } from "../../../api/client";
import { Button, TextField } from "../../../design-system";
import { ReferenceSection, WorkspaceReferencePage } from "../../../presentation/beanflow-refresh";
import { merchantSession, sanitizeStoreReturnPath, useMerchantSession } from "./merchantSession";

const PASSWORD_MIN_LENGTH = 15;

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
    <WorkspaceReferencePage title="점주 로그인" description="BeanFlow 점주 콘솔에 오신 것을 환영합니다.">
      <div className="bfr-merchant-auth-grid">
      <ReferenceSection>
      <form className="bfr-auth-form" onSubmit={(event) => void submit(event)} noValidate>
        <TextField
          label="아이디"
          id="merchant-login-id"
          name="loginId"
          value={loginId}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          required
          invalid={failure !== null}
          aria-describedby={failure ? "merchant-login-error" : undefined}
          onValueChange={setLoginId}
        />
        <TextField
          label="비밀번호"
          id="merchant-login-password"
          name="password"
          type="password"
          value={password}
          autoComplete="current-password"
          required
          invalid={failure !== null}
          aria-describedby={failure ? "merchant-login-error" : undefined}
          onValueChange={setPassword}
        />
        {failure ? (
          <p className="form-error" id="merchant-login-error" role="alert">
            {code === "AUTHENTICATION_RATE_LIMITED"
              ? "로그인 시도가 너무 많습니다. 잠시 뒤 다시 시도해 주세요."
              : code === "AUTHENTICATION_FAILED"
                ? "아이디 또는 비밀번호를 확인해 주세요."
                : "로그인을 완료하지 못했습니다. 잠시 뒤 다시 시도해 주세요."}
          </p>
        ) : null}
        <Button size="xl" block type="submit" loading={submitting} disabled={!loginId.trim() || !password}>
          {submitting ? "로그인 중" : "로그인"}
        </Button>
      </form>
      </ReferenceSection>
      <ReferenceSection tone="soft" title="매장의 운영을 더 쉽고 효율적으로">
        <p className="bfr-workspace-support-copy">주문 관리부터 정산과 이의제기까지, 계약으로 지원되는 업무를 한곳에서 처리할 수 있습니다.</p>
      </ReferenceSection>
      </div>
      <p className="auth-switch">계정 발급이나 비밀번호 초기화가 필요하면 운영팀에 문의해 주세요.</p>
    </WorkspaceReferencePage>
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
    <WorkspaceReferencePage title="최초 비밀번호 변경" description="보안을 위해 비밀번호를 변경해 주세요.">
      <div className="bfr-password-workspace">
      <ReferenceSection>
      <form className="bfr-auth-form" onSubmit={(event) => void submit(event)} noValidate>
        <p className="form-footnote">비밀번호를 변경해야 매장 화면을 이용할 수 있습니다.</p>
        <TextField
          label="임시 비밀번호"
          id="merchant-current-password"
          name="currentPassword"
          type="password"
          value={currentPassword}
          autoComplete="current-password"
          required
          onValueChange={setCurrentPassword}
        />
        <TextField
          label="새 비밀번호"
          id="merchant-new-password"
          name="newPassword"
          type="password"
          value={newPassword}
          autoComplete="new-password"
          required
          error={tooShort ? `${PASSWORD_MIN_LENGTH}자 이상으로 만들어 주세요.` : undefined}
          description={tooShort ? undefined : `${PASSWORD_MIN_LENGTH}자 이상으로 만들어 주세요.`}
          onValueChange={setNewPassword}
        />
        {failure ? (
          <p className="form-error" id="merchant-password-error" role="alert">
            {code === "PASSWORD_POLICY_VIOLATION"
              ? "비밀번호 규칙을 확인해 주세요."
              : code === "AUTHENTICATION_FAILED"
                ? "현재 비밀번호를 확인해 주세요."
                : "비밀번호를 변경하지 못했습니다. 잠시 뒤 다시 시도해 주세요."}
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
      </ReferenceSection>
      <ReferenceSection tone="soft" title="안전한 계정 사용">
        <p className="bfr-workspace-support-copy">변경이 완료되면 매장 콘솔을 이용할 수 있습니다. 확인이 끝나기 전에는 완료로 표시하지 않습니다.</p>
      </ReferenceSection>
      </div>
    </WorkspaceReferencePage>
  );
}
