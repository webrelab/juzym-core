package kz.juzym.user.avatar

import kz.juzym.user.UsersTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

object AvatarsTable : UUIDTable(name = "avatar") {
    val userId = reference("user_id", UsersTable).uniqueIndex()
    val displayName: Column<String> = text("display_name")
    val about: Column<String?> = text("about").nullable()
    val photoUrl: Column<String?> = text("photo_url").nullable()
    val level: Column<Int> = integer("level").default(1)
    val xp: Column<Int> = integer("xp").default(0)
    val reputation: Column<Int> = integer("reputation").default(0)
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
}

object AvatarSkillsTable : UUIDTable(name = "avatar_skill") {
    val avatarId = reference("avatar_id", AvatarsTable)
    val code: Column<String> = text("code")
    val name: Column<String> = text("name")
    val level: Column<Int> = integer("level")
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")

    init {
        uniqueIndex(avatarId, code)
    }
}

object AvatarAchievementsTable : UUIDTable(name = "avatar_achievement") {
    val avatarId = reference("avatar_id", AvatarsTable)
    val code: Column<String> = text("code")
    val title: Column<String> = text("title")
    val description: Column<String?> = text("description").nullable()
    val receivedAt: Column<OffsetDateTime> = timestampWithTimeZone("received_at")

    init {
        uniqueIndex(avatarId, code)
    }
}

object AvatarStatsCacheTable : UUIDTable(name = "avatar_stats_cache") {
    val avatarId = reference("avatar_id", AvatarsTable).uniqueIndex()
    val tasksCompleted: Column<Int> = integer("tasks_completed").default(0)
    val votesParticipated: Column<Int> = integer("votes_participated").default(0)
    val lastActivityAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_activity_at").nullable()
}
