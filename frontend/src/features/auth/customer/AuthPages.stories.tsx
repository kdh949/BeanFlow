import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
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
    routing: { path: "/app/login", initialEntry: "/app/login?next=%2Fapp%2Forders" },
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
};

export const SignUp: Story = {
  render: () => <CustomerSignupPage />,
  parameters: {
    routing: { path: "/app/signup", initialEntry: "/app/signup" },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText("표시 이름")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "가입하고 시작하기" })).toBeDisabled();
  },
};
