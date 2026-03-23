# Spring Boot Repository — Review Rules

Applied automatically when reviewing `*Repository.java / *Repository.kt / *Dao.java / *Dao.kt` files.

---

## Query Correctness & Safety
- Flag JPQL / native queries that concatenate user-controlled strings — always use named parameters (`:param`) or Spring Data method names
- Verify `@Query` methods have a matching `@Modifying` annotation when they perform INSERT / UPDATE / DELETE
- Check that `@Modifying` queries also have `@Transactional` (or the calling service is transactional)
- Ensure `findBy*` derived methods match actual entity field names — typos silently produce wrong queries

## N+1 & Performance
- Flag `List<Entity>` return types that could trigger N+1 queries when associations are accessed — use `@EntityGraph` or JOIN FETCH
- Check that pagination is used for queries that could return unbounded result sets (`Pageable` parameter)
- Warn on `findAll()` called without a `Pageable` or `Specification` on large tables
- Identify missing database index hints for columns used in WHERE / ORDER BY clauses in native queries

## Transactions
- Verify `@Transactional(readOnly = true)` is set on read-only queries for performance
- Flag `@Transactional` on methods that do not actually require a transaction (single read without modification)
- Ensure transactional boundaries do not span multiple repository calls at the repository layer — that belongs in the service

## Spring Data Conventions
- Verify the repository interface extends the correct base: `JpaRepository`, `CrudRepository`, or `PagingAndSortingRepository` as needed
- Flag custom base repository classes that re-implement logic already in Spring Data
- Ensure `Optional<T>` is used for methods that may return zero results, never `null`
- Verify projection interfaces or DTOs are used when only a subset of fields is needed (avoid fetching full entities)

## Auditing & Soft-Delete
- Check that entities with `@CreatedDate` / `@LastModifiedDate` have `@EnableJpaAuditing` configured
- Verify soft-delete repositories filter on `deletedAt IS NULL` in all queries unless explicitly listing deleted records
