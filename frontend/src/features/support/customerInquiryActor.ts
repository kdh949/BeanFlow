import { customerApi } from "../../api/customerClient";
import { unwrap } from "../../api/client";

/** Uses the current customer session only to scope the non-sensitive command journal. */
export async function customerInquiryActor() {
  const actor = unwrap(await customerApi.GET("/me"));
  return `customer:${actor.customerId}`;
}
