package kz.juzym.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class PostgresAuditEventStore(
    private val database: Database
) : AuditEventStore {

    private val mapper = jacksonObjectMapper()

    override fun record(event: AuditEvent) {
        transaction(database) {
            AuditEventsTable.insert { statement ->
                statement[AuditEventsTable.id] = eventId()
                statement[AuditEventsTable.userId] = event.userId
                statement[AuditEventsTable.action] = event.action
                statement[AuditEventsTable.method] = event.method
                statement[AuditEventsTable.arguments] = mapper.writeValueAsString(event.arguments)
                statement[AuditEventsTable.durationMs] = event.executionTimeMs
                statement[AuditEventsTable.createdAt] = event.createdAt
            }
        }
    }

    private fun eventId() = java.util.UUID.randomUUID()
}
