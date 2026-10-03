import { useEffect } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { ButtonLink, LoadingState } from "../../../design-system";
import { ConsoleFrame } from "../../../presentation/ConsoleFrame";
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

  const returnPath = `${location.pathname}${location.search}${location.hash}`;

  if (session.status === "unauthenticated") {
    return <Navigate replace to={`/store/login?next=${encodeURIComponent(returnPath)}`} />;
  }

  if (session.status === "initialPassword") {
    return <Navigate replace to={`/store/password?next=${encodeURIComponent(returnPath)}`} />;
  }

  if (session.status === "forbidden") {
    return (
      <ConsoleFrame kind="store" access="unavailable" actorLabel="매장 권한 확인 필요" onLogOut={() => merchantSession.logOut()}>
        <div className="state-page">
          <ErrorState error={session.error} />
          <p className="state-page-note">
            현재 로그인으로는 매장 콘솔을 이용할 수 없습니다. 점주 계정으로 다시 로그인해 주세요.
          </p>
          <ButtonLink to={`/store/login?next=${encodeURIComponent(returnPath)}`}>매장 계정으로 로그인</ButtonLink>
        </div>
      </ConsoleFrame>
    );
  }

  if (session.status === "unavailable") {
    return (
      <div className="state-page">
        <ErrorState error={session.error} retry={() => void merchantSession.refresh()} />
        <p className="state-page-note">로그인 상태를 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요.</p>
      </div>
    );
  }

  return <Outlet />;
}
