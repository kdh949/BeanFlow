import { createBrowserRouter } from "react-router";
import { ConsoleShell, CustomerShell, RootRedirect } from "./components/Shells";
import { CheckoutPage } from "./features/payment/CheckoutPage";
import {
  CustomerHelpPage,
  PaymentFailPage,
  PaymentSuccessPage,
} from "./features/payment/PaymentResultPages";
import { CartPage } from "./features/ordering/CartPage";
import { CustomerPointsPage } from "./features/loyalty/PointsPage";
import { StoreDetailPage } from "./features/ordering/StoreDetailPage";
import { CustomerHomePage } from "./features/discovery/HomePage";
import { StoreSearchPage } from "./features/discovery/StoreSearchPage";
import { CustomerOrderDetailPage, CustomerOrdersPage } from "./features/ordering/OrderPages";
import { CustomerLoginPage, CustomerSignupPage } from "./features/auth/customer/AuthPages";
import { CustomerMyPage } from "./features/auth/customer/MyPage";
import { CustomerSessionGate } from "./features/auth/customer/CustomerSessionGate";
import { MerchantLoginPage, MerchantPasswordChangePage } from "./features/auth/merchant/MerchantAuthPages";
import { MerchantSessionGate } from "./features/auth/merchant/MerchantSessionGate";
import { StoreRefundPage } from "./features/merchant/StoreRefundPage";
import { StoreSettlementsPage } from "./features/merchant/StoreSettlementsPage";
import { StoreDisputesPage } from "./features/merchant/StoreDisputesPage";
import { OpsDashboardPage, OpsOrderPage } from "./pages/console/ConsolePages";
import { StoreOrderBoardPage } from "./pages/console/StoreOrderBoard";
import { ButtonLink } from "./design-system";
import { CouponWalletPage } from "./features/customer/CouponWalletPage";
import { FavoriteStoresPage } from "./features/customer/FavoriteStoresPage";
import { StoreRegionPage } from "./features/merchant/StoreRegionPage";
import { OperationsSessionGate } from "./features/auth/operations/OperationsSessionGate";
import { MerchantAccountsPage } from "./features/operations/MerchantAccountsPage";

export function NotFoundPage() {
  return <main className="not-found"><strong>404</strong><h1>화면을 찾을 수 없습니다</h1><ButtonLink to="/">처음으로</ButtonLink></main>;
}

export const router = createBrowserRouter([
  { path: "/", element: <RootRedirect /> },
  {
    path: "/app", element: <CustomerShell />, children: [
      { path: "login", element: <CustomerLoginPage /> },
      { path: "signup", element: <CustomerSignupPage /> },
      { path: "help", element: <CustomerHelpPage /> },
      {
        element: <CustomerSessionGate />, children: [
          { index: true, element: <CustomerHomePage /> },
          { path: "stores", element: <StoreSearchPage /> },
          { path: "stores/:storeId", element: <StoreDetailPage /> },
          { path: "cart", element: <CartPage /> },
          { path: "checkout/:orderId", element: <CheckoutPage /> },
          { path: "payments/:paymentId/success", element: <PaymentSuccessPage /> },
          { path: "payments/:paymentId/fail", element: <PaymentFailPage /> },
          { path: "orders", element: <CustomerOrdersPage /> },
          { path: "orders/:orderReference", element: <CustomerOrderDetailPage /> },
          { path: "points", element: <CustomerPointsPage /> },
          { path: "coupons", element: <CouponWalletPage /> },
          { path: "favorites", element: <FavoriteStoresPage /> },
          { path: "me", element: <CustomerMyPage /> },
        ],
      },
    ],
  },
  {
    path: "/store", element: <ConsoleShell kind="store" />, children: [
      { path: "login", element: <MerchantLoginPage /> },
      { path: "password", element: <MerchantPasswordChangePage /> },
      {
        element: <MerchantSessionGate />, children: [
          { index: true, element: <StoreOrderBoardPage /> },
          { path: "refunds/:storeId/:orderReference", element: <StoreRefundPage /> },
          { path: "settlements", element: <StoreSettlementsPage /> },
          { path: "disputes", element: <StoreDisputesPage /> },
          { path: "region", element: <StoreRegionPage /> },
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
        ],
      },
    ],
  },
  { path: "*", element: <NotFoundPage /> },
]);
