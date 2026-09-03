import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { CustomerLoginPage, CustomerSignupPage } from "./AuthPages";

const unauthenticated = [
  http.get("/api/v1/me", () => HttpResponse.json({ code: "UNAUTHORIZED", message: "인증이 필요합니다." }, { status: 401 })),
];

const meta = {
  title: "Pages/Customer/Sign in",
  component: CustomerLoginPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component: "아이디와 비밀번호만 받는 고객 로그인입니다. 토큰이나 UUID를 입력하는 자리는 없습니다.",
      },
      story: { inline: false, height: "560px" },
    },
    routing: { surface: "customer", path: "/app/login", initialEntry: "/app/login?next=%2Fapp%2Forders" },
    msw: { handlers: unauthenticated },
  },
} satisfies Meta<typeof CustomerLoginPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignIn: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText("아이디")).toBeVisible();
    await expect(canvas.getByLabelText("비밀번호")).toHaveAttribute("type", "password");
  },
};

export const RejectedCredentials: Story = {
  parameters: {
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/customer/sessions", () =>
          HttpResponse.json({ code: "AUTHENTICATION_FAILED", message: "인증에 실패했습니다." }, { status: 401 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("아이디"), "locked-user");
    await userEvent.type(canvas.getByLabelText("비밀번호"), "invalid-password");
    await userEvent.click(canvas.getByRole("button", { name: "로그인" }));
    await expect(await canvas.findByText("아이디 또는 비밀번호를 확인해 주세요.")).toBeVisible();
  },
};

/** 잠긴 계정도 계정 존재 여부를 숨기기 위해 일반 인증 실패와 같은 화면을 사용합니다. */
export const AccountLockProtected: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/customer/sessions", () =>
          HttpResponse.json({ code: "AUTHENTICATION_FAILED", message: "인증에 실패했습니다." }, { status: 401 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("아이디"), "locked-user");
    await userEvent.type(canvas.getByLabelText("비밀번호"), "correct-but-protected");
    await userEvent.click(canvas.getByRole("button", { name: "로그인" }));
    await expect(await canvas.findByText("아이디 또는 비밀번호를 확인해 주세요.")).toBeVisible();
  },
};

export const RateLimited: Story = {
  tags: ["!autodocs"],
  parameters: {
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/customer/sessions", () =>
          HttpResponse.json(
            { code: "AUTHENTICATION_RATE_LIMITED", message: "로그인 시도가 너무 많습니다. 15분 뒤 다시 시도해 주세요." },
            { status: 429, headers: { "Retry-After": "900" } },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(await canvas.findByLabelText("아이디"), "busy-user");
    await userEvent.type(canvas.getByLabelText("비밀번호"), "invalid-password");
    await userEvent.click(canvas.getByRole("button", { name: "로그인" }));
    await expect(await canvas.findByText("로그인 시도가 너무 많습니다. 잠시 뒤 다시 시도해 주세요.")).toBeVisible();
  },
};

export const SignUp: Story = {
  render: () => <CustomerSignupPage />,
  parameters: {
    routing: { surface: "customer", path: "/app/signup", initialEntry: "/app/signup" },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText("표시 이름")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "가입하고 시작하기" })).toBeDisabled();
  },
};

async function completeSignupForm(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.type(await canvas.findByLabelText("아이디"), "customer01");
  await userEvent.type(canvas.getByLabelText("표시 이름"), "빈플로우 고객");
  await userEvent.type(canvas.getByLabelText("비밀번호"), "correct-horse-battery");
}

export const DuplicateSignupId: Story = {
  render: () => <CustomerSignupPage />,
  parameters: {
    routing: { surface: "customer", path: "/app/signup", initialEntry: "/app/signup" },
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/customer/registrations", () =>
          HttpResponse.json({ code: "LOGIN_ID_UNAVAILABLE", message: "이미 사용 중인 아이디입니다." }, { status: 409 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await completeSignupForm(canvas);
    await userEvent.click(canvas.getByRole("button", { name: "가입하고 시작하기" }));
    await expect(await canvas.findByText("이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.")).toBeVisible();
    await expect(canvas.getByLabelText("아이디")).toHaveAttribute("aria-invalid", "true");
  },
};

export const RegisteredThenLoginUnavailable: Story = {
  render: () => <CustomerSignupPage />,
  parameters: {
    routing: { surface: "customer", path: "/app/signup", initialEntry: "/app/signup" },
    msw: {
      handlers: [
        ...unauthenticated,
        http.post("/api/v1/auth/customer/registrations", () => HttpResponse.json({ loginId: "customer01" }, { status: 201 })),
        http.post("/api/v1/auth/customer/sessions", () =>
          HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "인증 의존성을 사용할 수 없습니다." }, { status: 503 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await completeSignupForm(canvas);
    await userEvent.click(canvas.getByRole("button", { name: "가입하고 시작하기" }));
    await expect(await canvas.findByText("가입은 완료됐지만 로그인하지 못했습니다. 비밀번호를 확인한 뒤 다시 시도해 주세요.")).toBeVisible();
    await expect(canvas.getByText(/회원가입은 완료됐어요/)).toBeVisible();
    await expect(canvas.getByLabelText("아이디")).toHaveAttribute("readonly");
    await expect(canvas.getByRole("button", { name: "다시 로그인" })).toBeVisible();
  },
};
