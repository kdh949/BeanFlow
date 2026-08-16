import { ArrowRight, Coffee, MapPin } from "lucide-react";
import { Link } from "react-router";
import { distanceLabel } from "./useBrowserLocation";

export type StoreCardModel = {
  storeId: string;
  name: string;
  open?: boolean;
  pickupAvailable: boolean;
  distanceMeters?: number;
  caption?: string | null;
};

/**
 * Availability comes only from the server flags. A closed or unreservable store
 * is still shown, but it is not a navigation target.
 *
 * The link carries no navigation state: the store screen reads the store itself,
 * so it behaves the same whether it was reached from here or from a pasted URL.
 */
export function StoreCard({ store }: { store: StoreCardModel }) {
  const available = (store.open ?? true) && store.pickupAvailable;
  const distance = distanceLabel(store.distanceMeters);
  return (
    <Link
      className={`store-card ${available ? "" : "is-closed"}`}
      to={`/app/stores/${store.storeId}`}
      aria-disabled={!available}
      tabIndex={available ? undefined : -1}
    >
      <span className="store-mark"><Coffee size={25} /></span>
      <span className="store-copy">
        <strong>{store.name}</strong>
        <span>
          {distance ? <><MapPin size={14} /> {distance}</> : null}
          {store.caption ? <em>{store.caption}</em> : null}
        </span>
      </span>
      <span className={`availability ${available ? "is-open" : ""}`}>{available ? "주문 가능" : "준비 중"}</span>
      <ArrowRight size={18} />
    </Link>
  );
}

export const recommendationReasonLabel: Record<string, string> = {
  FAVORITE: "자주 가는 매장",
  RECENT: "최근 주문한 매장",
  NEARBY: "가까운 매장",
};
