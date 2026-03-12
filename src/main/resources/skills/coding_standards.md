# PR Pilot — Coding Standards

These are the coding standards the AI will validate code against during review.
Replace the placeholders below with your team's actual standards.

## Naming Conventions
- **Variables & functions:** camelCase (e.g. `getUserById`)
- **Classes & interfaces:** PascalCase (e.g. `UserRepository`)
- **Constants:** UPPER_SNAKE_CASE (e.g. `MAX_RETRY_COUNT`)
- **Files:** match the primary class/object name they contain
- Avoid abbreviations unless they are universally understood (e.g. `id`, `url`)

## Code Style
- Maximum line length: **120 characters**
- Indentation: **4 spaces** (no tabs)
- No trailing whitespace
- One blank line between top-level declarations
- Braces on the same line as the statement (K&R style)

## Documentation
- All `public` and `internal` functions must have a KDoc/Javadoc comment
- Complex logic blocks must have inline comments explaining **why**, not **what**
- TODO comments must include a ticket reference (e.g. `// TODO(PROJ-123): refactor this`)

## Architecture
- No business logic in UI/View layer — delegate to ViewModels or Services
- All repository / data-access calls must be **asynchronous** (suspend / coroutine / RxJava)
- Avoid direct dependency on concrete implementations — program to interfaces
- Follow **single-responsibility principle**: one class, one job

## Dependencies
- Do not add new third-party dependencies without team approval
- Prefer standard library functions over external utilities for trivial tasks
- All new dependencies must be pinned to an exact version in the build file

## Testing
- Minimum **80% unit test coverage** for new business logic
- Tests must follow the **Arrange / Act / Assert** pattern
- Test class names: `<ClassUnderTest>Test` (e.g. `UserRepositoryTest`)
- No `Thread.sleep()` in tests — use test coroutine dispatchers or mocks

## Version Control
- Commits must reference a ticket (e.g. `feat(PROJ-123): add user login`)
- PR title format: `<type>(<scope>): <short description>`
  - Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- Each PR should address a single concern

