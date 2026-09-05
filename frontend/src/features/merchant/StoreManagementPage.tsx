import { BarChart3, Boxes, Clock3, Gift, PackageSearch, Settings2 } from "lucide-react";
import { useState } from "react";
import { EmptyState, InlineNotice, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { StatusText } from "../../presentation/shared";
import { won } from "../../lib/format";

type Workspace = "catalog" | "inventory" | "hours" | "benefits" | "analytics";

export type StoreCatalogItem = {
  menuId: string;
  name: string;
  category: string;
  priceKrw: number;
  state: "ACTIVE" | "SOLD_OUT" | "ARCHIVED";
  optionSummary: string;
};

export type StoreInventoryItem = {
  inventoryId: string;
  name: string;
  onHand: number;
  reserved: number;
  unit: string;
  state: "AVAILABLE" | "LOW" | "DEPLETED";
};

export type StoreScheduleDay = { day: string; hours: string; closed: boolean };
export type StoreMetric = { label: string; value: string; change: string };

export type StoreManagementPageProps = {
  initialWorkspace?: Workspace;
  scenario?: "contract-pending" | "ready" | "empty";
  catalog?: readonly StoreCatalogItem[];
  inventory?: readonly StoreInventoryItem[];
  schedule?: readonly StoreScheduleDay[];
  pickupPolicy?: { acceptingOrders: boolean; pickupEnabled: boolean; nextWindow: string; version: number };
  benefitPolicy?: { pointRateLabel: string; couponPolicyLabel: string; campaignCount: number };
  metrics?: readonly StoreMetric[];
};

const workspaces: Array<{ value: Workspace; label: string; icon: typeof Settings2 }> = [
  { value: "catalog", label: "메뉴와 가격", icon: PackageSearch },
  { value: "inventory", label: "재고", icon: Boxes },
  { value: "hours", label: "영업시간과 픽업", icon: Clock3 },
  { value: "benefits", label: "포인트와 쿠폰", icon: Gift },
  { value: "analytics", label: "매출", icon: BarChart3 },
];

/** Store owner workspace composed exclusively from the canonical console controls. */
export function StoreManagementPage({
  initialWorkspace = "catalog",
  scenario = "contract-pending",
  catalog = [],
  inventory = [],
  schedule = [],
  pickupPolicy,
  benefitPolicy,
  metrics = [],
}: StoreManagementPageProps) {
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  return (
    <div className="console-page store-management-page">
      <PageHeading title="매장 관리" />
      <Tabs value={workspace} onValueChange={(value) => setWorkspace(value as Workspace)}>
        <TabList label="매장 관리 업무 선택">
          {workspaces.map(({ value, label, icon: Icon }) => <Tab key={value} value={value}><Icon size={17} aria-hidden="true" /> {label}</Tab>)}
        </TabList>
        <TabPanel value="catalog">
          <WorkspaceSection eyebrow="판매 메뉴" title="메뉴와 가격">
            {scenario === "contract-pending" ? <ContractPending title="메뉴 관리를 준비하고 있습니다" description="지금은 메뉴, 옵션, 가격과 판매 상태를 바꿀 수 없습니다." />
              : catalog.length === 0 ? <EmptyState title="등록된 메뉴가 없습니다" description="메뉴 등록 기능이 준비되면 첫 메뉴를 추가할 수 있습니다." />
                : <div className="management-card-grid">{catalog.map((item) => <article className="surface-card management-card" key={item.menuId}><div className="panel-heading"><div><span className="context-label">{item.category}</span><h3>{item.name}</h3></div><StatusText state={item.state} /></div><strong className="management-value bf-num">{won.format(item.priceKrw)}</strong><p>{item.optionSummary}</p></article>)}</div>}
          </WorkspaceSection>
        </TabPanel>
        <TabPanel value="inventory">
          <WorkspaceSection eyebrow="남은 수량" title="재고">
            {scenario === "contract-pending" ? <ContractPending title="재고 관리를 준비하고 있습니다" description="지금은 남은 수량과 주문에 잡힌 수량을 확인하거나 바꿀 수 없습니다." />
              : inventory.length === 0 ? <EmptyState title="등록된 재고가 없습니다" description="메뉴와 재고를 연결하면 남은 수량을 볼 수 있습니다." />
                : <div className="management-card-grid">{inventory.map((item) => <article className="surface-card management-card" key={item.inventoryId}><div className="panel-heading"><div><span className="context-label">재고 항목</span><h3>{item.name}</h3></div><StatusText state={item.state} /></div><strong className="management-value">남은 {item.onHand - item.reserved}{item.unit}</strong><dl className="detail-list"><div><dt>보유</dt><dd>{item.onHand}{item.unit}</dd></div><div><dt>주문 예약</dt><dd>{item.reserved}{item.unit}</dd></div></dl></article>)}</div>}
          </WorkspaceSection>
        </TabPanel>
        <TabPanel value="hours">
          <WorkspaceSection eyebrow="한국 시간" title="영업시간과 픽업">
            {scenario === "contract-pending" ? <ContractPending title="영업시간과 픽업 관리를 준비하고 있습니다" description="현재 영업시간은 볼 수 있지만 픽업 시간과 주문 접수 설정은 아직 바꿀 수 없습니다." />
              : !pickupPolicy ? <EmptyState title="등록된 운영 설정이 없습니다" description="영업시간과 주문 접수 설정을 등록해 주세요." />
                : <div className="console-detail-grid management-detail-grid"><section className="surface-card management-card"><div className="panel-heading"><div><span className="context-label">주간 영업시간</span><h3>고객에게 표시되는 시간</h3></div><StatusText state={`v${pickupPolicy.version}`} label={`${pickupPolicy.version}번째 설정`} /></div><dl className="schedule-list">{schedule.map((item) => <div key={item.day}><dt>{item.day}</dt><dd>{item.hours}</dd></div>)}</dl></section><section className="surface-card management-card"><div className="panel-heading"><div><span className="context-label">현재 설정</span><h3>주문과 픽업</h3></div><StatusText state={pickupPolicy.acceptingOrders && pickupPolicy.pickupEnabled ? "AVAILABLE" : "PAUSED"} label={pickupPolicy.acceptingOrders && pickupPolicy.pickupEnabled ? "주문 가능" : "주문 중지"} /></div><dl className="detail-list"><div><dt>주문 접수</dt><dd>{pickupPolicy.acceptingOrders ? "받는 중" : "중지"}</dd></div><div><dt>픽업</dt><dd>{pickupPolicy.pickupEnabled ? "가능" : "중지"}</dd></div><div><dt>다음 픽업 시간</dt><dd>{pickupPolicy.nextWindow}</dd></div></dl></section></div>}
          </WorkspaceSection>
        </TabPanel>
        <TabPanel value="benefits">
          <WorkspaceSection eyebrow="고객 혜택과 매장 부담" title="포인트와 쿠폰">
            {scenario === "contract-pending" ? <ContractPending title="포인트와 쿠폰 관리를 준비하고 있습니다" description="지금은 적립률과 쿠폰 비용 부담을 바꿀 수 없습니다." />
              : !benefitPolicy ? <EmptyState title="등록된 혜택 설정이 없습니다" description="현재 적용 중인 포인트와 쿠폰 설정을 확인할 수 없습니다." />
                : <div className="management-card-grid"><article className="surface-card management-card"><span className="context-label">포인트 적립</span><h3>{benefitPolicy.pointRateLabel}</h3><p>이 적립률은 주문할 때 확정됩니다.</p></article><article className="surface-card management-card"><span className="context-label">쿠폰 비용</span><h3>{benefitPolicy.couponPolicyLabel}</h3><p>진행 중인 쿠폰 행사 {benefitPolicy.campaignCount}개</p></article></div>}
          </WorkspaceSection>
        </TabPanel>
        <TabPanel value="analytics">
          <WorkspaceSection eyebrow="한국 시간 기준" title="매출">
            {scenario === "contract-pending" ? <ContractPending title="매출 화면을 준비하고 있습니다" description="환불과 지연을 반영한 매출 정보를 아직 볼 수 없습니다." />
              : metrics.length === 0 ? <EmptyState title="표시할 매출이 없습니다" description="완료된 주문이 생기면 매출과 운영 정보가 표시됩니다." />
                : <section className="metric-grid">{metrics.map((metric) => <article className="metric-card" key={metric.label}><BarChart3 aria-hidden="true" /><small>{metric.label}</small><strong>{metric.value}</strong><p>{metric.change}</p></article>)}</section>}
          </WorkspaceSection>
        </TabPanel>
      </Tabs>
    </div>
  );
}

function WorkspaceSection({ eyebrow, title, children }: { eyebrow: string; title: string; children: React.ReactNode }) {
  return <section className="management-workspace"><div className="panel-heading"><div><span className="context-label">{eyebrow}</span><h2>{title}</h2></div></div>{children}</section>;
}

function ContractPending({ title, description }: { title: string; description: string }) {
  return <InlineNotice tone="danger" announce="assertive" title={title} description={description} />;
}
