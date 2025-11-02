package kz.juzym.graph

import kz.juzym.audit.AuditAction
import java.util.UUID

interface GraphService {

    data class BlockingRelationshipInfo(
        val relationship: GraphRelationship,
        val orphanNodeId: UUID
    )

    sealed interface DeleteNodeResult {
        data class Success(val deletedNode: GraphNode) : DeleteNodeResult
        data class Forbidden(val blockingRelationships: List<BlockingRelationshipInfo>) : DeleteNodeResult
        data object NotFound : DeleteNodeResult
    }

    sealed interface DeleteRelationshipResult {
        data object Success : DeleteRelationshipResult
        data class ForbiddenLastRelationship(val nodeIds: Set<UUID>) : DeleteRelationshipResult
        data object NotFound : DeleteRelationshipResult
    }

    sealed interface UpdateRelationshipResult {
        data class Success(val relationship: GraphRelationship) : UpdateRelationshipResult
        data class NodeNotFound(val nodeId: UUID) : UpdateRelationshipResult
        data class ForbiddenLastRelationship(val nodeIds: Set<UUID>) : UpdateRelationshipResult
        data object NotFound : UpdateRelationshipResult
    }

    @AuditAction("graph.createNode")
    fun createNode(dto: GraphNodeDto)

    @AuditAction("graph.deleteNode")
    fun deleteNode(id: UUID): DeleteNodeResult

    @AuditAction("graph.createRelationship")
    fun createRelationship(dto: GraphRelationshipDto)

    @AuditAction("graph.deleteRelationship")
    fun deleteRelationship(fromId: UUID, toId: UUID, type: RelationshipType): DeleteRelationshipResult

    @AuditAction("graph.updateRelationshipEndpoints")
    fun updateRelationshipEndpoints(
        fromId: UUID,
        toId: UUID,
        type: RelationshipType,
        newFromId: UUID,
        newToId: UUID
    ): UpdateRelationshipResult

    @AuditAction("graph.getUserNodesFromStatic")
    fun getUserNodesFromStatic(staticId: UUID): Set<UserNode>

    @AuditAction("graph.getActivityNodesFromUser")
    fun getActivityNodesFromUser(userId: UUID): Set<ActivityNode>
}

