package kz.juzym.user

import kz.juzym.audit.AuditAction
import java.util.UUID

interface UserService {

    @AuditAction("user.registerOrAuthenticate")
    fun registerOrAuthenticate(iin: String, email: String, password: String): RegistrationResult

    @AuditAction("user.resendActivationEmail")
    fun resendActivationEmail(iin: String): ResendActivationResult

    @AuditAction("user.activateAccount")
    fun activateAccount(token: String): ActivationResult

    @AuditAction("user.blockUser")
    fun blockUser(userId: UUID)

    @AuditAction("user.requestPasswordReset")
    fun requestPasswordReset(iin: String): PasswordResetRequestResult

    @AuditAction("user.resetPassword")
    fun resetPassword(token: String, newPassword: String): PasswordResetResult

    @AuditAction("user.requestDeletion")
    fun requestDeletion(iin: String): DeletionRequestResult

    @AuditAction("user.confirmDeletion")
    fun confirmDeletion(token: String): DeletionConfirmationResult

    @AuditAction("user.requestEmailChange")
    fun requestEmailChange(userId: UUID, newEmail: String): EmailChangeRequestResult

    @AuditAction("user.confirmEmailChange")
    fun confirmEmailChange(token: String): EmailChangeConfirmationResult
}

data class UserServiceConfig(
    val activationLinkBuilder: (String) -> String,
    val passwordResetLinkBuilder: (String) -> String,
    val deletionLinkBuilder: (String) -> String,
    val emailChangeLinkBuilder: (String) -> String
)

sealed interface RegistrationResult {
    data class Pending(val user: User, val activationLink: String) : RegistrationResult
    data class PendingExisting(val user: User) : RegistrationResult
    data class Authenticated(val user: User, val jwt: String) : RegistrationResult
    data class InvalidCredentials(val user: User) : RegistrationResult
    data class Blocked(val user: User) : RegistrationResult
}

sealed interface ResendActivationResult {
    data class Sent(val activationLink: String) : ResendActivationResult
    data class InvalidStatus(val status: UserStatus) : ResendActivationResult
    data object NotFound : ResendActivationResult
}

sealed interface ActivationResult {
    data class Activated(val user: User, val greeting: String) : ActivationResult
    data object InvalidLink : ActivationResult
}

sealed interface PasswordResetRequestResult {
    data class Sent(val link: String) : PasswordResetRequestResult
    data class InvalidStatus(val status: UserStatus) : PasswordResetRequestResult
    data object NotFound : PasswordResetRequestResult
}

sealed interface PasswordResetResult {
    data class Success(val jwt: String) : PasswordResetResult
    data object InvalidToken : PasswordResetResult
}

sealed interface DeletionRequestResult {
    data class Sent(val link: String) : DeletionRequestResult
    data class InvalidStatus(val status: UserStatus) : DeletionRequestResult
    data object NotFound : DeletionRequestResult
}

sealed interface DeletionConfirmationResult {
    data object Deleted : DeletionConfirmationResult
    data object InvalidToken : DeletionConfirmationResult
}

sealed interface EmailChangeRequestResult {
    data class Sent(val link: String) : EmailChangeRequestResult
    data object NotFound : EmailChangeRequestResult
}

sealed interface EmailChangeConfirmationResult {
    data object Updated : EmailChangeConfirmationResult
    data object InvalidToken : EmailChangeConfirmationResult
}
