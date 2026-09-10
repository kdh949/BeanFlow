import { createBrowserRouter, redirect } from "react-router";
import { ConsoleShell, CustomerShell, RootRedirect } from "./presentation/AppShells";
import {
  CustomerHelpPage,
  PaymentFailPage,
  PaymentSuccessPage,
} from "./features/payment/PaymentResultPages";
import { CustomerPointsPage } from "./features/loyalty/PointsPage";
import { CustomerOrdersPage } from "./features/ordering/CustomerOrdersPage";
import { CustomerLoginPage, CustomerSignupPage } from "./features/auth/customer/AuthPages";
import { CustomerMyPage } from "./features/auth/customer/MyPage";
import { CustomerSessionGate } from "./features/auth/customer/CustomerSessionGate";
import { ButtonLink } from "./design-system";
import { CouponWalletPage } from "./features/customer/CouponWalletPage";
import { FavoriteStoresPage } from "./features/customer/FavoriteStoresPage";
import { NotificationInboxPage } from "./features/notification/NotificationInboxPage";
import {
  RefreshCartPage,
  RefreshCheckoutPage,
  RefreshLegacyCheckoutPage,
  RefreshCustomerHomePage,
  EventCampaignPage,
  RefreshCustomerOrderDetailPage,
  RefreshStoreDetailPage,
  RefreshStoreSearchPage,
} from "./presentation/beanflow-refresh";

export function NotFoundPage() {
  return <main className="not-found"><strong>404</strong><h1>화면을 찾을 수 없습니다</h1><ButtonLink to="/">처음으로</ButtonLink></main>;
}

export const router = createBrowserRouter([
  { path: "/", element: <RootRedirect /> },
  {
    path: "/app", children: [
      { element: <CustomerShell />, children: [
        { path: "login", element: <CustomerLoginPage /> },
        { path: "signup", element: <CustomerSignupPage /> },
        { path: "help", element: <CustomerHelpPage /> },
      ] },
      {
        element: <CustomerSessionGate />, children: [
          { element: <CustomerShell />, children: [
            { index: true, element: <RefreshCustomerHomePage /> },
            { path: "events", element: <EventCampaignPage /> },
            { path: "stores", element: <RefreshStoreSearchPage /> },
            { path: "stores/:storeId", element: <RefreshStoreDetailPage /> },
            { path: "cart", element: <RefreshCartPage /> },
            { path: "checkout/:orderId", element: <RefreshLegacyCheckoutPage /> },
            { path: "orders/:orderReference/checkout", element: <RefreshCheckoutPage /> },
            { path: "orders/:orderReference", element: <RefreshCustomerOrderDetailPage /> },
          ] },
          { element: <CustomerShell />, children: [
            { path: "payments/:paymentId/success", element: <PaymentSuccessPage /> },
            { path: "payments/:paymentId/fail", element: <PaymentFailPage /> },
            { path: "orders", element: <CustomerOrdersPage /> },
            { path: "support", lazy: async () => { const { CustomerInquiryDirectoryPage: Component } = await import("./features/support/CustomerInquiryPages"); return { Component }; } },
            { path: "support/new", lazy: async () => { const { CustomerInquiryCreatePage: Component } = await import("./features/support/CustomerInquiryPages"); return { Component }; } },
            { path: "support/:inquiryId", lazy: async () => { const { CustomerInquiryDetailPage: Component } = await import("./features/support/CustomerInquiryPages"); return { Component }; } },
            { path: "points", element: <CustomerPointsPage /> },
            { path: "coupons", element: <CouponWalletPage /> },
            { path: "refunds", loader: () => redirect("/app/orders?status=PAST") },
            { path: "coupon-claims", loader: () => redirect("/app/events") },
            { path: "favorites", element: <FavoriteStoresPage /> },
            { path: "recent-stores", lazy: async () => { const { RecentStoresPage: Component } = await import("./features/customer/RecentStoresPage"); return { Component }; } },
            { path: "notifications", element: <NotificationInboxPage /> },
            { path: "me", element: <CustomerMyPage /> },
          ] },
        ],
      },
    ],
  },
  {
    path: "/store", children: [
      { element: <ConsoleShell kind="store" />, children: [
        { path: "login", lazy: async () => { const { MerchantLoginPage: Component } = await import("./features/auth/merchant/MerchantAuthPages"); return { Component }; } },
        { path: "password", lazy: async () => { const { MerchantPasswordChangePage: Component } = await import("./features/auth/merchant/MerchantAuthPages"); return { Component }; } },
      ] },
      {
        lazy: async () => { const { MerchantSessionGate: Component } = await import("./features/auth/merchant/MerchantSessionGate"); return { Component }; }, children: [
          { element: <ConsoleShell kind="store" />, children: [
            { index: true, lazy: async () => { const { RefreshStoreOrderBoardPage: Component } = await import("./presentation/beanflow-refresh/MerchantPages"); return { Component }; } },
            { path: "refunds/:storeId/:orderReference", lazy: async () => { const { RefreshStoreRefundPage: Component } = await import("./presentation/beanflow-refresh/MerchantPages"); return { Component }; } },
          ] },
          { element: <ConsoleShell kind="store" />, children: [
            { path: "settlements", lazy: async () => { const { StoreSettlementsPage: Component } = await import("./features/merchant/StoreSettlementsPage"); return { Component }; } },
            { path: "disputes", lazy: async () => { const { StoreDisputesPage: Component } = await import("./features/merchant/StoreDisputesPage"); return { Component }; } },
            { path: "disputes/:disputeId", lazy: async () => { const { StoreDisputeDetailPage: Component } = await import("./features/merchant/StoreDisputeDetailPage"); return { Component }; } },
            { path: "management", lazy: async () => { const [{ StoreManagementPage }, { StoreCatalogPage }] = await Promise.all([import("./features/merchant/StoreManagementPage"), import("./features/merchant/StoreCatalogPage")]); return { Component: () => <StoreManagementPage catalogContent={<StoreCatalogPage embedded />} /> }; } },
            { path: "catalog", loader: () => redirect("/store/management") },
            { path: "region", lazy: async () => { const { StoreRegionPage: Component } = await import("./features/merchant/StoreRegionPage"); return { Component }; } },
          ] },
        ],
      },
    ],
  },
  {
    path: "/ops", element: <ConsoleShell kind="ops" />, children: [
      { path: "auth/callback", lazy: async () => { const { OperationsSessionGate: Component } = await import("./features/auth/operations/OperationsSessionGate"); return { Component: () => <Component callback /> }; } },
      {
        lazy: async () => { const { OperationsSessionGate: Component } = await import("./features/auth/operations/OperationsSessionGate"); return { Component }; }, children: [
          { index: true, lazy: async () => { const { OpsDashboardPage: Component } = await import("./pages/console/ConsolePages"); return { Component }; } },
          { path: "orders", lazy: async () => { const { OpsOrderPage: Component } = await import("./pages/console/ConsolePages"); return { Component }; } },
          { path: "merchant-accounts", lazy: async () => { const { MerchantAccountsPage: Component } = await import("./features/operations/MerchantAccountsPage"); return { Component }; } },
          { path: "stores", lazy: async () => { const { OperationsStoresPage: Component } = await import("./features/operations/OperationsStoresPage"); return { Component }; } },
          { path: "recovery", lazy: async () => { const { OperationsRecoveryPage: Component } = await import("./features/operations/OperationsRecoveryPage"); return { Component }; } },
          { path: "support-investigations", lazy: async () => { const { OperationsSupportInvestigationPage: Component } = await import("./features/operations/OperationsSupportInvestigationPage"); return { Component }; } },
          { path: "control", lazy: async () => { const { OperationsControlPage: Component } = await import("./features/operations/OperationsControlPage"); return { Component }; } },
          { path: "policies", lazy: async () => { const { OperationsPolicyPage: Component } = await import("./features/operations/OperationsPolicyPage"); return { Component }; } },
          { path: "campaigns", lazy: async () => { const { CouponCampaignsPage: Component } = await import("./features/operations/CouponCampaignsPage"); return { Component }; } },
        ],
      },
    ],
  },
  {
    path: "/support", element: <ConsoleShell kind="support" />, children: [
      { lazy: async () => { const { OperationsSessionGate: Component } = await import("./features/auth/operations/OperationsSessionGate"); return { Component }; }, children: [
        { index: true, lazy: async () => { const { SupportWorkspacePage: Component } = await import("./features/support/SupportWorkspacePage"); return { Component }; } },
        { path: "cases", lazy: async () => { const { SupportCaseDirectoryPage: Component } = await import("./features/support/SupportCaseDirectoryPage"); return { Component }; } },
        { path: "inquiries", lazy: async () => { const { SupportInquiryDirectoryPage: Component } = await import("./features/support/SupportInquiryPages"); return { Component }; } },
        { path: "inquiries/:inquiryId", lazy: async () => { const { SupportInquiryDetailPage: Component } = await import("./features/support/SupportInquiryPages"); return { Component }; } },
        { path: "action-requests/:requestId", lazy: async () => { const { SupportOrderActionPage: Component } = await import("./features/support/SupportOrderActionWorkspace"); return { Component }; } },
        { path: "break-glass/:requestId", lazy: async () => { const { SupportBreakGlassPage: Component } = await import("./features/support/SupportBreakGlassWorkspace"); return { Component }; } },
        { path: "profile-changes/:profileChangeId", lazy: async () => { const { SupportProfileChangePage: Component } = await import("./features/support/SupportProfileChangeWorkspace"); return { Component }; } },
        { path: "compensations/:compensationRequestId", lazy: async () => { const { SupportCompensationPage: Component } = await import("./features/support/SupportCompensationWorkspace"); return { Component }; } },
        { path: "resolutions/:resolutionId", lazy: async () => { const { SupportResolutionPage: Component } = await import("./features/support/SupportResolutionWorkspace"); return { Component }; } },
        { path: "data-access/:grantId", lazy: async () => { const { SupportDataAccessPage: Component } = await import("./features/support/SupportDataAccessWorkspace"); return { Component }; } },
        { path: "cases/:caseId", lazy: async () => { const { SupportCaseManagementPage: Component } = await import("./features/support/SupportCaseManagementPage"); return { Component }; } },
        { path: "follow-up", lazy: async () => { const { SupportFollowUpRoute: Component } = await import("./features/support/SupportFollowUpRoute"); return { Component }; } },
      ] },
    ],
  },
  { path: "*", element: <NotFoundPage /> },
]);
