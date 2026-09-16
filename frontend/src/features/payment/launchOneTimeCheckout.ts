import { idempotencyKey, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { requestTossStandardPayment } from "../../payment/toss";
import { attemptStorage, checkoutCartStorage } from "./paymentAttempt";

/**
 * Opens the existing one-time payment flow for a server-created order.
 * A false result means the order is no longer payable and the caller should
 * fall back to the checkout recovery route instead of starting a new payment.
 */
export async function launchOneTimeCheckout(orderReference: string): Promise<boolean> {
  const checkout = unwrap(await customerApi.GET("/me/orders/{orderReference}/checkout", {
    params: { path: { orderReference } },
  }));
  if (!checkout.canPay) return false;

  const attempt = checkout.readyAttempt ?? unwrap(await customerApi.POST(
    "/me/orders/{orderReference}/payment-attempts",
    {
      params: {
        path: { orderReference },
        header: {
          "Idempotency-Key": idempotencyKey(`payment-attempt.${orderReference}`),
          ...(await customerCsrfHeader()),
        },
      },
    },
  ));
  if (attempt.state !== "READY") return false;

  attemptStorage.save(attempt, checkoutCartStorage.get(orderReference) ?? undefined);
  const config = unwrap(await customerApi.GET("/payment-config"));
  await requestTossStandardPayment(config.clientKey, {
    customerKey: attempt.customerKey,
    method: attempt.method,
    amount: attempt.amount,
    orderId: attempt.providerOrderId,
    orderName: attempt.orderName,
    successUrl: attempt.successUrl,
    failUrl: attempt.failUrl,
  });
  return true;
}
