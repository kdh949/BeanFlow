import { Store } from "lucide-react";
import { SelectField } from "../../design-system";
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
    <div className="store-selector">
      <Store size={17} />
      <SelectField label="매장 선택" labelVisibility="sr-only" value={selected?.storeId ?? ""} onValueChange={onSelect}>
        {stores.map((store) => (
          <option key={store.storeId} value={store.storeId}>
            {store.storeName} · {store.membershipRole === "OWNER" ? "점주" : "직원"}
          </option>
        ))}
      </SelectField>
    </div>
  );
}
