import { useCallback, useEffect } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { FeedbackState, RadioCard, RadioGroup } from "../../design-system";
import { won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { ErrorState } from "../../presentation/shared";

type Menu = components["schemas"]["Menu"];

/** Select exactly one current Merchant configuration; absent data never implies a basic option. */
export function MenuConfigurationChoice({ storeId, menu, optionIds, onChange, onValidityChange }: {
  storeId: string; menu: Menu; optionIds: string[];
  onChange: (optionIds: string[]) => void;
  onValidityChange: (valid: boolean) => void;
}) {
  const resource = useResource(useCallback(async () => unwrap(await customerApi.GET("/stores/{storeId}/menus/{menuId}/configurations", { params: { path: { storeId, menuId: menu.menuId } } })).items, [storeId, menu.menuId]));
  const configurations = resource.state.status === "ready" ? resource.state.value : [];
  const options = menu.options ?? [];
  const isAvailable = (ids: string[], available: boolean) => menu.available && available && ids.every((id) => options.some((option) => option.optionId === id && option.available));
  const selected = configurations.find((configuration) => configuration.optionIds.length === optionIds.length && configuration.optionIds.every((id) => optionIds.includes(id)));
  const valid = !!selected && isAvailable(selected.optionIds, selected.available);
  useEffect(() => { onValidityChange(valid); }, [valid, onValidityChange]);

  if (resource.state.status === "loading") return <FeedbackState kind="loading" title="판매 구성을 확인하는 중" description="잠시만 기다려 주세요." />;
  if (resource.state.status === "failed") return <ErrorState error={resource.state.error} retry={resource.reload} />;
  if (!configurations.length) return <FeedbackState kind="empty" title="판매 중인 구성이 없어요" description="다른 메뉴를 골라 주세요." />;
  return <>
    {!valid && optionIds.length > 0 ? <p role="status">이전에 선택한 구성은 지금 주문할 수 없어요. 판매 중인 구성을 다시 골라 주세요.</p> : null}
    <RadioGroup label={`${menu.name} 판매 구성`} value={selected?.configurationId ?? ""} onValueChange={(value) => { const choice = configurations.find((configuration) => configuration.configurationId === value); if (choice && isAvailable(choice.optionIds, choice.available)) onChange(choice.optionIds); }}>
      {configurations.map((configuration) => {
        const available = isAvailable(configuration.optionIds, configuration.available);
        const selectedOptions = configuration.optionIds.map((id) => options.find((option) => option.optionId === id));
        const label = selectedOptions.length ? selectedOptions.map((option) => option?.name ?? "판매가 끝난 옵션").join(" · ") : "기본";
        const price = menu.basePriceKrw + selectedOptions.reduce((sum, option) => sum + (option?.additionalPriceKrw ?? 0), 0);
        return <RadioCard key={configuration.configurationId} value={configuration.configurationId} label={`${label}${available ? "" : " · 품절"}`} description={selectedOptions.some((option) => !option) ? "현재 가격 확인 필요" : won.format(price)} disabled={!available} />;
      })}
    </RadioGroup>
  </>;
}
