package kz.juzym.audit

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object AuditEventsTable : UUIDTable("audit_events") {
    val userId = uuid("user_id").nullable()
    val action = varchar("action", length = 255)
    val method = varchar("method", length = 255)
    val arguments = text("arguments")
    val durationMs = long("duration_ms")
    val createdAt = timestamp("created_at")
}
