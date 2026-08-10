import { useSyncExternalStore } from "react";

const STORAGE_KEY = "beanflow.accessToken";
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export const authToken = {
  get: () => localStorage.getItem(STORAGE_KEY)?.trim() ?? "",
  set: (value: string) => {
    const normalized = value.trim().replace(/^Bearer\s+/i, "");
    if (normalized) localStorage.setItem(STORAGE_KEY, normalized);
    else localStorage.removeItem(STORAGE_KEY);
    emit();
  },
  clear: () => {
    localStorage.removeItem(STORAGE_KEY);
    emit();
  },
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

export function useAuthToken() {
  return useSyncExternalStore(authToken.subscribe, authToken.get, () => "");
}
