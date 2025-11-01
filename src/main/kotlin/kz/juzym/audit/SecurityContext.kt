package kz.juzym.audit

import java.util.UUID

object SecurityContext {
    private val currentUser = ThreadLocal<UUID?>()

    fun setCurrentUserId(id: UUID?) {
        currentUser.set(id)
    }

    fun currentUserId(): UUID? = currentUser.get()

    fun clear() {
        currentUser.remove()
    }
}
