package kz.juzym.user.security

interface PasswordHasher {
    fun hash(raw: String): String
    fun verify(raw: String, hashed: String): Boolean
}
