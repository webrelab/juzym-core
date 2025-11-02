package kz.juzym.user

import kz.juzym.user.security.PasswordHasher
import kz.juzym.user.security.jwt.JwtService
import java.time.Duration
import java.util.UUID

class UserServiceImpl(
    private val userRepository: UserRepository,
    private val tokenRepository: UserTokenRepository,
    private val mailSender: MailSenderStub,
    private val passwordHasher: PasswordHasher,
    private val jwtService: JwtService,
    private val config: UserServiceConfig
) : UserService {

    override fun registerOrAuthenticate(iin: String, email: String, password: String): RegistrationResult {
        val normalizedIin = iin.trim()
        val normalizedEmail = email.trim()
        val existing = userRepository.findByIin(normalizedIin)
        if (existing == null) {
            val user = User(
                iin = normalizedIin,
                email = normalizedEmail,
                passwordHash = passwordHasher.hash(password)
            )
            userRepository.create(user)
            val activationToken = tokenRepository.createToken(
                userId = user.id,
                type = UserTokenType.ACTIVATION,
                validity = Duration.ofHours(3)
            )
            val activationLink = config.activationLinkBuilder(activationToken.token)
            mailSender.sendActivationEmail(user.email, activationLink)
            return RegistrationResult.Pending(user, activationLink)
        }

        return when (existing.status) {
            UserStatus.PENDING -> RegistrationResult.PendingExisting(existing)
            UserStatus.BLOCKED -> RegistrationResult.Blocked(existing)
            UserStatus.ACTIVE -> {
                val credentialsValid = userRepository.verifyCredentials(existing.iin, password)
                if (credentialsValid) {
                    val token = jwtService.generate(existing.id, existing.iin)
                    RegistrationResult.Authenticated(existing, token)
                } else {
                    RegistrationResult.InvalidCredentials(existing)
                }
            }
        }
    }

    override fun resendActivationEmail(iin: String): ResendActivationResult {
        val user = userRepository.findByIin(iin) ?: return ResendActivationResult.NotFound
        if (user.status != UserStatus.PENDING) {
            return ResendActivationResult.InvalidStatus(user.status)
        }
        val activation = tokenRepository.createToken(
            userId = user.id,
            type = UserTokenType.ACTIVATION,
            validity = Duration.ofHours(3)
        )
        val activationLink = config.activationLinkBuilder(activation.token)
        mailSender.sendActivationEmail(user.email, activationLink)
        return ResendActivationResult.Sent(activationLink)
    }

    override fun activateAccount(token: String): ActivationResult {
        val activation = tokenRepository.findValidToken(token, UserTokenType.ACTIVATION)
            ?: return ActivationResult.InvalidLink
        userRepository.updateStatus(activation.userId, UserStatus.ACTIVE)
        tokenRepository.markConsumed(activation.id)
        tokenRepository.deleteTokens(activation.userId, UserTokenType.PASSWORD_RESET)
        val user = userRepository.findById(activation.userId) ?: return ActivationResult.InvalidLink
        val message = "Добро пожаловать, пользователь с ИИН ${user.iin}!"
        return ActivationResult.Activated(user, message)
    }

    override fun blockUser(userId: UUID) {
        userRepository.updateStatus(userId, UserStatus.BLOCKED)
        tokenRepository.deleteByUser(userId)
    }

    override fun requestPasswordReset(iin: String): PasswordResetRequestResult {
        val user = userRepository.findByIin(iin) ?: return PasswordResetRequestResult.NotFound
        if (user.status != UserStatus.ACTIVE) {
            return PasswordResetRequestResult.InvalidStatus(user.status)
        }
        val token = tokenRepository.createToken(
            userId = user.id,
            type = UserTokenType.PASSWORD_RESET,
            validity = Duration.ofMinutes(20)
        )
        val link = config.passwordResetLinkBuilder(token.token)
        mailSender.sendPasswordResetEmail(user.email, link)
        return PasswordResetRequestResult.Sent(link)
    }

    override fun resetPassword(token: String, newPassword: String): PasswordResetResult {
        val resetToken = tokenRepository.findValidToken(token, UserTokenType.PASSWORD_RESET)
            ?: return PasswordResetResult.InvalidToken
        val hashed = passwordHasher.hash(newPassword)
        userRepository.updatePassword(resetToken.userId, hashed)
        tokenRepository.markConsumed(resetToken.id)
        val user = userRepository.findById(resetToken.userId) ?: return PasswordResetResult.InvalidToken
        val jwt = jwtService.generate(user.id, user.iin)
        return PasswordResetResult.Success(jwt)
    }

    override fun requestDeletion(iin: String): DeletionRequestResult {
        val user = userRepository.findByIin(iin) ?: return DeletionRequestResult.NotFound
        if (user.status == UserStatus.BLOCKED) {
            return DeletionRequestResult.InvalidStatus(user.status)
        }
        val token = tokenRepository.createToken(
            userId = user.id,
            type = UserTokenType.DELETION,
            validity = Duration.ofMinutes(20)
        )
        val link = config.deletionLinkBuilder(token.token)
        mailSender.sendDeletionConfirmationEmail(user.email, link)
        return DeletionRequestResult.Sent(link)
    }

    override fun confirmDeletion(token: String): DeletionConfirmationResult {
        val deletionToken = tokenRepository.findValidToken(token, UserTokenType.DELETION)
            ?: return DeletionConfirmationResult.InvalidToken
        val user = userRepository.findById(deletionToken.userId) ?: return DeletionConfirmationResult.InvalidToken
        userRepository.delete(user.id)
        tokenRepository.deleteByUser(user.id)
        return DeletionConfirmationResult.Deleted
    }

    override fun requestEmailChange(userId: UUID, newEmail: String): EmailChangeRequestResult {
        val user = userRepository.findById(userId) ?: return EmailChangeRequestResult.NotFound
        val token = tokenRepository.createToken(
            userId = user.id,
            type = UserTokenType.EMAIL_CHANGE,
            validity = Duration.ofMinutes(20),
            payload = newEmail
        )
        val link = config.emailChangeLinkBuilder(token.token)
        mailSender.sendEmailChangeConfirmationEmail(newEmail, newEmail, link)
        return EmailChangeRequestResult.Sent(link)
    }

    override fun confirmEmailChange(token: String): EmailChangeConfirmationResult {
        val changeToken = tokenRepository.findValidToken(token, UserTokenType.EMAIL_CHANGE)
            ?: return EmailChangeConfirmationResult.InvalidToken
        val newEmail = changeToken.payload ?: return EmailChangeConfirmationResult.InvalidToken
        userRepository.updateEmail(changeToken.userId, newEmail)
        tokenRepository.markConsumed(changeToken.id)
        return EmailChangeConfirmationResult.Updated
    }
}
