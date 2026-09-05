import { useSyncExternalStore } from "react";
import { onCustomerLogout } from "../shared/customerLogout";

export const CART_STORAGE_KEY = "beanflow.customer.cart.v1";
const CART_SCHEMA_VERSION = 1;

/**
 * `display` is a snapshot for rendering only. The server recomputes price,
 * availability, stock, slot and benefits when the order is created, so nothing
 * here is ever treated as the amount the customer will pay.
 */
export type CartLine = {
  menuId: string;
  optionIds: string[];
  quantity: number;
  display: { menuName: string; optionNames: string[]; unitPriceKrw: number; imageUrl?: string };
};

export type Cart = {
  version: typeof CART_SCHEMA_VERSION;
  storeId: string;
  storeName: string;
  lines: CartLine[];
};

/**
 * A cart that cannot be decoded is never silently replaced with an empty one:
 * the customer is told and clears it explicitly.
 */
export type CartState =
  | { status: "empty" }
  | { status: "ready"; cart: Cart }
  | { status: "corrupt" };

export type AddResult = { outcome: "added" } | { outcome: "other-store"; currentStoreName: string };

const listeners = new Set<() => void>();
let cached: CartState | null = null;

function emit() {
  cached = null;
  listeners.forEach((listener) => listener());
}

// Logout removes the storage key directly (it never imports this module), so
// the in-memory `cached` snapshot must be dropped here or the next customer to
// sign in on the same tab would see the previous customer's cart.
onCustomerLogout(emit);

function isCartLine(value: unknown): value is CartLine {
  if (typeof value !== "object" || value === null) return false;
  const line = value as Record<string, unknown>;
  const display = line.display as Record<string, unknown> | undefined;
  return (
    typeof line.menuId === "string" && line.menuId.length > 0
    && Array.isArray(line.optionIds) && line.optionIds.every((option) => typeof option === "string")
    && typeof line.quantity === "number" && Number.isInteger(line.quantity) && line.quantity > 0
    && typeof display === "object" && display !== null
    && typeof display.menuName === "string"
    && Array.isArray(display.optionNames) && display.optionNames.every((option) => typeof option === "string")
    && typeof display.unitPriceKrw === "number" && Number.isFinite(display.unitPriceKrw)
    && (display.imageUrl === undefined || typeof display.imageUrl === "string")
  );
}

function parse(raw: string): CartState {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { status: "corrupt" };
  }
  if (typeof value !== "object" || value === null) return { status: "corrupt" };
  const cart = value as Record<string, unknown>;
  if (cart.version !== CART_SCHEMA_VERSION) return { status: "corrupt" };
  if (typeof cart.storeId !== "string" || cart.storeId.length === 0) return { status: "corrupt" };
  if (typeof cart.storeName !== "string") return { status: "corrupt" };
  if (!Array.isArray(cart.lines) || !cart.lines.every(isCartLine)) return { status: "corrupt" };
  if (cart.lines.length === 0) return { status: "empty" };
  return { status: "ready", cart: cart as Cart };
}

function read(): CartState {
  if (cached) return cached;
  const raw = localStorage.getItem(CART_STORAGE_KEY);
  cached = raw === null ? { status: "empty" } : parse(raw);
  return cached;
}

function write(cart: Cart) {
  if (cart.lines.length === 0) localStorage.removeItem(CART_STORAGE_KEY);
  else localStorage.setItem(CART_STORAGE_KEY, JSON.stringify(cart));
  emit();
}

function sameLine(left: CartLine, right: Pick<CartLine, "menuId" | "optionIds">) {
  return left.menuId === right.menuId
    && left.optionIds.length === right.optionIds.length
    && [...left.optionIds].sort().join() === [...right.optionIds].sort().join();
}

export const cart = {
  read,

  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  /** A cart holds one store. Switching stores is an explicit customer decision. */
  add(store: { storeId: string; storeName: string }, line: CartLine): AddResult {
    const state = read();
    if (state.status === "ready" && state.cart.storeId !== store.storeId) {
      return { outcome: "other-store", currentStoreName: state.cart.storeName };
    }
    const lines = state.status === "ready" ? [...state.cart.lines] : [];
    const existing = lines.findIndex((candidate) => sameLine(candidate, line));
    if (existing >= 0) {
      const current = lines[existing];
      if (current) lines[existing] = { ...current, quantity: current.quantity + line.quantity };
    } else {
      lines.push(line);
    }
    write({ version: CART_SCHEMA_VERSION, storeId: store.storeId, storeName: store.storeName, lines });
    return { outcome: "added" };
  },

  replaceWith(store: { storeId: string; storeName: string }, line: CartLine) {
    write({ version: CART_SCHEMA_VERSION, storeId: store.storeId, storeName: store.storeName, lines: [line] });
  },

  setQuantity(index: number, quantity: number) {
    const state = read();
    if (state.status !== "ready") return;
    const lines = state.cart.lines
      .map((line, position) => (position === index ? { ...line, quantity } : line))
      .filter((line) => line.quantity > 0);
    write({ ...state.cart, lines });
  },

  /** Edit one configuration and merge quantities if it now matches another line. */
  updateLine(index: number, replacement: CartLine) {
    const state = read();
    if (state.status !== "ready" || !state.cart.lines[index]) return;
    const lines = state.cart.lines.filter((_, position) => position !== index);
    const match = lines.findIndex((line) => sameLine(line, replacement));
    if (match >= 0) {
      const existing = lines[match]!;
      lines[match] = { ...replacement, quantity: existing.quantity + replacement.quantity };
    } else lines.splice(index, 0, replacement);
    write({ ...state.cart, lines });
  },

  clear() {
    localStorage.removeItem(CART_STORAGE_KEY);
    emit();
  },
};

export function useCart(): CartState {
  return useSyncExternalStore(cart.subscribe, cart.read, () => ({ status: "empty" }) as CartState);
}

/** Display-only sum. The payable amount always comes from the created order. */
export function cartDisplayTotalKrw(value: Cart): number {
  return value.lines.reduce((total, line) => total + (line.display.unitPriceKrw * line.quantity), 0);
}

export function cartItemCount(state: CartState): number {
  return state.status === "ready" ? state.cart.lines.reduce((total, line) => total + line.quantity, 0) : 0;
}
