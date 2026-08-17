import { Store } from "lucide-react";
import type { MerchantStore } from "../auth/merchant/merchantSession";

/**
 * Store switch for the console. The list comes from the server's current
 * memberships, so a revoked store disappears on the next read rather than being
 * filtered in the browser.
 */
export function StoreSelector({
  stores,
  selected,
  onSelect,
}: {
  stores: MerchantStore[];
  selected: MerchantStore | null;
  onSelect: (storeId: string) => void;
}) {
  if (stores.length === 0) return null;
  return (
    <label className="store-selector">
      <Store size={17} />
      <span className="visually-hidden">매장 선택</span>
      <select value={selected?.storeId ?? ""} onChange={(event) => onSelect(event.target.value)}>
        {stores.map((store) => (
          <option key={store.storeId} value={store.storeId}>
            {store.storeName} · {store.membershipRole === "OWNER" ? "점주" : "직원"}
          </option>
        ))}
      </select>
    </label>
  );
}
