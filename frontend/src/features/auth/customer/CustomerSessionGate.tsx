import { useEffect } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { ErrorState, LoadingState } from "../../../design-system";
import { customerSession, useCustomerSession } from "./customerSession";

/**
 * Route boundary for every customer screen that needs an actor. It renders the
 * four `/me` outcomes separately so a dependency failure is never shown as a
 * login prompt.
 */
export function CustomerSessionGate() {
  const session = useCustomerSession();
  const location = useLocation();

  useEffect(() => {
    if (session.status === "loading") void customerSession.refresh();
  }, [session.status]);

  if (session.status === "loading") return <LoadingState label="로그인 상태를 확인하는 중" />;

  if (session.status === "unauthenticated") {
    const returnPath = `${location.pathname}${location.search}`;
    return <Navigate replace to={`/app/login?next=${encodeURIComponent(returnPath)}`} />;
  }

  if (session.status === "forbidden") {
    return (
      <div className="customer-page state-page">
        <ErrorState error={session.error} />
        <p className="state-page-note">이 브라우저의 인증 정보는 고객 화면을 사용할 수 없습니다. 다른 역할로 로그인되어 있는지 확인해 주세요.</p>
      </div>
    );
  }

  if (session.status === "unavailable") {
    return (
      <div className="customer-page state-page">
        <ErrorState error={session.error} retry={() => void customerSession.refresh()} />
        <p className="state-page-note">로그인 상태를 확인하지 못했습니다. 로그아웃된 것이 아니므로 다시 시도해 주세요.</p>
      </div>
    );
  }

  return <Outlet />;
}
