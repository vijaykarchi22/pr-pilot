# Spring Boot Controller — Review Rules

Applied automatically when reviewing `*Controller.java / *Controller.kt / *Resource.java / *Resource.kt` files.

---

## REST Design
- Verify HTTP methods are semantically correct: GET for reads, POST for creation, PUT/PATCH for updates, DELETE for removal
- Flag endpoints that return 200 for operations that should return 201 (Created) or 204 (No Content)
- Ensure response bodies are typed DTOs / response objects — never return JPA entities directly
- Check that error responses use `ProblemDetail` (RFC 7807) or a consistent error envelope
- Flag `@RequestMapping` on methods without an explicit HTTP method — use `@GetMapping`, `@PostMapping`, etc.
- Detect missing `produces` / `consumes` media type specifications when the API is non-JSON

## Input Validation & Security
- Verify all request body, path variable, and query parameter inputs are validated with Bean Validation (`@Valid`, `@NotNull`, `@Size`, etc.)
- Flag `@PathVariable` or `@RequestParam` values used directly in queries, file paths, or commands without sanitisation
- Check that `@RequestBody` is validated with `@Valid` and a `BindingResult` or global `@ExceptionHandler` is in place
- Ensure authentication/authorisation annotations are present (`@PreAuthorize`, `@Secured`) where required
- Flag endpoints that expose internal IDs that could enable enumeration attacks — consider UUIDs
- Verify CSRF protection is not inadvertently disabled for state-changing endpoints

## Service Layer Delegation
- Flag business logic (calculations, transformations, decisions) directly in the controller — delegate to a `@Service`
- Ensure the controller does not directly call repositories — always go through a service
- Check transaction management is not done in the controller — it belongs in the service layer

## Response & Error Handling
- Verify all exceptions are either handled by a `@ControllerAdvice` / `@RestControllerAdvice` or explicitly caught
- Ensure HTTP status codes are set correctly for error conditions (400 vs 404 vs 409 vs 500)
- Flag controllers that return `null` — always return a typed `ResponseEntity` with an explicit status

## Documentation & Observability
- Ensure each endpoint has OpenAPI annotations (`@Operation`, `@ApiResponse`) if the project uses Springdoc
- Check that log statements use structured data (MDC or key=value) and do not log sensitive payloads
