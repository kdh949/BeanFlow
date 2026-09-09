import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes, ComponentProps, ReactNode } from "react";
import { Link } from "react-router";

export type ButtonVariant = "brand" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg" | "xl";

type ButtonVisualProps = {
  /** Visual emphasis. Use `brand` once per decision area and `danger` only for destructive actions. */
  variant?: ButtonVariant;
  /** Control height and horizontal density from the canonical size scale. */
  size?: ButtonSize;
  /** Expands the control to the width of its container. */
  block?: boolean;
  /** Shows progress and prevents repeated submission. */
  loading?: boolean;
  children: ReactNode;
};

export type ButtonProps = ButtonVisualProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "className" | "style">;

export type ButtonLinkProps = Omit<ButtonVisualProps, "loading"> &
  Omit<ComponentProps<typeof Link>, "children" | "className" | "style">;

/** Canonical BeanFlow action control derived from the selected customer and merchant stories. */
export function Button({
  variant = "brand",
  size = "md",
  block = false,
  loading = false,
  disabled,
  type = "button",
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      className={buttonClassName(variant, size, block)}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      {loading ? <LoaderCircle className="bf-spin" size={16} aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

/** Router-aware canonical navigation action. */
export function ButtonLink({ variant = "brand", size = "md", block = false, children, ...props }: ButtonLinkProps) {
  return <Link {...props} className={buttonClassName(variant, size, block)}>{children}</Link>;
}

function buttonClassName(variant: ButtonVariant, size: ButtonSize, block: boolean) {
  return ["bf-action", `bf-action--${variant}`, `bf-action--${size}`, block ? "bf-action--block" : null]
    .filter(Boolean)
    .join(" ");
}
