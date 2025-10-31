package kz.juzym.graph

import java.time.Instant
import java.util.UUID

enum class NodeKind(val value: String) {
    STATIC("STATIC"),
    ACTIVITY("ACTIVITY"),
    USER("USER");
}

enum class StaticNodeType(val value: String) {
    APARTMENT("apartment"),
    FLOOR("floor"),
    ENTRANCE("entrance"),
    BUILDING("building"),
    COMMUNITY("community"),
    COALITION("coalition");
}

enum class ActivityType(val value: String) {
    PROBLEM("problem"),
    TASK("task"),
    VOTE_CANDIDATE("vote_candidate"),
    AMENDMENT("amendment"),
    TASK_APPROVAL("task_approval");
}

enum class ActivityStatus(val value: String) {
    OPEN("open"),
    VOTING("voting"),
    READY_FOR_REVIEW("ready_for_review"),
    RESOLVED("resolved"),
    FAILED("failed");
}

enum class RelationshipType(val value: String) {
    PART_OF("PART_OF"),
    OWNER("OWNER"),
    RESIDENT("RESIDENT"),
    REPRESENTATIVE("REPRESENTATIVE"),
    VOTED("VOTED"),
    EXECUTOR("EXECUTOR"),
    TARGET("TARGET"),
    SUBTASK_OF("SUBTASK_OF");
}

sealed interface GraphNodeDto {
    val id: UUID
    val kind: NodeKind
    val type: String
    val name: String
    val metadata: Map<String, Any?>
}

data class StaticNodeDto(
    override val id: UUID,
    val staticType: StaticNodeType,
    override val name: String,
    override val metadata: Map<String, Any?> = emptyMap()
) : GraphNodeDto {
    override val kind: NodeKind = NodeKind.STATIC
    override val type: String = staticType.value
}

data class ActivityNodeDto(
    override val id: UUID,
    val activityType: ActivityType,
    override val name: String,
    val status: ActivityStatus,
    val options: List<String> = emptyList(),
    val contract: List<Map<String, Any?>> = emptyList(),
    val expiresAt: Instant? = null,
    override val metadata: Map<String, Any?> = emptyMap()
) : GraphNodeDto {
    override val kind: NodeKind = NodeKind.ACTIVITY
    override val type: String = activityType.value
}

data class UserNodeDto(
    override val id: UUID,
    override val name: String,
    val displayName: String,
    val userId: UUID,
    override val metadata: Map<String, Any?> = emptyMap()
) : GraphNodeDto {
    override val kind: NodeKind = NodeKind.USER
    override val type: String = "user"
}

sealed interface GraphNode {
    val id: UUID
    val kind: NodeKind
    val type: String
    val name: String
    val metadata: Map<String, Any?>
}

data class StaticNode(
    override val id: UUID,
    val staticType: StaticNodeType,
    override val name: String,
    override val metadata: Map<String, Any?>
) : GraphNode {
    override val kind: NodeKind = NodeKind.STATIC
    override val type: String = staticType.value
}

data class ActivityNode(
    override val id: UUID,
    val activityType: ActivityType,
    override val name: String,
    val status: String,
    val options: List<String>,
    val contract: List<Map<String, Any?>>, 
    val expiresAt: Instant?,
    override val metadata: Map<String, Any?>
) : GraphNode {
    override val kind: NodeKind = NodeKind.ACTIVITY
    override val type: String = activityType.value
}

data class UserNode(
    override val id: UUID,
    override val name: String,
    val displayName: String,
    val userId: UUID,
    override val metadata: Map<String, Any?>
) : GraphNode {
    override val kind: NodeKind = NodeKind.USER
    override val type: String = "user"
}

data class GraphRelationshipDto(
    val fromId: UUID,
    val toId: UUID,
    val type: RelationshipType,
    val weight: Double = 1.0,
    val createdAt: Instant,
    val meta: Map<String, Any?> = emptyMap()
)

data class GraphRelationship(
    val fromId: UUID,
    val toId: UUID,
    val type: RelationshipType,
    val weight: Double,
    val createdAt: Instant,
    val meta: Map<String, Any?>
)
