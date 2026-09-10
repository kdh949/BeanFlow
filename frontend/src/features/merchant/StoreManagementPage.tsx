import { useState, type ReactNode } from "react";
import { ButtonLink, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { StoreCatalogPage } from "./StoreCatalogPage";
import { StoreSchedulingPage } from "./StoreSchedulingPage";

/** Entry to live catalog and scheduling workflows; transaction totals belong to settlements. */
export function StoreManagementPage({ initialWorkspace = "catalog", catalogContent }: { initialWorkspace?: "catalog" | "hours"; catalogContent?: ReactNode }) {
  const [workspace, setWorkspace] = useState<string>(initialWorkspace);
  return <div className="console-page store-management-page">
    <PageHeading title="매장 관리" action={<ButtonLink variant="secondary" to="/store/settlements">정산 내역</ButtonLink>} />
    <Tabs value={workspace} onValueChange={setWorkspace}><TabList label="매장 관리 업무 선택"><Tab value="catalog">메뉴와 가격</Tab><Tab value="hours">영업시간과 픽업</Tab></TabList>
      <TabPanel value="catalog">{catalogContent ?? <StoreCatalogPage embedded />}</TabPanel>
      <TabPanel value="hours"><StoreSchedulingPage /></TabPanel>
    </Tabs>
  </div>;
}
