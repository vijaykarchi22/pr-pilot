# PR Pilot — Review Rules

These rules guide how the AI performs a code review.
Edit this file to customise the review focus for your team.

## Security
- Flag potential SQL injection, XSS, CSRF, and path traversal vulnerabilities
- Identify hardcoded secrets, tokens, or passwords
- Check for insecure deserialization or unsafe use of reflection
- Verify that sensitive data is not logged

## Performance
- Identify N+1 query patterns or missing database indexes
- Flag unnecessary blocking calls on the main/UI thread
- Highlight unbounded loops, large in-memory collections, or memory leaks
- Look for missing caching opportunities on expensive operations

## Error Handling
- Ensure exceptions are caught at the right level and not silently swallowed
- Check that error messages are informative without leaking sensitive details
- Verify resources (streams, connections, files) are properly closed

## Code Quality
- Flag duplicated logic that should be extracted into a shared function
- Identify overly complex methods that should be decomposed (> 30 lines)
- Check that all public methods/functions have clear documentation
- Ensure meaningful variable and function names are used

## Testing
- Note when new functionality lacks corresponding unit or integration tests
- Identify edge cases that are not covered by existing tests
- Check that test assertions are meaningful and not trivially true

## Null Safety & Type Safety
- Flag unchecked nullable dereferences
- Identify unsafe type casts that could throw at runtime
- Check for missing input validation on public API boundaries

