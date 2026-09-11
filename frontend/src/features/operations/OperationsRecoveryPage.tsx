import { useState } from "react";
import { PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { OperationsRefundWorkspace } from "./OperationsRefundWorkspace";
import { PointAccountWorkspace } from "./PointAccountWorkspace";
import { OrderCompensationWorkspace } from "./OrderCompensationWorkspace";
import { PaymentRepairWorkspace } from "./PaymentRepairWorkspace";
import { ManualRecoveryWorkspace } from "./ManualRecoveryWorkspace";

/** Manual recovery follows each source's permission and outcome contract. */
export function OperationsRecoveryPage() {
  const [workspace, setWorkspace] = useState("notification");
  const [orderLocked, setOrderLocked] = useState(false);
  const [repairLocked, setRepairLocked] = useState(false);
  return <div className="console-page"><PageHeading title="문제 확인 및 복구" /><Tabs value={workspace} onValueChange={value => { if (!orderLocked && !repairLocked) setWorkspace(value); }}><TabList label="복구 업무 선택"><Tab value="notification">알림 전달</Tab><Tab value="publication">이벤트 전달</Tab><Tab value="order">주문 후속 처리</Tab><Tab value="repair">환불 복구 승인</Tab><Tab value="points">포인트 조사</Tab><Tab value="refund">품목 환불</Tab></TabList><TabPanel value="notification"><ManualRecoveryWorkspace key="notification" kind="notification" /></TabPanel><TabPanel value="publication"><ManualRecoveryWorkspace key="publication" kind="publication" /></TabPanel><TabPanel value="order"><OrderCompensationWorkspace onLockChange={setOrderLocked} /></TabPanel><TabPanel value="repair"><PaymentRepairWorkspace onLockChange={setRepairLocked} /></TabPanel><TabPanel value="points"><PointAccountWorkspace /></TabPanel><TabPanel value="refund"><OperationsRefundWorkspace /></TabPanel></Tabs></div>;
}
