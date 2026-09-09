import { Minus, Plus } from "lucide-react";

export type QuantityStepperProps = {
  value: number;
  min?: number;
  max?: number;
  label: string;
  onChange: (value: number) => void;
  disabled?: boolean;
};

/** Bounded quantity input with independent keyboard-accessible decrement and increment actions. */
export function QuantityStepper({ value, min = 1, max = 20, label, onChange, disabled = false }: QuantityStepperProps) {
  return (
    <span className={`bf-stepper${disabled ? " is-disabled" : ""}`} role="group" aria-label={label} aria-disabled={disabled || undefined}>
      <button type="button" disabled={disabled || value <= min} aria-label={`${label} 줄이기`} onClick={() => onChange(Math.max(min, value - 1))}><Minus size={16} /></button>
      <output aria-live="polite" aria-label={`${label} ${value}`}>{value}</output>
      <button type="button" disabled={disabled || value >= max} aria-label={`${label} 늘리기`} onClick={() => onChange(Math.min(max, value + 1))}><Plus size={16} /></button>
    </span>
  );
}
