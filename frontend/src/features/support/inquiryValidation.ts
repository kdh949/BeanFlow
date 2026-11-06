import { ApiRequestError } from "../../api/client";

/** Fixed copy for the two public inquiry fields; never render server-provided values. */
export function inquiryFieldError(error: unknown, field: "title" | "content"): string | undefined {
  if (!(error instanceof ApiRequestError) || error.code !== "INVALID_REQUEST" || !error.details?.some(detail => detail.field === field && detail.reason === "INVALID_VALUE")) return undefined;
  return field === "title"
    ? "제목은 100자 이내로 작성하고 개인정보·인증정보를 제외해 주세요."
    : "내용은 2,000자 이내로 작성하고 개인정보·인증정보를 제외해 주세요. 날짜는 YYYY-MM-DD 형식으로 작성할 수 있어요.";
}
