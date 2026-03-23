# Angular Interceptor — Review Rules

Applied automatically when reviewing `*.interceptor.ts` files.

---

## Request Handling
- Verify the interceptor clones the request before modifying it (`req.clone(...)`) — never mutate the original
- Flag adding `Authorization` headers when the URL is a third-party domain (token leakage risk)
- Check that sensitive headers are not accidentally logged
- Ensure the interceptor is idempotent — adding a header twice should not break the request

## Response & Error Handling
- Verify error responses are handled with `catchError` and the error is re-thrown or translated (not swallowed)
- Check that 401 responses trigger a token refresh or logout flow, not just a redirect
- Flag interceptors that retry on every error — ensure retries are limited and only for appropriate status codes (e.g., 503, network errors)
- Ensure the interceptor calls `next.handle(req)` in all code paths — missing it stalls requests silently

## Performance & Ordering
- Flag interceptors that add significant synchronous computation in the request path
- Check that the interceptor is registered in the correct order relative to auth / logging interceptors
- Verify there is no redundant interceptor logic already handled by an existing interceptor

## Design
- Flag interceptors with more than one responsibility (e.g., auth + logging + caching) — split them
- Ensure the interceptor is functional (`HttpInterceptorFn`) or class-based consistently with the project style
- Check that the interceptor is covered by unit tests using `HttpClientTestingModule`
