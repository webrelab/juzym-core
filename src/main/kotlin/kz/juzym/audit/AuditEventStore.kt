package kz.juzym.audit

import java.time.Instant
import java.util.UUID

data class AuditEvent(
    val userId: UUID?,
    val action: String,
    val method: String,
    val arguments: List<String>,
    val executionTimeMs: Long,
    val createdAt: Instant = Instant.now()
)

interface AuditEventStore {
    fun record(event: AuditEvent)
}
