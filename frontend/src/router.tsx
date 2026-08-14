import { createBrowserRouter } from "react-router";
import { ConsoleShell, CustomerShell, RootRedirect } from "./components/Shells";
import {
  CheckoutPage,
  CustomerHelpPage,
  CustomerHomePage,
  PaymentFailPage,
  PaymentSuccessPage,
  StoreCatalogPage,
} from "./pages/customer/CustomerPages";
import { CustomerOrderDetailPage, CustomerOrdersPage } from "./pages/customer/CustomerOrders";
import { OpsDashboardPage, OpsOrderPage, OpsRefundPage } from "./pages/console/ConsolePages";
import { StoreOrderBoardPage } from "./pages/console/StoreOrderBoard";

function NotFoundPage() {
  return <main className="not-found"><strong>404</strong><h1>화면을 찾을 수 없습니다</h1><a className="button button-primary" href="/">처음으로</a></main>;
}

export const router = createBrowserRouter([
  { path: "/", element: <RootRedirect /> },
  {
    path: "/app", element: <CustomerShell />, children: [
      { index: true, element: <CustomerHomePage /> },
      { path: "stores/:storeId", element: <StoreCatalogPage /> },
      { path: "checkout/:orderId", element: <CheckoutPage /> },
      { path: "payments/:paymentId/success", element: <PaymentSuccessPage /> },
      { path: "payments/:paymentId/fail", element: <PaymentFailPage /> },
      { path: "orders", element: <CustomerOrdersPage /> },
      { path: "orders/:orderReference", element: <CustomerOrderDetailPage /> },
      { path: "help", element: <CustomerHelpPage /> },
    ],
  },
  { path: "/store", element: <ConsoleShell kind="store" />, children: [{ index: true, element: <StoreOrderBoardPage /> }] },
  { path: "/ops", element: <ConsoleShell kind="ops" />, children: [{ index: true, element: <OpsDashboardPage /> }, { path: "refunds", element: <OpsRefundPage /> }, { path: "orders", element: <OpsOrderPage /> }] },
  { path: "*", element: <NotFoundPage /> },
]);
