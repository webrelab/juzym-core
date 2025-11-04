package kz.juzym.registration

data class RegistrationConfig(
    val emailTokenTtlMinutes: Long = 60,
    val resendCooldownSeconds: Long = 300,
    val maxResendsPerDay: Int = 5,
    val maxAttemptsPerIpPerHour: Int = 20,
    val passwordPolicy: PasswordPolicyResponse = PasswordPolicyResponse(
        minLength = 10,
        requireUpper = false,
        requireLower = true,
        requireDigit = true,
        requireSymbol = false,
        forbidBreachedTopN = true
    ),
    val exposeDebugTokens: Boolean = false
)

class RegistrationConflictException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationValidationException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationRateLimitException(val cooldownSeconds: Long) : RuntimeException()
class RegistrationNotFoundException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationInvalidTokenException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationUnauthorizedException(message: String) : RuntimeException(message)
