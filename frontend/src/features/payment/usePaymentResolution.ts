import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, idempotencyKey, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import type { PaymentCallback } from "./paymentAttempt";

export type Payment = components["schemas"]["PaymentConfirmation"];
export type ApprovalState = Payment["approvalState"];

const PENDING_STATES: ApprovalState[] = ["APPROVING", "UNKNOWN", "RECONCILING", "MANUAL_REVIEW"];
const POLL_DELAYS_MS = [2_000, 4_000, 8_000, 15_000];

export function isPendingApproval(state: ApprovalState): boolean {
  return PENDING_STATES.includes(state);
}

/**
 * Only an explicit Provider decline is terminal. A timeout, a lost response and
 * a 202 are unresolved, not failures, and never unlock "start a new order".
 */
export function isTerminalFailure(state: ApprovalState): boolean {
  return state === "FAILED";
}

export type PaymentResolution =
  | { phase: "confirming" }
  | { phase: "pending"; payment: Payment; polls: number }
  | { phase: "approved"; payment: Payment }
  | { phase: "declined"; payment: Payment }
  | { phase: "retryable"; payment: Payment }
  | { phase: "failed"; error: unknown };

function classify(payment: Payment, polls: number): PaymentResolution {
  if (payment.approvalState === "APPROVED") return { phase: "approved", payment };
  if (isTerminalFailure(payment.approvalState)) return { phase: "declined", payment };
  if (payment.approvalState === "READY") return { phase: "retryable", payment };
  return { phase: "pending", payment, polls };
}

/**
 * One confirmation per payment per page lifecycle. After the request has left
 * the browser, the result is recovered by reading the payment, never by sending
 * another confirmation the Provider could approve twice.
 */
const confirmationsStarted = new Set<string>();

export function resetConfirmationGuard() {
  confirmationsStarted.clear();
}

export function usePaymentResolution(paymentId: string, callback: PaymentCallback | null): {
  resolution: PaymentResolution;
  refresh: () => void;
} {
  const [resolution, setResolution] = useState<PaymentResolution>({ phase: "confirming" });
  const wake = useRef<(() => void) | null>(null);

  const readStatus = useCallback(async (): Promise<Payment> => {
    const result = await customerApi.GET("/payments/{paymentId}", { params: { path: { paymentId } } });
    return unwrap(result);
  }, [paymentId]);

  useEffect(() => {
    let cancelled = false;
    let timer = 0;
    // Loop state belongs to this effect run, not to the component. A shared ref
    // would let a cancelled run leave `running` set and make the next run give
    // up on its first read, which strands the screen on "confirming" forever.
    let running = false;
    let polls = 0;

    async function readOnce() {
      if (running) return;
      running = true;
      try {
        const payment = await readStatus();
        if (cancelled) return;
        polls += 1;
        const next = classify(payment, polls);
        setResolution(next);
        if (next.phase === "pending") {
          timer = window.setTimeout(() => void readOnce(), POLL_DELAYS_MS[Math.min(polls - 1, POLL_DELAYS_MS.length - 1)]);
        }
      } catch (error) {
        if (cancelled) return;
        setResolution({ phase: "failed", error });
        // A rejected read is answered; a lost one is not. Only the unresolved
        // case keeps polling, and it polls the status, never the confirmation.
        const answered = error instanceof ApiRequestError && error.status >= 400 && error.status < 500;
        if (!answered) {
          timer = window.setTimeout(() => void readOnce(), POLL_DELAYS_MS[POLL_DELAYS_MS.length - 1]);
        }
      } finally {
        running = false;
      }
    }

    async function start() {
      if (!callback) {
        await readOnce();
        return;
      }
      if (confirmationsStarted.has(paymentId)) {
        await readOnce();
        return;
      }
      confirmationsStarted.add(paymentId);
      try {
        const result = await customerApi.POST("/payments/{paymentId}/confirmations", {
          params: {
            path: { paymentId },
            header: {
              "Idempotency-Key": idempotencyKey(`payment-confirm.${paymentId}`),
              ...(await customerCsrfHeader()),
            },
          },
          body: { paymentKey: callback.paymentKey, orderId: callback.providerOrderId, amount: callback.amount },
        });
        const payment = unwrap(result);
        if (cancelled) return;
        const next = classify(payment, polls);
        setResolution(next);
        if (next.phase === "pending") await readOnce();
      } catch {
        if (cancelled) return;
        // The confirmation may already have reached the server. Converge by
        // reading the payment instead of sending a second confirmation.
        await readOnce();
      }
    }

    wake.current = () => {
      if (cancelled) return;
      window.clearTimeout(timer);
      void readOnce();
    };
    const onOnline = () => wake.current?.();
    window.addEventListener("online", onOnline);
    void start();

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      window.removeEventListener("online", onOnline);
      wake.current = null;
    };
  }, [callback, paymentId, readStatus]);

  return { resolution, refresh: () => wake.current?.() };
}
