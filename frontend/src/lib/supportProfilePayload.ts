import type { components } from "../api/schema";
export type ProfilePurpose = components["schemas"]["SupportProfileChangeResource"]["purpose"];
export type ProfileValues = Record<string, string>;
type Field = { key: string; label: string; maxLength: number; optional: boolean; description?: string };
type Purpose = { label: string; subject: "CUSTOMER" | "STORE" | "DELIVERY"; risk: "R1" | "R2" | "R3" | "R4"; fields: readonly Field[] };
export const profilePurposes: Record<ProfilePurpose, Purpose> = {
  CUSTOMER_DISPLAY_NAME: {"label": "고객 표시 이름", "subject": "CUSTOMER", "risk": "R1", "fields": [{"key": "displayName", "label": "고객 표시 이름", "maxLength": 200, "optional": false}]},
  CUSTOMER_LEGAL_NAME_TYPO: {"label": "고객 실명 오탈자", "subject": "CUSTOMER", "risk": "R2", "fields": [{"key": "legalName", "label": "정정할 실명", "maxLength": 200, "optional": false}]},
  CUSTOMER_PRIMARY_PHONE: {"label": "고객 기본 전화번호", "subject": "CUSTOMER", "risk": "R3", "fields": [{"key": "primaryPhone", "label": "새 기본 전화번호", "maxLength": 32, "optional": false}]},
  CUSTOMER_CREDENTIAL_RESET: {"label": "고객 인증 재등록", "subject": "CUSTOMER", "risk": "R4", "fields": []},
  STORE_PUBLIC_PROFILE: {"label": "매장 공개 정보", "subject": "STORE", "risk": "R1", "fields": [{"key": "displayName", "label": "매장 표시 이름", "maxLength": 200, "optional": true}, {"key": "publicPhone", "label": "매장 공개 전화번호", "maxLength": 32, "optional": true}, {"key": "description", "label": "매장 설명", "maxLength": 1000, "optional": true}, {"key": "pickupInstructions", "label": "픽업 안내", "maxLength": 1000, "optional": true}]},
  STORE_OPERATIONS_CONTACT: {"label": "매장 운영 연락처", "subject": "STORE", "risk": "R2", "fields": [{"key": "phone", "label": "매장 운영 전화번호", "maxLength": 32, "optional": true}, {"key": "email", "label": "매장 운영 이메일", "maxLength": 320, "optional": true}]},
  STORE_REPRESENTATIVE: {"label": "매장 대표자", "subject": "STORE", "risk": "R3", "fields": [{"key": "representativeName", "label": "대표자 이름", "maxLength": 200, "optional": false}]},
  STORE_SETTLEMENT_ACCOUNT: {"label": "매장 정산 등록 코드", "subject": "STORE", "risk": "R3", "fields": [{"key": "accountReference", "label": "매장 정산 등록 코드", "maxLength": 200, "optional": false, "description": "정산기관에서 등록을 마친 계정의 업무 코드를 확인해 입력합니다. 영문·숫자·콜론(:)·밑줄(_)·하이픈(-) 4~200자입니다. 실제 계좌번호·카드정보·서비스 비밀키는 입력하지 않습니다."}]},
  STORE_ACCESS_REREGISTRATION: {"label": "매장 인증 재등록", "subject": "STORE", "risk": "R4", "fields": []},
  COURIER_DISPLAY_NAME: {"label": "배달원 표시 이름", "subject": "DELIVERY", "risk": "R1", "fields": [{"key": "displayName", "label": "배달원 표시 이름", "maxLength": 200, "optional": false}]},
  COURIER_RELAY_CONTACT: {"label": "배달원 중계 연락처", "subject": "DELIVERY", "risk": "R2", "fields": [{"key": "phone", "label": "배달원 중계 전화번호", "maxLength": 32, "optional": true}, {"key": "email", "label": "배달원 중계 이메일", "maxLength": 320, "optional": true}]},
  COURIER_PROVIDER_IDENTITY: {"label": "배달업체 배달원 등록 코드", "subject": "DELIVERY", "risk": "R3", "fields": [{"key": "providerReference", "label": "배달업체 배달원 등록 코드", "maxLength": 200, "optional": false, "description": "배달업체가 해당 배달원에게 발급한 등록 코드를 확인해 입력합니다. 영문·숫자·콜론(:)·밑줄(_)·하이픈(-) 4~200자입니다. 실제 계좌번호·카드정보·서비스 비밀키는 입력하지 않습니다."}]},
  COURIER_PAYOUT_REFERENCE: {"label": "배달원 정산 등록 코드", "subject": "DELIVERY", "risk": "R3", "fields": [{"key": "payoutReference", "label": "배달원 정산 등록 코드", "maxLength": 200, "optional": false, "description": "정산기관에서 등록을 마친 배달원의 정산 코드를 확인해 입력합니다. 영문·숫자·콜론(:)·밑줄(_)·하이픈(-) 4~200자입니다. 실제 계좌번호·카드정보·서비스 비밀키는 입력하지 않습니다."}]},
  COURIER_PROVIDER_REREGISTRATION: {"label": "배달 서비스 인증 재등록", "subject": "DELIVERY", "risk": "R4", "fields": []},
};
export function validProfileValues(purpose: ProfilePurpose, values: ProfileValues): boolean {
  const { fields } = profilePurposes[purpose];
  return fields.every(field => { const value = values[field.key]?.trim() ?? ""; return (!value ? field.optional : value.length <= field.maxLength) && (!field.key.endsWith("Reference") || !value || /^[A-Za-z0-9:_-]{4,200}$/.test(value)) && (field.key !== "email" || !value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)); }) && (!fields.length || fields.some(field => !!values[field.key]?.trim()));
}
/** Big-endian framing identical to SupportProfilePayloadDigest; null differs from an empty value. */
export async function profileDigest(subjectId: string, version: number, purpose: ProfilePurpose, values: ProfileValues): Promise<string> {
  const fields = ["support-profile-change:v2", purpose, subjectId.toLowerCase(), String(version), ...profilePurposes[purpose].fields.map(field => { const value = values[field.key]?.trim() ?? ""; return field.optional && !value ? null : value; })];
  const encoder = new TextEncoder(), encoded = fields.map(field => field === null ? null : encoder.encode(field));
  const buffer = new ArrayBuffer(4 + encoded.reduce((size, bytes) => size + (bytes === null ? 1 : 5 + bytes.length), 0));
  const view = new DataView(buffer), output = new Uint8Array(buffer); view.setInt32(0, fields.length); let offset = 4;
  encoded.forEach(bytes => { view.setUint8(offset++, bytes === null ? 0 : 1); if (bytes !== null) { view.setInt32(offset, bytes.length); offset += 4; output.set(bytes, offset); offset += bytes.length; } });
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", buffer)), byte => byte.toString(16).padStart(2, "0")).join("");
}
