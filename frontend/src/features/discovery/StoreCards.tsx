import { ArrowRight, Coffee, MapPin } from "lucide-react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { nextPickupLabel, operatingStatusLabel } from "./storeDisplay";
import { distanceLabel } from "./useBrowserLocation";

export type StoreCardModel = {
  storeId: string;
  name: string;
  orderingAvailable: boolean;
  pickupAvailable: boolean;
  nextPickupWindow?: components["schemas"]["NextPickupWindow"];
  customerDisplay: components["schemas"]["CustomerStoreDisplay"];
  distanceMeters?: number;
  caption?: string | null;
  image?: components["schemas"]["StorefrontImage"];
};

export function StoreCard({ store }: { store: StoreCardModel }) {
  const distance = distanceLabel(store.distanceMeters);
  return (
    <Link className={`store-card ${store.orderingAvailable ? "" : "is-unavailable"}`} to={`/app/stores/${store.storeId}`}>
      {store.image ? <img className="store-thumbnail" src={store.image.url} alt="" /> : <span className="store-mark"><Coffee size={25} /></span>}
      <span className="store-copy"><strong>{store.name}</strong><span>{distance ? <><MapPin size={14} /> {distance}</> : null}{store.caption ? <em>{store.caption}</em> : null}</span><span>{store.customerDisplay.addressLine ?? "주소 정보 없음"}</span></span>
      <span className="store-state-copy"><strong className={`availability ${store.orderingAvailable ? "is-open" : ""}`}>{store.orderingAvailable ? "주문 가능" : "주문 불가"}</strong><span>{operatingStatusLabel(store.customerDisplay.operatingStatus)}</span><span>{nextPickupLabel(store.nextPickupWindow)}</span></span>
      <ArrowRight size={18} />
    </Link>
  );
}

export const recommendationReasonLabel: Record<string, string> = { FAVORITE: "자주 가는 매장", RECENT: "최근 주문한 매장", NEARBY: "가까운 매장" };
