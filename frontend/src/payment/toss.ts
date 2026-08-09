import { loadTossPayments } from "@tosspayments/tosspayments-sdk";

const TOSS_V2_STANDARD_SDK = "https://js.tosspayments.com/v2/standard";

type TossV2PaymentRequest = {
  method: "CARD";
  amount: { value: number; currency: "KRW" };
  orderId: string;
  orderName: string;
  successUrl: string;
  failUrl: string;
};

export async function requestTossStandardPayment(
  clientKey: string,
  attempt: TossV2PaymentRequest & { customerKey: string },
) {
  const tossPayments = await loadTossPayments(clientKey, { src: TOSS_V2_STANDARD_SDK });
  const payment = tossPayments.payment({ customerKey: attempt.customerKey });
  await payment.requestPayment({
    method: attempt.method,
    amount: attempt.amount,
    orderId: attempt.orderId,
    orderName: attempt.orderName,
    successUrl: attempt.successUrl,
    failUrl: attempt.failUrl,
  });
}
