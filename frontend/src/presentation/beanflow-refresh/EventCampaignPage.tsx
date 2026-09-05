import { TicketPercent } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { Button, ButtonLink } from "../../design-system";
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
  const [claimed, setClaimed] = useState(event.claimed);
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const intent = useRef(new SubmissionIntent());

  async function claim() {
    setSubmitting(true);
    setNotice(null);
    try {
      unwrap(await customerApi.POST("/me/events/{campaignId}/claims", {
        params: {
          path: { campaignId: event.campaignId },
          header: {
            "Idempotency-Key": intent.current.keyFor(event.campaignId),
            ...(await customerCsrfHeader()),
          },
        },
      }));
      intent.current.complete();
      setClaimed(true);
      setNotice("쿠폰을 받았어요. 쿠폰함에서 바로 확인할 수 있어요.");
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "COUPON_ALREADY_ISSUED") {
        intent.current.complete();
        setClaimed(true);
      } else if (error instanceof ApiRequestError && ["CAMPAIGN_QUOTA_EXHAUSTED", "CAMPAIGN_NOT_ISSUABLE", "IDEMPOTENCY_KEY_REUSED"].includes(error.code)) {
        intent.current.complete();
      }
      setNotice(claimFailureMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

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
        <div className="bfr-event-benefit"><strong>{benefitLabel(event)}</strong><small><TicketPercent size={14} aria-hidden="true" />선착순 {event.remainingCount.toLocaleString("ko-KR")}명</small></div>
        <div className="bfr-event-actions">
          {claimed ? <ButtonLink size="sm" variant="secondary" to={`/app/coupons?storeId=${encodeURIComponent(event.store.storeId)}`}>쿠폰함 보기</ButtonLink> : <Button size="sm" variant="brand" loading={submitting} onClick={() => void claim()}>쿠폰 받기</Button>}
        </div>
        {notice ? <p className="bfr-event-notice" role="status" aria-live="polite">{notice}</p> : null}
      </div>
    </article>
  );
}

function claimFailureMessage(error: unknown) {
  if (!(error instanceof ApiRequestError)) return "쿠폰을 받지 못했어요. 네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (error.code === "CAMPAIGN_QUOTA_EXHAUSTED") return "방금 쿠폰이 모두 소진됐어요.";
  if (error.code === "COUPON_ALREADY_ISSUED") return "이미 받은 쿠폰이에요. 쿠폰함에서 확인해 주세요.";
  if (error.code === "CAMPAIGN_NOT_ISSUABLE") return "이 이벤트는 종료되었거나 다운로드가 중단됐어요.";
  return "쿠폰을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.";
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
