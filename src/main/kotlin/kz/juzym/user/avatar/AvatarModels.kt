package kz.juzym.user.avatar

import java.time.Instant
import java.util.UUID

data class Avatar(
    val id: UUID,
    val userId: UUID,
    val displayName: String,
    val about: String?,
    val photoUrl: String?,
    val level: Int,
    val xp: Int,
    val reputation: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AvatarSkill(
    val id: UUID,
    val avatarId: UUID,
    val code: String,
    val name: String,
    val level: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AvatarAchievement(
    val id: UUID,
    val avatarId: UUID,
    val code: String,
    val title: String,
    val description: String?,
    val receivedAt: Instant,
)

data class AvatarStats(
    val avatarId: UUID,
    val tasksCompleted: Int,
    val votesParticipated: Int,
    val level: Int,
    val xp: Int,
    val reputation: Int,
    val lastActivityAt: Instant?,
)

data class AvatarProfile(
    val avatar: Avatar,
    val skills: List<AvatarSkill>,
    val achievements: List<AvatarAchievement>,
    val stats: AvatarStats,
)

data class AvatarProgress(
    val avatarId: UUID,
    val level: Int,
    val xp: Int,
    val reputation: Int,
)

fun interface AvatarLevelStrategy {
    fun determineLevel(currentLevel: Int, newXp: Int): Int

    companion object {
        val Noop: AvatarLevelStrategy = AvatarLevelStrategy { currentLevel, _ -> currentLevel }
    }
}
