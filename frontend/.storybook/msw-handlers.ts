import { HttpResponse, http } from "msw";

/**
 * Cross-cutting browser security handlers shared by every Story. Domain reads
 * stay story-local so a missing fixture cannot silently inherit another
 * scenario's success response.
 */
export const mswHandlers = [
  http.get("/api/v1/auth/customer/csrf", () =>
    new HttpResponse(null, {
      status: 204,
      headers: { "Set-Cookie": "BEANFLOW_CUSTOMER_XSRF=storybook-customer-csrf; path=/" },
    })),
  http.get("/api/v1/auth/merchant/csrf", () =>
    new HttpResponse(null, {
      status: 204,
      headers: { "Set-Cookie": "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/" },
    })),
];
