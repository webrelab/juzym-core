package kz.juzym.user

class ConsoleMailSenderStub : MailSenderStub {
    override fun sendActivationEmail(to: String, activationLink: String) {
        println("[MAIL] Activation email to $to: $activationLink")
    }

    override fun sendPasswordResetEmail(to: String, resetLink: String) {
        println("[MAIL] Password reset email to $to: $resetLink")
    }

    override fun sendDeletionConfirmationEmail(to: String, confirmationLink: String) {
        println("[MAIL] Deletion confirmation email to $to: $confirmationLink")
    }

    override fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String) {
        println("[MAIL] Email change confirmation to $to (new email: $newEmail): $confirmationLink")
    }
}
