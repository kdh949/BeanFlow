import React from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router/dom";
import { router } from "./router";
import "./design-system/styles.css";
import "./styles.css";

const root = document.getElementById("root");

if (!root) {
  throw new Error("BeanFlow root element is missing");
}

createRoot(root).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>,
);
