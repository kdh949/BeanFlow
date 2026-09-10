import { useEffect } from "react";

/** Refresh owner state when returning to a screen and, optionally, while it stays visible. */
export function useAttentionRefresh(refresh: () => void, { enabled = true, intervalMs }: { enabled?: boolean; intervalMs?: number } = {}) {
  useEffect(() => {
    if (!enabled) return;
    const visibleRefresh = (event?: Event) => { if (event?.type === "focus" && event.target !== window) return; if (document.visibilityState === "visible") refresh(); };
    window.addEventListener("focus", visibleRefresh);
    document.addEventListener("visibilitychange", visibleRefresh);
    const timer = intervalMs ? window.setInterval(() => visibleRefresh(), intervalMs) : undefined;
    return () => { window.removeEventListener("focus", visibleRefresh); document.removeEventListener("visibilitychange", visibleRefresh); window.clearInterval(timer); };
  }, [enabled, intervalMs, refresh]);
}
