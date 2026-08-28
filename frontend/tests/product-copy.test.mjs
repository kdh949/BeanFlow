import assert from "node:assert/strict";
import test from "node:test";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { findProductCopyViolations, scanProductCopy } from "../scripts/check-product-copy.mjs";

test("rejects internal terms in rendered JSX text", () => {
  const violations = findProductCopyViolations(
    "src/features/customer/ExamplePage.tsx",
    `export function ExamplePage() { return <p>서버에서 새 견적을 확인합니다.</p>; }`,
  );
  assert.deepEqual(violations.map(({ term }) => term), ["서버"]);
});

test("rejects internal terms in visible string properties", () => {
  const violations = findProductCopyViolations(
    "src/features/support/ExamplePage.tsx",
    `<FeedbackState title="Fallback 결과" description={"POST body를 확인합니다."} />`,
  );
  assert.deepEqual(violations.map(({ term }) => term), ["fallback", "POST body"]);
});

test("checks static text in rendered template expressions", () => {
  const violations = findProductCopyViolations(
    "src/features/merchant/ExamplePage.tsx",
    "<p>{`API 요청 키 ${requestId}`}</p>",
  );
  assert.deepEqual(violations.map(({ term }) => term), ["API", "요청 키"]);
});

test("ignores comments, identifiers, implementation strings, and technical attributes", () => {
  const source = `
    // The server-owned token is not product copy.
    const fallbackContext = "exact API request";
    export function Example() {
      return <section id="canonical-token"><p>{fallbackContext}</p></section>;
    }
  `;
  assert.deepEqual(findProductCopyViolations("src/features/customer/ExamplePage.tsx", source), []);
});

test("ignores Storybook developer descriptions", () => {
  const source = `export default { parameters: { docs: { description: { component: "canonical fallback token" } } } };`;
  assert.deepEqual(findProductCopyViolations("src/features/customer/ExamplePage.stories.tsx", source), []);
});

test("keeps the user-facing warning not to share authentication information", () => {
  const source = `<p>문의할 때 화면의 문의 코드와 주문 번호를 알려주세요. 카드 번호나 인증 정보는 보내지 마세요.</p>`;
  assert.deepEqual(findProductCopyViolations("src/features/payment/PaymentResultPages.tsx", source), []);
});

test("rejects exact implementation-led helper copy in product UI", () => {
  const violations = findProductCopyViolations(
    "src/features/operations/ExamplePage.tsx",
    `<section>
      <p>계정과 첫 매장 권한을 한 번에 생성합니다.</p>
      <p>발급 직후 한 번만 확인할 수 있습니다.</p>
      <p>티켓·로그·브라우저 저장소에는 남기지 마세요.</p>
      <p>재시도 전 운영 Runbook을 확인하세요.</p>
    </section>`,
  );
  assert.deepEqual(violations.map(({ term }) => term), [
    "한 번에 생성합니다",
    "발급 직후 한 번만",
    "티켓·로그",
    "브라우저 저장소",
    "운영 Runbook",
  ]);
});

test("rejects page heading descriptions even when their copy is otherwise allowed", () => {
  const violations = findProductCopyViolations(
    "src/features/customer/ExamplePage.tsx",
    `<section>
      <PageHeading title="주문" description="진행 중인 주문을 확인하세요." />
      <RefreshPageHeading title="장바구니" description={copy} />
    </section>`,
  );
  assert.deepEqual(violations.map(({ term }) => term), [
    "page heading description",
    "page heading description",
  ]);
});

test("rejects exact redundant page and workspace helper copy", () => {
  const violations = findProductCopyViolations(
    "src/features/customer/ExamplePage.tsx",
    `<section>
      <p>주문 내역과 혜택을 한곳에서 관리해요.</p>
      <p>주문과 혜택이 이 계정에 모여요.</p>
      <p>거래 상태와 다음 작업을 정확하게 확인하세요</p>
      <p>역할에 맞는 BeanFlow 작업 공간을 선택하세요.</p>
    </section>`,
  );
  assert.deepEqual(violations.map(({ term }) => term), [
    "주문 내역과 혜택을 한곳에서 관리해요",
    "주문과 혜택이 이 계정에 모여요",
    "거래 상태와 다음 작업을 정확하게 확인하세요",
    "역할에 맞는 BeanFlow 작업 공간을 선택하세요",
  ]);
});

test("keeps operational documentation outside the product UI guard", () => {
  const source = `지금 전달하고 티켓·로그·브라우저 저장소에는 남기지 마세요. 운영 Runbook을 확인하세요.`;
  assert.deepEqual(findProductCopyViolations("docs/operations/merchant-account-administration-runbook.md", source), []);
});

test("current product UI passes the copy policy", () => {
  const frontendRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
  assert.deepEqual(scanProductCopy(frontendRoot), []);
});
