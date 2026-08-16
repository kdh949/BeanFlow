import { createBrowserRouter } from "react-router";
import { ConsoleShell, CustomerShell, RootRedirect } from "./components/Shells";
import { CustomerLoginPage, CustomerSignupPage } from "./features/auth/customer/AuthPages";
import { CustomerSessionGate } from "./features/auth/customer/CustomerSessionGate";
import { CustomerMyPage } from "./features/auth/customer/MyPage";
import { CheckoutPage } from "./features/payment/CheckoutPage";
import { CustomerHelpPage, PaymentFailPage, PaymentSuccessPage } from "./features/payment/PaymentResultPages";
import { CustomerHomePage } from "./features/discovery/HomePage";
import { StoreSearchPage } from "./features/discovery/StoreSearchPage";
import { StoreDetailPage } from "./features/ordering/StoreDetailPage";
import { CartPage } from "./features/ordering/CartPage";
import { CustomerOrderDetailPage, CustomerOrdersPage } from "./features/ordering/OrderPages";
import { OpsDashboardPage, OpsOrderPage, OpsRefundPage } from "./pages/console/ConsolePages";
import { StoreOrderBoardPage } from "./pages/console/StoreOrderBoard";
import { ButtonLink } from "./design-system";

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
        // Everything that needs an actor waits for `GET /me` behind this gate.
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
          { path: "me", element: <CustomerMyPage /> },
        ],
      },
    ],
  },
  { path: "/store", element: <ConsoleShell kind="store" />, children: [{ index: true, element: <StoreOrderBoardPage /> }] },
  { path: "/ops", element: <ConsoleShell kind="ops" />, children: [{ index: true, element: <OpsDashboardPage /> }, { path: "refunds", element: <OpsRefundPage /> }, { path: "orders", element: <OpsOrderPage /> }] },
  { path: "*", element: <NotFoundPage /> },
]);
