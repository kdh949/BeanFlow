import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import type { ProfilePurpose, ProfileValues } from "../../lib/supportProfilePayload";
type CreateBinding = components["schemas"]["ProfileChangeBinding"];
type RevisionBinding = components["schemas"]["ProfileChangeRevisionBinding"];
type ExecutionBinding = components["schemas"]["ProfileChangeExecutionBinding"];

export async function submitProfile(purpose: ProfilePurpose, caseId: string, binding: CreateBinding, values: ProfileValues, key: string) {
  const params = { path: { caseId }, header: { "Idempotency-Key": key } };
  switch (purpose) {
    case "CUSTOMER_DISPLAY_NAME": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/customer-display-name-corrections", { params, body: { binding, displayName: (values.displayName?.trim() ?? "") } }));
    case "CUSTOMER_LEGAL_NAME_TYPO": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/customer-legal-name-corrections", { params, body: { binding, legalName: (values.legalName?.trim() ?? "") } }));
    case "CUSTOMER_PRIMARY_PHONE": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/customer-primary-phone-requests", { params, body: { binding, primaryPhone: (values.primaryPhone?.trim() ?? "") } }));
    case "CUSTOMER_CREDENTIAL_RESET": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/customer-credential-reset-requests", { params, body: { binding } }));
    case "STORE_PUBLIC_PROFILE": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/store-public-profile-corrections", { params, body: { binding, displayName: (values.displayName?.trim() || null), publicPhone: (values.publicPhone?.trim() || null), description: (values.description?.trim() || null), pickupInstructions: (values.pickupInstructions?.trim() || null) } }));
    case "STORE_OPERATIONS_CONTACT": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/store-operations-contact-corrections", { params, body: { binding, phone: (values.phone?.trim() || null), email: (values.email?.trim() || null) } }));
    case "STORE_REPRESENTATIVE": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/store-representative-requests", { params, body: { binding, representativeName: (values.representativeName?.trim() ?? "") } }));
    case "STORE_SETTLEMENT_ACCOUNT": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/store-settlement-account-requests", { params, body: { binding, accountReference: (values.accountReference?.trim() ?? "") } }));
    case "STORE_ACCESS_REREGISTRATION": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/store-access-reregistration-requests", { params, body: { binding } }));
    case "COURIER_DISPLAY_NAME": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/courier-display-name-corrections", { params, body: { binding, displayName: (values.displayName?.trim() ?? "") } }));
    case "COURIER_RELAY_CONTACT": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/courier-relay-contact-corrections", { params, body: { binding, phone: (values.phone?.trim() || null), email: (values.email?.trim() || null) } }));
    case "COURIER_PROVIDER_IDENTITY": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/courier-provider-identity-requests", { params, body: { binding, providerReference: (values.providerReference?.trim() ?? "") } }));
    case "COURIER_PAYOUT_REFERENCE": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/courier-payout-reference-requests", { params, body: { binding, payoutReference: (values.payoutReference?.trim() ?? "") } }));
    case "COURIER_PROVIDER_REREGISTRATION": return unwrap(await operationsApi.POST("/support/cases/{caseId}/profile-changes/courier-provider-reregistration-requests", { params, body: { binding } }));
  }
}

export async function reviseProfile(purpose: ProfilePurpose, profileChangeId: string, binding: RevisionBinding, values: ProfileValues, key: string) {
  const params = { path: { profileChangeId }, header: { "Idempotency-Key": key } };
  switch (purpose) {
    case "CUSTOMER_PRIMARY_PHONE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/customer-primary-phone-revisions", { params, body: { binding, primaryPhone: (values.primaryPhone?.trim() ?? "") } }));
    case "CUSTOMER_CREDENTIAL_RESET": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/customer-credential-reset-revisions", { params, body: { binding } }));
    case "STORE_REPRESENTATIVE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-representative-revisions", { params, body: { binding, representativeName: (values.representativeName?.trim() ?? "") } }));
    case "STORE_SETTLEMENT_ACCOUNT": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-settlement-account-revisions", { params, body: { binding, accountReference: (values.accountReference?.trim() ?? "") } }));
    case "STORE_ACCESS_REREGISTRATION": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-access-reregistration-revisions", { params, body: { binding } }));
    case "COURIER_PROVIDER_IDENTITY": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-provider-identity-revisions", { params, body: { binding, providerReference: (values.providerReference?.trim() ?? "") } }));
    case "COURIER_PAYOUT_REFERENCE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-payout-reference-revisions", { params, body: { binding, payoutReference: (values.payoutReference?.trim() ?? "") } }));
    case "COURIER_PROVIDER_REREGISTRATION": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-provider-reregistration-revisions", { params, body: { binding } }));
    default: throw new ApiRequestError(400, "INVALID_REQUEST", "이 정정 목적에는 별도 승인안 실행이 없습니다.");
  }
}

export async function executeProfile(purpose: ProfilePurpose, profileChangeId: string, binding: ExecutionBinding, values: ProfileValues, key: string) {
  const params = { path: { profileChangeId }, header: { "Idempotency-Key": key } };
  switch (purpose) {
    case "CUSTOMER_PRIMARY_PHONE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/customer-primary-phone-executions", { params, body: { binding, primaryPhone: (values.primaryPhone?.trim() ?? "") } }));
    case "CUSTOMER_CREDENTIAL_RESET": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/customer-credential-reset-executions", { params, body: { binding } }));
    case "STORE_REPRESENTATIVE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-representative-executions", { params, body: { binding, representativeName: (values.representativeName?.trim() ?? "") } }));
    case "STORE_SETTLEMENT_ACCOUNT": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-settlement-account-executions", { params, body: { binding, accountReference: (values.accountReference?.trim() ?? "") } }));
    case "STORE_ACCESS_REREGISTRATION": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/store-access-reregistration-executions", { params, body: { binding } }));
    case "COURIER_PROVIDER_IDENTITY": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-provider-identity-executions", { params, body: { binding, providerReference: (values.providerReference?.trim() ?? "") } }));
    case "COURIER_PAYOUT_REFERENCE": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-payout-reference-executions", { params, body: { binding, payoutReference: (values.payoutReference?.trim() ?? "") } }));
    case "COURIER_PROVIDER_REREGISTRATION": return unwrap(await operationsApi.POST("/support/profile-changes/{profileChangeId}/courier-provider-reregistration-executions", { params, body: { binding } }));
    default: throw new ApiRequestError(400, "INVALID_REQUEST", "이 정정 목적에는 별도 승인안 실행이 없습니다.");
  }
}
