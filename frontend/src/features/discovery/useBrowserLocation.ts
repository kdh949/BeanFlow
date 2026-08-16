import { useCallback, useState } from "react";

export type Coordinates = { latitude: number; longitude: number };

/**
 * Location is an optional refinement, never a precondition. A denied permission
 * and an unavailable sensor are separate states so the screen can keep working
 * without coordinates instead of showing an error.
 */
export type BrowserLocationState =
  | { status: "idle" }
  | { status: "locating" }
  | { status: "granted"; coordinates: Coordinates }
  | { status: "denied" }
  | { status: "unavailable" };

export function useBrowserLocation(initial?: Coordinates | null) {
  const [state, setState] = useState<BrowserLocationState>(
    initial ? { status: "granted", coordinates: initial } : { status: "idle" },
  );

  const locate = useCallback(() => {
    if (!navigator.geolocation) {
      setState({ status: "unavailable" });
      return;
    }
    setState({ status: "locating" });
    navigator.geolocation.getCurrentPosition(
      (position) =>
        setState({
          status: "granted",
          coordinates: { latitude: position.coords.latitude, longitude: position.coords.longitude },
        }),
      (failure) => setState({ status: failure.code === failure.PERMISSION_DENIED ? "denied" : "unavailable" }),
      { enableHighAccuracy: false, timeout: 8_000 },
    );
  }, []);

  return { state, locate };
}

export function coordinatesOf(state: BrowserLocationState): Coordinates | null {
  return state.status === "granted" ? state.coordinates : null;
}

export function distanceLabel(distanceMeters: number | undefined): string | null {
  if (distanceMeters === undefined) return null;
  return distanceMeters < 1_000 ? `${distanceMeters}m` : `${(distanceMeters / 1_000).toFixed(1)}km`;
}
