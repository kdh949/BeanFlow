import assert from "node:assert/strict";
import { access, readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, extname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const staticRoot = join(root, "storybook-static");

const expectedDocs = new Set([
  "explorations-workflow--docs",
  "foundations-overview--docs",
  "components-brand-brandlockup--docs",
  "components-commerce-statustext--docs",
  "components-core-button--docs",
  "components-feedback-feedbackstate--docs",
  "components-forms-quantitystepper--docs",
  "components-forms-searchfield--docs",
  "patterns-layout-pageheading--docs",
  "patterns-navigation-app-shells--docs",
  "pages-operations-dashboard--docs",
  "pages-operations-compensationlookup--docs",
  "pages-refresh-store-order-board--docs",
  "pages-refresh-customer-checkout--docs",
  "pages-customer-help--docs",
  "pages-refresh-customer-order-detail--docs",
  "pages-customer-orders--docs",
  "pages-refresh-customer-home--docs",
  "pages-customer-paymentfailure--docs",
  "pages-customer-paymentsuccess--docs",
  "pages-refresh-customer-store-detail--docs",
  "pages-refresh-customer-store-search--docs",
  "pages-customer-sign-in--docs",
  "pages-customer-my-page--docs",
  "pages-refresh-customer-cart--docs",
  "pages-customer-notifications--docs",
  "pages-customer-points--docs",
  "patterns-customer-session-gate--docs",
  "pages-shared-notfound--docs",
  "patterns-store-session-gate--docs",
  "pages-store-sign-in--docs",
  "pages-refresh-store-item-refund--docs",
  "pages-store-settlements--docs",
  "pages-store-disputes--docs",
  "pages-store-region--docs",
  "patterns-customer-favorite-store-action--docs",
  "pages-customer-coupon-wallet--docs",
  "pages-customer-favorite-stores--docs",
  "pages-operations-authentication--docs",
  "pages-operations-merchant-accounts--docs",
  "pages-operations-policy-management--docs",
  "pages-support-workspace--docs",
]);

const statefulDocs = {
  "pages-operations-compensationlookup--docs": {
    "pages-operations-compensationlookup--idle": "감사 조회 대기",
    "pages-operations-compensationlookup--manual-review": "PROVIDER_TIMEOUT",
    "pages-operations-compensationlookup--successful-lookup": "완료",
    "pages-operations-compensationlookup--recoverable-error": "서비스 연결을 확인하고 있습니다",
    "pages-operations-compensationlookup--loading": "보상 상태를 조회하는 중",
  },
  "pages-refresh-store-order-board--docs": {
    "pages-refresh-store-order-board--active-orders": "A-142",
    "pages-refresh-store-order-board--empty-board": "대기 주문 없음",
    "pages-refresh-store-order-board--transition-conflict": "다른 작업자가 먼저 처리했습니다",
    "pages-refresh-store-order-board--overflow-queue": "오래된 준비 완료 작업 2건 보기",
  },
  "pages-refresh-customer-home--docs": {
    "pages-refresh-customer-home--active-order-and-recommendations": "최근 주문한 매장",
    "pages-refresh-customer-home--nothing-in-progress": "진행 중인 주문이 없어요",
    "pages-refresh-customer-home--recommendation-failure": "서비스 연결을 확인하고 있습니다",
  },
  "pages-refresh-customer-store-detail--docs": {
    "pages-refresh-customer-store-detail--orderable": "오트 라떼",
    "pages-refresh-customer-store-detail--pickup-unavailable": "판매 중인 메뉴가 없어요",
  },
  "pages-refresh-customer-checkout--docs": {
    "pages-refresh-customer-checkout--pending-payment": "Toss Payments 통합결제창에서 선택",
    "pages-refresh-customer-checkout--expired-order": "결제 시간이 만료됐어요",
  },
  "pages-customer-orders--docs": {
    "pages-customer-orders--active-order": "A-142",
    "pages-customer-orders--past-order": "픽업 완료",
    "pages-customer-orders--empty": "진행 중인 주문이 없어요",
    "pages-customer-orders--recoverable-error": "서비스 연결을 확인하고 있습니다",
    "pages-customer-orders--loading": "주문을 불러오는 중",
  },
  "pages-refresh-customer-order-detail--docs": {
    "pages-refresh-customer-order-detail--ready-for-pickup": "거래 요약",
    "pages-refresh-customer-order-detail--refund-delayed": "환불 확인이 지연되고 있어요",
    "pages-refresh-customer-order-detail--cancelled": "취소된 주문이에요",
    "pages-refresh-customer-order-detail--permission-failure": "이 작업을 진행할 수 없습니다",
  },
  "pages-customer-paymentsuccess--docs": {
    "pages-customer-paymentsuccess--approved": "결제가 완료됐어요",
    "pages-customer-paymentsuccess--unknown-reconciliation": "결제 결과를 확인하고 있어요",
    "pages-customer-paymentsuccess--not-paid-yet": "아직 결제가 끝나지 않았어요",
    "pages-customer-paymentsuccess--declined": "결제를 완료하지 못했어요",
    "pages-customer-paymentsuccess--dependency-error": "서비스 연결을 확인하고 있습니다",
    "pages-customer-paymentsuccess--manual-review": "결제 확인에 시간이 더 필요해요",
  },
  "pages-customer-paymentfailure--docs": {
    "pages-customer-paymentfailure--retryable-failure": "주문 상태 보기",
    "pages-customer-paymentfailure--manual-review": "결제 확인에 시간이 더 필요해요",
  },
  "pages-refresh-customer-store-search--docs": {
    "pages-refresh-customer-store-search--results": "시청점",
    "pages-refresh-customer-store-search--query-helper": "시청점",
    "pages-refresh-customer-store-search--no-results": "검색 결과가 없어요",
    "pages-refresh-customer-store-search--location-permission-denied": "위치 권한이 꺼져 있어",
  },
  "pages-customer-sign-in--docs": {
    "pages-customer-sign-in--sign-in": "로그인",
    "pages-customer-sign-in--sign-up": "가입하고 시작하기",
  },
  // The cart is localStorage-backed and every docs iframe shares this origin, so
  // only one cart state can be shown here. Empty and corrupt are covered by the
  // interaction tests instead.
  "pages-refresh-customer-cart--docs": {
    "pages-refresh-customer-cart--with-items": "시청점",
  },
  "pages-refresh-store-item-refund--docs": {
    "pages-refresh-store-item-refund--selectable-items": "환불 대상 주문",
    // Submission outcomes are exercised by their play tests. The static Docs
    // smoke asserts only states that exist before user interaction.
    "pages-refresh-store-item-refund--nothing-left-to-refund": "남은 환불 가능 수량이 없습니다",
  },
  "pages-customer-points--docs": {
    "pages-customer-points--balance-and-ledger": "1,500P",
    "pages-customer-points--zero-balance": "아직 포인트 내역이 없어요",
    "pages-customer-points--account-integrity-failure": "정확한 포인트 잔액을 확인할 수 없어",
  },
  "patterns-customer-session-gate--docs": {
    "patterns-customer-session-gate--checking": "로그인 상태를 확인하는 중",
    "patterns-customer-session-gate--wrong-actor": "고객 계정으로 다시 로그인해 주세요",
    "patterns-customer-session-gate--session-store-unavailable": "로그인 상태를 불러오지 못했어요",
  },
};

const contentTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};

function staticServer() {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      const requested = decodeURIComponent(url.pathname).replace(/^\/+/, "") || "index.html";
      let path = resolve(staticRoot, requested);
      if (path !== staticRoot && !path.startsWith(`${staticRoot}${sep}`)) {
        response.writeHead(403).end("Forbidden");
        return;
      }
      if ((await stat(path)).isDirectory()) path = join(path, "index.html");
      const body = await readFile(path);
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Type": contentTypes[extname(path)] ?? "application/octet-stream",
      });
      response.end(body);
    } catch (error) {
      console.error(`Static Storybook request failed: ${request.url} (${error.code ?? error.message})`);
      response.writeHead(404).end("Not found");
    }
  });
}

async function waitForStorySurfaces(page, storyId, marker) {
  const deadline = Date.now() + 12_000;
  let lastTexts = [];
  while (Date.now() < deadline) {
    const frames = page.frameLocator("#storybook-preview-iframe").locator(`iframe#iframe--${storyId}`);
    const frameCount = await frames.count();
    if (frameCount) {
      await Promise.all(Array.from({ length: frameCount }, (_, index) => frames.nth(index).scrollIntoViewIfNeeded()));
      lastTexts = await Promise.all(Array.from({ length: frameCount }, async (_, index) => {
        try {
          return await frames.nth(index).contentFrame().locator("body").innerText({ timeout: 1_000 });
        } catch {
          return "";
        }
      }));
      if (lastTexts.every((text) => text.includes(marker))) return;
    } else {
      const surface = page.frameLocator("#storybook-preview-iframe").locator(`#story--${storyId}`);
      if (await surface.count()) {
        lastTexts = [await surface.innerText().catch(() => "")];
        if (lastTexts[0].includes(marker)) return;
      }
    }
    await page.waitForTimeout(100);
  }
  assert.fail(`${storyId} did not render isolated marker ${JSON.stringify(marker)}. Frames: ${JSON.stringify(lastTexts)}`);
}

async function waitForDocsText(page, docsBody) {
  const deadline = Date.now() + 12_000;
  let text = "";
  while (Date.now() < deadline) {
    text = await docsBody.innerText();
    if (text.length > 50) return text;
    await page.waitForTimeout(100);
  }
  return text;
}

await access(join(staticRoot, "index.html"));
const server = staticServer();
await new Promise((resolveListen, reject) => {
  server.once("error", reject);
  server.listen(0, "127.0.0.1", resolveListen);
});

const address = server.address();
assert(address && typeof address !== "string");
const origin = `http://127.0.0.1:${address.port}`;
let browser;

try {
  const index = await fetch(`${origin}/index.json`).then((response) => response.json());
  const docs = Object.values(index.entries).filter((entry) => entry.type === "docs");
  const indexedDocs = new Set(docs.map((entry) => entry.id));
  for (const id of expectedDocs) assert(indexedDocs.has(id), `Missing expected Docs entry: ${id}`);

  browser = await chromium.launch({ headless: true });

  for (const entry of docs) {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${origin}/?path=/docs/${entry.id}`, { waitUntil: "domcontentloaded" });
      const docsBody = page.frameLocator("#storybook-preview-iframe").locator("body");
      await docsBody.waitFor({ state: "visible", timeout: 12_000 });
      const text = await waitForDocsText(page, docsBody);
      assert(text.length > 50, `${entry.id} rendered an empty Docs page`);
      assert(!/Couldn't find story|No Preview|Failed to fetch dynamically imported module|ReferenceError|TypeError/.test(text), `${entry.id} rendered a Storybook error`);

      const expectations = statefulDocs[entry.id];
      if (expectations) {
        for (const [storyId, marker] of Object.entries(expectations)) {
          await waitForStorySurfaces(page, storyId, marker);
        }
      }
    } finally {
      await context.close();
    }
  }

  const stateCount = Object.values(statefulDocs).reduce((count, stories) => count + Object.keys(stories).length, 0);
  console.log(`Storybook Docs smoke passed: ${docs.length} docs entries, ${Object.keys(statefulDocs).length} stateful docs, ${stateCount} state surfaces.`);
} finally {
  await browser?.close();
  await new Promise((resolveClose, reject) => server.close((error) => error ? reject(error) : resolveClose()));
}
