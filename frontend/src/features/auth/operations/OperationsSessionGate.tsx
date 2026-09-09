import { useEffect, useState, useSyncExternalStore } from "react";
import { Navigate, Outlet } from "react-router";
import type { components } from "../../../api/schema";
import { ApiRequestError, unwrap } from "../../../api/client";
import { operationsApi } from "../../../api/consoleClient";
import { operationsAuth, type OperationsAuthState } from "../../../auth/session";
import { PageHeading, InlineNotice, LoadingState } from "../../../design-system";
import { Button } from "../../../design-system";
import { ErrorState } from "../../../presentation/shared";

type OperationsSession = {
  get(): OperationsAuthState;
  subscribe(listener: () => void): () => void;
  initialize(): Promise<OperationsAuthState>;
  retry(): Promise<OperationsAuthState>;
  logIn(): Promise<void>;
  clear(): void;
  consumeReturnPath(): string;
};

type OperatorActor = components["schemas"]["OperatorActor"];

/**
 * Blocks every Operations route until Keycloak authentication and the server's
 * current-operator boundary both succeed. A local token or cached actor is
 * never substituted when configuration, callback or permission checks fail.
 */
export function OperationsSessionGate({
  callback = false,
  session = operationsAuth,
}: {
  callback?: boolean;
  session?: OperationsSession;
}) {
  const auth = useSyncExternalStore(session.subscribe, session.get, session.get);
  const [actor, setActor] = useState<OperatorActor | null>(null);
  const [actorError, setActorError] = useState<unknown>(null);
  const [checkingActor, setCheckingActor] = useState(false);
  const [actorAttempt, setActorAttempt] = useState(0);
  const [loginError, setLoginError] = useState<unknown>(null);

  useEffect(() => {
    if (auth.status === "idle") void session.initialize();
  }, [auth.status, session]);

  useEffect(() => {
    if (auth.status !== "authenticated") {
      setActor(null);
      setActorError(null);
      setCheckingActor(false);
      return;
    }
    let disposed = false;
    setCheckingActor(true);
    setActorError(null);
    void (async () => {
      try {
        const current = unwrap(await operationsApi.GET("/operations/me"));
        if (!disposed) setActor(current);
      } catch (error) {
        if (disposed) return;
        if (error instanceof ApiRequestError && error.status === 401) session.clear();
        else setActorError(error);
      } finally {
        if (!disposed) setCheckingActor(false);
      }
    })();
    return () => {
      disposed = true;
    };
  }, [auth.status, session, actorAttempt]);

  if (auth.status === "idle" || auth.status === "loading" || checkingActor) {
    return <div className="console-page state-page"><LoadingState label="운영자 로그인을 확인하는 중" /></div>;
  }
  if (auth.status === "unavailable") {
    return (
      <div className="console-page state-page">
        <ErrorState error={auth.error} retry={() => void session.retry()} />
      </div>
    );
  }
  if (auth.status === "unauthenticated") {
    return (
      <div className="console-page state-page operations-login-state">
        <PageHeading title="조직 계정 로그인" />
        <InlineNotice title="로그인이 필요합니다" description="업무 권한이 연결된 조직 계정으로 로그인해 주세요." />
        <Button onClick={() => {
          setLoginError(null);
          void Promise.resolve(session.logIn()).catch(setLoginError);
        }}>조직 계정으로 로그인</Button>
        {loginError ? <ErrorState error={loginError} /> : null}
      </div>
    );
  }
  if (actorError) {
    const permissionDenied = actorError instanceof ApiRequestError && actorError.status === 403;
    return (
      <div className="console-page state-page">
        {permissionDenied ? (
          <>
            <PageHeading title="업무 접근 권한이 없습니다" />
            <InlineNotice tone="warning" title="현재 계정의 업무 권한을 확인해 주세요" description="관리자에게 필요한 업무 권한을 요청해 주세요. 권한이 부여되었다면 다시 확인할 수 있습니다." action={<Button variant="secondary" onClick={() => setActorAttempt((value) => value + 1)}>권한 다시 확인</Button>} />
          </>
        ) : (
          <ErrorState error={actorError} retry={() => setActorAttempt((value) => value + 1)} />
        )}
      </div>
    );
  }
  if (!actor) return <div className="console-page state-page"><LoadingState label="운영자 권한을 확인하는 중" /></div>;
  if (callback) return <Navigate to={session.consumeReturnPath()} replace />;
  return <Outlet />;
}
