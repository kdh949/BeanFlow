import { ArrowLeft, ArrowRight, MapPin, Navigation, Store } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { immediateOrderDisplay, operatingStatusLabel } from "../../features/discovery/storeDisplay";
import { BrandLockup, FeedbackState } from "../../design-system";
import { NotificationAction } from "../AppShells";
import { ErrorState } from "../shared";

type StoreSearchItem = components["schemas"]["StoreSearchItem"];
type NearbyStore = components["schemas"]["NearbyStore"];
type Recommendation = components["schemas"]["StoreRecommendation"];
type CardStore = StoreSearchItem | NearbyStore | Recommendation["store"];

export function RefreshMobileTopbar({ title, backTo, backState, brand = false }: { title: string; backTo?: string; backState?: unknown; brand?: boolean }) {
  return (
    <header className="bfr-mobile-topbar">
      {backTo ? <Link to={backTo} state={backState} aria-label="뒤로"><ArrowLeft size={19} aria-hidden="true" /></Link> : <span aria-hidden="true" />}
      {brand ? <BrandLockup /> : <h1>{title}</h1>}
      <NotificationAction />
    </header>
  );
}

export function RefreshLoading({ label }: { label: string }) {
  return <FeedbackState kind="loading" title={label} description="잠시만 기다려 주세요." />;
}

export function RefreshError({ error, retry }: { error: unknown; retry?: () => void }) {
  return <ErrorState error={error} retry={retry} />;
}

export function RefreshEmpty({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <FeedbackState kind="empty" title={title} description={description} action={action} />;
}

export function RefreshStoreCard({ store, caption, navigationState }: { store: CardStore; caption?: string | null; navigationState?: unknown }) {
  const availability = immediateOrderDisplay(store);
  const matchedMenus = "matchedMenus" in store ? store.matchedMenus : undefined;
  const line = caption ?? (matchedMenus?.length ? matchedMenus.map((menu) => menu.name).join(" · ") : null);
  return (
    <Link className="bfr-store-card" to={`/app/stores/${store.storeId}`} state={navigationState}>
      <span className="bfr-store-card__media">
        {store.image ? <img src={store.image.url} alt="" /> : <Store size={28} aria-hidden="true" />}
      </span>
      <span className="bfr-store-card__body">
        <span className="bfr-store-card__title"><strong>{store.name}</strong><small>{availability.label}</small></span>
        {line ? <span className="bfr-store-card__caption">{line}</span> : null}
        <span className="bfr-store-card__meta">
          <span><MapPin size={14} />{"distanceMeters" in store && store.distanceMeters != null ? `${Math.round(store.distanceMeters / 10) / 100}km` : operatingStatusLabel(store.customerDisplay.operatingStatus)}</span>
          <span><Navigation size={14} />{availability.description}</span>
        </span>
      </span>
      <ArrowRight size={18} aria-hidden="true" />
    </Link>
  );
}
