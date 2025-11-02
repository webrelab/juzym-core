package kz.juzym.user.avatar

import kz.juzym.audit.AuditAction
import kz.juzym.core.FieldUpdate
import java.util.UUID

interface AvatarService {

    @AuditAction("avatar.createAvatar")
    fun createAvatar(userId: UUID, displayName: String, about: String? = null, photoUrl: String? = null): UUID

    fun getAvatarByUserId(userId: UUID): AvatarProfile?

    @AuditAction("avatar.updateProfile")
    fun updateAvatarProfile(
        avatarId: UUID,
        displayName: FieldUpdate<String> = FieldUpdate.Keep,
        about: FieldUpdate<String?> = FieldUpdate.Keep,
        photoUrl: FieldUpdate<String?> = FieldUpdate.Keep,
    ): Avatar

    @AuditAction("avatar.addOrUpdateSkill")
    fun addAvatarSkill(
        avatarId: UUID,
        code: String,
        name: String? = null,
        level: Int? = null,
    ): AvatarSkill

    @AuditAction("avatar.updateSkillLevel")
    fun updateAvatarSkillLevel(avatarId: UUID, code: String, delta: Int): AvatarSkill

    @AuditAction("avatar.addAchievement")
    fun addAvatarAchievement(
        avatarId: UUID,
        code: String,
        title: String,
        description: String? = null,
    ): AvatarAchievement

    @AuditAction("avatar.updateProgress")
    fun updateAvatarProgress(
        avatarId: UUID,
        xpDelta: Int? = null,
        reputationDelta: Int? = null,
    ): AvatarProgress

    fun getAvatarStats(avatarId: UUID): AvatarStats?
}
