import { TicketPercent } from "lucide-react";
import { useCallback } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { useResource } from "../../features/shared/useResource";
import { won } from "../../lib/format";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshMobileTopbar } from "./RefreshShared";

type EventCampaign = components["schemas"]["CustomerEventCampaign"];

export function EventCampaignPage() {
  const events = useResource<EventCampaign[]>(useCallback(async () => unwrap(await customerApi.GET("/me/events")), []));

  return (
    <div className="bfr-page bfr-events bfr-has-page-topbar">
      <RefreshMobileTopbar title="이벤트" backTo="/app" />
      {events.state.status === "loading" ? <RefreshLoading label="진행 중인 이벤트를 불러오는 중" /> : null}
      {events.state.status === "failed" ? <RefreshError error={events.state.error} retry={events.reload} /> : null}
      {events.state.status === "ready" && events.state.value.length === 0 ? <RefreshEmpty title="진행 중인 이벤트가 없어요" description="새로운 쿠폰 이벤트가 열리면 여기에 알려드릴게요." /> : null}
      {events.state.status === "ready" && events.state.value.length > 0 ? (
        <section className="bfr-event-list" aria-label="진행 중인 쿠폰 이벤트">
          {events.state.value.map((event) => <EventCard key={event.campaignId} event={event} />)}
        </section>
      ) : null}
    </div>
  );
}

function EventCard({ event }: { event: EventCampaign }) {
  return (
    <article className="bfr-event-card">
      <div className="bfr-event-banner">
        <img src={event.banner.url} alt={event.bannerAltText} />
        <span>~ {shortDate(event.claimEndsAt)}까지</span>
      </div>
      <div className="bfr-event-copy">
        <span>{event.store.name}</span>
        <h2>{event.title}</h2>
        <p>{event.summary}</p>
        <div><strong>{benefitLabel(event)}</strong><small><TicketPercent size={14} aria-hidden="true" />선착순 {event.remainingCount.toLocaleString("ko-KR")}명</small></div>
      </div>
    </article>
  );
}

function benefitLabel(event: EventCampaign) {
  if (event.benefit.discountType === "FIXED_KRW") return `${won.format(event.benefit.fixedAmountKrw ?? 0)} 할인`;
  return `${((event.benefit.rateBps ?? 0) / 100).toLocaleString("ko-KR")}% 할인`;
}

function shortDate(value: string) {
  const parts = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "2-digit", day: "2-digit" }).formatToParts(new Date(value));
  const month = parts.find((part) => part.type === "month")?.value ?? "--";
  const day = parts.find((part) => part.type === "day")?.value ?? "--";
  return `${month}.${day}`;
}
