# Copilot Instructions

## Scala Principles

- Prefer idiomatic Scala over Java-style implementations.
- Prefer immutable data structures.
- Avoid mutable state and `var` where possible.
- Use `Option` instead of `null`.
- Prefer expression-oriented code.
- Prefer composition over inheritance.

## Code Style

- Explicitly declare types for all public API members, including method return types.
- Keep methods small and focused.
- Use descriptive names.
- Follow existing patterns in the surrounding code.
- Do not introduce new libraries without clear justification.

## Testing

- Generate unit tests for new business logic.
- Follow existing test patterns.
- Test behaviour rather than implementation details.

## Architecture

- Keep business logic separate from controllers and endpoints.
- Reuse existing services and utilities before creating new abstractions.

## Security

- Never log PII, secrets, or credentials.