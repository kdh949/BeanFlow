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
  "pages-shared-rolechoice--docs",
  "components-commerce-statusbadge--docs",
  "components-core-button--docs",
  "components-feedback-feedbackstate--docs",
  "pages-operations-dashboard--docs",
  "pages-operations-compensationlookup--docs",
  "pages-operations-refundadjustment--docs",
  "pages-store-orderboard--docs",
  "pages-customer-checkout--docs",
  "pages-customer-help--docs",
  "pages-customer-orderdetail--docs",
  "pages-customer-orders--docs",
  "pages-customer-home--docs",
  "pages-customer-paymentfailure--docs",
  "pages-customer-paymentsuccess--docs",
  "pages-customer-storecatalog--docs",
  "pages-shared-notfound--docs",
]);

const statefulDocs = {
  "pages-operations-compensationlookup--docs": {
    "pages-operations-compensationlookup--idle": "감사 조회 대기",
    "pages-operations-compensationlookup--manual-review": "PROVIDER_TIMEOUT",
    "pages-operations-compensationlookup--successful-lookup": "완료",
    "pages-operations-compensationlookup--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-operations-compensationlookup--loading": "보상 상태를 조회하는 중",
  },
  "pages-operations-refundadjustment--docs": {
    "pages-operations-refundadjustment--full-refund-form": "결제 번호",
    "pages-operations-refundadjustment--partial-refund-form": "주문 상품 번호",
    "pages-operations-refundadjustment--successful-refund": "현금 환불이 확인되었습니다",
    "pages-operations-refundadjustment--unknown-reconciliation": "환불 결과를 확인 중입니다",
    "pages-operations-refundadjustment--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-operations-refundadjustment--loading": "환불 요청 중",
  },
  "pages-store-orderboard--docs": {
    "pages-store-orderboard--active-orders": "A-142",
    "pages-store-orderboard--empty-board": "대기 주문 없음",
    "pages-store-orderboard--permission-failure": "접근 가능한 매장을 확인할 권한이 없습니다.",
  },
  "pages-customer-home--docs": {
    "pages-customer-home--nearby-stores": "시청점",
    "pages-customer-home--location-required": "위치를 알려주세요",
    "pages-customer-home--empty-radius": "가까운 매장이 없어요",
    "pages-customer-home--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-customer-home--loading": "가까운 매장을 찾는 중",
  },
  "pages-customer-storecatalog--docs": {
    "pages-customer-storecatalog--menu-and-pickup-selection": "오트 라떼",
    "pages-customer-storecatalog--empty-menu": "판매 중인 메뉴가 없어요",
    "pages-customer-storecatalog--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-customer-storecatalog--loading": "메뉴와 픽업 시간을 준비하는 중",
  },
  "pages-customer-checkout--docs": {
    "pages-customer-checkout--pending-payment": "₩12,800 결제하기",
    "pages-customer-checkout--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-customer-checkout--loading": "주문서를 불러오는 중",
  },
  "pages-customer-orders--docs": {
    "pages-customer-orders--active-order": "A-142",
    "pages-customer-orders--past-order": "픽업 완료",
    "pages-customer-orders--empty": "진행 중인 주문이 없어요",
    "pages-customer-orders--recoverable-error": "서비스 연결을 확인하고 있습니다.",
    "pages-customer-orders--loading": "주문을 불러오는 중",
  },
  "pages-customer-orderdetail--docs": {
    "pages-customer-orderdetail--ready-for-pickup": "A-142",
    "pages-customer-orderdetail--recovery-pending": "환불 처리 상태",
    "pages-customer-orderdetail--cancelled": "취소된 주문입니다",
    "pages-customer-orderdetail--permission-failure": "이 주문을 볼 권한이 없습니다.",
  },
  "pages-customer-paymentsuccess--docs": {
    "pages-customer-paymentsuccess--approved": "결제가 완료됐어요",
    "pages-customer-paymentsuccess--unknown-reconciliation": "결제 결과를 확인하고 있어요",
    "pages-customer-paymentsuccess--dependency-error": "서비스 연결을 확인하고 있습니다.",
  },
  "pages-customer-paymentfailure--docs": {
    "pages-customer-paymentfailure--retryable-failure": "주문서로 돌아가기",
    "pages-customer-paymentfailure--manual-review": "결제 결과를 확인하고 있어요",
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
