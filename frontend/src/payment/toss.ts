import { loadTossPayments } from "@tosspayments/payment-sdk";

const TOSS_V2_STANDARD_SDK = "https://js.tosspayments.com/v2/standard";

type TossV2PaymentRequest = {
  method: "CARD";
  amount: { value: number; currency: "KRW" };
  orderId: string;
  orderName: string;
  successUrl: string;
  failUrl: string;
};

type TossV2 = {
  payment(input: { customerKey: string }): {
    requestPayment(input: TossV2PaymentRequest): Promise<void>;
  };
};

export async function requestTossStandardPayment(
  clientKey: string,
  attempt: TossV2PaymentRequest & { customerKey: string },
) {
  const loaded = await loadTossPayments(clientKey, { src: TOSS_V2_STANDARD_SDK });
  const tossPayments = loaded as unknown as TossV2;
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
