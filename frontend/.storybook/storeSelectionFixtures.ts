import { http, HttpResponse } from "msw";
import type { components } from "../src/api/schema";
import { ids } from "./fixtures";

export const selectionStore: components["schemas"]["StoreIdentitySnapshot"] = {
  storeId: ids.store, name: "빈플로우 성수점", latitude: 37.5445, longitude: 127.056,
  regionCode: "1120011400", version: 2, acceptingOrders: true, pickupEnabled: true,
};
export const storeSelectionHandler = http.get("/api/v1/operations/stores", () => HttpResponse.json({ items: [selectionStore] }));
