import { createContext, useContext, useId, type KeyboardEvent, type ReactNode } from "react";

type TabsContextValue = {
  baseId: string;
  value: string;
  activationMode: "manual" | "automatic";
  onValueChange: (value: string) => void;
};

const TabsContext = createContext<TabsContextValue | null>(null);

export type TabsProps = {
  value: string;
  onValueChange: (value: string) => void;
  children: ReactNode;
  activationMode?: "manual" | "automatic";
};

/** Controlled tabs. Manual activation avoids accidental network work while arrowing through remote views. */
export function Tabs({ value, onValueChange, children, activationMode = "manual" }: TabsProps) {
  const id = useId().replaceAll(":", "");
  return <TabsContext.Provider value={{ baseId: `bf-tabs-${id}`, value, activationMode, onValueChange }}><div className="bf-tabs">{children}</div></TabsContext.Provider>;
}

export type TabListProps = { label: string; children: ReactNode };

export function TabList({ label, children }: TabListProps) {
  return <div className="bf-tab-list" role="tablist" aria-label={label}>{children}</div>;
}

export type TabProps = { value: string; children: ReactNode; disabled?: boolean };

export function Tab({ value, children, disabled = false }: TabProps) {
  const tabs = useTabsContext();
  const selected = tabs.value === value;
  const tabId = `${tabs.baseId}-tab-${safeId(value)}`;
  const panelId = `${tabs.baseId}-panel-${safeId(value)}`;
  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    const list = event.currentTarget.closest('[role="tablist"]');
    const options = Array.from(list?.querySelectorAll<HTMLButtonElement>('[role="tab"]:not(:disabled)') ?? []);
    if (!options.length) return;
    const current = options.indexOf(event.currentTarget);
    const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 : (current + (event.key === "ArrowRight" ? 1 : -1) + options.length) % options.length;
    event.preventDefault();
    options[next]?.focus();
    if (tabs.activationMode === "automatic") options[next]?.click();
  }
  return (
    <button className="bf-tab" type="button" role="tab" id={tabId} aria-controls={panelId} aria-selected={selected} tabIndex={selected ? 0 : -1} disabled={disabled} onClick={() => tabs.onValueChange(value)} onKeyDown={handleKeyDown}>
      {children}
    </button>
  );
}

export type TabPanelProps = { value: string; children: ReactNode };

export function TabPanel({ value, children }: TabPanelProps) {
  const tabs = useTabsContext();
  const selected = tabs.value === value;
  return <div className="bf-tab-panel" role="tabpanel" id={`${tabs.baseId}-panel-${safeId(value)}`} aria-labelledby={`${tabs.baseId}-tab-${safeId(value)}`} hidden={!selected} tabIndex={0}>{selected ? children : null}</div>;
}

function useTabsContext() {
  const context = useContext(TabsContext);
  if (!context) throw new Error("Tab components must be rendered inside Tabs");
  return context;
}

function safeId(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, "-");
}
