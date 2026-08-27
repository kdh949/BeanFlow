import { Search, X } from "lucide-react";
import { useId, type InputHTMLAttributes } from "react";

export type SearchFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "className" | "style"> & {
  label: string;
  description?: string;
  error?: string;
  invalid?: boolean;
  onClear?: () => void;
};

/** Search input with a visible search affordance and an accessible clear action. */
export function SearchField({ label, description, error, invalid = false, onClear, value, id: providedId, "aria-describedby": providedDescription, ...props }: SearchFieldProps) {
  const generatedId = useId().replaceAll(":", "");
  const inputId = providedId ?? `bf-search-${generatedId}`;
  const descriptionId = description ? `${inputId}-description` : undefined;
  const errorId = error ? `${inputId}-error` : undefined;
  const describedBy = [providedDescription, descriptionId, errorId].filter(Boolean).join(" ") || undefined;
  const hasValue = typeof value === "string" && value.length > 0;
  return (
    <div className="bf-field">
      <label className="bf-search-field" htmlFor={inputId}>
        <Search size={22} aria-hidden="true" />
        <span className="bf-sr-only">{label}</span>
        <input {...props} id={inputId} value={value} type="search" aria-invalid={error || invalid ? true : undefined} aria-describedby={describedBy} />
        {hasValue && onClear ? <button type="button" aria-label="검색어 지우기" onClick={onClear}><X size={16} aria-hidden="true" /></button> : null}
      </label>
      {description ? <span className="bf-field__description" id={descriptionId}>{description}</span> : null}
      {error ? <span className="bf-field__error" id={errorId} role="alert">{error}</span> : null}
    </div>
  );
}
