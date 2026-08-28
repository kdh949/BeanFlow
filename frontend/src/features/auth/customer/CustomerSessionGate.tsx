import { useEffect } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { LoadingState } from "../../../design-system";
import { ErrorState } from "../../../presentation/shared";
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
        <p className="state-page-note">현재 로그인으로는 고객 화면을 이용할 수 없어요. 고객 계정으로 다시 로그인해 주세요.</p>
      </div>
    );
  }

  if (session.status === "unavailable") {
    return (
      <div className="customer-page state-page">
        <ErrorState error={session.error} retry={() => void customerSession.refresh()} />
        <p className="state-page-note">로그인 상태를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.</p>
      </div>
    );
  }

  return <Outlet />;
}
