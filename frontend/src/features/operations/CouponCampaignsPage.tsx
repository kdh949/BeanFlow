import { CalendarClock, Plus, TicketPercent } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, Checkbox, EmptyState, FileField, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { shortDateTime } from "../../lib/format";
import { ErrorState, StatusText } from "../../presentation/shared";

type Campaign = components["schemas"]["CouponCampaign"];
type StoreOption = components["schemas"]["CouponCampaignStoreOption"];
type MenuOption = components["schemas"]["CouponCampaignMenuOption"];
type DiscountType = components["schemas"]["CouponCampaignDiscount"]["discountType"];
type CostBearer = components["schemas"]["CouponCampaignCost"]["costBearer"];

type DraftForm = {
  storeId: string;
  title: string;
  summary: string;
  bannerAltText: string;
  discountType: DiscountType;
  fixedAmountKrw: string;
  ratePercent: string;
  maximumDiscountKrw: string;
  minimumOrderKrw: string;
  allMenusEligible: boolean;
  eligibleMenuIds: string;
  costBearer: CostBearer;
  platformSharePercent: string;
  storeSharePercent: string;
  totalQuota: string;
  claimStartsAt: string;
  claimEndsAt: string;
  couponExpiresAt: string;
  reason: string;
};

const initialForm: DraftForm = {
  storeId: "",
  title: "",
  summary: "",
  bannerAltText: "",
  discountType: "FIXED_KRW",
  fixedAmountKrw: "1000",
  ratePercent: "10",
  maximumDiscountKrw: "5000",
  minimumOrderKrw: "5000",
  allMenusEligible: true,
  eligibleMenuIds: "",
  costBearer: "PLATFORM",
  platformSharePercent: "100",
  storeSharePercent: "0",
  totalQuota: "100",
  claimStartsAt: "",
  claimEndsAt: "",
  couponExpiresAt: "",
  reason: "",
};

function percentToBps(value: string) {
  return Math.round(Number(value) * 100);
}

function menuIds(value: string) {
  return value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
}

function discountLabel(campaign: Campaign) {
  if (campaign.discount.discountType === "FIXED_KRW") return `${campaign.discount.fixedAmountKrw?.toLocaleString("ko-KR")}원 할인`;
  return `${((campaign.discount.rateBps ?? 0) / 100).toLocaleString("ko-KR")}% 할인`;
}

export function CouponCampaignsPage() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [storeOptions, setStoreOptions] = useState<StoreOption[]>([]);
  const [menuOptions, setMenuOptions] = useState<MenuOption[]>([]);
  const [loadingMenus, setLoadingMenus] = useState(false);
  const [creating, setCreating] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [form, setForm] = useState<DraftForm>(initialForm);
  const intent = useRef(new SubmissionIntent());

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [page, stores] = await Promise.all([
        operationsApi.GET("/operations/coupon-campaigns", { params: { query: { limit: 100 } } }).then(unwrap),
        operationsApi.GET("/operations/coupon-campaigns/store-options").then(unwrap),
      ]);
      setCampaigns(page.items);
      setStoreOptions(stores);
    } catch (failure) {
      setCampaigns([]);
      setError(failure);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  function update<K extends keyof DraftForm>(key: K, value: DraftForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setError(null);
    intent.current.rotate();
  }

  function costChange(value: CostBearer) {
    const shares = value === "PLATFORM"
      ? { platformSharePercent: "100", storeSharePercent: "0" }
      : value === "STORE"
        ? { platformSharePercent: "0", storeSharePercent: "100" }
        : { platformSharePercent: "50", storeSharePercent: "50" };
    setForm((current) => ({ ...current, costBearer: value, ...shares }));
    intent.current.rotate();
  }

  async function selectStore(storeId: string) {
    update("storeId", storeId);
    setForm((current) => ({ ...current, storeId, eligibleMenuIds: "" }));
    setMenuOptions([]);
    if (!storeId) return;
    setLoadingMenus(true);
    try {
      setMenuOptions(unwrap(await operationsApi.GET("/operations/coupon-campaigns/store-options/{storeId}/menus", { params: { path: { storeId } } })));
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMenus(false);
    }
  }

  function toggleMenu(menuId: string, checked: boolean) {
    const selected = new Set(menuIds(form.eligibleMenuIds));
    if (checked) selected.add(menuId); else selected.delete(menuId);
    update("eligibleMenuIds", Array.from(selected).join(","));
  }

  async function createDraft() {
    const eligibleMenuIds = form.allMenusEligible ? [] : menuIds(form.eligibleMenuIds);
    const body = {
      storeId: form.storeId.trim(),
      title: form.title.trim(),
      summary: form.summary.trim(),
      bannerAltText: form.bannerAltText.trim(),
      discount: form.discountType === "FIXED_KRW"
        ? { discountType: form.discountType, fixedAmountKrw: Number(form.fixedAmountKrw), rateBps: null, maximumDiscountKrw: null }
        : { discountType: form.discountType, fixedAmountKrw: null, rateBps: percentToBps(form.ratePercent), maximumDiscountKrw: Number(form.maximumDiscountKrw) },
      minimumOrderKrw: Number(form.minimumOrderKrw),
      allMenusEligible: form.allMenusEligible,
      eligibleMenuIds,
      cost: {
        costBearer: form.costBearer,
        platformShareBps: percentToBps(form.platformSharePercent),
        storeShareBps: percentToBps(form.storeSharePercent),
      },
      totalQuota: Number(form.totalQuota),
      claimStartsAt: form.claimStartsAt.trim(),
      claimEndsAt: form.claimEndsAt.trim(),
      couponExpiresAt: form.couponExpiresAt.trim(),
      reason: form.reason.trim(),
    };
    const fingerprint = JSON.stringify(body);
    setCreating(true);
    setError(null);
    try {
      const created = unwrap(await operationsApi.POST("/operations/coupon-campaigns", {
        params: { header: { "Idempotency-Key": intent.current.keyFor(fingerprint) } },
        body,
      }));
      setCampaigns((current) => [created, ...current.filter((item) => item.campaignId !== created.campaignId)]);
      setForm(initialForm);
      setShowForm(false);
      intent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setError(failure);
    } finally {
      setCreating(false);
    }
  }

  const complete = Boolean(
    form.storeId.trim() && form.title.trim() && form.summary.trim() && form.bannerAltText.trim()
      && form.totalQuota && form.claimStartsAt && form.claimEndsAt && form.couponExpiresAt && form.reason.trim()
      && (form.allMenusEligible || menuIds(form.eligibleMenuIds).length > 0),
  );

  function updateCampaign(updated: Campaign) {
    setCampaigns((current) => current.map((campaign) => campaign.campaignId === updated.campaignId ? updated : campaign));
  }

  return (
    <div className="console-page coupon-campaign-page">
      <PageHeading
        title="선착순 쿠폰 캠페인"
        action={<Button onClick={() => setShowForm((current) => !current)}><Plus size={17} aria-hidden="true" />{showForm ? "목록 보기" : "새 캠페인"}</Button>}
      />

      {showForm ? (
        <form className="surface-card campaign-form" onSubmit={(event) => { event.preventDefault(); void createDraft(); }}>
          <div className="campaign-form-heading"><div className="campaign-icon"><TicketPercent size={22} aria-hidden="true" /></div><div><span className="context-label">DRAFT</span><h2>캠페인 기본 설정</h2><p>초안을 만든 뒤 배너를 업로드하고 게시할 수 있습니다.</p></div></div>

          <fieldset>
            <legend>노출 정보</legend>
            <div className="field-grid">
              <SelectField label="매장" value={form.storeId} onValueChange={(value) => void selectStore(value)}><option value="">대상 매장 선택</option>{storeOptions.map((store) => <option key={store.storeId} value={store.storeId}>{store.name}</option>)}</SelectField>
              <TextField label="캠페인 제목" value={form.title} onValueChange={(value) => update("title", value)} />
            </div>
            <TextAreaField label="한 줄 혜택 설명" resize="none" rows={2} value={form.summary} onValueChange={(value) => update("summary", value)} />
            <TextAreaField label="배너 대체 텍스트" description="이미지 없이도 혜택을 이해할 수 있게 작성합니다." resize="none" rows={2} value={form.bannerAltText} onValueChange={(value) => update("bannerAltText", value)} />
          </fieldset>

          <fieldset>
            <legend>할인과 적용 메뉴</legend>
            <div className="field-grid">
              <SelectField label="할인 방식" value={form.discountType} onValueChange={(value) => update("discountType", value as DiscountType)}><option value="FIXED_KRW">정액 할인</option><option value="RATE_BPS">정률 할인</option></SelectField>
              {form.discountType === "FIXED_KRW"
                ? <TextField label="할인 금액(원)" type="number" value={form.fixedAmountKrw} onValueChange={(value) => update("fixedAmountKrw", value)} />
                : <TextField label="할인율(%)" type="number" value={form.ratePercent} onValueChange={(value) => update("ratePercent", value)} />}
              {form.discountType === "RATE_BPS" ? <TextField label="최대 할인 금액(원)" type="number" value={form.maximumDiscountKrw} onValueChange={(value) => update("maximumDiscountKrw", value)} /> : null}
              <TextField label="최소 주문 금액(원)" type="number" value={form.minimumOrderKrw} onValueChange={(value) => update("minimumOrderKrw", value)} />
            </div>
            <Checkbox label="모든 메뉴에 적용" description="끄면 아래에 적용할 메뉴 UUID를 입력해야 합니다." checked={form.allMenusEligible} onCheckedChange={(value) => update("allMenusEligible", value)} />
            {!form.allMenusEligible ? (
              <div className="campaign-menu-options" aria-label="적용 메뉴 선택">
                {loadingMenus ? <p>메뉴를 불러오는 중입니다.</p> : null}
                {!loadingMenus && !form.storeId ? <p>먼저 대상 매장을 선택해 주세요.</p> : null}
                {!loadingMenus && form.storeId && menuOptions.length === 0 ? <p>적용 가능한 메뉴가 없습니다.</p> : null}
                {menuOptions.map((menu) => <Checkbox key={menu.menuId} label={menu.name} trailing={`${menu.basePriceKrw.toLocaleString("ko-KR")}원`} variant="card" checked={menuIds(form.eligibleMenuIds).includes(menu.menuId)} onCheckedChange={(checked) => toggleMenu(menu.menuId, checked)} />)}
              </div>
            ) : null}
          </fieldset>

          <fieldset>
            <legend>수량과 비용 부담</legend>
            <div className="field-grid">
              <TextField label="선착순 수량" type="number" value={form.totalQuota} onValueChange={(value) => update("totalQuota", value)} />
              <SelectField label="비용 부담 주체" value={form.costBearer} onValueChange={(value) => costChange(value as CostBearer)}><option value="PLATFORM">플랫폼 100%</option><option value="STORE">매장 100%</option><option value="SHARED">공동 부담</option></SelectField>
              {form.costBearer === "SHARED" ? <TextField label="플랫폼 부담률(%)" type="number" value={form.platformSharePercent} onValueChange={(value) => update("platformSharePercent", value)} /> : null}
              {form.costBearer === "SHARED" ? <TextField label="매장 부담률(%)" type="number" value={form.storeSharePercent} onValueChange={(value) => update("storeSharePercent", value)} /> : null}
            </div>
          </fieldset>

          <fieldset>
            <legend>다운로드와 쿠폰 유효기간</legend>
            <div className="field-grid">
              <TextField label="다운로드 시작 시각" description="ISO-8601 예: 2026-10-01T00:00:00+09:00" value={form.claimStartsAt} onValueChange={(value) => update("claimStartsAt", value)} />
              <TextField label="다운로드 종료 시각" description="시작보다 늦어야 합니다." value={form.claimEndsAt} onValueChange={(value) => update("claimEndsAt", value)} />
              <TextField label="쿠폰 만료 시각" description="모든 다운로드 쿠폰에 같은 절대 만료 시각이 적용됩니다." value={form.couponExpiresAt} onValueChange={(value) => update("couponExpiresAt", value)} />
            </div>
          </fieldset>

          <TextAreaField label="초안 생성 사유" description="감사 기록에 남습니다. 고객 개인정보나 비밀값은 입력하지 않습니다." resize="none" rows={3} value={form.reason} onValueChange={(value) => update("reason", value)} />
          {error ? <ErrorState error={error} /> : null}
          <div className="campaign-form-actions"><Button variant="secondary" onClick={() => setShowForm(false)}>취소</Button><Button type="submit" loading={creating} disabled={!complete}>초안 생성</Button></div>
        </form>
      ) : (
        <section aria-label="쿠폰 캠페인 목록">
          {loading ? <LoadingState label="쿠폰 캠페인을 조회하는 중" /> : null}
          {!loading && error ? <ErrorState error={error} retry={() => void load()} /> : null}
          {!loading && !error && campaigns.length === 0 ? <EmptyState title="등록된 캠페인이 없습니다" description="첫 선착순 쿠폰 캠페인 초안을 만들어 보세요." action={<Button onClick={() => setShowForm(true)}>첫 캠페인 만들기</Button>} /> : null}
          {campaigns.length > 0 ? <div className="campaign-card-list">{campaigns.map((campaign) => <CampaignCard key={campaign.campaignId} campaign={campaign} onUpdated={updateCampaign} />)}</div> : null}
        </section>
      )}
    </div>
  );
}

function CampaignCard({ campaign, onUpdated }: { campaign: Campaign; onUpdated: (campaign: Campaign) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [reason, setReason] = useState("");
  const [uploading, setUploading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [commandError, setCommandError] = useState<unknown>(null);
  const uploadIntent = useRef(new SubmissionIntent());
  const publishIntent = useRef(new SubmissionIntent());
  const stopIntent = useRef(new SubmissionIntent());

  function selectFile(selected: File | null) {
    setFile(selected);
    setCommandError(null);
    uploadIntent.current.rotate();
  }

  function changeReason(value: string) {
    setReason(value);
    setCommandError(null);
    uploadIntent.current.rotate();
    publishIntent.current.rotate();
    stopIntent.current.rotate();
  }

  async function uploadBanner() {
    if (!file || !reason.trim()) return;
    const fingerprint = `${campaign.campaignId}:${campaign.version}:${file.name}:${file.size}:${file.lastModified}:${reason.trim()}`;
    const formData = new FormData();
    formData.set("image", file);
    formData.set("reason", reason.trim());
    setUploading(true);
    setCommandError(null);
    try {
      const updated = unwrap(await operationsApi.PUT("/operations/coupon-campaigns/{campaignId}/banner", {
        params: {
          path: { campaignId: campaign.campaignId },
          query: { expectedVersion: campaign.version },
          header: {
            "Idempotency-Key": uploadIntent.current.keyFor(fingerprint),
          },
        },
        body: { image: file.name, reason: reason.trim() },
        bodySerializer: () => formData,
      }));
      onUpdated(updated);
      setFile(null);
      setFileInputKey((current) => current + 1);
      uploadIntent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") uploadIntent.current.rotate();
      setCommandError(failure);
    } finally {
      setUploading(false);
    }
  }

  async function publishCampaign() {
    if (!campaign.banner || !reason.trim()) return;
    const body = { expectedVersion: campaign.version, reason: reason.trim() };
    const fingerprint = JSON.stringify({ campaignId: campaign.campaignId, ...body });
    setPublishing(true);
    setCommandError(null);
    try {
      const updated = unwrap(await operationsApi.POST("/operations/coupon-campaigns/{campaignId}/publication", {
        params: {
          path: { campaignId: campaign.campaignId },
          header: { "Idempotency-Key": publishIntent.current.keyFor(fingerprint) },
        },
        body,
      }));
      onUpdated(updated);
      setReason("");
      publishIntent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") publishIntent.current.rotate();
      setCommandError(failure);
    } finally {
      setPublishing(false);
    }
  }

  async function stopCampaign() {
    if (!reason.trim()) return;
    const body = { expectedVersion: campaign.version, reason: reason.trim() };
    const fingerprint = JSON.stringify({ campaignId: campaign.campaignId, ...body });
    setStopping(true);
    setCommandError(null);
    try {
      const updated = unwrap(await operationsApi.POST("/operations/coupon-campaigns/{campaignId}/stoppage", {
        params: {
          path: { campaignId: campaign.campaignId },
          header: { "Idempotency-Key": stopIntent.current.keyFor(fingerprint) },
        },
        body,
      }));
      onUpdated(updated);
      setReason("");
      stopIntent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") stopIntent.current.rotate();
      setCommandError(failure);
    } finally {
      setStopping(false);
    }
  }

  return (
    <article className="surface-card campaign-card">
      <div className="campaign-card-main">
        <div className="campaign-card-title"><span className="context-label">{campaign.store.name}</span><h2>{campaign.title}</h2><p>{campaign.summary}</p></div>
        <StatusText state={campaign.state} />
      </div>
      <div className="campaign-card-metrics">
        <div><span>혜택</span><strong>{discountLabel(campaign)}</strong></div>
        <div><span>발급 현황</span><strong>{campaign.issuedCount.toLocaleString("ko-KR")} / {campaign.totalQuota.toLocaleString("ko-KR")}</strong></div>
        <div><span>다운로드 종료</span><strong><CalendarClock size={15} aria-hidden="true" />{shortDateTime.format(new Date(campaign.claimEndsAt))}</strong></div>
        <div><span>쿠폰 만료</span><strong>{shortDateTime.format(new Date(campaign.couponExpiresAt))}</strong></div>
      </div>
      {campaign.banner ? <img className="campaign-banner-preview" src={campaign.banner.url} alt={campaign.bannerAltText} /> : null}
      {campaign.state === "DRAFT" ? (
        <section className="campaign-publication" aria-label={`${campaign.title} 게시 설정`}>
          <div className="campaign-publication-fields">
            <FileField key={fileInputKey} label="이벤트 배너" description="JPEG 또는 PNG 원본, 최대 5MiB. 1200x450으로 정규화됩니다." accept="image/jpeg,image/png" onFileChange={selectFile} />
            <TextAreaField label="변경 사유" description="배너 등록과 게시 감사 기록에 남습니다." resize="none" rows={2} value={reason} onValueChange={changeReason} />
          </div>
          {commandError ? <ErrorState error={commandError} /> : null}
          <div className="campaign-publication-actions">
            <Button variant="secondary" loading={uploading} disabled={!file || !reason.trim()} onClick={() => void uploadBanner()}>{campaign.banner ? "배너 교체" : "배너 업로드"}</Button>
            <Button loading={publishing} disabled={!campaign.banner || !reason.trim()} onClick={() => void publishCampaign()}>고객에게 게시</Button>
          </div>
        </section>
      ) : null}
      {campaign.state === "PUBLISHED" ? (
        <section className="campaign-publication" aria-label={`${campaign.title} 다운로드 중단`}>
          <TextAreaField label="중단 사유" description="중단하면 신규 다운로드만 막고 이미 받은 쿠폰은 만료일까지 유지됩니다." resize="none" rows={2} value={reason} onValueChange={changeReason} />
          {commandError ? <ErrorState error={commandError} /> : null}
          <div className="campaign-publication-actions">
            <Button variant="danger" loading={stopping} disabled={!reason.trim()} onClick={() => void stopCampaign()}>신규 다운로드 중단</Button>
          </div>
        </section>
      ) : null}
    </article>
  );
}
