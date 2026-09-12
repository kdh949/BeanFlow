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
export function useResource<T>(load: () => Promise<T>): { state: Resource<T>; reload: () => void; refresh: () => void; refreshing: boolean } {
  const [state, setState] = useState<Resource<T>>({ status: "loading" });
  const [refreshing, setRefreshing] = useState(false);
  const generation = useRef(0);

  const run = useCallback(async (clear = true) => {
    const current = ++generation.current;
    if (clear) setState({ status: "loading" });
    setRefreshing(true);
    try {
      const value = await load();
      if (generation.current === current) setState({ status: "ready", value });
    } catch (error) {
      if (generation.current === current) setState({ status: "failed", error });
    } finally {
      if (generation.current === current) setRefreshing(false);
    }
  }, [load]);

  useEffect(() => {
    void run();
    return () => { ++generation.current; };
  }, [run]);

  const reload = useCallback(() => void run(), [run]);
  // Keep controls mounted during a background read; failed reads still remove the old value.
  const refresh = useCallback(() => void run(false), [run]);
  return { state, reload, refresh, refreshing };
}
