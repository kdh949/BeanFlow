import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";

export type FieldSize = "md" | "lg";
export type FieldLabelVisibility = "visible" | "sr-only";

type FieldContentProps = {
  /** User-facing label. Every field keeps an accessible label even when visually hidden. */
  label: string;
  /** Secondary guidance shown before validation feedback. */
  description?: string;
  /** Presentation-safe validation message. Its presence marks the field invalid. */
  error?: string;
  /** Marks a field invalid when a shared form-level error message is rendered elsewhere. */
  invalid?: boolean;
  /** Customer controls generally use `lg`; dense workspaces generally use `md`. */
  size?: FieldSize;
  labelVisibility?: FieldLabelVisibility;
};

export type TextFieldType = "text" | "email" | "tel" | "password" | "number" | "date";

export type TextFieldProps = FieldContentProps &
  Omit<InputHTMLAttributes<HTMLInputElement>, "className" | "style" | "size" | "type" | "value" | "onChange"> & {
    type?: TextFieldType;
    value: string;
    onValueChange: (value: string) => void;
  };

export type TextAreaFieldProps = FieldContentProps &
  Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, "className" | "style" | "value" | "onChange"> & {
    value: string;
    onValueChange: (value: string) => void;
    resize?: "vertical" | "none";
  };

export type SelectFieldProps = FieldContentProps &
  Omit<SelectHTMLAttributes<HTMLSelectElement>, "className" | "style" | "size" | "value" | "onChange" | "children"> & {
    value: string;
    onValueChange: (value: string) => void;
    children: ReactNode;
  };

export type FileFieldProps = FieldContentProps &
  Omit<InputHTMLAttributes<HTMLInputElement>, "className" | "style" | "size" | "type" | "value" | "onChange"> & {
    onFileChange: (file: File | null) => void;
  };

/** Canonical controlled single-line field. Numeric and date values remain strings so empty input is never coerced. */
export function TextField({
  label,
  description,
  error,
  invalid = false,
  size = "md",
  labelVisibility = "visible",
  type = "text",
  value,
  onValueChange,
  id: providedId,
  "aria-describedby": providedDescription,
  ...props
}: TextFieldProps) {
  const ids = useFieldIds(providedId, description, error, providedDescription);
  return (
    <FieldFrame label={label} labelVisibility={labelVisibility} inputId={ids.inputId} description={description} error={error} descriptionId={ids.descriptionId} errorId={ids.errorId}>
      <input {...props} id={ids.inputId} className={`bf-field__control bf-field__control--${size}`} type={type} value={value} aria-invalid={error || invalid ? true : undefined} aria-describedby={ids.describedBy} onChange={(event) => onValueChange(event.target.value)} />
    </FieldFrame>
  );
}

/** Canonical controlled multiline field with vertical resize by default. */
export function TextAreaField({
  label,
  description,
  error,
  invalid = false,
  size = "md",
  labelVisibility = "visible",
  value,
  onValueChange,
  resize = "vertical",
  id: providedId,
  "aria-describedby": providedDescription,
  rows = 4,
  ...props
}: TextAreaFieldProps) {
  const ids = useFieldIds(providedId, description, error, providedDescription);
  return (
    <FieldFrame label={label} labelVisibility={labelVisibility} inputId={ids.inputId} description={description} error={error} descriptionId={ids.descriptionId} errorId={ids.errorId}>
      <textarea {...props} id={ids.inputId} className={`bf-field__control bf-field__control--${size} bf-field__textarea bf-field__textarea--${resize}`} rows={rows} value={value} aria-invalid={error || invalid ? true : undefined} aria-describedby={ids.describedBy} onChange={(event) => onValueChange(event.target.value)} />
    </FieldFrame>
  );
}

/** Canonical controlled native select. Options remain product-owned children. */
export function SelectField({
  label,
  description,
  error,
  invalid = false,
  size = "md",
  labelVisibility = "visible",
  value,
  onValueChange,
  children,
  id: providedId,
  "aria-describedby": providedDescription,
  ...props
}: SelectFieldProps) {
  const ids = useFieldIds(providedId, description, error, providedDescription);
  return (
    <FieldFrame label={label} labelVisibility={labelVisibility} inputId={ids.inputId} description={description} error={error} descriptionId={ids.descriptionId} errorId={ids.errorId}>
      <select {...props} id={ids.inputId} className={`bf-field__control bf-field__control--${size} bf-field__select`} value={value} aria-invalid={error || invalid ? true : undefined} aria-describedby={ids.describedBy} onChange={(event) => onValueChange(event.target.value)}>
        {children}
      </select>
    </FieldFrame>
  );
}

/** Canonical single-file upload field. The browser owns the filename while product code owns the selected File. */
export function FileField({
  label,
  description,
  error,
  invalid = false,
  size = "md",
  labelVisibility = "visible",
  onFileChange,
  id: providedId,
  "aria-describedby": providedDescription,
  ...props
}: FileFieldProps) {
  const ids = useFieldIds(providedId, description, error, providedDescription);
  return (
    <FieldFrame label={label} labelVisibility={labelVisibility} inputId={ids.inputId} description={description} error={error} descriptionId={ids.descriptionId} errorId={ids.errorId}>
      <input {...props} id={ids.inputId} className={`bf-field__control bf-field__control--${size} bf-field__file`} type="file" aria-invalid={error || invalid ? true : undefined} aria-describedby={ids.describedBy} onChange={(event) => onFileChange(event.target.files?.item(0) ?? null)} />
    </FieldFrame>
  );
}

type FieldFrameProps = {
  label: string;
  labelVisibility: FieldLabelVisibility;
  inputId: string;
  description?: string;
  error?: string;
  descriptionId?: string;
  errorId?: string;
  children: ReactNode;
};

function FieldFrame({ label, labelVisibility, inputId, description, error, descriptionId, errorId, children }: FieldFrameProps) {
  return (
    <div className="bf-field">
      <label className={labelVisibility === "sr-only" ? "bf-sr-only" : "bf-field__label"} htmlFor={inputId}>{label}</label>
      {children}
      {description ? <span className="bf-field__description" id={descriptionId}>{description}</span> : null}
      {error ? <span className="bf-field__error" id={errorId} role="alert">{error}</span> : null}
    </div>
  );
}

function useFieldIds(providedId: string | undefined, description: string | undefined, error: string | undefined, providedDescription: string | undefined) {
  const generatedId = useId();
  const inputId = providedId ?? `bf-field-${generatedId.replaceAll(":", "")}`;
  const descriptionId = description ? `${inputId}-description` : undefined;
  const errorId = error ? `${inputId}-error` : undefined;
  return {
    inputId,
    descriptionId,
    errorId,
    describedBy: [providedDescription, descriptionId, errorId].filter(Boolean).join(" ") || undefined,
  };
}
