import { Heart, HeartOff, Trash2 } from "lucide-react";
import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button, ButtonLink } from "../../design-system";
import { StoreCard } from "../discovery/StoreCards";
import { useResource } from "../shared/useResource";

type CustomerStore = components["schemas"]["CustomerStore"];

async function requestFavorites(): Promise<CustomerStore[]> {
  return unwrap(await customerApi.GET("/me/favorite-stores")).items;
}

function requireNoContent(result: { response: Response; error?: unknown }) {
  if (!result.response.ok) unwrap(result);
}

export function FavoriteStoresPage() {
  const load = useCallback(requestFavorites, []);
  const favorites = useResource(load);
  const [removing, setRemoving] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<unknown>(null);

  async function remove(storeId: string) {
    setRemoving(storeId);
    setMutationError(null);
    try {
      requireNoContent(await customerApi.DELETE("/me/favorite-stores/{storeId}", {
        params: { path: { storeId }, header: await customerCsrfHeader() },
      }));
      favorites.reload();
    } catch (failure) {
      setMutationError(failure);
    } finally {
      setRemoving(null);
    }
  }

  return (
    <div className="customer-page favorite-stores-page">
      <PageTitle eyebrow="FAVORITES" title="즐겨찾기" description="자주 찾는 매장을 모아두고 바로 주문을 시작할 수 있어요." />
      {favorites.state.status === "loading" ? <LoadingState label="즐겨찾기 매장을 불러오는 중" /> : null}
      {favorites.state.status === "failed" ? <ErrorState error={favorites.state.error} retry={favorites.reload} /> : null}
      {mutationError ? <ErrorState error={mutationError} /> : null}
      {favorites.state.status === "ready" && favorites.state.value.length === 0 ? (
        <EmptyState
          title="즐겨찾기한 매장이 없어요"
          description="매장 화면의 하트 버튼으로 최대 200개까지 저장할 수 있어요."
          action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>}
        />
      ) : null}
      {favorites.state.status === "ready" && favorites.state.value.length ? (
        <section className="favorite-store-list" aria-label="즐겨찾기 매장">
          {favorites.state.value.map((store) => (
            <article className="favorite-store-row" key={store.storeId}>
              <StoreCard store={store} />
              <Button
                variant="ghost"
                loading={removing === store.storeId}
                aria-label={`${store.name} 즐겨찾기 해제`}
                onClick={() => void remove(store.storeId)}
              >
                <Trash2 size={17} /> {removing === store.storeId ? "해제 중" : "해제"}
              </Button>
            </article>
          ))}
        </section>
      ) : null}
    </div>
  );
}

export function FavoriteStoreButton({ storeId, storeName }: { storeId: string; storeName: string }) {
  const load = useCallback(requestFavorites, []);
  const favorites = useResource(load);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const saved = favorites.state.status === "ready" && favorites.state.value.some((store) => store.storeId === storeId);

  async function toggle() {
    if (favorites.state.status !== "ready" || saving) return;
    setSaving(true);
    setFailure(null);
    try {
      const params = { path: { storeId }, header: await customerCsrfHeader() };
      if (saved) requireNoContent(await customerApi.DELETE("/me/favorite-stores/{storeId}", { params }));
      else requireNoContent(await customerApi.PUT("/me/favorite-stores/{storeId}", { params }));
      favorites.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setSaving(false);
    }
  }

  if (favorites.state.status === "loading") return <Button variant="ghost" loading aria-label="즐겨찾기 상태 확인 중">확인 중</Button>;
  if (favorites.state.status === "failed") return <Button variant="ghost" onClick={favorites.reload}>즐겨찾기 다시 확인</Button>;

  return (
    <div className="favorite-action">
      <Button
        variant={saved ? "secondary" : "ghost"}
        loading={saving}
        aria-pressed={saved}
        aria-label={`${storeName} 즐겨찾기 ${saved ? "해제" : "추가"}`}
        onClick={() => void toggle()}
      >
        {saved ? <HeartOff size={17} /> : <Heart size={17} />} {saved ? "저장됨" : "즐겨찾기"}
      </Button>
      {failure ? (
        <p className="form-error" role="alert">
          {failure instanceof ApiRequestError && failure.code === "FAVORITE_STORE_LIMIT_EXCEEDED"
            ? "즐겨찾기는 최대 200개까지 저장할 수 있어요. 기존 매장을 해제한 뒤 다시 시도해 주세요."
            : failure instanceof ApiRequestError ? failure.message : "즐겨찾기를 변경하지 못했습니다."}
        </p>
      ) : null}
    </div>
  );
}
