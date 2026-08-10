import "@testing-library/jest-dom/vitest";
import { beforeEach } from "vitest";

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => { values.delete(key); },
    setItem: (key, value) => { values.set(key, String(value)); },
  };
}

Object.defineProperty(globalThis, "localStorage", { configurable: true, value: memoryStorage() });
Object.defineProperty(globalThis, "sessionStorage", { configurable: true, value: memoryStorage() });

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
