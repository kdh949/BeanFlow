import type { components } from "../../api/schema";

export type PaymentAttempt = components["schemas"]["OneTimePaymentAttempt"];

const KEY_PREFIX = "beanflow.payment-attempt.";

/**
 * The attempt the browser opened the payment window with. It exists only to
 * detect a callback that does not belong to this attempt; it is never used as
 * the source of the amount that gets approved.
 */
export const attemptStorage = {
  save(attempt: PaymentAttempt) {
    sessionStorage.setItem(`${KEY_PREFIX}${attempt.paymentId}`, JSON.stringify(attempt));
  },
  get(paymentId: string): PaymentAttempt | null {
    const value = sessionStorage.getItem(`${KEY_PREFIX}${paymentId}`);
    if (!value) return null;
    try {
      return JSON.parse(value) as PaymentAttempt;
    } catch {
      return null;
    }
  },
};

export type PaymentCallback = { paymentKey: string; providerOrderId: string; amount: number };

export type CallbackCheck =
  | { valid: true; callback: PaymentCallback }
  | { valid: false; code: "INVALID_PAYMENT_CALLBACK" | "PAYMENT_CALLBACK_MISMATCH" };

/**
 * A malformed or mismatched callback never starts a new payment. It is reported
 * so the customer can check the order instead.
 */
export function checkCallback(paymentId: string, raw: URLSearchParams): CallbackCheck {
  const paymentKey = raw.get("paymentKey") ?? "";
  const providerOrderId = raw.get("orderId") ?? "";
  const amount = Number(raw.get("amount"));
  if (!paymentKey || !providerOrderId || !Number.isSafeInteger(amount) || amount <= 0) {
    return { valid: false, code: "INVALID_PAYMENT_CALLBACK" };
  }
  const attempt = attemptStorage.get(paymentId);
  if (attempt && (attempt.providerOrderId !== providerOrderId || attempt.amount.value !== amount)) {
    return { valid: false, code: "PAYMENT_CALLBACK_MISMATCH" };
  }
  return { valid: true, callback: { paymentKey, providerOrderId, amount } };
}

export function hasCallbackQuery(raw: URLSearchParams): boolean {
  return raw.has("paymentKey") || raw.has("orderId") || raw.has("amount");
}
