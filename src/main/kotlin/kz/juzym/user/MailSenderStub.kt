package kz.juzym.user

interface MailSenderStub {
    fun sendActivationEmail(to: String, activationLink: String)
    fun sendPasswordResetEmail(to: String, resetLink: String)
    fun sendDeletionConfirmationEmail(to: String, confirmationLink: String)
    fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String)
}
