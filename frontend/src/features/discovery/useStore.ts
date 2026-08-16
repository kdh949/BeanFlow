import { useCallback } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { useResource } from "../shared/useResource";

export type CustomerStore = components["schemas"]["CustomerStore"];

/**
 * Reads one store's display identity from the server.
 *
 * The store name is server-owned. A screen reached by URL, deep link or reload has
 * no navigation state to read it from, and a client that invents a placeholder
 * name would be naming a real business on its own.
 */
export function useStore(storeId: string) {
  const load = useCallback(
    async (): Promise<CustomerStore> => unwrap(await customerApi.GET("/stores/{storeId}", { params: { path: { storeId } } })),
    [storeId],
  );
  return useResource<CustomerStore>(load);
}
