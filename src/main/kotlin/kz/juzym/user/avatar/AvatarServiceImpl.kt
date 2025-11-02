package kz.juzym.user.avatar

import kz.juzym.core.FieldUpdate
import kz.juzym.user.UsersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AvatarServiceImpl(
    private val database: org.jetbrains.exposed.sql.Database,
    private val levelStrategy: AvatarLevelStrategy = AvatarLevelStrategy.Noop,
) : AvatarService {

    override fun createAvatar(
        userId: UUID,
        displayName: String,
        about: String?,
        photoUrl: String?,
    ): UUID = transaction(database) {
        require(displayName.isNotBlank()) { "Display name is required" }
        requireNotNull(findUser(userId)) { "User $userId not found" }

        val existing = AvatarsTable.select { AvatarsTable.userId eq userId }.count()
        require(existing == 0L) { "Avatar already exists for user $userId" }

        val now = currentTime()
        AvatarsTable.insert { statement ->
            statement[id] = UUID.randomUUID()
            statement[AvatarsTable.userId] = userId
            statement[AvatarsTable.displayName] = displayName
            statement[AvatarsTable.about] = about
            statement[AvatarsTable.photoUrl] = photoUrl
            statement[AvatarsTable.level] = 1
            statement[AvatarsTable.xp] = 0
            statement[AvatarsTable.reputation] = 0
            statement[AvatarsTable.createdAt] = now
            statement[AvatarsTable.updatedAt] = now
        } get AvatarsTable.id
    }.value

    override fun getAvatarByUserId(userId: UUID): AvatarProfile? = transaction(database) {
        val avatarRow = AvatarsTable.select { AvatarsTable.userId eq userId }.singleOrNull() ?: return@transaction null
        val avatar = avatarRow.toAvatar()
        val skills = AvatarSkillsTable.select { AvatarSkillsTable.avatarId eq avatar.id }
            .map { it.toAvatarSkill() }
            .sortedBy { it.code }
        val achievements = AvatarAchievementsTable.select { AvatarAchievementsTable.avatarId eq avatar.id }
            .map { it.toAvatarAchievement() }
            .sortedBy { it.code }
        val stats = resolveStats(avatarRow, avatar.id)
        AvatarProfile(avatar, skills, achievements, stats)
    }

    override fun updateAvatarProfile(
        avatarId: UUID,
        displayName: FieldUpdate<String>,
        about: FieldUpdate<String?>,
        photoUrl: FieldUpdate<String?>,
    ): Avatar = transaction(database) {
        val avatarExists = AvatarsTable.select { AvatarsTable.id eq avatarId }.singleOrNull()
            ?: error("Avatar $avatarId not found")

        val updateDisplayName = displayName is FieldUpdate.Value
        val updateAbout = about is FieldUpdate.Value
        val updatePhoto = photoUrl is FieldUpdate.Value

        if (!updateDisplayName && !updateAbout && !updatePhoto) {
            return@transaction avatarExists.toAvatar()
        }

        val now = currentTime()

        AvatarsTable.update({ AvatarsTable.id eq avatarId }) { statement ->
            if (updateDisplayName) {
                val value = (displayName as FieldUpdate.Value).value
                require(value.isNotBlank()) { "Display name cannot be blank" }
                statement[AvatarsTable.displayName] = value
            }
            if (updateAbout) {
                statement[AvatarsTable.about] = (about as FieldUpdate.Value).value
            }
            if (updatePhoto) {
                statement[AvatarsTable.photoUrl] = (photoUrl as FieldUpdate.Value).value
            }
            statement[AvatarsTable.updatedAt] = now
        }

        AvatarsTable.select { AvatarsTable.id eq avatarId }.single().toAvatar()
    }

    override fun addAvatarSkill(
        avatarId: UUID,
        code: String,
        name: String?,
        level: Int?,
    ): AvatarSkill = transaction(database) {
        val avatarExists = AvatarsTable.select { AvatarsTable.id eq avatarId }.count() > 0
        require(avatarExists) { "Avatar $avatarId not found" }

        val now = currentTime()
        val existing = AvatarSkillsTable.select {
            (AvatarSkillsTable.avatarId eq avatarId) and (AvatarSkillsTable.code eq code)
        }.singleOrNull()

        if (existing == null) {
            val skillLevel = level ?: 1
            require(skillLevel >= 0) { "Skill level cannot be negative" }
            AvatarSkillsTable.insert { statement ->
                statement[id] = UUID.randomUUID()
                statement[AvatarSkillsTable.avatarId] = avatarId
                statement[AvatarSkillsTable.code] = code
                statement[AvatarSkillsTable.name] = name ?: code
                statement[AvatarSkillsTable.level] = skillLevel
                statement[AvatarSkillsTable.createdAt] = now
                statement[AvatarSkillsTable.updatedAt] = now
            }
        } else {
            val newLevel = level ?: existing[AvatarSkillsTable.level]
            require(newLevel >= 0) { "Skill level cannot be negative" }
            AvatarSkillsTable.update({ AvatarSkillsTable.id eq existing[AvatarSkillsTable.id].value }) { statement ->
                statement[AvatarSkillsTable.level] = newLevel
                statement[AvatarSkillsTable.name] = name ?: existing[AvatarSkillsTable.name]
                statement[AvatarSkillsTable.updatedAt] = now
            }
        }

        AvatarSkillsTable.select {
            (AvatarSkillsTable.avatarId eq avatarId) and (AvatarSkillsTable.code eq code)
        }.single().toAvatarSkill()
    }

    override fun updateAvatarSkillLevel(avatarId: UUID, code: String, delta: Int): AvatarSkill = transaction(database) {
        val row = AvatarSkillsTable.select {
            (AvatarSkillsTable.avatarId eq avatarId) and (AvatarSkillsTable.code eq code)
        }.singleOrNull() ?: error("Skill $code not found for avatar $avatarId")

        val newLevel = row[AvatarSkillsTable.level] + delta
        require(newLevel >= 0) { "Skill level cannot be negative" }
        AvatarSkillsTable.update({ AvatarSkillsTable.id eq row[AvatarSkillsTable.id].value }) { statement ->
            statement[AvatarSkillsTable.level] = newLevel
            statement[AvatarSkillsTable.updatedAt] = currentTime()
        }

        AvatarSkillsTable.select { AvatarSkillsTable.id eq row[AvatarSkillsTable.id].value }
            .single()
            .toAvatarSkill()
    }

    override fun addAvatarAchievement(
        avatarId: UUID,
        code: String,
        title: String,
        description: String?,
    ): AvatarAchievement = transaction(database) {
        val avatarExists = AvatarsTable.select { AvatarsTable.id eq avatarId }.count() > 0
        require(avatarExists) { "Avatar $avatarId not found" }

        val existing = AvatarAchievementsTable.select {
            (AvatarAchievementsTable.avatarId eq avatarId) and (AvatarAchievementsTable.code eq code)
        }.singleOrNull()

        if (existing != null) {
            return@transaction existing.toAvatarAchievement()
        }

        val now = currentTime()
        AvatarAchievementsTable.insert { statement ->
            statement[id] = UUID.randomUUID()
            statement[AvatarAchievementsTable.avatarId] = avatarId
            statement[AvatarAchievementsTable.code] = code
            statement[AvatarAchievementsTable.title] = title
            statement[AvatarAchievementsTable.description] = description
            statement[AvatarAchievementsTable.receivedAt] = now
        }

        AvatarAchievementsTable.select {
            (AvatarAchievementsTable.avatarId eq avatarId) and (AvatarAchievementsTable.code eq code)
        }.single().toAvatarAchievement()
    }

    override fun updateAvatarProgress(
        avatarId: UUID,
        xpDelta: Int?,
        reputationDelta: Int?,
    ): AvatarProgress = transaction(database) {
        require(xpDelta != null || reputationDelta != null) { "At least one delta must be provided" }

        val row = AvatarsTable.select { AvatarsTable.id eq avatarId }.singleOrNull()
            ?: error("Avatar $avatarId not found")

        val currentXp = row[AvatarsTable.xp]
        val currentLevel = row[AvatarsTable.level]
        val currentReputation = row[AvatarsTable.reputation]

        val newXp = currentXp + (xpDelta ?: 0)
        require(newXp >= 0) { "XP cannot be negative" }
        val newReputation = currentReputation + (reputationDelta ?: 0)
        require(newReputation >= 0) { "Reputation cannot be negative" }
        val newLevel = levelStrategy.determineLevel(currentLevel, newXp).coerceAtLeast(1)

        val now = currentTime()
        AvatarsTable.update({ AvatarsTable.id eq avatarId }) { statement ->
            statement[AvatarsTable.xp] = newXp
            statement[AvatarsTable.reputation] = newReputation
            statement[AvatarsTable.level] = newLevel
            statement[AvatarsTable.updatedAt] = now
        }

        AvatarProgress(avatarId, newLevel, newXp, newReputation)
    }

    override fun getAvatarStats(avatarId: UUID): AvatarStats? = transaction(database) {
        val avatarRow = AvatarsTable.select { AvatarsTable.id eq avatarId }.singleOrNull() ?: return@transaction null
        resolveStats(avatarRow, avatarId)
    }

    private fun resolveStats(avatarRow: ResultRow, avatarId: UUID): AvatarStats {
        val cacheRow = AvatarStatsCacheTable.select { AvatarStatsCacheTable.avatarId eq avatarId }.singleOrNull()
        val tasks = cacheRow?.get(AvatarStatsCacheTable.tasksCompleted) ?: 0
        val votes = cacheRow?.get(AvatarStatsCacheTable.votesParticipated) ?: 0
        val lastActivity = cacheRow?.get(AvatarStatsCacheTable.lastActivityAt)?.toInstant()
            ?: avatarRow[AvatarsTable.updatedAt].toInstant()

        return AvatarStats(
            avatarId = avatarId,
            tasksCompleted = tasks,
            votesParticipated = votes,
            level = avatarRow[AvatarsTable.level],
            xp = avatarRow[AvatarsTable.xp],
            reputation = avatarRow[AvatarsTable.reputation],
            lastActivityAt = lastActivity,
        )
    }

    private fun ResultRow.toAvatar(): Avatar = Avatar(
        id = this[AvatarsTable.id].value,
        userId = this[AvatarsTable.userId].value,
        displayName = this[AvatarsTable.displayName],
        about = this[AvatarsTable.about],
        photoUrl = this[AvatarsTable.photoUrl],
        level = this[AvatarsTable.level],
        xp = this[AvatarsTable.xp],
        reputation = this[AvatarsTable.reputation],
        createdAt = this[AvatarsTable.createdAt].toInstant(),
        updatedAt = this[AvatarsTable.updatedAt].toInstant(),
    )

    private fun ResultRow.toAvatarSkill(): AvatarSkill = AvatarSkill(
        id = this[AvatarSkillsTable.id].value,
        avatarId = this[AvatarSkillsTable.avatarId].value,
        code = this[AvatarSkillsTable.code],
        name = this[AvatarSkillsTable.name],
        level = this[AvatarSkillsTable.level],
        createdAt = this[AvatarSkillsTable.createdAt].toInstant(),
        updatedAt = this[AvatarSkillsTable.updatedAt].toInstant(),
    )

    private fun ResultRow.toAvatarAchievement(): AvatarAchievement = AvatarAchievement(
        id = this[AvatarAchievementsTable.id].value,
        avatarId = this[AvatarAchievementsTable.avatarId].value,
        code = this[AvatarAchievementsTable.code],
        title = this[AvatarAchievementsTable.title],
        description = this[AvatarAchievementsTable.description],
        receivedAt = this[AvatarAchievementsTable.receivedAt].toInstant(),
    )

    private fun findUser(userId: UUID): ResultRow? = UsersTable.select { UsersTable.id eq userId }.singleOrNull()

    private fun currentTime(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
}
