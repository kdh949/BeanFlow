import { useEffect, useState } from "react";

export function useExpired(expiresAt?: string) {
  const [clock, setClock] = useState(Date.now());
  useEffect(() => { if (!expiresAt) return; const timer = window.setTimeout(() => setClock(Date.now()), Math.max(0, new Date(expiresAt).getTime() - Date.now()) + 1); return () => window.clearTimeout(timer); }, [expiresAt]);
  return !!expiresAt && new Date(expiresAt).getTime() <= Math.max(clock, Date.now());
}
