# Angular Guard — Review Rules

Applied automatically when reviewing `*.guard.ts` files.

---

## Route Protection
- Verify the guard returns `Observable<boolean | UrlTree>`, `Promise<boolean | UrlTree>`, or `boolean | UrlTree` — never `void`
- Flag guards that always return `true` — they are dead code and should be removed
- Check that unauthorised users are redirected (`router.createUrlTree(['/login'])`) rather than just returning `false` (which leaves users on a blank screen)
- Ensure the guard does not hard-code redirect paths — use a configurable constant or service

## Authentication & Authorisation
- Verify that the guard reads auth state from a service / store, not from `localStorage` directly
- Flag guards that check roles or permissions with inline strings — use an enum or constant
- Check that token expiry is validated, not just token presence
- Ensure the guard handles the unauthenticated loading state (observable not yet resolved) gracefully

## Design & Testability
- Flag guards with business logic beyond auth/permission checks — move logic to a service
- Verify the guard is `inject()`-based (functional guard) or properly uses `CanActivate` / `CanActivateFn`
- Ensure error paths (e.g., failed auth API call) redirect to an error page or login, not a silent failure
- Check that guards are covered by unit tests that stub the auth service
