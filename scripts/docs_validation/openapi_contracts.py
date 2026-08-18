from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from .common import ValidationError


@dataclass(frozen=True)
class OperationContract:
    path: str
    method: str
    operation_id: str
    security: tuple[str, ...]
    responses: tuple[str, ...]
    parameters: tuple[str, ...] = ()


@dataclass(frozen=True)
class SchemaContract:
    name: str
    required: tuple[str, ...] = ()
    properties: tuple[str, ...] = ()
    forbidden_properties: tuple[str, ...] = ()
    enum: tuple[str, ...] = ()
    write_only: tuple[str, ...] = ()


def operation(
    path: str,
    method: str,
    operation_id: str,
    security: str | None,
    responses: Iterable[int | str],
    *parameters: str,
) -> OperationContract:
    return OperationContract(
        path=path,
        method=method,
        operation_id=operation_id,
        security=() if security is None else (security,),
        responses=tuple(str(status) for status in responses),
        parameters=tuple(parameters),
    )


def support_query(path: str, operation_id: str, *parameters: str) -> OperationContract:
    return operation(path, "get", operation_id, "bearerAuth", (200, 403, 503), *parameters)


def support_command(
    path: str,
    operation_id: str,
    success: int = 200,
    *parameters: str,
    idempotent: bool = True,
) -> OperationContract:
    expected_parameters = parameters + (("IdempotencyKey",) if idempotent else ())
    statuses = (success, 403, 503) if not idempotent else (success, 403, 409, 503)
    return operation(path, "post", operation_id, "bearerAuth", statuses, *expected_parameters)


def profile_change_operations() -> tuple[OperationContract, ...]:
    creates = (
        ("customer-display-name-corrections", "createCustomerDisplayNameCorrection"),
        ("customer-legal-name-corrections", "createCustomerLegalNameCorrection"),
        ("customer-primary-phone-requests", "createCustomerPrimaryPhoneChange"),
        ("customer-credential-reset-requests", "createCustomerCredentialReset"),
        ("store-public-profile-corrections", "createStorePublicProfileCorrection"),
        ("store-operations-contact-corrections", "createStoreOperationsContactCorrection"),
        ("store-representative-requests", "createStoreRepresentativeChange"),
        ("store-settlement-account-requests", "createStoreSettlementAccountChange"),
        ("store-access-reregistration-requests", "createStoreAccessReregistration"),
        ("courier-display-name-corrections", "createCourierDisplayNameCorrection"),
        ("courier-relay-contact-corrections", "createCourierRelayContactCorrection"),
        ("courier-provider-identity-requests", "createCourierProviderIdentityChange"),
        ("courier-payout-reference-requests", "createCourierPayoutReferenceChange"),
        ("courier-provider-reregistration-requests", "createCourierProviderReregistration"),
    )
    revisions = (
        ("customer-primary-phone", "reviseCustomerPrimaryPhoneChange"),
        ("customer-credential-reset", "reviseCustomerCredentialReset"),
        ("store-representative", "reviseStoreRepresentativeChange"),
        ("store-settlement-account", "reviseStoreSettlementAccountChange"),
        ("store-access-reregistration", "reviseStoreAccessReregistration"),
        ("courier-provider-identity", "reviseCourierProviderIdentityChange"),
        ("courier-payout-reference", "reviseCourierPayoutReferenceChange"),
        ("courier-provider-reregistration", "reviseCourierProviderReregistration"),
    )
    executions = tuple((suffix, operation_id.replace("revise", "execute", 1)) for suffix, operation_id in revisions)
    create_contracts = tuple(
        support_command(
            f"/support/cases/{{caseId}}/profile-changes/{suffix}",
            operation_id,
            201,
            "SupportCaseId",
        )
        for suffix, operation_id in creates
    )
    revision_contracts = tuple(
        support_command(
            f"/support/profile-changes/{{profileChangeId}}/{suffix}-revisions",
            operation_id,
            200,
            "SupportProfileChangeId",
        )
        for suffix, operation_id in revisions
    )
    execution_contracts = tuple(
        support_command(
            f"/support/profile-changes/{{profileChangeId}}/{suffix}-executions",
            operation_id,
            200,
            "SupportProfileChangeId",
        )
        for suffix, operation_id in executions
    )
    return (
        *create_contracts,
        *revision_contracts,
        *execution_contracts,
        support_query(
            "/support/profile-changes/{profileChangeId}",
            "getSupportProfileChange",
            "SupportProfileChangeId",
        ),
        support_command(
            "/support/profile-changes/{profileChangeId}/notification-retries",
            "retrySupportProfileChangeNotifications",
            200,
            "SupportProfileChangeId",
        ),
    )


OPERATION_CONTRACTS = (
    operation("/auth/customer/registrations", "post", "registerCustomerAccount", None, (201, 400, 403, 409, 503)),
    operation("/auth/customer/sessions", "post", "createCustomerSession", None, (200, 400, 401, 403, 429, 503)),
    operation("/auth/customer/sessions/current", "delete", "deleteCurrentCustomerSession", "customerSession", (204, 401, 403, 503)),
    operation("/me", "get", "getCurrentCustomer", "customerSession", (200, 401, 403, 503)),
    operation("/auth/merchant/sessions", "post", "createMerchantSession", None, (200, 400, 401, 403, 429, 503)),
    operation("/auth/merchant/password-changes", "post", "changeMerchantPassword", "merchantSession", (204, 400, 401, 403, 503)),
    operation("/auth/merchant/sessions/current", "delete", "deleteCurrentMerchantSession", "merchantSession", (204, 401, 403, 503)),
    operation("/merchant/me", "get", "getCurrentMerchant", "merchantSession", (200, 401, 503)),
    operation("/merchant/me/stores", "get", "listCurrentMerchantStores", "merchantSession", (200, 401, 403, 503)),
    operation("/operations/merchant-accounts", "get", "getMerchantAccountByLoginId", "bearerAuth", (200, 400, 401, 403, 404, 503)),
    operation("/operations/merchant-accounts", "post", "createMerchantAccount", "bearerAuth", (201, 400, 401, 403, 409, 503)),
    operation("/operations/merchant-accounts/{merchantAccountId}/temporary-password-resets", "post", "resetMerchantTemporaryPassword", "bearerAuth", (200, 400, 401, 403, 404, 409, 503)),
    operation("/operations/merchant-accounts/{merchantAccountId}/lock-releases", "post", "releaseMerchantAccountLock", "bearerAuth", (204, 400, 401, 403, 404, 409, 503)),
    operation("/stores/{storeId}", "get", "getStore", "customerSession", (200, 404, 503), "StoreId"),
    operation("/stores/{storeId}/menus", "get", "listStoreMenus", "customerSession", (200, 404, 503), "StoreId"),
    operation("/stores/{storeId}/pickup-slots", "get", "listStorePickupSlots", "customerSession", (200, 404, 503), "StoreId"),
    operation("/me/orders", "get", "listCurrentCustomerOrders", "customerSession", (200, 400, 503), "Cursor", "Limit"),
    operation("/me/orders/{orderReference}", "get", "getCurrentCustomerOrder", "customerSession", (200, 400, 403, 404, 503), "OrderReference"),
    operation("/me/orders/{orderReference}/cancellations", "post", "cancelCurrentCustomerOrder", "customerSession", (200, 202, 400, 403, 404, 409, 503), "OrderReference", "IdempotencyKey"),
    operation("/payments/{paymentId}", "get", "getOneTimePayment", "customerSession", (200, 202, 403, 404, 409, 422, 503), "PaymentId"),
    operation("/payments/{paymentId}/confirmations", "post", "confirmOneTimePayment", "customerSession", (200, 202, 400, 403, 404, 409, 422, 503), "PaymentId", "IdempotencyKey"),
    operation("/me/coupons", "get", "listCurrentCustomerCoupons", "customerSession", (200, 400, 401, 403, 503), "storeId", "Cursor", "Limit"),
    operation("/stores/{storeId}/orders", "get", "listStoreOrderBoard", "merchantSession", (200, 304, 403, 503), "StoreId", "If-None-Match", "lane"),
    operation("/stores/{storeId}/orders/overflow", "get", "listStoreOrderOverflowQueue", "merchantSession", (200, 400, 403, 503), "StoreId", "lane", "cursor"),
    operation("/stores/{storeId}/orders/{orderReference}", "get", "getStoreOrderByReference", "merchantSession", (200, 400, 403, 404, 503), "StoreId", "OrderReference"),
    operation("/stores/{storeId}/orders/{orderReference}/transitions", "post", "transitionStoreOrderByReference", "merchantSession", (200, 202, 400, 403, 404, 409, 422, 503), "StoreId", "OrderReference", "IdempotencyKey"),
    support_command("/support/cases", "createSupportCase", 201),
    operation("/support/cases", "get", "listSupportCases", "bearerAuth", (200, 400, 403, 503), "Cursor", "Limit"),
    support_query("/support/cases/{caseId}", "getSupportCase", "SupportCaseId"),
    support_command("/support/cases/{caseId}/assignments", "assignSupportCase", 200, "SupportCaseId"),
    support_command("/support/cases/{caseId}/status-transitions", "transitionSupportCase", 200, "SupportCaseId"),
    support_command("/support/cases/{caseId}/interactions", "appendSupportInteraction", 200, "SupportCaseId"),
    support_command("/support/cases/{caseId}/notes", "appendSupportNote", 200, "SupportCaseId"),
    support_command("/support/cases/{caseId}/subject-links", "linkSupportSubject", 200, "SupportCaseId"),
    operation("/support/cases/{caseId}/subject-links/{linkId}", "delete", "unlinkSupportSubject", "bearerAuth", (200, 403, 409, 503), "SupportCaseId", "SupportSubjectLinkId", "IdempotencyKey"),
    operation("/support/searches", "post", "searchSupportSubjects", "bearerAuth", (200, 400, 403, 429, 503)),
    support_command("/support/cases/{caseId}/verification-sessions", "createSupportVerificationSession", 201, "SupportCaseId"),
    support_query("/support/verification-sessions/{sessionId}", "getSupportVerificationSession", "VerificationSessionId"),
    support_command("/support/verification-sessions/{sessionId}/challenges", "issueSupportVerificationChallenge", 201, "VerificationSessionId"),
    support_command("/support/verification-challenges/{challengeId}/verifications", "verifySupportVerificationChallenge", 200, "VerificationChallengeId"),
    support_command("/support/verification-sessions/{sessionId}/revocations", "revokeSupportVerificationSession", 200, "VerificationSessionId"),
    support_command("/support/cases/{caseId}/data-access-grants", "requestSupportDataAccessGrant", 201, "SupportCaseId"),
    support_command("/support/data-access-grants/{grantId}/approvals", "decideSupportDataAccessGrant", 200, "DataAccessGrantId"),
    support_command("/support/data-access-grants/{grantId}/reveals", "revealSupportPersonalData", 200, "DataAccessGrantId"),
    support_command("/support/cases/{caseId}/break-glass-requests", "requestSupportBreakGlass", 201, "SupportCaseId"),
    support_command("/support/break-glass-requests/{requestId}/approvals", "decideSupportBreakGlass", 200, "BreakGlassRequestId"),
    support_command("/support/break-glass-requests/{requestId}/reveals", "revealSupportBreakGlassData", 200, "BreakGlassRequestId"),
    support_command("/support/break-glass-requests/{requestId}/reviews", "reviewSupportBreakGlass", 200, "BreakGlassRequestId"),
    operation("/support/cases/{caseId}/timeline", "get", "listSupportCaseTimeline", "bearerAuth", (200, 403, 503), "SupportCaseId", "SupportTimelineCursor", "SupportTimelineLimit"),
    operation("/support/orders/{orderId}/timeline", "get", "listSupportOrderTimeline", "bearerAuth", (200, 403, 503), "OrderId", "SupportOrderTimelineCaseId"),
    support_command("/support/cases/{caseId}/action-evaluations", "evaluateSupportAction", 200, "SupportCaseId", idempotent=False),
    support_command("/support/cases/{caseId}/action-requests", "createSupportActionRequest", 201, "SupportCaseId"),
    support_query("/support/action-requests/{requestId}", "getSupportActionRequest", "SupportActionRequestId"),
    support_command("/support/action-requests/{requestId}/revisions", "reviseSupportActionRequest", 200, "SupportActionRequestId"),
    support_command("/support/action-requests/{requestId}/support-manager-decisions", "decideSupportManagerApproval", 200, "SupportActionRequestId"),
    support_command("/support/action-requests/{requestId}/reassignments", "reassignSupportActionRequest", 200, "SupportActionRequestId"),
    support_command("/operations/investigations/{investigationId}/decisions", "decideOperationsSupportInvestigation", 200, "OperationsInvestigationId"),
    support_command("/support/action-requests/{requestId}/executions", "executeSupportOrderChange", 200, "SupportActionRequestId"),
    support_command("/stores/{storeId}/support-order-change-authorizations", "createSupportOrderChangeAuthorization", 201, "StoreId"),
    support_command("/support/orders/{orderId}/post-acceptance-resolutions", "createPostAcceptanceResolution", 201, "OrderId"),
    support_query("/support/post-acceptance-resolutions/{resolutionId}", "getPostAcceptanceResolution", "PostAcceptanceResolutionId"),
    support_command("/support/post-acceptance-resolutions/{resolutionId}/executions", "executePostAcceptanceResolution", 200, "PostAcceptanceResolutionId"),
    support_command("/support/post-acceptance-resolutions/{resolutionId}/reconciliations", "reconcilePostAcceptanceResolution", 200, "PostAcceptanceResolutionId"),
    support_command("/support/cases/{caseId}/compensation-evaluations", "evaluateSupportCompensation", 200, "SupportCaseId", idempotent=False),
    support_command("/support/cases/{caseId}/compensations", "createSupportCompensation", 201, "SupportCaseId"),
    support_query("/support/compensations/{compensationRequestId}", "getSupportCompensation", "SupportCompensationRequestId"),
    support_command("/support/compensations/{compensationRequestId}/executions", "executeSupportCompensation", 200, "SupportCompensationRequestId"),
    support_command("/support/compensations/{compensationRequestId}/notification-retries", "retrySupportCompensationNotification", 200, "SupportCompensationRequestId"),
    *profile_change_operations(),
)


CLOSED_OBJECT_SCHEMAS = (
    "CreateSupportCaseRequest", "AssignSupportCaseRequest", "TransitionSupportCaseRequest",
    "AppendSupportInteractionRequest", "AppendSupportNoteRequest", "LinkSupportSubjectRequest",
    "UnlinkSupportSubjectRequest", "SupportCase", "SupportCasePage", "SupportCaseAssignment",
    "SupportCaseTransition", "SupportInteraction", "SupportNote", "SupportSubjectLink",
    "SupportSubjectUnlink", "SupportSearchCriterion", "SearchSupportSubjectsRequest",
    "SupportSubjectSearchCandidate", "SupportSubjectSearchResult", "CreateVerificationSessionRequest",
    "IssueVerificationChallengeRequest", "VerifyVerificationChallengeRequest", "VerificationSessionResource",
    "VerificationChallengeResource", "VerificationResultResource", "RequestDataAccessGrantRequest",
    "DecideDataAccessGrantRequest", "RevealGrantedPersonalDataRequest", "DataAccessGrantResource",
    "RevealedPersonalDataResource", "RequestBreakGlassRequest", "DecideBreakGlassRequest",
    "RevealBreakGlassRequest", "ReviewBreakGlassRequest", "BreakGlassResource", "BreakGlassRevealResource",
    "SupportTimelineItem", "SupportTimelinePage", "EvaluateSupportActionRequest",
    "SupportActionEvaluationResource", "CreateSupportActionRequest", "ReviseSupportActionRequest",
    "DecideSupportManagerApprovalRequest", "ReassignSupportActionRequest",
    "DecideOperationsSupportInvestigationRequest", "SupportApprovalStepResource",
    "SupportActionRequestResource", "OperationsSupportInvestigationDecisionResource",
    "ExecuteSupportOrderCancellationRequest", "ExecuteSupportPickupRescheduleRequest",
    "CreateSupportOrderChangeAuthorizationRequest", "SupportOrderChangeAuthorizationResource",
    "SupportOrderChangeExecutionResource", "CreatePostAcceptanceResolutionRequest",
    "ExecutePostAcceptanceResolutionRequest", "ReconcilePostAcceptanceResolutionRequest",
    "PostAcceptanceResolutionStepResource", "PostAcceptanceResolutionResource",
    "EvaluateSupportCompensationRequest", "CreateSupportCompensationRequest",
    "ExecuteSupportCompensationRequest", "SupportCompensationEvaluationResource",
    "SupportCompensationResource",
)


SCHEMA_CONTRACTS = (
    SchemaContract("CustomerOrderAllowedAction", enum=("CANCEL", "REORDER", "VIEW_REFUND")),
    SchemaContract("CustomerOrderSummary", properties=("orderReference", "pickupNumber", "itemSummary", "allowedActions"), forbidden_properties=("orderId", "paymentId", "providerReference", "failureCode", "cancellationDetail")),
    SchemaContract("CustomerOrderDetail", properties=("orderReference", "lines", "allowedActions", "paymentRecovery"), forbidden_properties=("orderId", "orderLineId", "menuId", "paymentId", "providerReference", "failureCode")),
    SchemaContract("PaymentConfirmation", required=("orderReference",), forbidden_properties=("orderId",)),
    SchemaContract("CustomerCancellationResult", required=("orderReference",), forbidden_properties=("orderId",)),
    SchemaContract("StoreOrderActionRequest", required=("action", "expectedStatus"), properties=("reason",)),
    SchemaContract("StoreOrderBoardItem", properties=("orderReference", "pickupNumber", "pickupBusinessDate", "lane", "status", "itemSummary", "acceptancePhase", "allowedActions", "compensationRecovery"), forbidden_properties=("orderId", "customerId", "paymentId", "providerReference", "subtotalKrw", "payableKrw", "steps", "attemptCount", "lastErrorCode")),
    SchemaContract("StoreOrderBoard", required=("groups", "overflow")),
    SchemaContract("StoreOrderBoardOverflow", required=("lane", "overflowCount", "nextCursor")),
    SchemaContract("StoreOrderBoardOverflowPage", required=("lane", "items", "nextCursor")),
    SchemaContract("CustomerCouponWalletItem", required=("couponIssuanceId", "benefit", "minimumOrderKrw", "couponExpiresAt", "applicable"), properties=("reasonCode",), forbidden_properties=("campaignId", "customerId", "originalIssuanceId")),
    SchemaContract("CouponWalletInapplicableReason", enum=("STORE_NOT_APPLICABLE",)),
    SchemaContract("SearchSupportSubjectsRequest", required=("criterion", "subjectTypes", "reasonCode")),
    SchemaContract("SupportSubjectSearchCandidate", properties=("maskedDisplayName", "maskedMatchedValue"), forbidden_properties=("ciphertext", "blindIndex", "criterionValue", "keyVersion")),
    SchemaContract("SupportSubjectSearchResult", forbidden_properties=("criterion", "normalized")),
    SchemaContract("VerifyVerificationChallengeRequest", write_only=("proof",)),
    SchemaContract("SupportPersonalDataValues", forbidden_properties=("secret", "password", "token")),
    SchemaContract("SupportActionRequestState", enum=("AWAITING_SUPPORT_MANAGER", "AWAITING_OPERATIONS", "READY_FOR_EXECUTION", "REASSIGNMENT_REQUIRED", "REVISION_REQUIRED", "DENIED", "EXPIRED", "STALE", "MANUAL_REVIEW", "EXECUTED", "RESOLUTION_REQUIRED")),
    SchemaContract("OperationsSupportInvestigationDecision", enum=("APPROVE", "DENY", "RETURN_FOR_REVISION", "ESCALATE")),
    SchemaContract("SupportActionRequestResource", properties=("actionPayloadDigest", "evidenceDigest", "approvalSteps", "terminalExecutionId", "terminalResolutionId"), forbidden_properties=("reason", "rawPayload", "proof", "otp", "token")),
    SchemaContract("SupportOrderChangeAuthorizationResource", properties=("maxSuccessfulUses", "successfulUses", "costResponsibility"), forbidden_properties=("rawPayload", "reasonDetail", "customerNote", "otp", "token")),
    SchemaContract("SupportOrderChangeExecutionResource", properties=("paymentRecoveryState", "targetVersionAfter", "requestState"), forbidden_properties=("rawPayload", "reasonDetail", "customerNote", "providerPayload")),
    SchemaContract("PostAcceptanceResolutionState", enum=("PLANNED", "EXECUTING", "PARTIALLY_RESOLVED", "RECONCILING", "RESOLVED", "MANUAL_REVIEW")),
    SchemaContract("PostAcceptanceResolutionStepState", enum=("PENDING", "PROCESSING", "RETRY_SCHEDULED", "SUCCEEDED", "NOT_REQUIRED", "UNKNOWN", "RECONCILING", "MANUAL_REVIEW", "BLOCKED")),
    SchemaContract("PostAcceptanceResolutionResponsibility", enum=("CUSTOMER", "STORE", "PLATFORM", "SHARED", "UNDETERMINED")),
    SchemaContract("PostAcceptanceResolutionResource", properties=("triggerOrderState", "settlementAdjustmentKrw", "steps"), forbidden_properties=("evidenceDigest", "providerPayload", "reason", "customerName", "phone", "email")),
    SchemaContract("SupportCompensationBand", enum=("LOW", "MEDIUM", "HIGH", "EXCEPTIONAL")),
    SchemaContract("SupportCompensationRequestState", enum=("AWAITING_APPROVAL", "READY_FOR_EXECUTION", "BENEFIT_ISSUED", "NOTIFICATION_RETRY", "NOTIFICATION_ACCEPTED")),
    SchemaContract("SupportCompensationResource", properties=("policyVersionId", "terminalBenefitId", "notificationState"), forbidden_properties=("customerId", "evidenceDigest", "costEvidenceDigest", "providerPayload")),
    SchemaContract("SupportTimelineSource", enum=("SUPPORT", "ORDERING", "PAYMENT", "LOYALTY", "PROMOTION", "FULFILLMENT", "SETTLEMENT", "NOTIFICATION", "OPERATIONS")),
    SchemaContract("SupportActionType", enum=("ORDER_CANCELLATION", "PICKUP_RESCHEDULE", "POST_ACCEPTANCE_RESOLUTION")),
    SchemaContract("SupportActionDecision", enum=("ALLOWED", "APPROVAL_REQUIRED", "DENIED")),
    SchemaContract("VerificationActionScope", enum=("PERSONAL_DATA_REVEAL", "SUPPORT_ACTION")),
    SchemaContract("SupportProfileChangeResource", properties=("maskedBefore", "maskedAfter", "payloadDigest", "notificationState"), forbidden_properties=("primaryPhone", "legalName", "accountReference", "providerReference", "payoutReference")),
    SchemaContract("CustomerPrimaryPhoneProfileChangeRequest", write_only=("primaryPhone",)),
    SchemaContract("StoreSettlementAccountProfileChangeRequest", write_only=("accountReference",)),
    SchemaContract("CourierProviderIdentityProfileChangeRequest", write_only=("providerReference",)),
    SchemaContract("CourierPayoutReferenceProfileChangeRequest", write_only=("payoutReference",)),
    SchemaContract("CustomerCredentialResetProfileChangeRequest", forbidden_properties=("password", "secret", "token")),
)


FORBIDDEN_OPERATIONS = (("/support/profile-changes/{profileChangeId}", "patch"),)


def reference_name(value: object) -> str | None:
    if not isinstance(value, dict):
        return None
    reference = value.get("$ref")
    return reference.rsplit("/", maxsplit=1)[-1] if isinstance(reference, str) else value.get("name")


def validate_operation_contracts(document: dict, contracts: Iterable[OperationContract]) -> int:
    paths = document.get("paths", {})
    checked = 0
    for contract in contracts:
        path_item = paths.get(contract.path)
        if not isinstance(path_item, dict):
            raise ValidationError(f"OpenAPI contract path is missing: {contract.path}")
        operation_item = path_item.get(contract.method)
        if not isinstance(operation_item, dict):
            raise ValidationError(f"OpenAPI contract operation is missing: {contract.method.upper()} {contract.path}")
        if operation_item.get("operationId") != contract.operation_id:
            raise ValidationError(
                f"OpenAPI operationId mismatch for {contract.method.upper()} {contract.path}: "
                f"expected {contract.operation_id!r}, got {operation_item.get('operationId')!r}"
            )
        security = operation_item.get("security", path_item.get("security", document.get("security", [])))
        security_names = tuple(sorted(name for requirement in security for name in requirement))
        if security_names != contract.security:
            raise ValidationError(
                f"OpenAPI security mismatch for {contract.method.upper()} {contract.path}: "
                f"expected {contract.security}, got {security_names}"
            )
        responses = {str(status) for status in operation_item.get("responses", {})}
        missing_responses = set(contract.responses) - responses
        if missing_responses:
            raise ValidationError(
                f"OpenAPI responses missing for {contract.method.upper()} {contract.path}: "
                f"{sorted(missing_responses)}"
            )
        parameters = {
            reference_name(parameter)
            for parameter in (*path_item.get("parameters", []), *operation_item.get("parameters", []))
        }
        missing_parameters = set(contract.parameters) - parameters
        if missing_parameters:
            raise ValidationError(
                f"OpenAPI parameters missing for {contract.method.upper()} {contract.path}: "
                f"{sorted(missing_parameters)}"
            )
        checked += 1
    return checked


def validate_schema_contracts(document: dict, contracts: Iterable[SchemaContract]) -> int:
    schemas = document.get("components", {}).get("schemas", {})
    checked = 0
    for contract in contracts:
        schema = schemas.get(contract.name)
        if not isinstance(schema, dict):
            raise ValidationError(f"OpenAPI contract schema is missing: {contract.name}")
        required = set(schema.get("required", []))
        properties = schema.get("properties", {})
        if missing := set(contract.required) - required:
            raise ValidationError(f"OpenAPI required fields missing from {contract.name}: {sorted(missing)}")
        if missing := set(contract.properties) - set(properties):
            raise ValidationError(f"OpenAPI properties missing from {contract.name}: {sorted(missing)}")
        if leaked := set(contract.forbidden_properties) & set(properties):
            raise ValidationError(f"OpenAPI forbidden properties exposed by {contract.name}: {sorted(leaked)}")
        if contract.enum and tuple(schema.get("enum", ())) != contract.enum:
            raise ValidationError(
                f"OpenAPI enum mismatch for {contract.name}: expected {contract.enum}, got {schema.get('enum')}"
            )
        for property_name in contract.write_only:
            if properties.get(property_name, {}).get("writeOnly") is not True:
                raise ValidationError(f"OpenAPI property must be writeOnly: {contract.name}.{property_name}")
        checked += 1
    return checked


def validate_semantic_contracts(document: dict) -> tuple[int, int]:
    operation_count = validate_operation_contracts(document, OPERATION_CONTRACTS)
    schema_count = validate_schema_contracts(document, SCHEMA_CONTRACTS)
    schemas = document.get("components", {}).get("schemas", {})
    for name in CLOSED_OBJECT_SCHEMAS:
        schema = schemas.get(name)
        if not isinstance(schema, dict) or schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            raise ValidationError(f"OpenAPI schema must be a closed object: {name}")
    for path, method in FORBIDDEN_OPERATIONS:
        if method in document.get("paths", {}).get(path, {}):
            raise ValidationError(f"OpenAPI operation must remain absent: {method.upper()} {path}")
    return operation_count, schema_count + len(CLOSED_OBJECT_SCHEMAS)
