import { useEffect } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { LoadingState } from "../../../design-system";
import { ErrorState } from "../../../presentation/shared";
import { merchantSession, useMerchantSession } from "./merchantSession";

/**
 * Route boundary for every store console screen. It renders the `/merchant/me`
 * outcomes separately so a dependency failure is never shown as a login prompt,
 * and an account that has not changed its initial password is sent to the
 * password screen instead of a store screen it cannot use.
 */
export function MerchantSessionGate() {
  const session = useMerchantSession();
  const location = useLocation();

  useEffect(() => {
    if (session.status === "loading") void merchantSession.refresh();
  }, [session.status]);

  if (session.status === "loading") return <LoadingState label="로그인 상태를 확인하는 중" />;

  if (session.status === "unauthenticated") {
    const returnPath = `${location.pathname}${location.search}`;
    return <Navigate replace to={`/store/login?next=${encodeURIComponent(returnPath)}`} />;
  }

  if (session.status === "initialPassword") {
    return <Navigate replace to="/store/password" />;
  }

  if (session.status === "forbidden") {
    return (
      <div className="state-page">
        <ErrorState error={session.error} />
        <p className="state-page-note">
          이 브라우저의 인증 정보는 매장 콘솔을 사용할 수 없습니다. 다른 역할로 로그인되어 있는지 확인해 주세요.
        </p>
      </div>
    );
  }

  if (session.status === "unavailable") {
    return (
      <div className="state-page">
        <ErrorState error={session.error} retry={() => void merchantSession.refresh()} />
        <p className="state-page-note">로그인 상태를 확인하지 못했습니다. 로그아웃된 것이 아니므로 다시 시도해 주세요.</p>
      </div>
    );
  }

  return <Outlet />;
}
