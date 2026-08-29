import { createBrowserRouter } from "react-router";
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
import { MerchantLoginPage, MerchantPasswordChangePage } from "./features/auth/merchant/MerchantAuthPages";
import { MerchantSessionGate } from "./features/auth/merchant/MerchantSessionGate";
import { StoreSettlementsPage } from "./features/merchant/StoreSettlementsPage";
import { StoreDisputesPage } from "./features/merchant/StoreDisputesPage";
import { OpsDashboardPage, OpsOrderPage } from "./pages/console/ConsolePages";
import { ButtonLink } from "./design-system";
import { CouponWalletPage } from "./features/customer/CouponWalletPage";
import { FavoriteStoresPage } from "./features/customer/FavoriteStoresPage";
import { StoreRegionPage } from "./features/merchant/StoreRegionPage";
import { OperationsSessionGate } from "./features/auth/operations/OperationsSessionGate";
import { MerchantAccountsPage } from "./features/operations/MerchantAccountsPage";
import {
  SupportApprovalsRoute,
  SupportCaseDetailRoute,
  SupportCaseIntakeRoute,
  SupportCompensationRoute,
  SupportIndexRoute,
  SupportOrderActionRoute,
  SupportProfileChangeRoute,
  SupportQueueRoute,
  SupportVerificationRoute,
} from "./features/support/SupportCenterRoutes";
import { OperationsPolicyPage } from "./features/operations/OperationsPolicyPage";
import { NotificationInboxPage } from "./features/notification/NotificationInboxPage";
import {
  RefreshCartPage,
  RefreshCheckoutPage,
  RefreshCustomerHomePage,
  RefreshCustomerOrderDetailPage,
  RefreshStoreDetailPage,
  RefreshStoreOrderBoardPage,
  RefreshStoreRefundPage,
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
            { path: "stores", element: <RefreshStoreSearchPage /> },
            { path: "stores/:storeId", element: <RefreshStoreDetailPage /> },
            { path: "cart", element: <RefreshCartPage /> },
            { path: "checkout/:orderId", element: <RefreshCheckoutPage /> },
            { path: "orders/:orderReference", element: <RefreshCustomerOrderDetailPage /> },
          ] },
          { element: <CustomerShell />, children: [
            { path: "payments/:paymentId/success", element: <PaymentSuccessPage /> },
            { path: "payments/:paymentId/fail", element: <PaymentFailPage /> },
            { path: "orders", element: <CustomerOrdersPage /> },
            { path: "points", element: <CustomerPointsPage /> },
            { path: "coupons", element: <CouponWalletPage /> },
            { path: "favorites", element: <FavoriteStoresPage /> },
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
        { path: "login", element: <MerchantLoginPage /> },
        { path: "password", element: <MerchantPasswordChangePage /> },
      ] },
      {
        element: <MerchantSessionGate />, children: [
          { element: <ConsoleShell kind="store" />, children: [
            { index: true, element: <RefreshStoreOrderBoardPage /> },
            { path: "refunds/:storeId/:orderReference", element: <RefreshStoreRefundPage /> },
          ] },
          { element: <ConsoleShell kind="store" />, children: [
            { path: "settlements", element: <StoreSettlementsPage /> },
            { path: "disputes", element: <StoreDisputesPage /> },
            { path: "region", element: <StoreRegionPage /> },
          ] },
        ],
      },
    ],
  },
  {
    path: "/ops", element: <ConsoleShell kind="ops" />, children: [
      { path: "auth/callback", element: <OperationsSessionGate callback /> },
      {
        element: <OperationsSessionGate />, children: [
          { index: true, element: <OpsDashboardPage /> },
          { path: "orders", element: <OpsOrderPage /> },
          { path: "merchant-accounts", element: <MerchantAccountsPage /> },
          { path: "policies", element: <OperationsPolicyPage /> },
        ],
      },
    ],
  },
  {
    path: "/support", element: <ConsoleShell kind="support" />, children: [
      { element: <OperationsSessionGate />, children: [
        { index: true, element: <SupportIndexRoute /> },
        { path: "queue", element: <SupportQueueRoute /> },
        { path: "cases/new", element: <SupportCaseIntakeRoute /> },
        { path: "cases/:caseId", element: <SupportCaseDetailRoute /> },
        { path: "cases/:caseId/verification", element: <SupportVerificationRoute /> },
        { path: "cases/:caseId/orders/:orderId/action", element: <SupportOrderActionRoute /> },
        { path: "cases/:caseId/compensations/:requestId?", element: <SupportCompensationRoute /> },
        { path: "cases/:caseId/profile-changes/:profileChangeId?", element: <SupportProfileChangeRoute /> },
        { path: "compensations", element: <SupportCompensationRoute /> },
        { path: "profile-changes", element: <SupportProfileChangeRoute /> },
        { path: "approvals/:taskType?/:resourceId?", element: <SupportApprovalsRoute /> },
      ] },
    ],
  },
  { path: "*", element: <NotFoundPage /> },
]);
