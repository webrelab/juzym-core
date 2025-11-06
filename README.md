# Juzym Core

This service uses [Koin](https://insert-koin.io/) for dependency injection. All service implementations **must** be registered in the Koin graph through the `auditProxy` helper so that audit events are recorded automatically.

## Audit policy for services

* Every service must expose an interface.
* All functions that should be tracked must be annotated with `@AuditAction(action = "...")` on the interface declaration.
* Service implementations are wrapped with `auditProxy` before being registered in Koin. The proxy captures annotation metadata and writes audit events using the configured `AuditEventStore` implementation (PostgreSQL or stdout).

Refer to `GraphService` for an example of the expected pattern.


## Role-based API authorization
The platform defines two roles, `Role.USER` and `Role.ADMIN`. Users automatically receive the `USER` role once they confirm their email address, and additional roles can be attached to expand access.

Secure protected routes with the `authorize` helper by passing the shared `JwtService` and the roles that are allowed to access the nested handlers. For example, to restrict an admin dashboard to administrators only:

```kotlin
route("/admin") {
    authorize(jwtService, Role.ADMIN) {
        get("/dashboard") {
            call.respondText("Admin panel")
        }
    }
}
```

The helper verifies the caller's JWT, populates the request's `JwtPrincipal`, and rejects callers missing the required roles before your handler executes.
