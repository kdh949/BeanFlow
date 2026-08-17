/**
 * A place for feature modules (cart, submit-intent caches, ...) to register a
 * reset that runs on customer logout, without the auth module importing those
 * feature modules directly and without them importing the auth module back.
 */
const handlers = new Set<() => void>();

export function onCustomerLogout(handler: () => void): void {
  handlers.add(handler);
}

export function runCustomerLogoutHandlers(): void {
  handlers.forEach((handler) => handler());
}
