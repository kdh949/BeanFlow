import { ApiRequestError } from "../../api/client";

export type RequestErrorPresentation = {
  title: string;
  description: string;
  reference?: string;
};

const knownErrors: Record<string, Omit<RequestErrorPresentation, "reference">> = {
  INVALID_REQUEST: {
    title: "입력 내용을 확인해 주세요",
    description: "요청 형식이나 입력한 값이 올바르지 않습니다. 내용을 수정한 뒤 다시 시도해 주세요.",
  },
  RESOURCE_NOT_FOUND: {
    title: "요청한 대상을 찾을 수 없습니다",
    description: "대상이 존재하는지, 현재 계정으로 접근할 수 있는지 확인해 주세요.",
  },
  RESOURCE_STATE_CONFLICT: {
    title: "현재 상태와 요청이 맞지 않습니다",
    description: "대상의 현재 상태를 다시 조회하고, 변경할 내용과 적용 조건을 확인해 주세요.",
  },
  POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE: {
    title: "차감할 수 있는 포인트가 부족합니다",
    description: "일부만 차감하지 않았습니다. 현재 사용 가능한 포인트와 조정 금액을 다시 확인해 주세요.",
  },
  REPROCESSING_PROPOSAL_STALE: {
    title: "복구 제안 이후 상태가 바뀌었습니다",
    description: "현재 복구 건을 다시 확인하고 필요한 경우 새 제안을 준비해 주세요.",
  },
  REPROCESSING_PROPOSAL_EXPIRED: {
    title: "복구 제안 기한이 지났습니다",
    description: "현재 상태를 확인하고 새 제안을 준비해 주세요.",
  },
  REPROCESSING_APPROVER_MUST_DIFFER: {
    title: "다른 담당자의 판정이 필요합니다",
    description: "제안자는 자신의 복구 제안을 승인하거나 반려할 수 없습니다.",
  },
  REPROCESSING_NOT_SAFE: {
    title: "현재 상태에서 복구할 수 없습니다",
    description: "복구에 필요한 정보와 현재 처리 상태를 다시 확인해 주세요.",
  },
  REFUND_QUANTITY_UNAVAILABLE: {
    title: "환불 가능 수량이 바뀌었습니다",
    description: "환불 가능 상태를 다시 조회하고 품목과 수량을 선택해 주세요.",
  },
  REFUND_OUTCOME_UNRESOLVED: {
    title: "이전 환불 결과를 확인하고 있습니다",
    description: "이전 환불이 확정될 때까지 새 환불을 실행할 수 없습니다. 잠시 뒤 환불 가능 상태를 다시 확인해 주세요.",
  },
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
  POLICY_DATA_INCOMPLETE: {
    title: "정책 값을 확인하지 못했습니다",
    description: "완전한 정책 정보를 다시 조회한 뒤 변경해 주세요.",
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
