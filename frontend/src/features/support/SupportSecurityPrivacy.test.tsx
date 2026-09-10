import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError } from "../../api/client";
import { SupportDataAccessWorkspace } from "./SupportDataAccessWorkspace";
import { SupportVerificationPanel } from "./SupportVerificationPanel";
import type { components } from "../../api/schema";
const caseId = "a1000000-0000-4000-8000-000000000001";
const grantId = "a6000000-0000-4000-8000-000000000001";
const linkId = "a3000000-0000-4000-8000-000000000001";
const sessionId = "a4000000-0000-4000-8000-000000000001";
const rawPhone = "010-1234-5678";
function response(data: unknown, status = 200) { return { data, response: new Response(null, { status }) } as never; }
function grant() { return { grantId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: caseId, purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "ACTIVE", maxReveals: 1, reservedReveals: 0, requestedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 300_000).toISOString(), version: 2 }; }
const revealed = () => response({ revealAttemptId: grantId, grantId, caseId, subjectId: caseId, values: { CUSTOMER_PRIMARY_PHONE: rawPhone }, revealedAt: new Date().toISOString() });
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });
async function revealButton() { const button = await screen.findByRole("button", { name: "선택한 정보 한시 열람" }); await waitFor(() => expect(button).toBeEnabled()); return button; }
describe("support sensitive state lifetime", () => {
  it("keeps raw values out of storage and clears them immediately on window exit", async () => {
    vi.spyOn(operationsApi, "GET").mockResolvedValue(response({ grant: grant(), viewerRole: "REQUESTER" }));
    vi.spyOn(operationsApi, "POST").mockResolvedValue(revealed());
    render(<MemoryRouter><SupportDataAccessWorkspace initialGrantId={grantId} /></MemoryRouter>);
    await userEvent.click(await revealButton());
    expect(await screen.findByText(rawPhone)).toBeVisible();
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toContain(rawPhone);
    fireEvent(window, new Event("blur"));
    expect(screen.queryByText(rawPhone)).not.toBeInTheDocument();
  });
  it("does not release a late reveal response after window exit or replay the raw request", async () => {
    vi.spyOn(operationsApi, "GET").mockResolvedValue(response({ grant: grant(), viewerRole: "REQUESTER" }));
    let release!: (value: never) => void;
    const post = vi.spyOn(operationsApi, "POST").mockImplementation(() => new Promise(resolve => { release = resolve; }));
    render(<MemoryRouter><SupportDataAccessWorkspace initialGrantId={grantId} /></MemoryRouter>);
    await userEvent.click(await revealButton());
    fireEvent(window, new Event("blur"));
    await act(async () => release(revealed()));
    expect(screen.queryByText(rawPhone)).not.toBeInTheDocument();
    expect(post).toHaveBeenCalledOnce();
  });
  it("clears displayed raw data when the next permission check fails", async () => {
    const get = vi.spyOn(operationsApi, "GET").mockResolvedValue(response({ grant: grant(), viewerRole: "REQUESTER" }));
    vi.spyOn(operationsApi, "POST").mockResolvedValue(revealed());
    render(<MemoryRouter><SupportDataAccessWorkspace initialGrantId={grantId} /></MemoryRouter>);
    await userEvent.click(await revealButton());
    expect(await screen.findByText(rawPhone)).toBeVisible();
    get.mockRejectedValue(new ApiRequestError(403, "ACCESS_DENIED", "Permission was revoked"));
    fireEvent(window, new Event("blur")); fireEvent(window, new Event("focus"));
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.queryByText(rawPhone)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument();
  });
  it("removes raw values at the grant expiry even before the sixty second display limit", async () => {
    vi.useFakeTimers();
    const shortGrant = { ...grant(), expiresAt: new Date(Date.now() + 5000).toISOString() };
    vi.spyOn(operationsApi, "GET").mockResolvedValue(response({ grant: shortGrant, viewerRole: "REQUESTER" }));
    vi.spyOn(operationsApi, "POST").mockResolvedValue(revealed());
    render(<MemoryRouter><SupportDataAccessWorkspace initialGrantId={grantId} /></MemoryRouter>);
    await act(async () => {});
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "선택한 정보 한시 열람" })); });
    expect(screen.getByText(rawPhone)).toBeVisible();
    await act(async () => vi.advanceTimersByTimeAsync(5001));
    expect(screen.queryByText(rawPhone)).not.toBeInTheDocument();
    expect(screen.getByText("열람 기한이 지났습니다")).toBeVisible();
  });
  it("clears proof after sending and uses the server session rather than synthesizing enhanced verification", async () => {
    let session: components["schemas"]["VerificationSessionResource"] = { sessionId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: caseId, purpose: "CONTACT_CONFIRMATION", actionScope: "PERSONAL_DATA_REVEAL", requestedLevel: "ENHANCED", achievedLevel: "UNVERIFIED", state: "PENDING", invalidAttempts: 0, startedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 900_000).toISOString(), version: 1, challenges: [{ challengeId: grantId, sessionId, channel: "REGISTERED_PHONE", state: "ISSUED", requestedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 300_000).toISOString() }] };
    vi.spyOn(operationsApi, "GET").mockImplementation((async () => response(session)) as never);
    let sentProof = "";
    vi.spyOn(operationsApi, "POST").mockImplementation((async (_path: string, options: { body: { proof: string } }) => { sentProof = options.body.proof; session = { ...session, challenges: session.challenges.map(c => ({ ...c, state: "VERIFIED" })) }; return response({ challenge: session.challenges[0], sessionState: "PENDING", achievedLevel: "UNVERIFIED", invalidAttempts: 0, lockedUntil: null }); }) as never);
    const onChange = vi.fn();
    render(<SupportVerificationPanel caseId={caseId} links={[{ linkId, subjectType: "CUSTOMER", subjectId: caseId, relationship: "REQUESTER", linkedAt: session.startedAt }]} disabled={false} onChange={onChange} />);
    await userEvent.type(screen.getByLabelText("기존 본인확인 ID"), sessionId);
    await userEvent.click(screen.getByRole("button", { name: "본인확인 현재 상태 조회" }));
    await userEvent.type(await screen.findByLabelText("일회성 인증 코드"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "인증 코드 확인" }));
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ state: "PENDING", achievedLevel: "UNVERIFIED", challenges: [expect.objectContaining({ state: "VERIFIED" })] })));
    expect(sentProof).toBe("123456");
    expect(screen.queryByLabelText("일회성 인증 코드")).not.toBeInTheDocument();
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toContain("123456");
  });
});
