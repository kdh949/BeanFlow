import { Search, X } from "lucide-react";
import type { InputHTMLAttributes } from "react";

export type SearchFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "className" | "style"> & {
  label: string;
  onClear?: () => void;
};

/** Search input with a visible search affordance and an accessible clear action. */
export function SearchField({ label, onClear, value, ...props }: SearchFieldProps) {
  const hasValue = typeof value === "string" && value.length > 0;
  return (
    <label className="bf-search-field">
      <Search size={22} aria-hidden="true" />
      <span className="bf-sr-only">{label}</span>
      <input {...props} value={value} type="search" />
      {hasValue && onClear ? <button type="button" aria-label="검색어 지우기" onClick={onClear}><X size={16} aria-hidden="true" /></button> : null}
    </label>
  );
}
