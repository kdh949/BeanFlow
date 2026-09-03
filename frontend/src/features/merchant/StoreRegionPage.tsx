import { MapPin, Search } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, EmptyState, LoadingState, RadioCard, RadioGroup, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { WorkspaceReferencePage } from "../../presentation/beanflow-refresh";
import { StoreSelector } from "./StoreSelector";
import { useMerchantStores } from "./useMerchantStores";

type Region = components["schemas"]["Region"];
type RegionPage = components["schemas"]["RegionPage"];
type StoreRegion = components["schemas"]["StoreRegion"];

/**
 * Region assignment accepts only a code returned by the server's active
 * law-dong vocabulary. Search text is never reused as a command value.
 */
export function StoreRegionPage() {
  const { state: storesState, stores, selected, select, reload } = useMerchantStores("OWNER");
  const [draftQuery, setDraftQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [page, setPage] = useState<RegionPage | null>(null);
  const [searchError, setSearchError] = useState<unknown>(null);
  const [searching, setSearching] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [selectedRegion, setSelectedRegion] = useState<Region | null>(null);
  const [reason, setReason] = useState("");
  const [saved, setSaved] = useState<StoreRegion | null>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const searchGeneration = useRef(0);
  const intent = useRef(new SubmissionIntent());
  const storeId = selected?.storeId ?? null;

  const resetDraft = useCallback(() => {
    searchGeneration.current += 1;
    setDraftQuery("");
    setSubmittedQuery("");
    setPage(null);
    setSearchError(null);
    setSelectedRegion(null);
    setReason("");
    setSaved(null);
    setSaveError(null);
    intent.current.rotate();
  }, []);

  useEffect(() => {
    resetDraft();
  }, [storeId, resetDraft]);

  async function search(query: string, cursor?: string, append = false) {
    const normalized = query.trim();
    if (!normalized) return;
    const generation = ++searchGeneration.current;
    if (append) setLoadingMore(true);
    else {
      setSearching(true);
      setPage(null);
      setSelectedRegion(null);
    }
    setSearchError(null);
    try {
      const next = unwrap(await merchantApi.GET("/regions", {
        params: { query: { query: normalized, cursor, limit: 20 } },
      }));
      if (generation !== searchGeneration.current) return;
      setSubmittedQuery(normalized);
      setPage((current) => append && current
        ? { items: [...current.items, ...next.items], page: next.page }
        : next);
    } catch (failure) {
      if (generation === searchGeneration.current) setSearchError(failure);
    } finally {
      if (generation === searchGeneration.current) {
        setSearching(false);
        setLoadingMore(false);
      }
    }
  }

  async function assign() {
    if (!storeId || !selectedRegion || !reason.trim()) return;
    const body = { regionCode: selectedRegion.code, reason: reason.trim() };
    const fingerprint = JSON.stringify({ storeId, ...body });
    setSaving(true);
    setSaveError(null);
    try {
      const result = unwrap(await merchantApi.PUT("/stores/{storeId}/region", {
        params: {
          path: { storeId },
          header: {
            "Idempotency-Key": intent.current.keyFor(fingerprint),
            ...(await merchantCsrfHeader()),
          },
        },
        body,
      }));
      setSaved(result);
      intent.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
        intent.current.rotate();
      }
      setSaveError(failure);
    } finally {
      setSaving(false);
    }
  }

  if (storesState.status === "loading") return <LoadingState label="매장 목록을 불러오는 중" />;
  if (storesState.status === "failed") {
    return <div className="console-page"><ErrorState error={storesState.error} retry={reload} /></div>;
  }

  return (
    <WorkspaceReferencePage title="영업 지역 설정" description="주문을 받을 수 있는 기본 영업 지역을 설정해 주세요." action={<StoreSelector stores={stores} selected={selected} onSelect={select} />}>

      {stores.length === 0 ? (
        <EmptyState
          title="지역을 설정할 수 있는 매장이 없습니다"
          description="매장 지역은 활성 점주 권한이 있는 계정만 지정할 수 있습니다."
        />
      ) : (
        <div className="console-detail-grid region-workspace">
          <section className="surface-card region-search-panel" aria-labelledby="region-search-title">
            <div className="panel-heading">
              <div>
                <span className="context-label">현재 지역</span>
                <h2 id="region-search-title">법정동 검색</h2>
              </div>
              <MapPin aria-hidden="true" />
            </div>
            <form
              className="lookup-bar region-lookup"
              onSubmit={(event) => {
                event.preventDefault();
                void search(draftQuery);
              }}
            >
              <div>
                <Search size={18} aria-hidden="true" />
                <TextField
                  label="지역 검색"
                  id="region-query"
                  value={draftQuery}
                  placeholder="예: 역삼동, 강남구"
                  required
                  onValueChange={setDraftQuery}
                />
                <Button type="submit" loading={searching}>{searching ? "검색 중" : "검색"}</Button>
              </div>
            </form>

            {searching ? <LoadingState label="지역을 검색하는 중" /> : null}
            {searchError ? <ErrorState error={searchError} retry={() => void search(submittedQuery || draftQuery)} /> : null}
            {page?.items.length === 0 ? (
              <EmptyState
                title="검색 결과가 없습니다"
                description="시·군·구 또는 읍·면·동 이름을 확인해 다시 검색해 주세요."
              />
            ) : null}
            {page?.items.length ? (
              <div className="region-results">
                <RadioGroup label={`${submittedQuery} 검색 결과`} value={selectedRegion?.code ?? ""} onValueChange={(value) => { setSelectedRegion(page.items.find((region) => region.code === value) ?? null); setSaved(null); setSaveError(null); intent.current.rotate(); }}>
                  {page.items.map((region) => <RadioCard key={region.code} value={region.code} label={region.fullName} description={region.code} />)}
                </RadioGroup>
                {page.page.nextCursor ? (
                  <Button
                    type="button"
                    variant="secondary"
                    loading={loadingMore}
                    onClick={() => void search(submittedQuery, page.page.nextCursor ?? undefined, true)}
                  >
                    {loadingMore ? "더 불러오는 중" : "지역 더 보기"}
                  </Button>
                ) : null}
              </div>
            ) : null}
          </section>

          <aside className="surface-card action-panel region-assignment" aria-labelledby="region-assignment-title">
            <div>
              <span className="context-label">지역 지정</span>
              <h2 id="region-assignment-title">선택 지역 지정</h2>
            </div>
            {selectedRegion ? (
              <div className="selected-region">
                <MapPin size={19} aria-hidden="true" />
                <div><strong>{selectedRegion.fullName}</strong><small>{selectedRegion.code}</small></div>
              </div>
            ) : (
              <p className="form-footnote">검색 결과에서 지역을 선택해 주세요.</p>
            )}
            <TextAreaField
              label="지정 사유"
              id="region-reason"
              value={reason}
              maxLength={1000}
              required
              placeholder="예: 사업자등록증상 소재지 기준"
              onValueChange={(value) => {
                setReason(value);
                setSaved(null);
                setSaveError(null);
                intent.current.rotate();
              }}
            />
            <Button
              type="button"
              block
              loading={saving}
              disabled={!selectedRegion || !reason.trim()}
              onClick={() => void assign()}
            >
              {saving ? "지정 중" : "지역 지정"}
            </Button>
            {saveError ? <ErrorState error={saveError} retry={() => void assign()} /> : null}
            {saved ? (
              <div className="region-success" role="status">
                <strong>지역을 지정했습니다</strong>
                <span>{saved.regionFullName}</span>
                <small>{saved.regionCode}</small>
              </div>
            ) : null}
            <p className="form-footnote">
              저장한 지역은 완료 메시지에서 확인할 수 있어요. 다시 변경하려면 지역을 새로 검색해 주세요.
            </p>
          </aside>
        </div>
      )}
    </WorkspaceReferencePage>
  );
}
