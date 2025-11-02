package kz.juzym.audit

class StdoutAuditEventStore : AuditEventStore {
    override fun record(event: AuditEvent) {
        val args = if (event.arguments.isEmpty()) "" else event.arguments.joinToString(prefix = "[", postfix = "]")
        println(
            "[AUDIT] userId=${event.userId} action=${event.action} method=${event.method} args=$args durationMs=${event.executionTimeMs} at=${event.createdAt}"
        )
    }
}
