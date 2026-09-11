import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, SearchField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";

type StoreRecord = components["schemas"]["StoreIdentitySnapshot"];
export type StoreSelection = Pick<StoreRecord, "storeId" | "name">;
type SearchState = { status: "idle" } | { status: "loading" } | { status: "failed"; error: unknown } | { status: "ready"; items: StoreRecord[]; nextCursor?: string };

/** Name-based operator store selection. Safe inside a command form; never submits its parent. */
export function StoreTargetPicker({ value, onValueChange, disabled = false }: {
  /** A current selection returned by the authorized store directory. */
  value: StoreSelection | null;
  /** Clearing or replacing the selection also invalidates the caller's dependent reads. */
  onValueChange: (value: StoreSelection | null) => void;
  /** Locks the target while the caller is processing or resolving a command. */
  disabled?: boolean;
}) {
  const [search, setSearch] = useState("");
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const [state, setState] = useState<SearchState>({ status: "idle" });
  const generation = useRef(0);
  useEffect(() => () => { ++generation.current; }, []);

  function edit(next: string) {
    if (disabled) return;
    ++generation.current;
    setSearch(next); setCursors([undefined]); setState({ status: "idle" }); onValueChange(null);
  }
  async function find(nextCursors: Array<string | undefined>) {
    if (disabled || state.status === "loading") return;
    const current = ++generation.current;
    setCursors(nextCursors); setState({ status: "loading" }); onValueChange(null);
    try {
      const result = unwrap(await operationsApi.GET("/operations/stores", { params: { query: { query: search.trim() || undefined, cursor: nextCursors.at(-1), limit: 20 } } }));
      if (generation.current === current) setState({ status: "ready", items: result.items, nextCursor: result.nextCursor ?? undefined });
    } catch (error) {
      if (generation.current === current) setState({ status: "failed", error });
    }
  }
  return <fieldset className="catalog-fieldset" disabled={disabled}>
    <legend>{value ? "선택한 매장" : "업무 대상 매장"}</legend>
    {value ? <div className="button-row"><strong>{value.name}</strong><Button type="button" variant="secondary" onClick={() => edit("")}>다른 매장 찾기</Button></div> : <>
      <div className="button-row">
        <SearchField label="매장 이름 검색" description="매장 이름으로 검색하거나 전체 목록에서 선택하세요." value={search} onChange={event => edit(event.target.value)} onClear={() => edit("")} maxLength={200} autoComplete="off" onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); void find([undefined]); } }} />
        <Button type="button" variant="secondary" loading={state.status === "loading"} onClick={() => void find([undefined])}>매장 찾기</Button>
      </div>
      {state.status === "loading" ? <LoadingState label="매장을 찾는 중" /> : state.status === "failed" ? <ErrorState error={state.error} retry={() => void find(cursors)} /> : state.status === "ready" ? <>
        {state.items.length ? <ul className="menu-authoring-list">{state.items.map(store => <li key={store.storeId}><div><strong>{store.name}</strong><p>주문 {store.acceptingOrders ? "접수 중" : "접수 중지"} · 픽업 {store.pickupEnabled ? "사용" : "중지"}</p></div><Button type="button" variant="secondary" aria-label={`${store.name} 선택`} onClick={() => { ++generation.current; onValueChange(store); }}>선택</Button></li>)}</ul> : <EmptyState title="일치하는 매장이 없습니다" description="매장 이름을 확인하거나 검색어를 비워 전체 목록을 확인해 주세요." />}
        <div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => void find(cursors.slice(0, -1))}>이전 매장 목록</Button><Button type="button" variant="secondary" disabled={!state.nextCursor} onClick={() => void find([...cursors, state.nextCursor])}>다음 매장 목록</Button></div>
      </> : null}
    </>}
  </fieldset>;
}
