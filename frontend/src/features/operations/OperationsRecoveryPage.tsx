import { useState } from "react";
import { PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { ManualRecoveryWorkspace } from "./ManualRecoveryWorkspace";

/** Manual recovery follows each source's permission and outcome contract. */
export function OperationsRecoveryPage() {
  const [workspace, setWorkspace] = useState("notification");
  return <div className="console-page"><PageHeading title="문제 확인 및 복구" /><Tabs value={workspace} onValueChange={setWorkspace}><TabList label="복구 업무 선택"><Tab value="notification">알림 전달</Tab><Tab value="publication">이벤트 전달</Tab></TabList><TabPanel value="notification"><ManualRecoveryWorkspace key="notification" kind="notification" /></TabPanel><TabPanel value="publication"><ManualRecoveryWorkspace key="publication" kind="publication" /></TabPanel></Tabs></div>;
}
