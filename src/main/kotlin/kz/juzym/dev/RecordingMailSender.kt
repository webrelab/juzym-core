package kz.juzym.dev

import kz.juzym.user.MailSenderStub

class RecordingMailSender(
    private val store: DebugMailStore,
    private val delegate: MailSenderStub? = null
) : MailSenderStub {

    override fun sendActivationEmail(to: String, activationLink: String) {
        store.record(
            CapturedMail(
                type = CapturedMailType.ACTIVATION,
                to = to,
                link = activationLink
            )
        )
        delegate?.sendActivationEmail(to, activationLink)
    }

    override fun sendPasswordResetEmail(to: String, resetLink: String) {
        store.record(
            CapturedMail(
                type = CapturedMailType.PASSWORD_RESET,
                to = to,
                link = resetLink
            )
        )
        delegate?.sendPasswordResetEmail(to, resetLink)
    }

    override fun sendDeletionConfirmationEmail(to: String, confirmationLink: String) {
        store.record(
            CapturedMail(
                type = CapturedMailType.DELETION,
                to = to,
                link = confirmationLink
            )
        )
        delegate?.sendDeletionConfirmationEmail(to, confirmationLink)
    }

    override fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String) {
        store.record(
            CapturedMail(
                type = CapturedMailType.EMAIL_CHANGE,
                to = to,
                link = confirmationLink,
                metadata = mapOf("newEmail" to newEmail)
            )
        )
        delegate?.sendEmailChangeConfirmationEmail(to, newEmail, confirmationLink)
    }
}
