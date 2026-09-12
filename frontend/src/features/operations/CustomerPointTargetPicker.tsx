import { useEffect, useRef, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, SearchField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";

export type PointCustomerSelection = components["schemas"]["OperationsCustomerSearchItem"];
type SearchState = { status: "idle" } | { status: "loading" } | { status: "failed"; error: unknown } | { status: "ready"; items: PointCustomerSelection[] };

/** Exact, masked customer selection held only in component memory; editing the search invalidates its results. */
export function CustomerPointTargetPicker({ value, onValueChange, disabled = false }: {
  /** Current customer chosen from the server result, never a manually entered identifier. */
  value: PointCustomerSelection | null;
  /** Clears the parent target before another search or customer can be selected. */
  onValueChange: (value: PointCustomerSelection | null) => void;
  /** Prevents changing the target while a financial command is pending or unresolved. */
  disabled?: boolean;
}) {
  const [loginId, setLoginId] = useState("");
  const [state, setState] = useState<SearchState>({ status: "idle" });
  const generation = useRef(0);
  useEffect(() => () => { ++generation.current; }, []);

  function changeSearch(next: string) {
    if (disabled) return;
    ++generation.current;
    setLoginId(next);
    setState({ status: "idle" });
    onValueChange(null);
  }

  async function search() {
    if (disabled || !loginId.trim() || state.status === "loading") return;
    const current = ++generation.current;
    onValueChange(null);
    setState({ status: "loading" });
    try {
      const result = unwrap(await operationsApi.POST("/operations/customer-searches", { body: { loginId: loginId.trim(), reasonCode: "POINT_ACCOUNT_INVESTIGATION" } }));
      if (generation.current === current) setState({ status: "ready", items: result.items });
    } catch (error) {
      if (generation.current === current) setState({ status: "failed", error });
    }
  }

  return <section className="surface-card management-card">
    <h3>{value ? "선택한 고객" : "포인트를 확인할 고객 찾기"}</h3>
    {value ? <div className="management-workspace">
      <p className="support-case-reference"><strong>{value.maskedDisplayName}</strong> · {value.maskedLoginId}</p>
      <Button variant="secondary" disabled={disabled} onClick={() => changeSearch("")}>다른 고객 찾기</Button>
    </div> : <>
      <form onSubmit={event => { event.preventDefault(); void search(); }}>
        <fieldset className="catalog-fieldset" disabled={disabled}>
          <legend>고객 검색</legend>
          <SearchField label="고객 로그인 아이디" value={loginId} onChange={event => changeSearch(event.target.value)} onClear={() => changeSearch("")} maxLength={100} autoComplete="off" description="고객이 가입할 때 정한 로그인 아이디 전체를 입력하세요. 조회 목적은 포인트 내역 조사로 기록됩니다." />
          <Button type="submit" loading={state.status === "loading"} disabled={!loginId.trim()}>고객 찾기</Button>
        </fieldset>
      </form>
      {state.status === "loading" ? <LoadingState label="고객을 찾는 중" /> : state.status === "failed" ? <ErrorState error={state.error} retry={() => void search()} /> : state.status === "ready" ? state.items.length ? <ul className="management-card-grid">
        {state.items.map(customer => <li className="management-card" key={customer.customerId}>
          <p className="support-case-reference"><strong>{customer.maskedDisplayName}</strong> · {customer.maskedLoginId}</p>
          <Button disabled={disabled} onClick={() => onValueChange(customer)}>이 고객 선택</Button>
        </li>)}
      </ul> : <EmptyState title="일치하는 고객이 없습니다" description="입력한 고객 로그인 아이디를 확인해 주세요." /> : null}
    </>}
  </section>;
}
