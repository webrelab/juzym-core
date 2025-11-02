package kz.juzym.audit

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

inline fun <reified T : Any> auditProxy(target: T, auditEventStore: AuditEventStore): T {
    val clazz = T::class.java
    require(clazz.isInterface) { "Audit proxy can only be created for interfaces. Provided: ${clazz.name}" }

    return Proxy.newProxyInstance(
        clazz.classLoader,
        arrayOf(clazz)
    ) { _, method, args ->
        val annotation = method.getAnnotation(AuditAction::class.java)
        val startedAt = if (annotation != null) System.nanoTime() else 0L

        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        } finally {
            if (annotation != null) {
                val userId = SecurityContext.currentUserId()
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                val event = AuditEvent(
                    userId = userId,
                    action = annotation.action,
                    method = method.name,
                    arguments = args?.map { it?.toString() ?: "null" }.orEmpty(),
                    executionTimeMs = durationMs
                )
                auditEventStore.record(event)
            }
        }
    } as T
}
