import {
  Bell, Headset, Home, ReceiptText, Search, ShieldCheck, ShoppingBag, Store, UserRound,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, NavLink, Outlet } from "react-router";
import { ApiRequestError, unwrap } from "../api/client";
import { customerApi } from "../api/customerClient";
import { operationsAuth, useOperationsAuth } from "../auth/session";
import { BrandLockup, ButtonLink } from "../design-system";
import { merchantSession, requestMerchantStores, useMerchantSession } from "../features/auth/merchant/merchantSession";
import { CUSTOMER_NOTIFICATION_SUMMARY_CHANGED } from "../features/notification/notificationSummary";
import { ConsoleFrame, type ConsoleAccess, type ConsoleKind } from "./ConsoleFrame";
import "./beanflow-refresh/refresh.css";

type BellState = "loading" | "read" | "unread" | "failed" | "unauthenticated";

export function NotificationAction() {
  const [state, setState] = useState<BellState>("loading");
  const load = useCallback(async () => {
    setState((current) => current === "read" || current === "unread" ? current : "loading");
    try {
      const result = unwrap(await customerApi.GET("/me/notification-summary"));
      setState(result.hasUnread ? "unread" : "read");
    } catch (failure) {
      setState(failure instanceof ApiRequestError && failure.status === 401 ? "unauthenticated" : "failed");
    }
  }, []);

  useEffect(() => {
    void load();
    const reload = () => void load();
    window.addEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
    return () => window.removeEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
  }, [load]);

  const label = state === "failed"
    ? "알림 상태를 확인하지 못했습니다. 알림함 열기"
    : state === "unread" ? "읽지 않은 알림 있음. 알림함 열기" : "알림함 열기";
  return (
    <Link className={`bfr-header-action ${state === "failed" ? "is-failed" : ""}`} to="/app/notifications" aria-label={label} aria-busy={state === "loading" || undefined}>
      <Bell size={19} aria-hidden="true" />
      {state === "unread" || state === "failed" ? <span aria-hidden="true">{state === "failed" ? "!" : ""}</span> : null}
    </Link>
  );
}

/** Single customer frame used by every customer route. */
export function CustomerShell() {
  return (
    <div className="bfr-customer-stage">
      <div className="bfr-customer-app">
        <header className="bfr-customer-header">
          <BrandLockup to="/app" />
          <div className="bfr-header-actions">
            <Link className="bfr-header-action" to="/app/cart" aria-label="장바구니 열기"><ShoppingBag size={19} aria-hidden="true" /></Link>
            <NotificationAction />
          </div>
        </header>
        <main className="bfr-customer-content"><Outlet /></main>
        <nav className="bfr-customer-tabs" aria-label="고객 메뉴">
          <NavLink to="/app" end><Home size={20} /><span>홈</span></NavLink>
          <NavLink to="/app/stores"><Search size={20} /><span>매장</span></NavLink>
          <NavLink to="/app/orders"><ReceiptText size={20} /><span>주문</span></NavLink>
          <NavLink to="/app/me"><UserRound size={20} /><span>마이</span></NavLink>
        </nav>
      </div>
    </div>
  );
}

/** Shared session adapter for all console routes. */
export function ConsoleShell({ kind }: { kind: ConsoleKind }) {
  const merchant = useMerchantSession();
  const operations = useOperationsAuth();
  const membership = useOwnerMembership(kind === "store" && merchant.status === "authenticated", merchant.status === "authenticated" ? merchant.actor.merchantId : null);
  const access: ConsoleAccess = kind === "store"
    ? merchant.status === "authenticated" ? "authenticated" : merchant.status === "initialPassword" ? "initial-password" : merchant.status === "loading" ? "checking" : merchant.status === "unauthenticated" ? "unauthenticated" : "unavailable"
    : operations.status === "authenticated" ? "authenticated" : operations.status === "unauthenticated" ? "unauthenticated" : operations.status === "unavailable" ? "unavailable" : "checking";
  const actorLabel = kind === "store" && (merchant.status === "authenticated" || merchant.status === "initialPassword")
    ? merchant.actor.displayName
    : kind !== "store" && operations.status === "authenticated" ? operations.displayName ?? "조직 계정 로그인됨"
    : access === "checking" ? "로그인 확인 중" : access === "unavailable" ? "로그인 확인 필요" : "로그인 필요";
  return <ConsoleFrame kind={kind} access={access} actorLabel={actorLabel} ownsAnyStore={membership.ownsAnyStore} membershipState={kind === "store" ? membership.status : undefined} onRetryMembership={membership.retry} onLogOut={() => kind === "store" ? merchantSession.logOut() : operationsAuth.logOut()}><Outlet /></ConsoleFrame>;
}

function useOwnerMembership(enabled: boolean, accountId: string | null) {
  const [state, setState] = useState<{ status: "checking" | "ready" | "failed"; ownsAnyStore: boolean }>({ status: "checking", ownsAnyStore: false });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!enabled) { setState({ status: "ready", ownsAnyStore: false }); return; }
    let disposed = false;
    setState({ status: "checking", ownsAnyStore: false });
    void requestMerchantStores()
      .then((stores) => { if (!disposed) setState({ status: "ready", ownsAnyStore: stores.some((store) => store.membershipRole === "OWNER") }); })
      .catch(() => { if (!disposed) setState({ status: "failed", ownsAnyStore: false }); });
    return () => { disposed = true; };
  }, [enabled, accountId, attempt]);
  return { ...state, retry: () => setAttempt((value) => value + 1) };
}

export function RootRedirect() {
  return (
    <main className="surface-card root-choice">
      <BrandLockup />
      <h1>어떤 화면을 열까요?</h1>
      <div>
        <ButtonLink to="/app">고객 앱</ButtonLink>
        <ButtonLink variant="secondary" to="/store"><Store size={18} /> 매장 콘솔</ButtonLink>
        <ButtonLink variant="secondary" to="/ops"><ShieldCheck size={18} /> 운영 콘솔</ButtonLink>
        <ButtonLink variant="secondary" to="/support"><Headset size={18} /> 고객지원 콘솔</ButtonLink>
      </div>
    </main>
  );
}
