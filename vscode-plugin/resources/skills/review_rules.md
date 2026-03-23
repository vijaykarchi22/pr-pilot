# PR Pilot — Review Rules

These rules guide how the AI performs a code review.  
Edit this file to customise the review focus for your team.

---

## Security
- Flag potential SQL injection, XSS, CSRF, path traversal, command injection, and SSRF vulnerabilities
- Identify hardcoded secrets, API keys, tokens, private certificates, or passwords
- Check for insecure deserialization or unsafe use of reflection
- Detect insecure cryptographic practices such as weak hashing algorithms or custom encryption
- Verify that authentication and authorization checks are correctly enforced
- Ensure sensitive data (tokens, credentials, PII) is not logged
- Identify unsafe file upload handling or improper file validation
- Ensure proper input sanitization and output encoding where user input is processed
- Highlight insecure HTTP usage such as missing HTTPS enforcement or insecure cookies
- Verify security best practices such as rate limiting, input validation, and secure session handling

---

## Performance
- Identify N+1 query patterns or missing database indexes
- Flag unnecessary blocking calls on the main/UI thread
- Highlight unbounded loops, recursion without limits, or excessive nested iterations
- Detect large in-memory collections that could lead to memory pressure or leaks
- Look for missing caching opportunities on expensive operations
- Identify repeated expensive computations that could be memoized or cached
- Check for redundant database queries or API calls inside loops
- Highlight inefficient algorithms with high time complexity
- Verify pagination or batching is used when processing large datasets
- Flag unnecessary object creation or repeated allocations in hot paths

---

## Error Handling
- Ensure exceptions are caught at the right level and not silently swallowed
- Check that error messages are informative without leaking sensitive details
- Verify resources (streams, connections, files, sockets) are properly closed
- Flag catch blocks that log errors but fail to handle them
- Ensure retry logic exists for transient failures such as network or IO operations
- Verify error responses are consistent and predictable
- Ensure logs include enough context such as request IDs or relevant input parameters
- Identify missing validation that could lead to runtime exceptions

---

## Code Quality
- Flag duplicated logic that should be extracted into a shared function
- Identify overly complex methods that should be decomposed (> 30 lines)
- Highlight deeply nested conditionals that could be simplified with guard clauses
- Check that all public methods/functions have clear documentation
- Ensure meaningful variable and function names are used
- Verify consistent formatting and naming conventions across the codebase
- Suggest refactoring opportunities for readability and maintainability
- Flag commented-out code or dead code that should be removed
- Ensure configuration values are not hardcoded and are externalized where appropriate
- Check that new code follows existing architectural patterns and conventions

---

## Testing
- Note when new functionality lacks corresponding unit or integration tests
- Identify edge cases that are not covered by existing tests
- Check that test assertions are meaningful and not trivially true
- Highlight missing negative tests or failure-path tests
- Verify tests validate behavior rather than internal implementation details
- Ensure deterministic test behavior without reliance on timing or external state
- Check that mocks or stubs are used appropriately for external dependencies
- Ensure tests clearly describe expected behavior and are maintainable
- Identify flaky test patterns or unreliable assertions

---

## Null Safety & Type Safety
- Flag unchecked nullable dereferences
- Identify unsafe type casts that could throw at runtime
- Verify proper use of optional or nullable types where applicable
- Check for missing input validation on public API boundaries
- Detect uninitialized variables or unsafe defaults
- Ensure collections are checked before access when empty states are possible
- Identify implicit type conversions that could cause subtle bugs
- Ensure defensive programming practices for external inputs and integrations

---

## Visibility & Encapsulation
- 🔴 **Flag any class, interface, or enum that is missing an explicit visibility modifier** — in Java, omitting `public`/`private`/`protected` makes the type package-private, which is rarely intentional and breaks expected API contracts
- 🔴 Flag any `public` field that should be encapsulated behind a getter/setter
- 🟡 Flag methods with wider visibility than required (e.g. `public` when only used internally)
- 🟡 Flag Kotlin/Java classes that are `open` or non-`final` without a clear documented reason
- Verify that internal implementation classes are not unnecessarily exposed in public APIs
- Check that utility/helper classes are not accidentally left with package-private visibility

---

# Additional Review Dimensions

## API Design
- Verify REST or RPC endpoints follow consistent naming and versioning conventions
- Ensure APIs follow resource-oriented design where appropriate
- Check that HTTP status codes accurately reflect request outcomes
- Validate request and response schemas for clarity and consistency
- Ensure pagination, filtering, and sorting are implemented for list endpoints
- Identify overly large or complex API responses that should be decomposed
- Check that APIs remain backward compatible and do not introduce unintended breaking changes
- Verify validation exists for all request parameters and payloads
- Ensure idempotency is respected for operations that may be retried
- Confirm API contracts are documented or defined via OpenAPI/Swagger specifications

---

## Observability
- Verify that important operations emit structured logs with sufficient context
- Ensure logs include trace identifiers, request IDs, or correlation IDs when applicable
- Flag logging of sensitive information such as credentials or personal data
- Check that meaningful metrics are emitted for critical operations
- Identify missing instrumentation for latency, error rates, and throughput
- Ensure critical services expose health checks or readiness endpoints
- Verify distributed tracing support where applicable
- Ensure logging levels (debug, info, warn, error) are used appropriately
- Check that monitoring hooks exist for failure scenarios and retries

---

## Architecture Review
- Ensure new code follows existing architectural patterns and layering
- Identify violations of separation of concerns or tight coupling between modules
- Check that domain logic is not embedded inside controllers or presentation layers
- Verify dependency direction follows intended architecture boundaries
- Highlight large modules or classes that should be split into smaller components
- Ensure configuration or environment logic is not embedded in business logic
- Identify potential circular dependencies between modules
- Verify shared utilities or services are reused instead of duplicating functionality
- Ensure new abstractions are justified and not unnecessarily complex

---

## Concurrency & Thread Safety
- Identify shared mutable state that could lead to race conditions
- Verify thread-safe handling of shared resources such as caches or collections
- Check that synchronization mechanisms such as locks or atomics are used correctly
- Flag blocking operations inside asynchronous or event-driven code paths
- Identify potential deadlocks caused by improper locking order
- Ensure long-running tasks are executed through background workers or job queues
- Verify safe use of concurrent data structures
- Check that parallel execution does not introduce inconsistent data states
- Ensure retries or concurrent requests do not cause duplicate side effects
