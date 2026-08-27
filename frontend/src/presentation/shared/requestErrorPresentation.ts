import { ApiRequestError } from "../../api/client";

export type RequestErrorPresentation = {
  title: string;
  description: string;
  reference?: string;
};

const knownErrors: Record<string, Omit<RequestErrorPresentation, "reference">> = {
  INVALID_PAYMENT_CALLBACK: {
    title: "결제 정보를 확인할 수 없습니다",
    description: "결제 결과 정보가 올바르지 않습니다. 주문 상태를 확인해 주세요.",
  },
  PAYMENT_CALLBACK_MISMATCH: {
    title: "결제 정보를 확인할 수 없습니다",
    description: "결제창에서 돌아온 정보가 주문과 일치하지 않습니다. 주문 상태를 확인해 주세요.",
  },
  POLICY_VERSION_CONFLICT: {
    title: "정책 버전이 변경되었습니다",
    description: "다른 운영자가 정책을 먼저 변경했습니다. 현재 값을 다시 조회해 주세요.",
  },
  TEMPORARY_PASSWORD_NOT_REPLAYABLE: {
    title: "임시 비밀번호를 다시 표시할 수 없습니다",
    description: "새 요청으로 임시 비밀번호를 다시 발급해 주세요.",
  },
  SUPPORT_SEARCH_RATE_LIMITED: {
    title: "검색 요청이 너무 많습니다",
    description: "잠시 뒤 다시 시도해 주세요.",
  },
  COUPON_TERMS_INTEGRITY_FAILURE: {
    title: "쿠폰 조건을 확인하지 못했습니다",
    description: "잠시 뒤 다시 시도해 주세요.",
  },
  OPERATIONS_OIDC_CONFIG_UNAVAILABLE: {
    title: "운영자 로그인 설정을 확인할 수 없습니다",
    description: "잠시 뒤 다시 시도하거나 운영 담당자에게 문의해 주세요.",
  },
  IDEMPOTENCY_REQUEST_IN_PROGRESS: {
    title: "요청을 처리하고 있습니다",
    description: "같은 요청을 다시 보내지 말고 잠시 뒤 결과를 확인해 주세요.",
  },
  IDEMPOTENCY_MANUAL_REVIEW_REQUIRED: {
    title: "요청 결과를 확인하고 있습니다",
    description: "같은 요청을 다시 보내지 말고 문의 코드와 함께 결과를 확인해 주세요.",
  },
  IDEMPOTENCY_KEY_REUSED: {
    title: "요청 정보가 변경되었습니다",
    description: "같은 요청 키를 다른 내용에 사용할 수 없습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.",
  },
  DEPENDENCY_UNAVAILABLE: {
    title: "서비스 연결을 확인하고 있습니다",
    description: "잠시 뒤 다시 시도해 주세요.",
  },
};

const genericError: Omit<RequestErrorPresentation, "reference"> = {
  title: "요청을 완료하지 못했습니다",
  description: "네트워크 연결을 확인하고 다시 시도해 주세요.",
};

/** Converts application failures into copy that is safe to render to a customer. */
export function requestErrorPresentation(error: unknown): RequestErrorPresentation {
  if (!(error instanceof ApiRequestError)) return genericError;

  const known = knownErrors[error.code];
  if (known) return { ...known, reference: error.correlationId };

  if (error.status === 401) {
    return {
      title: "인증이 필요합니다",
      description: "다시 로그인한 뒤 요청을 이어가 주세요.",
      reference: error.correlationId,
    };
  }
  if (error.status === 403) {
    return {
      title: "이 작업을 진행할 수 없습니다",
      description: "현재 계정의 권한을 확인해 주세요.",
      reference: error.correlationId,
    };
  }
  if (error.status === 429) {
    return {
      title: "요청이 너무 많습니다",
      description: "잠시 뒤 다시 시도해 주세요.",
      reference: error.correlationId,
    };
  }

  return { ...genericError, reference: error.correlationId };
}
