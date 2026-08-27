import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";

export type IconButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label" | "className" | "style" | "children"> & {
  label: string;
  children: ReactNode;
  variant?: "secondary" | "ghost";
  size?: "md" | "lg";
  loading?: boolean;
};

/** Icon-only action with a mandatory accessible label and canonical hit target. */
export function IconButton({ label, children, variant = "secondary", size = "md", loading = false, disabled, type = "button", ...props }: IconButtonProps) {
  return (
    <button {...props} type={type} className={`bf-icon-action bf-icon-action--${variant} bf-icon-action--${size}`} aria-label={label} aria-busy={loading || undefined} disabled={disabled || loading}>
      {loading ? <LoaderCircle className="bf-spin" size={16} aria-hidden="true" /> : children}
    </button>
  );
}

export type ChipButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className" | "style"> & {
  children: ReactNode;
};

/** Compact helper or filter action. Use `aria-pressed` only when it represents a persistent filter selection. */
export function ChipButton({ children, type = "button", ...props }: ChipButtonProps) {
  return <button {...props} type={type} className="bf-chip-action">{children}</button>;
}
