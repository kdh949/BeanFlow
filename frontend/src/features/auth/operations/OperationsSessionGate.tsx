import { useEffect, useState, useSyncExternalStore } from "react";
import { Navigate, Outlet } from "react-router";
import type { components } from "../../../api/schema";
import { ApiRequestError, unwrap } from "../../../api/client";
import { operationsApi } from "../../../api/consoleClient";
import { operationsAuth, type OperationsAuthState } from "../../../auth/session";
import { EmptyState, LoadingState } from "../../../design-system";
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
  }, [auth.status, session]);

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
        <EmptyState
          title="운영자 로그인이 필요합니다"
          description="BeanFlow 운영 권한이 연결된 조직 계정으로 로그인해 주세요."
          action={(
            <Button onClick={() => {
              setLoginError(null);
              void Promise.resolve(session.logIn()).catch(setLoginError);
            }}>
              Keycloak로 로그인
            </Button>
          )}
        />
        {loginError ? <ErrorState error={loginError} /> : null}
      </div>
    );
  }
  if (actorError) {
    const permissionDenied = actorError instanceof ApiRequestError && actorError.status === 403;
    return (
      <div className="console-page state-page">
        {permissionDenied ? (
          <EmptyState
            title="운영 권한이 없습니다"
            description="PLATFORM_OPERATOR 역할과 업무별 active permission grant를 확인해 주세요. 다른 역할로 대체하지 않습니다."
          />
        ) : (
          <ErrorState error={actorError} />
        )}
      </div>
    );
  }
  if (!actor) return <div className="console-page state-page"><LoadingState label="운영자 권한을 확인하는 중" /></div>;
  if (callback) return <Navigate to={session.consumeReturnPath()} replace />;
  return <Outlet />;
}
