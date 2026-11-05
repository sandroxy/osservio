# Repository conventions

Read [the engineering standards](doc/engineering-standards.md) before changing
this repository. Use [the architecture](doc/architecture.md) for runtime design
and [the development guide](DEVELOPMENT.md) for repository ownership and checks.

- Preserve host application stability, behavior, privacy, and performance ahead
  of debugger convenience. Development use and production diagnosis are both
  first-class use cases.
- Implement and refine native Android and iOS first, including their inspection
  UI and interaction details. Defer framework adapters and adapter prototypes
  until the native workflow is ready for that expansion.
- Keep tracked files suitable for a public SDK repository. Investigation, plans,
  progress, private references, and temporary verification evidence belong in
  the ignored `doc/dev/` directory. Never force-add it or link public documents
  to it.
- Keep the root README and Uni user guides in Simplified Chinese and English.
  Maintain the Uni changelog in Chinese. Other documents may be English only.
  Review English actively and update both translations together when required;
  preserve meaning and technical facts rather than translating word for word.
- Name files, modules, and APIs by responsibility. Do not encode roadmap order,
  development stages, priorities, or replacement history in stable names.
  Product versions and protocol compatibility revisions are separate concepts.
- Decompose by responsibility, dependency, lifecycle, and reason to change.
  Avoid mechanical line limits, forwarding-only layers, and speculative
  abstractions.
- Keep Protocol below platform modules. Core declares storage and query ports;
  concrete implementations are wired in a composition root. Native cores do
  not depend on React Native, Uni, Flutter, or application business logic.
- Declare platform capability differences. Invalid configuration, unsupported
  behavior, and missing evidence must never look like successful capture.
- Bound capture work and buffering before parsing or transferring observations.
  Redact before persistence or transport. Stores, bridges, and exporters accept
  sanitized events only; payload blobs remain separate from searchable metadata.
- Use reviewed platform crash mechanisms. Never run normal application logic
  inside a fatal crash handler.
- Prefer suitable maintained implementations over rebuilding mature components.
  Review behavior, dependencies, licensing, and host impact before adopting code;
  preserve required attribution and notices.
- Keep SDK implementation and packaging here. Consumer hosts and acceptance
  orchestration belong to the separate integration repository. Do not copy SDK
  implementation into a host or treat generated projects as source.
- Update schemas and positive, negative, lifecycle, and compatibility fixtures
  when shared semantics change. Run the documented checks for affected modules
  and report compilation, automated tests, and device behavior separately.
  Never fabricate test results or treat a build as proof of runtime correctness.
- Keep credentials, production signing, machine paths, downloaded SDKs, build
  outputs, and private diagnostic data out of Git. Publishing is separate from
  local implementation and verification.
- Make local commits at independently verified development milestones. Use a
  concise Chinese summary followed by 3–5 focused bullet points. Pushes and
  publication require separate explicit authorization.
