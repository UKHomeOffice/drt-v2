---
applyTo: "client/**/*.scala"
---

# Scala.js Client Instructions

## Scala.js

- Generate code compatible with Scala.js.
- Do not use JVM-only APIs or libraries.
- Follow existing Scala.js patterns in the project.

## scalajs-react

- Follow existing scalajs-react component patterns.
- Prefer existing components before creating new ones.
- Keep components small and focused.
- Keep business logic separate from presentation logic where practical.
- Follow existing patterns for component state, props, and callbacks.

## UI

- Reuse existing UI components and styles.
- Maintain existing accessibility behaviour.
- Use semantic HTML where appropriate.

## Shared UI Components

- `drt-react` is the shared React component library used across DRT applications.
- Prefer existing components from `drt-react` rather than implementing equivalent components locally.
- Before creating a new UI component, check whether an appropriate component already exists in `drt-react`.
- Follow existing project patterns for integrating `drt-react` components with Scala.js and scalajs-react.
