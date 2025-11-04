package kz.juzym.dev

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class CapturedMailType {
    ACTIVATION,
    PASSWORD_RESET,
    DELETION,
    EMAIL_CHANGE
}

data class CapturedMail(
    val id: UUID = UUID.randomUUID(),
    val type: CapturedMailType,
    val to: String,
    val link: String,
    val createdAt: Instant = Instant.now(),
    val metadata: Map<String, String?> = emptyMap()
)

interface DebugMailStore {
    fun record(mail: CapturedMail)
    fun list(): List<CapturedMail>
    fun clear()
    fun latest(type: CapturedMailType? = null, to: String? = null): CapturedMail?
}

class InMemoryDebugMailStore : DebugMailStore {
    private val mails = CopyOnWriteArrayList<CapturedMail>()

    override fun record(mail: CapturedMail) {
        mails += mail
    }

    override fun list(): List<CapturedMail> = mails.sortedBy { it.createdAt }

    override fun clear() {
        mails.clear()
    }

    override fun latest(type: CapturedMailType?, to: String?): CapturedMail? {
        return mails
            .asSequence()
            .filter { mail -> type == null || mail.type == type }
            .filter { mail -> to == null || mail.to.equals(to, ignoreCase = true) }
            .maxByOrNull { it.createdAt }
    }
}

class NoopDebugMailStore : DebugMailStore {
    override fun record(mail: CapturedMail) = Unit
    override fun list(): List<CapturedMail> = emptyList()
    override fun clear() = Unit
    override fun latest(type: CapturedMailType?, to: String?): CapturedMail? = null
}
