import { useCallback, useEffect, useRef, useState } from "react";

export type Resource<T> =
  | { status: "loading" }
  | { status: "ready"; value: T }
  | { status: "failed"; error: unknown };

/**
 * One read with explicit loading, ready and failed states. A failed read stays
 * failed: it never falls back to an empty list or a zero value, because a
 * failure and "there is nothing" are different answers.
 */
export function useResource<T>(load: () => Promise<T>): { state: Resource<T>; reload: () => void } {
  const [state, setState] = useState<Resource<T>>({ status: "loading" });
  const generation = useRef(0);

  const run = useCallback(async () => {
    const current = ++generation.current;
    setState({ status: "loading" });
    try {
      const value = await load();
      if (generation.current === current) setState({ status: "ready", value });
    } catch (error) {
      if (generation.current === current) setState({ status: "failed", error });
    }
  }, [load]);

  useEffect(() => {
    void run();
  }, [run]);

  return { state, reload: () => void run() };
}
