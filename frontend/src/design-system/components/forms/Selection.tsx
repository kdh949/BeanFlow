import { createContext, useContext, useId, type ReactNode } from "react";

type SelectionCommonProps = {
  label: string;
  description?: string;
  disabled?: boolean;
  required?: boolean;
  name?: string;
};

export type CheckboxProps = SelectionCommonProps & {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  trailing?: ReactNode;
  variant?: "standard" | "card";
  value?: string;
};

/** Native checkbox with a full-row 44px target and an optional card presentation. */
export function Checkbox({ label, description, checked, onCheckedChange, trailing, variant = "standard", disabled, required, name, value }: CheckboxProps) {
  return (
    <label className={`bf-check bf-check--${variant}${disabled ? " is-disabled" : ""}`}>
      <input type="checkbox" checked={checked} disabled={disabled} required={required} name={name} value={value} onChange={(event) => onCheckedChange(event.target.checked)} />
      <span className="bf-check__copy"><strong>{label}</strong>{description ? <small>{description}</small> : null}</span>
      {trailing ? <span className="bf-check__trailing">{trailing}</span> : null}
    </label>
  );
}

export type SwitchProps = SelectionCommonProps & {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
};

/** Binary preference control. Use Checkbox when the choice belongs to a form submission. */
export function Switch({ label, description, checked, onCheckedChange, disabled, required, name }: SwitchProps) {
  return (
    <label className={`bf-switch${disabled ? " is-disabled" : ""}`}>
      <span className="bf-switch__copy"><strong>{label}</strong>{description ? <small>{description}</small> : null}</span>
      <input role="switch" type="checkbox" checked={checked} disabled={disabled} required={required} name={name} onChange={(event) => onCheckedChange(event.target.checked)} />
      <span className="bf-switch__track" aria-hidden="true"><span /></span>
    </label>
  );
}

type RadioContextValue = {
  name: string;
  value: string;
  disabled: boolean;
  onValueChange: (value: string) => void;
};

const RadioContext = createContext<RadioContextValue | null>(null);

export type RadioGroupProps = {
  label: string;
  description?: string;
  error?: string;
  value: string;
  onValueChange: (value: string) => void;
  children: ReactNode;
  name?: string;
  disabled?: boolean;
};

/** Controlled native radio group that owns labeling and keyboard semantics. */
export function RadioGroup({ label, description, error, value, onValueChange, children, name, disabled = false }: RadioGroupProps) {
  const generated = useId();
  const groupName = name ?? `bf-radio-${generated.replaceAll(":", "")}`;
  const descriptionId = description ? `${groupName}-description` : undefined;
  const errorId = error ? `${groupName}-error` : undefined;
  const describedBy = [descriptionId, errorId].filter(Boolean).join(" ") || undefined;
  return (
    <fieldset className="bf-radio-group" aria-describedby={describedBy} aria-invalid={error ? true : undefined} disabled={disabled}>
      <legend>{label}</legend>
      {description ? <p id={descriptionId} className="bf-field__description">{description}</p> : null}
      <RadioContext.Provider value={{ name: groupName, value, disabled, onValueChange }}>{children}</RadioContext.Provider>
      {error ? <p id={errorId} className="bf-field__error">{error}</p> : null}
    </fieldset>
  );
}

export type RadioOptionProps = {
  value: string;
  label: string;
  description?: string;
  trailing?: ReactNode;
  disabled?: boolean;
};

/** Compact option inside RadioGroup. */
export function RadioOption(props: RadioOptionProps) {
  return <RadioChoice {...props} variant="standard" />;
}

/** Rich card option for pickup slots and region results. */
export function RadioCard(props: RadioOptionProps) {
  return <RadioChoice {...props} variant="card" />;
}

function RadioChoice({ value, label, description, trailing, disabled = false, variant }: RadioOptionProps & { variant: "standard" | "card" }) {
  const group = useContext(RadioContext);
  if (!group) throw new Error("RadioOption and RadioCard must be rendered inside RadioGroup");
  const unavailable = disabled || group.disabled;
  return (
    <label className={`bf-radio bf-radio--${variant}${unavailable ? " is-disabled" : ""}`}>
      <input type="radio" name={group.name} value={value} checked={group.value === value} disabled={unavailable} onChange={() => group.onValueChange(value)} />
      <span className="bf-radio__copy"><strong>{label}</strong>{description ? <small>{description}</small> : null}</span>
      {trailing ? <span className="bf-radio__trailing">{trailing}</span> : null}
    </label>
  );
}
