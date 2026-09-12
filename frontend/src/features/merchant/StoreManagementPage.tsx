import { useState, type ReactNode } from "react";
import { ButtonLink, InlineNotice, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { StoreSupportOrderChanges } from "./StoreSupportOrderChangeWorkspace";
import { StoreCatalogPage } from "./StoreCatalogPage";
import { StoreSchedulingPage } from "./StoreSchedulingPage";

/** Entry to live catalog and scheduling workflows; transaction totals belong to settlements. */
export function StoreManagementPage({ initialWorkspace = "catalog", catalogContent }: { initialWorkspace?: "catalog" | "hours" | "support"; catalogContent?: ReactNode }) {
  const [workspace, setWorkspace] = useState<string>(initialWorkspace);
  const [catalogBusy, setCatalogBusy] = useState(false);
  const [hoursBusy, setHoursBusy] = useState(false);
  const locked = catalogBusy || hoursBusy;
  return <div className="console-page store-management-page">
    <PageHeading title="매장 관리" action={<ButtonLink variant="secondary" to="/store/settlements">정산 내역</ButtonLink>} />
    {locked ? <InlineNotice tone="info" title="편집을 마친 뒤 다른 업무로 이동해 주세요" description="변경 내용을 저장하거나 다시 읽기·편집 닫기로 취소하면 이동할 수 있습니다." /> : null}
    <Tabs value={workspace} onValueChange={value => { if (!locked) setWorkspace(value); }}><TabList label="매장 관리 업무 선택"><Tab value="catalog" disabled={locked}>메뉴와 가격</Tab><Tab value="hours" disabled={locked}>영업시간과 픽업</Tab><Tab value="support" disabled={locked}>상담 주문 변경 동의</Tab></TabList>
      <TabPanel value="catalog">{catalogContent ?? <StoreCatalogPage embedded onBusyChange={setCatalogBusy} />}</TabPanel>
      <TabPanel value="hours"><StoreSchedulingPage onBusyChange={setHoursBusy} /></TabPanel>
      <TabPanel value="support"><StoreSupportOrderChanges /></TabPanel>
    </Tabs>
  </div>;
}
