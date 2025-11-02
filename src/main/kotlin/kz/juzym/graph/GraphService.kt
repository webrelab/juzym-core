package kz.juzym.graph

import kz.juzym.audit.AuditAction
import java.util.ArrayDeque
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

class GraphServiceImpl(private val repository: GraphRepository) : GraphService {

    override fun createNode(dto: GraphNodeDto) {
        repository.createNode(dto)
    }

    override fun deleteNode(id: UUID): GraphService.DeleteNodeResult {
        val node = repository.getNode(id) ?: return GraphService.DeleteNodeResult.NotFound
        val relationships = getAllRelationships(id)
        val counterpartIds = relationships
            .mapNotNull { relationship -> relationship.otherNodeId(id)?.takeIf { it != id } }
            .toSet()

        val blockingRelationships = counterpartIds.flatMap { counterpartId ->
            val otherRelationships = getAllRelationships(counterpartId)
            val remaining = otherRelationships.filter { it.fromId != id && it.toId != id }
            if (remaining.isEmpty()) {
                relationships
                    .filter { it.connectsNodes(id, counterpartId) }
                    .map { GraphService.BlockingRelationshipInfo(it, counterpartId) }
            } else {
                emptyList()
            }
        }.distinct()

        if (blockingRelationships.isNotEmpty()) {
            return GraphService.DeleteNodeResult.Forbidden(blockingRelationships)
        }

        repository.deleteNode(id)
        return GraphService.DeleteNodeResult.Success(node)
    }

    override fun createRelationship(dto: GraphRelationshipDto) {
        repository.createRelationship(dto)
    }

    override fun deleteRelationship(
        fromId: UUID,
        toId: UUID,
        type: RelationshipType
    ): GraphService.DeleteRelationshipResult {
        val relationship = repository.getRelationship(fromId, toId, type)
            ?: return GraphService.DeleteRelationshipResult.NotFound

        val blockedNodes = mutableSetOf<UUID>()
        val fromRelationships = getAllRelationships(relationship.fromId)
        if (fromRelationships.size <= 1) {
            blockedNodes += relationship.fromId
        }
        val toRelationships = getAllRelationships(relationship.toId)
        if (toRelationships.size <= 1) {
            blockedNodes += relationship.toId
        }

        if (blockedNodes.isNotEmpty()) {
            return GraphService.DeleteRelationshipResult.ForbiddenLastRelationship(blockedNodes)
        }

        repository.deleteRelationship(fromId, toId, type)
        return GraphService.DeleteRelationshipResult.Success
    }

    override fun updateRelationshipEndpoints(
        fromId: UUID,
        toId: UUID,
        type: RelationshipType,
        newFromId: UUID,
        newToId: UUID
    ): GraphService.UpdateRelationshipResult {
        val relationship = repository.getRelationship(fromId, toId, type)
            ?: return GraphService.UpdateRelationshipResult.NotFound

        if (fromId == newFromId && toId == newToId) {
            return GraphService.UpdateRelationshipResult.Success(relationship)
        }

        if (repository.getNode(newFromId) == null) {
            return GraphService.UpdateRelationshipResult.NodeNotFound(newFromId)
        }
        if (repository.getNode(newToId) == null) {
            return GraphService.UpdateRelationshipResult.NodeNotFound(newToId)
        }

        val blockedNodes = mutableSetOf<UUID>()
        if (relationship.fromId != newFromId) {
            val fromRelationships = getAllRelationships(relationship.fromId)
            if (fromRelationships.size <= 1) {
                blockedNodes += relationship.fromId
            }
        }
        if (relationship.toId != newToId) {
            val toRelationships = getAllRelationships(relationship.toId)
            if (toRelationships.size <= 1) {
                blockedNodes += relationship.toId
            }
        }

        if (blockedNodes.isNotEmpty()) {
            return GraphService.UpdateRelationshipResult.ForbiddenLastRelationship(blockedNodes)
        }

        val updated = repository.updateRelationshipEndpoints(fromId, toId, type, newFromId, newToId)
        if (!updated) {
            return GraphService.UpdateRelationshipResult.NotFound
        }

        val updatedRelationship = repository.getRelationship(newFromId, newToId, type)
            ?: return GraphService.UpdateRelationshipResult.NotFound
        return GraphService.UpdateRelationshipResult.Success(updatedRelationship)
    }

    override fun getUserNodesFromStatic(staticId: UUID): Set<UserNode> {
        val startNode = repository.getNode(staticId)
        if (startNode !is StaticNode) {
            return emptySet()
        }

        val nodeCache = mutableMapOf<UUID, GraphNode?>()
        nodeCache[staticId] = startNode
        val visited = mutableSetOf<UUID>()
        val queue: ArrayDeque<UUID> = ArrayDeque()
        queue.add(staticId)
        val result = linkedSetOf<UserNode>()

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) {
                continue
            }
            repository.getOutgoingRelationships(currentId).forEach { relationship ->
                val targetId = relationship.toId
                val targetNode = nodeCache.getOrPut(targetId) { repository.getNode(targetId) }
                when (targetNode) {
                    is UserNode -> result.add(targetNode)
                    is StaticNode -> queue.add(targetId)
                    else -> Unit
                }
            }
        }

        return result
    }

    override fun getActivityNodesFromUser(userId: UUID): Set<ActivityNode> {
        val startNode = repository.getNode(userId)
        if (startNode !is UserNode) {
            return emptySet()
        }

        val nodeCache = mutableMapOf<UUID, GraphNode?>()
        nodeCache[userId] = startNode

        val staticNodeIds = collectStaticAncestors(userId, nodeCache)
        if (staticNodeIds.isEmpty()) {
            return emptySet()
        }

        return collectActivitiesFromStatics(staticNodeIds, nodeCache)
    }

    private fun collectStaticAncestors(
        userId: UUID,
        nodeCache: MutableMap<UUID, GraphNode?>
    ): Set<UUID> {
        val visited = mutableSetOf<UUID>()
        val queue: ArrayDeque<UUID> = ArrayDeque()
        queue.add(userId)
        val staticNodes = mutableSetOf<UUID>()

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) {
                continue
            }
            repository.getIncomingRelationships(currentId).forEach { relationship ->
                val sourceId = relationship.fromId
                val sourceNode = nodeCache.getOrPut(sourceId) { repository.getNode(sourceId) }
                when (sourceNode) {
                    is StaticNode -> {
                        if (staticNodes.add(sourceId)) {
                            queue.add(sourceId)
                        }
                    }
                    else -> Unit
                }
            }
        }

        return staticNodes
    }

    private fun collectActivitiesFromStatics(
        staticNodeIds: Set<UUID>,
        nodeCache: MutableMap<UUID, GraphNode?>
    ): Set<ActivityNode> {
        val queue: ArrayDeque<UUID> = ArrayDeque(staticNodeIds)
        val visited = mutableSetOf<UUID>()
        val activityIds = mutableSetOf<UUID>()
        val result = linkedSetOf<ActivityNode>()

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) {
                continue
            }
            repository.getOutgoingRelationships(currentId).forEach { relationship ->
                val targetId = relationship.toId
                val targetNode = nodeCache.getOrPut(targetId) { repository.getNode(targetId) }
                when (targetNode) {
                    is ActivityNode -> {
                        if (activityIds.add(targetId)) {
                            result.add(targetNode)
                            queue.add(targetId)
                        } else if (!visited.contains(targetId)) {
                            queue.add(targetId)
                        }
                    }
                    is StaticNode -> {
                        if (!visited.contains(targetId)) {
                            queue.add(targetId)
                        }
                    }
                    else -> Unit
                }
            }
        }

        return result
    }

    private fun getAllRelationships(nodeId: UUID): List<GraphRelationship> {
        val outgoing = repository.getOutgoingRelationships(nodeId)
        val incoming = repository.getIncomingRelationships(nodeId)
        return (outgoing + incoming).distinct()
    }

    private fun GraphRelationship.otherNodeId(nodeId: UUID): UUID? {
        return when (nodeId) {
            fromId -> toId
            toId -> fromId
            else -> null
        }
    }

    private fun GraphRelationship.connectsNodes(first: UUID, second: UUID): Boolean {
        return (fromId == first && toId == second) || (fromId == second && toId == first)
    }
}
