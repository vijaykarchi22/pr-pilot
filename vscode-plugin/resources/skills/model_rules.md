# Spring Boot Model / Entity / DTO — Review Rules

Applied automatically when reviewing `*Entity.java/kt`, `*Dto.java/kt`, `*Request.java/kt`, `*Response.java/kt`, `*Domain.java/kt` files.

---

## JPA Entity Design
- Verify every entity has a primary key annotated with `@Id` and an appropriate `@GeneratedValue` strategy
- Flag entities missing `@Table(name = "...")` — rely on explicit names, not JPA defaults, to avoid migration surprises
- Check that `equals()` and `hashCode()` are implemented based on the business key (not the auto-generated `id`) when entities are used in sets or maps
- Flag mutable `List` / `Set` associations without `FetchType.LAZY` — eager fetching of collections is a common performance trap
- Ensure `@OneToMany` relationships use `cascade = CascadeType.ALL` + `orphanRemoval = true` only when the child cannot exist independently
- Verify `@Column(nullable = false)` is set for fields that must not be null — align with DB schema constraints
- Flag Lombok `@Data` on JPA entities — it generates `equals/hashCode` based on all fields (including the ID), causing Hibernate issues; use `@Getter @Setter` instead
- Check that entity classes are not `final` (Hibernate proxies cannot subclass final classes)

## DTO / Request / Response Design
- Ensure DTOs do not contain JPA annotations — they are plain data carriers
- Verify all fields that require validation have Bean Validation annotations (`@NotBlank`, `@Size`, `@Min`, etc.)
- Flag DTOs that expose internal database IDs without necessity — use UUIDs or opaque tokens for external-facing IDs
- Check that sensitive fields (passwords, tokens, secrets) are excluded from response DTOs and never serialised
- Ensure `@JsonIgnore` or `@JsonProperty(access = WRITE_ONLY)` protects write-only fields (e.g. passwords)
- Verify `@JsonNaming` or explicit `@JsonProperty` is used for fields that diverge from the Java naming convention

## Immutability & Thread Safety
- Flag shared mutable state in domain objects — prefer value objects or records where applicable
- Check that Java records or Kotlin `data class` are used for DTOs where mutation is not needed
- Ensure no entity or DTO is used as a thread-shared singleton
