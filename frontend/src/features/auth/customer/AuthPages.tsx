import { type FormEvent, useEffect, useState } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router";
import { ApiRequestError } from "../../../api/client";
import { Button, PageHeading, TextField } from "../../../design-system";
import { customerSession, sanitizeReturnPath, useCustomerSession } from "./customerSession";

const PASSWORD_MIN_LENGTH = 15;

function codeOf(failure: unknown): string | null {
  return failure instanceof ApiRequestError ? failure.code : null;
}

export function CustomerLoginPage() {
  const session = useCustomerSession();
  const [searchParams] = useSearchParams();
  const returnPath = sanitizeReturnPath(searchParams.get("next"));
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (session.status === "loading") void customerSession.refresh();
  }, [session.status]);

  if (session.status === "authenticated") return <Navigate replace to={returnPath} />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);
    try {
      await customerSession.logIn({ loginId: loginId.trim().toLowerCase(), password });
    } catch (error) {
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  const code = codeOf(failure);
  const rateLimited = code === "AUTHENTICATION_RATE_LIMITED";
  return (
    <div className="customer-page auth-page">
      <PageHeading title="로그인" description="주문과 포인트는 로그인한 계정에만 표시됩니다." />
      <form className="surface-card auth-form" onSubmit={(event) => void submit(event)} noValidate>
        <TextField
          label="아이디"
          id="customer-login-id"
          name="loginId"
          value={loginId}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          required
          invalid={failure !== null}
          aria-describedby={failure ? "customer-login-error" : undefined}
          onValueChange={setLoginId}
        />
        <TextField
          label="비밀번호"
          id="customer-login-password"
          name="password"
          type="password"
          value={password}
          autoComplete="current-password"
          required
          invalid={failure !== null}
          aria-describedby={failure ? "customer-login-error" : undefined}
          onValueChange={setPassword}
        />
        {failure ? (
          <p className="form-error" id="customer-login-error" role="alert">
            {rateLimited
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
      <p className="auth-switch">
        처음이신가요? <Link to={`/app/signup?next=${encodeURIComponent(returnPath)}`}>회원가입</Link>
      </p>
    </div>
  );
}

export function CustomerSignupPage() {
  const session = useCustomerSession();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const returnPath = sanitizeReturnPath(searchParams.get("next"));
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  // The account is already committed once register() resolves. A later logIn()
  // failure (network hiccup, auth dependency outage) must never be shown as a
  // signup failure: resubmitting would then hit LOGIN_ID_UNAVAILABLE and leave
  // the customer unsure whether an account exists at all.
  const [registered, setRegistered] = useState(false);

  if (session.status === "authenticated") return <Navigate replace to={returnPath} />;

  const normalizedLoginId = loginId.trim().toLowerCase();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);
    if (!registered) {
      try {
        await customerSession.register({ loginId: normalizedLoginId, password, displayName: displayName.trim() });
        setRegistered(true);
      } catch (error) {
        setFailure(error);
        setSubmitting(false);
        return;
      }
    }
    try {
      await customerSession.logIn({ loginId: normalizedLoginId, password });
      navigate(returnPath, { replace: true });
    } catch (error) {
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  const code = codeOf(failure);
  const duplicateLoginId = !registered && code === "LOGIN_ID_UNAVAILABLE";
  const passwordTooShort = password.length > 0 && password.length < PASSWORD_MIN_LENGTH;
  return (
    <div className="customer-page auth-page">
      <PageHeading title="회원가입" description="아이디와 비밀번호만으로 가입하고 바로 주문할 수 있어요." />
      <form className="surface-card auth-form" onSubmit={(event) => void submit(event)} noValidate>
        {registered ? (
          <p className="inline-note" role="status">
            회원가입은 완료됐어요. 아이디는 <strong>{normalizedLoginId}</strong>입니다.
          </p>
        ) : null}
        <TextField
          label="아이디"
          id="customer-signup-id"
          name="loginId"
          value={loginId}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          required
          readOnly={registered}
          error={duplicateLoginId ? "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요." : undefined}
          description="영문 소문자와 숫자로 5~32자를 사용합니다."
          onValueChange={(value) => {
            setLoginId(value);
            if (duplicateLoginId) setFailure(null);
          }}
        />
        <TextField
          label="표시 이름"
          id="customer-signup-name"
          name="displayName"
          value={displayName}
          autoComplete="nickname"
          required
          readOnly={registered}
          onValueChange={setDisplayName}
        />
        <TextField
          label="비밀번호"
          id="customer-signup-password"
          name="password"
          type="password"
          value={password}
          autoComplete="new-password"
          required
          error={passwordTooShort ? `${PASSWORD_MIN_LENGTH}자 이상으로 만들어 주세요.` : undefined}
          description={passwordTooShort ? undefined : `${PASSWORD_MIN_LENGTH}자 이상으로 만들어 주세요.`}
          onValueChange={setPassword}
        />
        {failure && !duplicateLoginId ? (
          <p className="form-error" id="customer-signup-error" role="alert">
            {registered
              ? "가입은 완료됐지만 로그인하지 못했습니다. 비밀번호를 확인한 뒤 다시 시도해 주세요."
              : "가입을 완료하지 못했습니다. 입력값을 확인한 뒤 다시 시도해 주세요."}
          </p>
        ) : null}
        <Button
          size="xl"
          block
          type="submit"
          loading={submitting}
          disabled={registered ? password.length < PASSWORD_MIN_LENGTH : (!loginId.trim() || !displayName.trim() || password.length < PASSWORD_MIN_LENGTH)}
        >
          {submitting ? (registered ? "로그인 중" : "가입 중") : (registered ? "다시 로그인" : "가입하고 시작하기")}
        </Button>
      </form>
      <p className="auth-switch">
        이미 계정이 있으신가요? <Link to={`/app/login?next=${encodeURIComponent(returnPath)}`}>로그인</Link>
      </p>
    </div>
  );
}
