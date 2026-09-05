import { Check, Clock3, TicketPercent } from "lucide-react";
import { useState } from "react";
import { Button, ButtonLink, EmptyState, InlineNotice, PageHeading } from "../../design-system";
import { shortDateTime, won } from "../../lib/format";

export type CustomerCouponOffer = {
  campaignId: string;
  title: string;
  benefitLabel: string;
  minimumOrderKrw: number;
  claimEndsAt: string;
  expiresAt: string;
  remainingLabel: string;
};

export type CustomerCouponClaimsPageProps = {
  scenario?: "contract-pending" | "ready" | "empty";
  couponOffers?: CustomerCouponOffer[];
  onClaimCoupon?: (campaignId: string) => Promise<void>;
};

/** Customer-facing coupon claim UI. Refund status belongs to the customer's order detail. */
export function CustomerCouponClaimsPage({
  scenario = "contract-pending",
  couponOffers = [],
  onClaimCoupon,
}: CustomerCouponClaimsPageProps) {
  const [claimingId, setClaimingId] = useState<string | null>(null);
  const [claimedIds, setClaimedIds] = useState<Set<string>>(() => new Set());
  const [claimError, setClaimError] = useState(false);

  async function claimCoupon(campaignId: string) {
    if (!onClaimCoupon) return;
    setClaimingId(campaignId);
    setClaimError(false);
    try {
      await onClaimCoupon(campaignId);
      setClaimedIds((current) => new Set(current).add(campaignId));
    } catch {
      setClaimError(true);
    } finally {
      setClaimingId(null);
    }
  }

  return (
    <div className="customer-page customer-aftercare-page">
      <PageHeading title="쿠폰 받기" />
      <section className="aftercare-section" aria-labelledby="customer-coupon-claim-title">
        <div className="panel-heading">
          <div><span className="context-label">받을 수 있는 쿠폰</span><h2 id="customer-coupon-claim-title">받을 수 있는 쿠폰</h2></div>
        </div>
        {scenario === "contract-pending" ? (
          <InlineNotice
            tone="danger"
            announce="assertive"
            title="쿠폰 받기를 준비하고 있어요"
            description="이미 받은 쿠폰은 쿠폰함에서 확인할 수 있어요."
            action={<ButtonLink variant="secondary" to="/app/coupons">쿠폰함 보기</ButtonLink>}
          />
        ) : couponOffers.length === 0 ? (
          <EmptyState title="지금 받을 수 있는 쿠폰이 없어요" description="새 쿠폰이 생기면 여기에 보여요." />
        ) : (
          <div className="coupon-list" aria-label="받을 수 있는 쿠폰">
            {couponOffers.map((offer) => {
              const claimed = claimedIds.has(offer.campaignId);
              return (
                <article className="surface-card coupon-card" key={offer.campaignId}>
                  <span className="coupon-icon"><TicketPercent size={22} aria-hidden="true" /></span>
                  <div className="coupon-copy">
                    <strong>{offer.title}</strong>
                    <span>{offer.benefitLabel} · {won.format(offer.minimumOrderKrw)} 이상 주문</span>
                    <small><Clock3 size={13} aria-hidden="true" /> {shortDateTime.format(new Date(offer.claimEndsAt))}까지 · {offer.remainingLabel}</small>
                  </div>
                  <Button
                    variant={claimed ? "secondary" : "brand"}
                    loading={claimingId === offer.campaignId}
                    disabled={claimed || !onClaimCoupon}
                    aria-label={`${offer.title} ${claimed ? "받기 완료" : "받기"}`}
                    onClick={() => void claimCoupon(offer.campaignId)}
                  >
                    {claimed ? <><Check size={16} aria-hidden="true" /> 받기 완료</> : "쿠폰 받기"}
                  </Button>
                </article>
              );
            })}
          </div>
        )}
        {claimedIds.size > 0 ? <p className="surface-card aftercare-success" role="status"><Check size={18} aria-hidden="true" /> 쿠폰을 받았어요</p> : null}
        {claimError ? <InlineNotice tone="danger" announce="assertive" title="쿠폰을 받지 못했어요" description="잠시 후 다시 시도해 주세요." /> : null}
      </section>
    </div>
  );
}
