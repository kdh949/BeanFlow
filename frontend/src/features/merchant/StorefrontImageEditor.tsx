import { useCallback, useEffect, useState } from "react";
import { unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { Button, FileField, InlineNotice, LoadingState } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";

/** A current image owned by a store or menu, without persisting a signed URL. */
export function StorefrontImageEditor({ storeId, menuId, label }: { storeId: string; menuId?: string; label: string }) {
  const resource = useResource(useCallback(async () => menuId
    ? unwrap(await merchantApi.GET("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId } } }))
    : unwrap(await merchantApi.GET("/stores/{storeId}/image", { params: { path: { storeId } } })), [storeId, menuId]));
  const [file, setFile] = useState<File | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [broken, setBroken] = useState(false);
  const image = resource.state.status === "ready" ? resource.state.value.image : null;
  const invalidFile = !!file && (!["image/jpeg", "image/png"].includes(file.type) || file.size > 5 * 1024 * 1024 || file.size === 0);
  const expired = !!image && Date.parse(image.expiresAt) <= Date.now();
  useEffect(() => {
    setBroken(false);
    if (!image || Date.parse(image.expiresAt) <= Date.now()) return;
    const timer = window.setTimeout(resource.reload, Math.min(Date.parse(image.expiresAt) - Date.now(), 2147483647));
    return () => window.clearTimeout(timer);
  }, [image, resource.reload]);
  async function save(remove: boolean) {
    if (busy || (!remove && (!file || invalidFile))) return;
    setBusy(true); setFailure(null); setNotice("");
    try {
      const header = await merchantCsrfHeader();
      if (remove) {
        if (menuId) unwrap(await merchantApi.DELETE("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header } }));
        else unwrap(await merchantApi.DELETE("/stores/{storeId}/image", { params: { path: { storeId }, header } }));
      } else {
        const form = new FormData(); form.set("image", file!);
        const body = { image: file!.name };
        if (menuId) unwrap(await merchantApi.PUT("/stores/{storeId}/menus/{menuId}/image", { params: { path: { storeId, menuId }, header }, body, bodySerializer: () => form }));
        else unwrap(await merchantApi.PUT("/stores/{storeId}/image", { params: { path: { storeId }, header }, body, bodySerializer: () => form }));
      }
      setNotice(remove ? "이미지를 삭제했습니다." : "이미지를 저장했습니다."); setFile(null); setInputKey(value => value + 1); setConfirmDelete(false);
    } catch (error) { setFailure(error); }
    finally { setBusy(false); resource.reload(); }
  }
  return <section className="surface-card management-card media-editor"><h3>{label}</h3>
    {resource.state.status === "loading" ? <LoadingState label="현재 이미지를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : image ? expired || broken ? <InlineNotice tone="warning" title="이미지를 다시 확인해 주세요" description="표시 시간이 지났거나 이미지 연결을 확인하지 못했습니다." action={<Button variant="secondary" onClick={resource.reload}>이미지 다시 읽기</Button>} /> : <img className="media-editor-image" src={image.url} alt={label} width={512} height={512} onError={() => setBroken(true)} /> : <p>등록된 이미지가 없습니다.</p>}
    <FileField key={inputKey} label={`${label} 파일`} accept="image/jpeg,image/png" disabled={busy} description="JPEG 또는 PNG, 최대 5 MiB. 각 변은 256~4096px이며 중앙의 정사각형 영역이 표시됩니다." onFileChange={next => { setFile(next); setNotice(""); setFailure(null); }} error={invalidFile ? "JPEG 또는 PNG, 최대 5 MiB 파일을 선택해 주세요." : undefined} />
    <div className="button-row"><Button loading={busy} disabled={!file || invalidFile || resource.state.status !== "ready"} onClick={() => void save(false)}>이미지 저장</Button><Button variant="danger" disabled={busy || !image} onClick={() => setConfirmDelete(true)}>이미지 삭제</Button><Button variant="ghost" disabled={busy} onClick={resource.reload}>현재 이미지 조회</Button></div>
    {confirmDelete ? <InlineNotice tone="warning" title="현재 이미지를 삭제할까요?" description="고객 화면에서 이 이미지가 사라집니다. 다시 표시하려면 파일을 업로드해야 합니다." action={<div className="button-row"><Button variant="danger" loading={busy} onClick={() => void save(true)}>삭제 확인</Button><Button variant="secondary" disabled={busy} onClick={() => setConfirmDelete(false)}>취소</Button></div>} /> : null}
    {notice ? <p role="status">{notice}</p> : null}{failure ? <ErrorState error={failure} /> : null}
  </section>;
}
