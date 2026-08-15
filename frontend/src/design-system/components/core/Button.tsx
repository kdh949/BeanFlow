import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes, ComponentProps, ReactNode } from "react";
import { Link } from "react-router";

export type ButtonVariant = "primary" | "accent" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg" | "xl";

type ButtonVisualProps = {
  /** Visual emphasis. Use `primary` once per decision area and `danger` only for destructive actions. */
  variant?: ButtonVariant;
  /** Control height and horizontal density. */
  size?: ButtonSize;
  /** Expands the control to the width of its container. */
  block?: boolean;
  /** Replaces the leading content with a spinner and prevents repeated submission. */
  loading?: boolean;
  /** Visible button content. Labels should name the action and its object. */
  children: ReactNode;
};

export type ButtonProps = ButtonVisualProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "className" | "style">;

export type ButtonLinkProps = Omit<ButtonVisualProps, "loading"> &
  Omit<ComponentProps<typeof Link>, "children" | "className" | "style">;

/** Canonical BeanFlow action control. Static visual overrides are intentionally not exposed. */
export function Button({
  variant = "primary",
  size = "md",
  block = false,
  loading = false,
  disabled,
  children,
  type = "button",
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
      {loading ? <LoaderCircle className="bf-btn__spinner" size={16} aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

/** Router-aware canonical action link. Use for navigation, never for form submission. */
export function ButtonLink({
  variant = "primary",
  size = "md",
  block = false,
  children,
  ...props
}: ButtonLinkProps) {
  return (
    <Link {...props} className={buttonClassName(variant, size, block)}>
      {children}
    </Link>
  );
}

function buttonClassName(variant: ButtonVariant, size: ButtonSize, block: boolean) {
  return ["bf-btn", `bf-btn--${variant}`, `bf-btn--${size}`, block ? "bf-btn--block" : null]
    .filter(Boolean)
    .join(" ");
}
