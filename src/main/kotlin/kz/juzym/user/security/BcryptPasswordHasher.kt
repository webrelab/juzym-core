package kz.juzym.user.security

import org.mindrot.jbcrypt.BCrypt

class BcryptPasswordHasher(private val logRounds: Int = 12) : PasswordHasher {
    override fun hash(raw: String): String = BCrypt.hashpw(raw, BCrypt.gensalt(logRounds))

    override fun verify(raw: String, hashed: String): Boolean =
        try {
            BCrypt.checkpw(raw, hashed)
        } catch (ex: IllegalArgumentException) {
            false
        }
}
