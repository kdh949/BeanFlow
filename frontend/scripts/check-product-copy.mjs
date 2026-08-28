import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const VISIBLE_ATTRIBUTES = new Set([
  "alt",
  "aria-label",
  "description",
  "error",
  "eyebrow",
  "helper",
  "hint",
  "label",
  "message",
  "placeholder",
  "title",
]);

const GLOBAL_TERMS = [
  { term: "exact", pattern: /\bexact\b/i },
  { term: "canonical", pattern: /\bcanonical\b/i },
  { term: "fallback", pattern: /\bfallback\b/i },
  { term: "Context", pattern: /\bcontext\b/i },
  { term: "POST body", pattern: /\bpost\s+body\b/i },
  { term: "digest", pattern: /\bdigest\b/i },
  { term: "token", pattern: /\btoken\b/i },
  { term: "server-owned", pattern: /\bserver-owned\b/i },
  { term: "티켓·로그", pattern: /티켓·로그/ },
  { term: "브라우저 저장소", pattern: /브라우저 저장소/ },
  { term: "한 번에 생성합니다", pattern: /한 번에 생성합니다/ },
  { term: "발급 직후 한 번만", pattern: /발급 직후 한 번만/ },
  { term: "운영 Runbook", pattern: /운영\s+Runbook/i },
  { term: "검색어 자체는 저장되지 않습니다", pattern: /검색어 자체는 저장되지 않습니다/ },
  { term: "검색 후 바로 지워집니다", pattern: /검색 후 바로 지워집니다/ },
  { term: "브라우저에 저장되지 않습니다", pattern: /브라우저에 저장되지 않습니다/ },
  { term: "주문 내역과 혜택을 한곳에서 관리해요", pattern: /주문 내역과 혜택을 한곳에서 관리해요/ },
  { term: "주문과 혜택이 이 계정에 모여요", pattern: /주문과 혜택이 이 계정에 모여요/ },
  { term: "거래 상태와 다음 작업을 정확하게 확인하세요", pattern: /거래 상태와 다음 작업을 정확하게 확인하세요/ },
  { term: "역할에 맞는 BeanFlow 작업 공간을 선택하세요", pattern: /역할에 맞는 BeanFlow 작업 공간을 선택하세요/ },
];

const CUSTOMER_AND_STORE_TERMS = [
  { term: "서버", pattern: /서버/ },
  { term: "API", pattern: /\bAPI\b/ },
  { term: "요청 키", pattern: /요청\s*키/ },
  { term: "이 브라우저의 인증 정보", pattern: /이 브라우저의 인증 정보/ },
];

function normalizeFile(file) {
  return file.replaceAll(path.sep, "/");
}

function isProductSource(file) {
  const normalized = normalizeFile(file);
  return normalized.endsWith(".tsx")
    && !normalized.endsWith(".stories.tsx")
    && !normalized.endsWith(".test.tsx")
    && !normalized.includes("/design-system/")
    && !normalized.includes("/api/");
}

function isCustomerOrStoreSource(file) {
  const normalized = normalizeFile(file);
  return normalized.includes("/features/auth/customer/")
    || normalized.includes("/features/auth/merchant/")
    || normalized.includes("/features/customer/")
    || normalized.includes("/features/loyalty/")
    || normalized.includes("/features/notification/")
    || normalized.includes("/features/ordering/")
    || normalized.includes("/features/payment/")
    || normalized.includes("/features/merchant/")
    || /\/presentation\/beanflow-refresh\/(?:Customer|Merchant)[^/]*\.tsx$/.test(normalized);
}

function lineAndColumn(sourceFile, position) {
  const location = sourceFile.getLineAndCharacterOfPosition(position);
  return { line: location.line + 1, column: location.character + 1 };
}

function renderedLiterals(sourceFile) {
  const literals = [];

  function add(node, text) {
    const normalized = text.replace(/\s+/g, " ").trim();
    if (normalized) literals.push({ text: normalized, ...lineAndColumn(sourceFile, node.getStart(sourceFile)) });
  }

  function collectExpression(node) {
    if (ts.isJsxElement(node) || ts.isJsxSelfClosingElement(node) || ts.isJsxFragment(node)) {
      visit(node);
      return;
    }
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      add(node, node.text);
      return;
    }
    if (ts.isTemplateExpression(node)) {
      add(node.head, node.head.text);
      for (const span of node.templateSpans) add(span.literal, span.literal.text);
      return;
    }
    ts.forEachChild(node, collectExpression);
  }

  function visit(node) {
    if (ts.isJsxText(node)) {
      add(node, node.text);
    } else if (ts.isJsxAttribute(node) && VISIBLE_ATTRIBUTES.has(node.name.text)) {
      if (node.initializer && ts.isStringLiteral(node.initializer)) add(node.initializer, node.initializer.text);
      if (node.initializer && ts.isJsxExpression(node.initializer) && node.initializer.expression) {
        collectExpression(node.initializer.expression);
      }
    } else if (ts.isJsxExpression(node) && node.expression && !ts.isJsxAttribute(node.parent)) {
      collectExpression(node.expression);
      return;
    }
    ts.forEachChild(node, visit);
  }

  visit(sourceFile);
  return literals;
}

function pageHeadingDescriptionViolations(sourceFile) {
  const violations = [];

  function visit(node) {
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      const tagName = node.tagName.getText(sourceFile);
      if (tagName === "PageHeading" || tagName === "RefreshPageHeading") {
        for (const attribute of node.attributes.properties) {
          if (ts.isJsxAttribute(attribute) && attribute.name.text === "description") {
            violations.push({
              term: "page heading description",
              text: `${tagName} description`,
              ...lineAndColumn(sourceFile, attribute.getStart(sourceFile)),
            });
          }
        }
      }
    }
    ts.forEachChild(node, visit);
  }

  visit(sourceFile);
  return violations;
}

export function findProductCopyViolations(file, source) {
  if (!isProductSource(file)) return [];
  const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const terms = isCustomerOrStoreSource(file)
    ? [...GLOBAL_TERMS, ...CUSTOMER_AND_STORE_TERMS]
    : GLOBAL_TERMS;

  return [
    ...pageHeadingDescriptionViolations(sourceFile),
    ...renderedLiterals(sourceFile).flatMap((literal) => terms
      .filter(({ pattern }) => pattern.test(literal.text))
      .map(({ term }) => ({ ...literal, term }))),
  ];
}

function collectFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(directory, entry.name);
    return entry.isDirectory() ? collectFiles(target) : [target];
  });
}

export function scanProductCopy(frontendRoot) {
  return collectFiles(path.join(frontendRoot, "src"))
    .filter(isProductSource)
    .flatMap((file) => {
      const relative = normalizeFile(path.relative(frontendRoot, file));
      return findProductCopyViolations(relative, fs.readFileSync(file, "utf8"))
        .map((violation) => ({ file: relative, ...violation }));
    });
}

function run() {
  const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const violations = scanProductCopy(frontendRoot);
  if (violations.length) {
    for (const violation of violations) {
      console.error(`${violation.file}:${violation.line}:${violation.column}: product-copy: ${violation.term}: ${JSON.stringify(violation.text)}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log("product copy: passed");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) run();
