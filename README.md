# Juzym Core

This service uses [Koin](https://insert-koin.io/) for dependency injection. All service implementations **must** be registered in the Koin graph through the `auditProxy` helper so that audit events are recorded automatically.

## Audit policy for services

* Every service must expose an interface.
* All functions that should be tracked must be annotated with `@AuditAction(action = "...")` on the interface declaration.
* Service implementations are wrapped with `auditProxy` before being registered in Koin. The proxy captures annotation metadata and writes audit events using the configured `AuditEventStore` implementation (PostgreSQL or stdout).

Refer to `GraphService` for an example of the expected pattern.
