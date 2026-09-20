import type { components } from "../../api/schema";

export type CheckoutCartContext = {
  cartRevision?: string;
  cartStoreId: string;
  couponIssuanceId?: string;
};

export type PaymentAttempt = Omit<components["schemas"]["OneTimePaymentAttempt"], "orderId"> & {
  orderId?: string;
  orderReference?: string;
  checkoutCart?: CheckoutCartContext;
};

const KEY_PREFIX = "beanflow.payment-attempt.";
const CHECKOUT_CART_PREFIX = "beanflow.customer.checkout-cart.";

export const checkoutCartStorage = {
  save(orderReference: string, context: CheckoutCartContext) {
    sessionStorage.setItem(`${CHECKOUT_CART_PREFIX}${orderReference}`, JSON.stringify(context));
  },
  get(orderReference: string): CheckoutCartContext | null {
    const value = sessionStorage.getItem(`${CHECKOUT_CART_PREFIX}${orderReference}`);
    if (!value) return null;
    try {
      const context = JSON.parse(value) as Partial<CheckoutCartContext>;
      return (context.cartRevision === undefined || typeof context.cartRevision === "string")
        && (context.couponIssuanceId === undefined || typeof context.couponIssuanceId === "string")
        && typeof context.cartStoreId === "string"
        ? context as CheckoutCartContext
        : null;
    } catch {
      return null;
    }
  },
  remove(orderReference: string) {
    sessionStorage.removeItem(`${CHECKOUT_CART_PREFIX}${orderReference}`);
  },
};

/**
 * The attempt the browser opened the payment window with. It exists only to
 * detect a callback that does not belong to this attempt; it is never used as
 * the source of the amount that gets approved.
 */
export const attemptStorage = {
  save(attempt: PaymentAttempt, checkoutCart?: CheckoutCartContext) {
    const previous = this.get(attempt.paymentId);
    sessionStorage.setItem(`${KEY_PREFIX}${attempt.paymentId}`, JSON.stringify({
      ...attempt,
      checkoutCart: checkoutCart ?? previous?.checkoutCart,
    }));
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
  remove(paymentId: string) {
    sessionStorage.removeItem(`${KEY_PREFIX}${paymentId}`);
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
