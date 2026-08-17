import {
  BarChart3,
  Coffee,
  Home,
  LogOut,
  PackageCheck,
  ReceiptText,
  WalletCards,
  Search,
  Settings,
  ShieldCheck,
  Store,
  UserRound,
} from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { Link, NavLink, Outlet } from "react-router";
import { authToken, useAuthToken } from "../auth/session";
import { merchantSession, requestMerchantStores, useMerchantSession } from "../features/auth/merchant/merchantSession";
import { Button, ButtonLink } from "../design-system";

export function CustomerShell() {
  return (
    <div className="customer-stage">
      <div className="customer-app">
        <header className="mobile-header">
          <Link to="/app" aria-label="BeanFlow 홈">
            <img src="/brand/logo-full.png" alt="BeanFlow" className="brand-full" />
          </Link>
          <Link className="icon-action" to="/app/orders" aria-label="주문 조회">
            <ReceiptText size={20} />
          </Link>
        </header>
        <main className="mobile-content">
          <Outlet />
        </main>
        <nav className="mobile-tabbar" aria-label="고객 메뉴">
          <NavLink to="/app" end>
            <Home size={20} />
            <span>홈</span>
          </NavLink>
          <NavLink to="/app/stores">
            <Search size={20} />
            <span>매장</span>
          </NavLink>
          <NavLink to="/app/orders">
            <ReceiptText size={20} />
            <span>주문</span>
          </NavLink>
          <NavLink to="/app/me">
            <UserRound size={20} />
            <span>마이</span>
          </NavLink>
        </nav>
      </div>
    </div>
  );
}

type ConsoleShellProps = {
  kind: "store" | "ops";
};

export function ConsoleShell({ kind }: ConsoleShellProps) {
  // 정산·이의제기는 ACTIVE OWNER만 쓸 수 있다. 이 gate는 표시 편의일 뿐이고
  // 모든 endpoint가 요청 시점의 membership을 다시 검증한다.
  const ownsAnyStore = useOwnerMembership(kind === "store");
  const [storeLogoutFailed, setStoreLogoutFailed] = useState(false);

  async function logOutOfStore() {
    setStoreLogoutFailed(false);
    try {
      await merchantSession.logOut();
    } catch {
      setStoreLogoutFailed(true);
    }
  }
  const storeItems = [
    { to: "/store", label: "주문 보드", icon: PackageCheck, end: true },
    ...(ownsAnyStore
      ? [
          { to: "/store/settlements", label: "정산 내역", icon: WalletCards, end: false },
          { to: "/store/disputes", label: "이의제기", icon: ReceiptText, end: false },
        ]
      : []),
  ];
  const opsItems = [
    { to: "/ops", label: "운영 현황", icon: BarChart3, end: true },
    { to: "/ops/orders", label: "주문 조회", icon: Search },
  ];
  const items = kind === "store" ? storeItems : opsItems;
  return (
    <div className="console-layout">
      <aside className="console-sidebar">
        <Link to={kind === "store" ? "/store" : "/ops"}>
          <img src="/brand/logo-full.png" alt="BeanFlow" className="sidebar-logo" />
        </Link>
        <p className="sidebar-context">{kind === "store" ? "STORE CONSOLE" : "OPS CONSOLE"}</p>
        <nav aria-label={kind === "store" ? "매장 콘솔" : "운영 콘솔"}>
          {items.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end}>
              <Icon size={19} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <Link to="/app">
            <Coffee size={18} /> 고객 앱
          </Link>
          <button type="button" onClick={kind === "store" ? () => void logOutOfStore() : authToken.clear}>
            <LogOut size={18} /> 로그아웃
          </button>
          {kind === "store" && storeLogoutFailed ? (
            <p className="form-error" role="alert">
              로그아웃에 실패했습니다. 세션이 남아 있을 수 있으니 다시 시도해 주세요.
            </p>
          ) : null}
        </div>
      </aside>
      <section className="console-main">
        <header className="console-topbar">
          <div>
            <span className="eyebrow">BEANFLOW</span>
            <strong>{kind === "store" ? "매장 운영" : "플랫폼 운영"}</strong>
          </div>
          <div className="topbar-actions">
            {kind === "store" ? <MerchantAuthStatus /> : <AuthStatus />}
            <button className="icon-action" type="button" aria-label="설정">
              <Settings size={19} />
            </button>
          </div>
        </header>
        {kind === "store" ? null : <ConsoleTokenStrip />}
        <main className="console-content">
          <Outlet />
        </main>
      </section>
    </div>
  );
}

/**
 * Console-only credential entry. Customer screens authenticate with a Session
 * Cookie and must never expose a token field.
 */
function ConsoleTokenStrip() {
  const token = useAuthToken();
  const [open, setOpen] = useState(false);
  if (token && !open) return null;
  return (
    <div className="auth-strip">
      <ShieldCheck size={17} />
      <span>{token ? "인증 토큰이 연결되었습니다." : "API를 사용하려면 액세스 토큰을 연결하세요."}</span>
      <button type="button" onClick={() => setOpen((value) => !value)}>
        {open ? "닫기" : token ? "변경" : "연결"}
      </button>
      {open ? <TokenEditor onClose={() => setOpen(false)} /> : null}
    </div>
  );
}

/**
 * Reads the current memberships to decide which console entries to show. A read
 * failure hides the owner-only entries rather than guessing that the actor owns
 * a store; the endpoints answer 403 either way.
 */
function useOwnerMembership(enabled: boolean): boolean {
  const [ownsAnyStore, setOwnsAnyStore] = useState(false);
  const session = useMerchantSession();
  const signedIn = session.status === "authenticated";

  useEffect(() => {
    if (!enabled || !signedIn) {
      setOwnsAnyStore(false);
      return;
    }
    let disposed = false;
    void (async () => {
      try {
        const stores = await requestMerchantStores();
        if (!disposed) setOwnsAnyStore(stores.some((store) => store.membershipRole === "OWNER"));
      } catch {
        if (!disposed) setOwnsAnyStore(false);
      }
    })();
    return () => {
      disposed = true;
    };
  }, [enabled, signedIn]);

  return ownsAnyStore;
}

function AuthStatus() {
  const token = useAuthToken();
  return <span className={`auth-status ${token ? "is-ready" : ""}`}>{token ? "인증됨" : "인증 필요"}</span>;
}

/**
 * The store console authenticates with a Session Cookie, so it shows who the
 * server says is signed in rather than whether a token was pasted.
 */
function MerchantAuthStatus() {
  const session = useMerchantSession();
  const signedIn = session.status === "authenticated" || session.status === "initialPassword";
  return (
    <span className={`auth-status ${signedIn ? "is-ready" : ""}`}>
      {signedIn ? session.actor.displayName : "인증 필요"}
    </span>
  );
}

function TokenEditor({ onClose }: { onClose: () => void }) {
  const [value, setValue] = useState(authToken.get());
  return (
    <form
      className="token-editor"
      onSubmit={(event) => {
        event.preventDefault();
        authToken.set(value);
        onClose();
      }}
    >
      <label htmlFor="access-token">OIDC 액세스 토큰</label>
      <textarea
        id="access-token"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        autoComplete="off"
        spellCheck={false}
      />
      <div>
        <Button variant="ghost" type="button" onClick={authToken.clear}>
          지우기
        </Button>
        <Button type="submit">
          연결
        </Button>
      </div>
    </form>
  );
}

export function PageTitle({ eyebrow, title, description, action }: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-title">
      <div>
        {eyebrow ? <span className="eyebrow">{eyebrow}</span> : null}
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {action}
    </header>
  );
}

export function RootRedirect() {
  return (
    <div className="surface-card root-choice">
      <img src="/brand/logo-full.png" alt="BeanFlow" />
      <h1>어떤 화면을 열까요?</h1>
      <p>역할에 맞는 BeanFlow 작업 공간을 선택하세요.</p>
      <div>
        <ButtonLink to="/app">고객 앱</ButtonLink>
        <ButtonLink variant="secondary" to="/store"><Store size={18} /> 매장 콘솔</ButtonLink>
        <ButtonLink variant="secondary" to="/ops"><ShieldCheck size={18} /> 운영 콘솔</ButtonLink>
      </div>
    </div>
  );
}
