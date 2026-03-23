# Spring Boot Service — Review Rules

Applied automatically when reviewing `*Service.java / *Service.kt / *ServiceImpl.java / *ServiceImpl.kt` files.

---

## Transaction Management
- Verify `@Transactional` is placed on the service method, not on the repository call
- Check that `@Transactional(readOnly = true)` is used for all read-only service methods
- Flag methods that initiate multiple repository calls without a wrapping `@Transactional` — partial failures will leave data inconsistent
- Ensure `@Transactional(rollbackFor = SpecificException.class)` is used when checked exceptions must roll back the transaction

## Business Logic & Design
- Flag direct repository calls from methods that should delegate conditionally — ensure the service owns the orchestration
- Check that the service does not construct HTTP responses, error codes, or web-layer objects — that belongs in the controller
- Verify complex branching logic is not duplicated across multiple service methods — extract shared rules
- Ensure domain invariants (e.g. "a cancelled order cannot be re-opened") are enforced here, not in the controller or repository

## Exception Handling
- Verify domain exceptions (e.g. `OrderNotFoundException`) are thrown with meaningful messages, not generic `RuntimeException`
- Flag swallowed exceptions (empty catch blocks or catch-and-return-null patterns)
- Ensure exceptions cross the service boundary at the right abstraction level (domain exception, not `DataIntegrityViolationException`)
- Check that infrastructure exceptions (SQL, IO) are translated into domain exceptions before propagating to callers

## External Dependencies & Side-Effects
- Verify calls to external services (email, SMS, payment, message broker) are wrapped in a try/catch with proper fallback or retry logic
- Flag synchronous calls to slow external services on a request thread — consider async or event-driven approaches
- Ensure outbox or event dispatch logic is inside the same transaction as the state change (transactional outbox pattern)

## Testability
- Check that the service can be instantiated with constructor injection (no `@Autowired` field injection) for easy unit testing
- Verify all external collaborators are interfaces, not concrete classes, so they can be mocked in tests
- Ensure tests cover the happy path, not-found, and validation-failure scenarios
