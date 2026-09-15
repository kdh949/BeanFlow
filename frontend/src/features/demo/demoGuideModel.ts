export type DemoAction = "customer" | "merchant" | "restart" | "direct" | "exit";
export type DemoOrderState = "PAID" | "ACCEPTED" | "PREPARING" | "READY" | "COMPLETED" | "REJECTED" | "CANCELLED" | "EXPIRED" | "PENDING_PAYMENT";
export type DemoGuideView = {
  title: string; body: string; step: number; steps: readonly string[];
  footer: string; actions: Array<{ kind: DemoAction; label: string }>;
};
const processingSteps = ["주문 접수", "제조·준비", "고객 화면 확인", "픽업 완료"];
const directSteps = ["메뉴 선택", "픽업 시간·혜택 확인", "테스트 결제"];

/** Derives instructions from a confirmed server state, never from an action button click. */
export function demoGuideView(input: {
  status: DemoOrderState | null; surface: "customer" | "store"; pathname: string;
  pickupNumber?: string; customerChecked: boolean; expired?: boolean;
}): DemoGuideView {
  const { status, surface, pathname, customerChecked } = input;
  const order = input.pickupNumber ? `${input.pickupNumber} 주문` : "체험 주문";
  const base = { steps: processingSteps, footer: "현재 주문 상태를 확인하며 안내합니다.", actions: [] as DemoGuideView["actions"] };
  if (input.expired) return { ...base, step: 0, title: "체험 시간이 끝났어요", body: "체험 계정의 접근이 종료됐어요. 새 체험 공간에서 다시 시작할 수 있어요.", actions: [{ kind: "restart", label: "새 체험 시작" }] };
  if (surface === "customer" && (/\/stores\//.test(pathname) || pathname === "/app/cart" || pathname.endsWith("/checkout") || status === "PENDING_PAYMENT" || status === null)) {
    const step = pathname === "/app/cart" ? 1 : pathname.endsWith("/checkout") || status === "PENDING_PAYMENT" ? 2 : 0;
    const titles = ["원하는 메뉴를 골라보세요", "픽업 시간과 주문 금액을 확인하세요", "테스트 결제로 주문을 완료하세요"];
    const bodies = ["메뉴와 옵션을 선택해 장바구니에 담아주세요.", "예약 가능한 픽업 시간을 고르고, 사용할 수 있는 혜택을 확인해 주세요.", "Toss 테스트 결제창에서 결제를 진행해 주세요. 결제 결과가 확인되면 점주 화면에서 이어갈 수 있어요."];
    return { ...base, steps: directSteps, step, title: titles[step]!, body: bodies[step]!, footer: "테스트 환경에서 진행하며 실제 청구는 발생하지 않습니다." };
  }
  if (status === "REJECTED" || status === "CANCELLED" || status === "EXPIRED") return { ...base, step: 0,
    title: status === "REJECTED" ? "주문이 종료됐어요" : "새 주문으로 체험을 이어가세요",
    body: status === "REJECTED" ? "3분 안에 접수하지 않으면 자동으로 거절돼요. 종료된 주문의 처리 내역을 확인하거나 새 샘플 주문으로 시작해보세요." : "종료된 주문은 다시 진행할 수 없어요. 새로운 샘플 주문을 준비할게요.",
    footer: "주문 종료와 환불·혜택 복원 완료는 다를 수 있어요.", actions: [{ kind: "restart", label: "새 샘플 주문으로 다시 시작" }, { kind: "customer", label: "주문 처리 내역 확인" }] };
  if (status === "COMPLETED") return { ...base, step: 4, title: "주문 한 건의 흐름을 모두 확인했어요", body: "주문 접수부터 제조, 준비 완료, 픽업까지 직접 처리했어요. 메뉴를 골라 주문하는 과정도 체험해보세요.", actions: [{ kind: "direct", label: "직접 메뉴를 골라 주문하기" }, { kind: "exit", label: "체험 마치기" }] };
  if (status === "PAID") return { ...base, step: 0, title: "첫 주문을 접수해보세요", body: `${order}을 확인하고 ‘주문 접수’를 눌러주세요. 결제 후 3분 안에 접수해야 해요.`, actions: surface === "customer" ? [{ kind: "merchant", label: "점주 화면에서 주문 접수" }] : [] };
  if (status === "ACCEPTED") return { ...base, step: 1, title: "이제 음료 제조를 시작해보세요", body: "주문이 접수됐어요. 주문 카드의 ‘제조 시작’을 눌러 다음 상태를 확인해 주세요.", actions: surface === "customer" ? [{ kind: "merchant", label: "점주 화면으로 돌아가기" }] : [] };
  if (status === "PREPARING") return { ...base, step: 1, title: "음료가 준비되면 알려주세요", body: "‘준비 완료’를 누르면 고객에게 픽업 가능한 상태가 표시돼요.", actions: surface === "customer" ? [{ kind: "merchant", label: "점주 화면으로 돌아가기" }] : [] };
  if (status === "READY" && surface === "customer") return { ...base, step: 2, title: "고객에게도 준비 완료가 표시돼요", body: `화면에 표시된 픽업 번호 ${input.pickupNumber ?? ""}를 확인해 주세요. 점주 화면으로 돌아가 픽업을 완료할 수 있어요.`, actions: [{ kind: "merchant", label: "점주 화면으로 돌아가기" }] };
  if (status === "READY" && customerChecked) return { ...base, step: 3, title: "픽업을 완료해보세요", body: "고객 화면의 픽업 번호를 확인했어요. 주문 카드에서 ‘픽업 완료’를 눌러 체험을 마무리해 주세요." };
  if (status === "READY") return { ...base, step: 2, title: "고객 화면의 변화를 확인해보세요", body: "고객 화면에서 준비 완료 상태와 픽업 번호를 확인해 주세요.", actions: [{ kind: "customer", label: "고객 화면 확인하기" }] };
  return { ...base, step: 0, title: "체험 주문을 확인하고 있어요", body: "서버에서 주문 상태가 확인되면 다음 단계를 안내해 드릴게요." };
}
