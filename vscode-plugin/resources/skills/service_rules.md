# Angular Service — Review Rules

Applied automatically when reviewing `*.service.ts` files.

---

## Responsibility & Design
- Flag services that mix HTTP communication, business logic, AND UI state in one class — split into data service + facade/store
- Identify services registered in `root` that hold mutable shared state without proper synchronisation — prefer `BehaviorSubject` / `Signal`
- Check that services are provided at the correct scope (`providedIn: 'root'` vs feature-module providers)
- Flag direct use of `localStorage` / `sessionStorage` — wrap in a dedicated storage service for testability

## RxJS Usage
- Detect subscriptions that are never unsubscribed (no `takeUntil`, `take(1)`, `async` pipe, or `DestroyRef`)
- Flag nested `subscribe()` calls — flatten with `switchMap`, `mergeMap`, or `concatMap`
- Warn on `subscribe()` inside a service that returns `void` — consider returning `Observable` to the caller instead
- Check that error paths in pipelines use `catchError` and return a safe fallback, not an empty `Subject`
- Flag use of `tap()` for side-effects that change state — document intent clearly

## HTTP & API Calls
- Verify all HTTP calls go through a typed service method, never raw `HttpClient` in components
- Ensure `HttpErrorResponse` is handled explicitly and translated into domain errors
- Check that request payloads are strongly typed (no `any`)
- Flag missing retry logic for idempotent GET requests in unreliable network conditions
- Warn when sensitive data (tokens, PII) is included in query parameters

## State Management
- Flag imperative state mutation when the project uses NgRx / Akita / NGXS — dispatch actions instead
- Identify `BehaviorSubject` that are exposed as writable — expose only `asObservable()`
- Verify effects/side-effects are isolated in Effects files, not inside services, when NgRx is used

## Testing
- Ensure each public method has a unit test covering the happy path and error path
- Verify `HttpClientTestingModule` is used for services with HTTP calls
