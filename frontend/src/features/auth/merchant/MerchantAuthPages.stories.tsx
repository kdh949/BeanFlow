import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { MerchantLoginPage, MerchantPasswordChangePage } from "./MerchantAuthPages";
import { merchantSession } from "./merchantSession";

/** The real endpoint issues the JS-readable XSRF cookie, so the fixture must too. */
const merchantCsrfIssued = http.get(
  "/api/v1/auth/merchant/csrf",
  () => new HttpResponse(null, { status: 204, headers: { "Set-Cookie": "BEANFLOW_MERCHANT_XSRF=storybook-merchant-csrf; path=/" } }),
);

const unauthenticated = [
  http.get("/api/v1/merchant/me", () =>
    HttpResponse.json({ code: "UNAUTHORIZED", message: "인증이 필요합니다." }, { status: 401 })),
  merchantCsrfIssued,
];

const initialPassword = [
  http.get("/api/v1/merchant/me", () =>
    HttpResponse.json({
      actorType: "MERCHANT",
      merchantId: "80000000-0000-4000-8000-000000000001",
      displayName: "시청점 점주",
      accountState: "INITIAL_PASSWORD",
    })),
  merchantCsrfIssued,
];

const meta = {
  title: "Pages/Store/Sign in",
  component: MerchantLoginPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "운영팀이 발급한 계정으로만 들어오는 매장 로그인입니다. 가입 경로가 없고 액세스 토큰을 붙여 넣는 자리도 없습니다.",
      },
      story: { inline: false, height: "560px" },
    },
    routing: { surface: "store", path: "/store/login", initialEntry: "/store/login?next=%2Fstore" },
    msw: { handlers: unauthenticated },
  },
  beforeEach: () => {
    merchantSession.reset();
  },
} satisfies Meta<typeof MerchantLoginPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignIn: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText("아이디")).toBeVisible();
    await expect(canvas.getByLabelText("비밀번호")).toHaveAttribute("type", "password");
    await expect(canvas.queryByLabelText("OIDC 액세스 토큰")).toBeNull();
  },
};

export const RejectedCredentials: Story = {
  parameters: {
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/merchant/sessions", () =>
          HttpResponse.json({ code: "AUTHENTICATION_FAILED", message: "인증에 실패했습니다." }, { status: 401 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("아이디"), "sicheong.owner");
    await userEvent.type(canvas.getByLabelText("비밀번호"), "wrong-password-value");
    await userEvent.click(canvas.getByRole("button", { name: "로그인" }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent("아이디 또는 비밀번호를 확인해 주세요.");
  },
};

/** An `INITIAL_PASSWORD` account is signed in but may not open a store screen yet. */
export const InitialPasswordChange: Story = {
  render: () => <MerchantPasswordChangePage />,
  parameters: {
    routing: { surface: "store", path: "/store/password", initialEntry: "/store/password" },
    msw: { handlers: initialPassword },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText("임시 비밀번호")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "비밀번호 변경" })).toBeDisabled();
  },
};

export const RejectedNewPassword: Story = {
  render: () => <MerchantPasswordChangePage />,
  parameters: {
    routing: { surface: "store", path: "/store/password", initialEntry: "/store/password" },
    msw: {
      handlers: [
        ...initialPassword,
        http.post("/api/v1/auth/merchant/password-changes", () =>
          HttpResponse.json(
            { code: "PASSWORD_POLICY_VIOLATION", message: "비밀번호가 정책을 충족하지 않습니다." },
            { status: 400 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("임시 비밀번호"), "TemporaryPass1234!");
    await userEvent.type(canvas.getByLabelText("새 비밀번호"), "NewMerchantPass2026!");
    await userEvent.click(canvas.getByRole("button", { name: "비밀번호 변경" }));
    await expect(await canvas.findByRole("alert")).toHaveTextContent("비밀번호 규칙을 확인해 주세요.");
  },
};
