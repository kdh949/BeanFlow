import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Outlet, useLocation, useNavigate } from "react-router";
import { clearCustomerBrowserState, customerSession } from "../auth/customer/customerSession";
import { clearMerchantBrowserState, merchantSession } from "../auth/merchant/merchantSession";
import { demoApi, demoFailureCopy, type DemoConfig, type DemoSession } from "./demoClient";
import type { DemoAction } from "./demoGuideModel";

const marker = "beanflow.demo.active.v1";
const intentKey = "beanflow.demo.intent.v1";
type DemoContextValue = {
  session: DemoSession | null; config: DemoConfig | null; busy: boolean; checking: boolean;
  error: string | undefined; customerChecked: boolean;
  refresh: () => Promise<void>; start: (mode: "GUIDED" | "DIRECT") => Promise<void>;
  resume: () => Promise<void>; retry: () => Promise<void>; act: (action: DemoAction) => Promise<void>;
};
const DemoContext = createContext<DemoContextValue | null>(null);
export const useDemo = () => useContext(DemoContext);

/** Coordinates the visitor session only. Ordinary account gates keep their existing authority. */
export function DemoProvider({ children }: { children: ReactNode }) {
  const location = useLocation(); const navigate = useNavigate();
  const [session, setSession] = useState<DemoSession | null>(null);
  const [config, setConfig] = useState<DemoConfig | null>(null);
  const [busy, setBusy] = useState(false); const busyRef = useRef(false);
  const [checking, setChecking] = useState(false); const [error, setError] = useState<string>();
  const [checkedReference, setCheckedReference] = useState<string | null>(null);
  const [trackRetry, setTrackRetry] = useState(0);
  const generation = useRef(0); const refreshInFlight = useRef<Promise<void> | null>(null);
  const wanted = location.pathname === "/demo" || localStorage.getItem(marker) === "true";
  const refresh = useCallback(async () => {
    if (refreshInFlight.current) return refreshInFlight.current;
    if (busyRef.current) return;
    const currentGeneration = generation.current;
    const task = (async () => {
      setChecking(true);
      try {
        const config = await demoApi.config();
        const next = config.enabled ? await demoApi.current() : null;
        if (currentGeneration !== generation.current) return;
        setConfig(config); setSession(next); setError(undefined);
      } catch (failure) { if (currentGeneration === generation.current) setError(demoFailureCopy(failure)); }
      finally { setChecking(false); refreshInFlight.current = null; }
    })();
    refreshInFlight.current = task; return task;
  }, []);
  useEffect(() => { if (wanted) void refresh(); }, [wanted, refresh]);
  useEffect(() => {
    if (!session || session.status !== "ACTIVE") return;
    const reload = () => { if (!busyRef.current && document.visibilityState === "visible") void refresh(); };
    const timer = window.setInterval(reload, 3000);
    window.addEventListener("focus", reload); document.addEventListener("visibilitychange", reload);
    return () => { window.clearInterval(timer); window.removeEventListener("focus", reload); document.removeEventListener("visibilitychange", reload); };
  }, [session?.workspaceId, session?.status, refresh]);
  useEffect(() => {
    if (session?.status === "ACTIVE") localStorage.setItem(marker, "true");
    if (session && session.status !== "ACTIVE" && location.pathname !== "/demo") navigate("/demo?expired=1", { replace: true });
  }, [session?.status, location.pathname, navigate]);
  useEffect(() => {
    const reference = session?.order?.orderReference;
    if (reference && session.order?.status === "READY" && location.pathname === `/app/orders/${reference}`) setCheckedReference(reference);
  }, [session?.order?.orderReference, session?.order?.status, location.pathname]);

  function intent(operation: string): string {
    const stored = sessionStorage.getItem(intentKey);
    if (stored) {
      try { const value = JSON.parse(stored) as { operation: string; key: string }; if (value.operation === operation) return value.key; }
      catch { /* Local UI journal is not authentication. A corrupt entry is discarded. */ }
    }
    const key = crypto.randomUUID(); sessionStorage.setItem(intentKey, JSON.stringify({ operation, key })); return key;
  }
  async function command(action: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); setError(undefined); generation.current += 1;
    try { await action(); sessionStorage.removeItem(intentKey); }
    catch (failure) { setError(demoFailureCopy(failure)); }
    finally { busyRef.current = false; setBusy(false); }
  }
  async function adopt(next: DemoSession) {
    setSession(next); localStorage.setItem(marker, "true");
    clearCustomerBrowserState(); clearMerchantBrowserState();
    await Promise.all([customerSession.refresh(), merchantSession.refresh()]);
  }
  function destination(next: DemoSession) {
    return next.mode === "DIRECT" && !next.order ? `/app/stores/${next.storeId}` : "/store";
  }
  async function start(mode: "GUIDED" | "DIRECT") {
    await command(async () => {
      const next = await demoApi.start(mode, intent(`start:${mode}`)); await adopt(next); setCheckedReference(null);
      navigate(destination(next));
    });
  }
  async function resume() { await command(async () => { const next = await demoApi.resume(); await adopt(next); navigate(destination(next)); }); }
  async function act(action: DemoAction) {
    if (action === "exit") {
      await command(async () => {
        await demoApi.end(); clearCustomerBrowserState(); clearMerchantBrowserState();
        customerSession.reset(); merchantSession.reset(); setSession(null); localStorage.removeItem(marker);
        navigate("/demo", { replace: true });
      }); return;
    }
    if (action === "restart") {
      if (!session || session.status !== "ACTIVE") { await start("GUIDED"); return; }
      await command(async () => { const next = await demoApi.sample(intent(`sample:${session.workspaceId}`)); setSession(next); setCheckedReference(null); navigate("/store"); }); return;
    }
    if (!session || busyRef.current) return;
    if (action === "merchant") navigate("/store");
    if (action === "customer") navigate(session.order ? `/app/orders/${session.order.orderReference}` : `/app/stores/${session.storeId}`);
    if (action === "direct") navigate(`/app/stores/${session.storeId}`);
  }
  // Bind a newly created real order when its route is entered. The server checks both customer and store ownership.
  const routeReference = /^\/app\/orders\/([^/]+)(?:\/checkout)?$/.exec(location.pathname)?.[1];
  async function retry() {
    if (session?.status === "ACTIVE" && routeReference && routeReference !== session.order?.orderReference) {
      setError(undefined); setTrackRetry((current) => current + 1); return;
    }
    await refresh();
  }
  useEffect(() => {
    if (!session || session.status !== "ACTIVE" || !routeReference || routeReference === session.order?.orderReference || busyRef.current) return;
    let disposed = false;
    generation.current += 1;
    const trackGeneration = generation.current;
    void demoApi.track(routeReference, intent(`track:${session.workspaceId}:${routeReference}`)).then((next) => {
      if (!disposed && trackGeneration === generation.current) { generation.current += 1; setSession(next); setCheckedReference(null); sessionStorage.removeItem(intentKey); }
    }).catch((failure) => { if (!disposed) setError(demoFailureCopy(failure)); });
    return () => { disposed = true; };
  }, [routeReference, session?.workspaceId, session?.order?.orderReference, session?.status, trackRetry]);
  return <DemoContext.Provider value={{ session, config, busy, checking, error, customerChecked: checkedReference === session?.order?.orderReference,
    refresh, start, resume, retry, act }}>{children}</DemoContext.Provider>;
}
export function DemoRoot() { return <DemoProvider><Outlet /></DemoProvider>; }
