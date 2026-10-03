import { onCustomerLogout } from "../shared/customerLogout";
import type { Coordinates } from "./useBrowserLocation";

export type SearchVisit = {
  id: string;
  entryKey?: string;
  path: string;
  coordinates: Coordinates | null;
  pageCount: number;
  scrollY: number;
};
const visits = new Map<string, SearchVisit>();
onCustomerLogout(() => visits.clear());

function searchPath(value: unknown): value is string {
  if (typeof value !== "string" || !/^\/app\/stores(?:\?|$)/.test(value)) return false;
  const url = new URL(value, "https://beanflow.invalid");
  return url.origin === "https://beanflow.invalid" && url.pathname === "/app/stores" && !url.hash;
}

/** Only a bounded navigation hint lives here, never API responses or persisted coordinates. */
export function newSearchVisit(path: string, coordinates: Coordinates | null, entryKey?: string): SearchVisit {
  const visit = { id: crypto.randomUUID(), entryKey, path, coordinates, pageCount: 1, scrollY: 0 };
  visits.set(visit.id, visit);
  while (visits.size > 20) visits.delete(visits.keys().next().value!);
  return visit;
}

export function searchVisitForEntry(entryKey: string, path: string, state: unknown): SearchVisit {
  const existing = [...visits.values()].find((visit) => visit.entryKey === entryKey && visit.path === path);
  if (existing) return existing;
  const id = state && typeof state === "object" ? (state as { searchVisitId?: unknown }).searchVisitId : undefined;
  const known = typeof id === "string" ? visits.get(id) : undefined;
  if (known?.path !== path) return newSearchVisit(path, null, entryKey);
  if (!known.entryKey) { known.entryKey = entryKey; return known; }
  // A topbar Link creates a new history entry. Later location changes must not mutate its origin.
  const copy = newSearchVisit(path, known.coordinates, entryKey);
  copy.pageCount = known.pageCount; copy.scrollY = known.scrollY;
  return copy;
}

export function updateSearchVisit(visit: SearchVisit, update: Partial<Pick<SearchVisit, "coordinates" | "pageCount" | "scrollY">>) {
  // A late callback cannot revive location data after logout or eviction.
  if (visits.get(visit.id) === visit) Object.assign(visit, update);
}

export function searchOriginState(visit: SearchVisit) {
  return { searchOrigin: { path: visit.path, visitId: visit.id } };
}

/** A return target must match a real in-memory search visit, including query-less nearby search. */
export function searchReturnTarget(state: unknown): { to: string; state?: { searchVisitId: string } } {
  const origin = state && typeof state === "object" ? (state as { searchOrigin?: unknown }).searchOrigin : undefined;
  if (!origin || typeof origin !== "object") return { to: "/app/stores" };
  const { path, visitId } = origin as { path?: unknown; visitId?: unknown };
  if (!searchPath(path) || typeof visitId !== "string" || visits.get(visitId)?.path !== path) return { to: "/app/stores" };
  return { to: path, state: { searchVisitId: visitId } };
}
