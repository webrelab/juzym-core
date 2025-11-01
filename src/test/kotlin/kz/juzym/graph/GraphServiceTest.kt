package kz.juzym.graph

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class GraphServiceTest {

    @MockK
    lateinit var repository: GraphRepository

    private lateinit var service: GraphService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        service = GraphService(repository)
    }

    @Test
    fun `delete node succeeds when counterpart retains relationships`() {
        val nodeId = UUID.randomUUID()
        val node = staticNode(nodeId)
        val counterpartId = UUID.randomUUID()
        val otherNodeId = UUID.randomUUID()

        val relationship = relationship(nodeId, counterpartId)
        val counterpartOther = relationship(counterpartId, otherNodeId)

        every { repository.getNode(nodeId) } returns node
        every { repository.getOutgoingRelationships(nodeId) } returns listOf(relationship)
        every { repository.getIncomingRelationships(nodeId) } returns emptyList()
        every { repository.getOutgoingRelationships(counterpartId) } returns listOf(counterpartOther)
        every { repository.getIncomingRelationships(counterpartId) } returns listOf(relationship)
        every { repository.deleteNode(nodeId) } returns true

        val result = service.deleteNode(nodeId)

        val success = assertIs<GraphService.DeleteNodeResult.Success>(result)
        assertEquals(node, success.deletedNode)
        verify(exactly = 1) { repository.deleteNode(nodeId) }
    }

    @Test
    fun `delete node is forbidden when counterpart would become orphan`() {
        val nodeId = UUID.randomUUID()
        val node = staticNode(nodeId)
        val counterpartId = UUID.randomUUID()
        val relationship = relationship(nodeId, counterpartId)

        every { repository.getNode(nodeId) } returns node
        every { repository.getOutgoingRelationships(nodeId) } returns listOf(relationship)
        every { repository.getIncomingRelationships(nodeId) } returns emptyList()
        every { repository.getOutgoingRelationships(counterpartId) } returns emptyList()
        every { repository.getIncomingRelationships(counterpartId) } returns listOf(relationship)
        every { repository.getNode(counterpartId) } returns userNode(counterpartId)

        val result = service.deleteNode(nodeId)

        val forbidden = assertIs<GraphService.DeleteNodeResult.Forbidden>(result)
        assertEquals(
            listOf(GraphService.BlockingRelationshipInfo(relationship, counterpartId)),
            forbidden.blockingRelationships
        )
        verify(exactly = 0) { repository.deleteNode(nodeId) }
    }

    @Test
    fun `delete node returns not found when node missing`() {
        val nodeId = UUID.randomUUID()
        every { repository.getNode(nodeId) } returns null

        val result = service.deleteNode(nodeId)

        assertTrue(result is GraphService.DeleteNodeResult.NotFound)
    }

    @Test
    fun `delete relationship returns not found when missing`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns null

        val result = service.deleteRelationship(fromId, toId, RelationshipType.OWNER)

        assertTrue(result is GraphService.DeleteRelationshipResult.NotFound)
    }

    @Test
    fun `delete relationship is forbidden when it is last connection for nodes`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()
        every { repository.getOutgoingRelationships(toId) } returns emptyList()
        every { repository.getIncomingRelationships(toId) } returns listOf(relationship)

        val result = service.deleteRelationship(fromId, toId, RelationshipType.OWNER)

        val forbidden = assertIs<GraphService.DeleteRelationshipResult.ForbiddenLastRelationship>(result)
        assertEquals(setOf(fromId, toId), forbidden.nodeIds)
        verify(exactly = 0) { repository.deleteRelationship(fromId, toId, RelationshipType.OWNER) }
    }

    @Test
    fun `delete relationship succeeds when alternative connections exist`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)
        val fromAlternate = relationship(fromId, UUID.randomUUID(), RelationshipType.REPRESENTATIVE)
        val toAlternate = relationship(UUID.randomUUID(), toId, RelationshipType.RESIDENT)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship, fromAlternate)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()
        every { repository.getOutgoingRelationships(toId) } returns emptyList()
        every { repository.getIncomingRelationships(toId) } returns listOf(relationship, toAlternate)
        every { repository.deleteRelationship(fromId, toId, RelationshipType.OWNER) } returns true

        val result = service.deleteRelationship(fromId, toId, RelationshipType.OWNER)

        assertTrue(result is GraphService.DeleteRelationshipResult.Success)
        verify(exactly = 1) { repository.deleteRelationship(fromId, toId, RelationshipType.OWNER) }
    }

    @Test
    fun `update relationship returns not found when relationship missing`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns null

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, fromId, toId)

        assertTrue(result is GraphService.UpdateRelationshipResult.NotFound)
    }

    @Test
    fun `update relationship short-circuits when endpoints unchanged`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, fromId, toId)

        val success = assertIs<GraphService.UpdateRelationshipResult.Success>(result)
        assertEquals(relationship, success.relationship)
        verify(exactly = 0) { repository.updateRelationshipEndpoints(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update relationship fails when new from node missing`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newFromId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(newFromId) } returns null

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, toId)

        val failure = assertIs<GraphService.UpdateRelationshipResult.NodeNotFound>(result)
        assertEquals(newFromId, failure.nodeId)
    }

    @Test
    fun `update relationship fails when new to node missing`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newToId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(fromId) } returns staticNode(fromId)
        every { repository.getNode(newToId) } returns null

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, fromId, newToId)

        val failure = assertIs<GraphService.UpdateRelationshipResult.NodeNotFound>(result)
        assertEquals(newToId, failure.nodeId)
    }

    @Test
    fun `update relationship is forbidden when old from node would lose last link`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newFromId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(newFromId) } returns staticNode(newFromId)
        every { repository.getNode(toId) } returns userNode(toId)
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, toId)

        val forbidden = assertIs<GraphService.UpdateRelationshipResult.ForbiddenLastRelationship>(result)
        assertEquals(setOf(fromId), forbidden.nodeIds)
    }

    @Test
    fun `update relationship fails when repository cannot move relationship`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newFromId = UUID.randomUUID()
        val newToId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)
        val fromAlternate = relationship(fromId, UUID.randomUUID(), RelationshipType.REPRESENTATIVE)
        val toAlternate = relationship(UUID.randomUUID(), toId, RelationshipType.RESIDENT)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(newFromId) } returns staticNode(newFromId)
        every { repository.getNode(newToId) } returns userNode(newToId)
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship, fromAlternate)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()
        every { repository.getOutgoingRelationships(toId) } returns emptyList()
        every { repository.getIncomingRelationships(toId) } returns listOf(relationship, toAlternate)
        every { repository.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId) } returns false

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId)

        assertTrue(result is GraphService.UpdateRelationshipResult.NotFound)
    }

    @Test
    fun `update relationship fails when updated relationship cannot be loaded`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newFromId = UUID.randomUUID()
        val newToId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)
        val fromAlternate = relationship(fromId, UUID.randomUUID(), RelationshipType.REPRESENTATIVE)
        val toAlternate = relationship(UUID.randomUUID(), toId, RelationshipType.RESIDENT)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(newFromId) } returns staticNode(newFromId)
        every { repository.getNode(newToId) } returns userNode(newToId)
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship, fromAlternate)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()
        every { repository.getOutgoingRelationships(toId) } returns emptyList()
        every { repository.getIncomingRelationships(toId) } returns listOf(relationship, toAlternate)
        every { repository.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId) } returns true
        every { repository.getRelationship(newFromId, newToId, RelationshipType.OWNER) } returns null

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId)

        assertTrue(result is GraphService.UpdateRelationshipResult.NotFound)
    }

    @Test
    fun `update relationship succeeds when validation passes`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val newFromId = UUID.randomUUID()
        val newToId = UUID.randomUUID()
        val relationship = relationship(fromId, toId, RelationshipType.OWNER)
        val fromAlternate = relationship(fromId, UUID.randomUUID(), RelationshipType.REPRESENTATIVE)
        val toAlternate = relationship(UUID.randomUUID(), toId, RelationshipType.RESIDENT)
        val updatedRelationship = relationship(newFromId, newToId, RelationshipType.OWNER)

        every { repository.getRelationship(fromId, toId, RelationshipType.OWNER) } returns relationship
        every { repository.getNode(newFromId) } returns staticNode(newFromId)
        every { repository.getNode(newToId) } returns userNode(newToId)
        every { repository.getOutgoingRelationships(fromId) } returns listOf(relationship, fromAlternate)
        every { repository.getIncomingRelationships(fromId) } returns emptyList()
        every { repository.getOutgoingRelationships(toId) } returns emptyList()
        every { repository.getIncomingRelationships(toId) } returns listOf(relationship, toAlternate)
        every { repository.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId) } returns true
        every { repository.getRelationship(newFromId, newToId, RelationshipType.OWNER) } returns updatedRelationship

        val result = service.updateRelationshipEndpoints(fromId, toId, RelationshipType.OWNER, newFromId, newToId)

        val success = assertIs<GraphService.UpdateRelationshipResult.Success>(result)
        assertEquals(updatedRelationship, success.relationship)
    }

    @Test
    fun `get user nodes from static traverses static hierarchy only`() {
        val staticId = UUID.randomUUID()
        val childStaticId = UUID.randomUUID()
        val userDirectId = UUID.randomUUID()
        val userIndirectId = UUID.randomUUID()
        val activityId = UUID.randomUUID()
        val userThroughActivityId = UUID.randomUUID()

        val staticNode = staticNode(staticId)
        val childStatic = staticNode(childStaticId)
        val userDirect = userNode(userDirectId)
        val userIndirect = userNode(userIndirectId)
        val activity = activityNode(activityId)
        val userThroughActivity = userNode(userThroughActivityId)

        val relToChildStatic = relationship(staticId, childStaticId, RelationshipType.PART_OF)
        val relToUserDirect = relationship(staticId, userDirectId, RelationshipType.RESIDENT)
        val relToActivity = relationship(staticId, activityId, RelationshipType.TARGET)
        val relChildToUserIndirect = relationship(childStaticId, userIndirectId, RelationshipType.RESIDENT)
        val relActivityToUser = relationship(activityId, userThroughActivityId, RelationshipType.TARGET)

        every { repository.getNode(staticId) } returns staticNode
        every { repository.getNode(childStaticId) } returns childStatic
        every { repository.getNode(userDirectId) } returns userDirect
        every { repository.getNode(userIndirectId) } returns userIndirect
        every { repository.getNode(activityId) } returns activity
        every { repository.getNode(userThroughActivityId) } returns userThroughActivity

        every { repository.getOutgoingRelationships(staticId) } returns listOf(
            relToChildStatic,
            relToUserDirect,
            relToActivity
        )
        every { repository.getOutgoingRelationships(childStaticId) } returns listOf(relChildToUserIndirect)
        every { repository.getOutgoingRelationships(activityId) } returns listOf(relActivityToUser)

        val result = service.getUserNodesFromStatic(staticId)

        assertEquals(setOf(userDirect, userIndirect), result)
    }

    @Test
    fun `get user nodes from static returns empty set when start node invalid`() {
        val staticId = UUID.randomUUID()
        every { repository.getNode(staticId) } returns null

        val result = service.getUserNodesFromStatic(staticId)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `get activity nodes from user collects ancestors and nested activities`() {
        val userId = UUID.randomUUID()
        val staticChildId = UUID.randomUUID()
        val staticParentId = UUID.randomUUID()
        val activityRootId = UUID.randomUUID()
        val activityNestedId = UUID.randomUUID()
        val activityLeafId = UUID.randomUUID()

        val userNode = userNode(userId)
        val staticChild = staticNode(staticChildId)
        val staticParent = staticNode(staticParentId)
        val activityRoot = activityNode(activityRootId)
        val activityNested = activityNode(activityNestedId)
        val activityLeaf = activityNode(activityLeafId)

        val relStaticToUser = relationship(staticChildId, userId, RelationshipType.RESIDENT)
        val relParentToChild = relationship(staticParentId, staticChildId, RelationshipType.PART_OF)
        val relChildToActivity = relationship(staticChildId, activityRootId, RelationshipType.TARGET)
        val relParentToActivity = relationship(staticParentId, activityRootId, RelationshipType.TARGET)
        val relActivityToNested = relationship(activityRootId, activityNestedId, RelationshipType.SUBTASK_OF)
        val relNestedToLeaf = relationship(activityNestedId, activityLeafId, RelationshipType.SUBTASK_OF)

        every { repository.getNode(userId) } returns userNode
        every { repository.getNode(staticChildId) } returns staticChild
        every { repository.getNode(staticParentId) } returns staticParent
        every { repository.getNode(activityRootId) } returns activityRoot
        every { repository.getNode(activityNestedId) } returns activityNested
        every { repository.getNode(activityLeafId) } returns activityLeaf

        every { repository.getIncomingRelationships(userId) } returns listOf(relStaticToUser)
        every { repository.getIncomingRelationships(staticChildId) } returns listOf(relParentToChild)
        every { repository.getIncomingRelationships(staticParentId) } returns emptyList()
        every { repository.getIncomingRelationships(activityRootId) } returns emptyList()
        every { repository.getIncomingRelationships(activityNestedId) } returns emptyList()
        every { repository.getIncomingRelationships(activityLeafId) } returns emptyList()

        every { repository.getOutgoingRelationships(staticChildId) } returns listOf(relChildToActivity)
        every { repository.getOutgoingRelationships(staticParentId) } returns listOf(relParentToActivity)
        every { repository.getOutgoingRelationships(activityRootId) } returns listOf(relActivityToNested)
        every { repository.getOutgoingRelationships(activityNestedId) } returns listOf(relNestedToLeaf)
        every { repository.getOutgoingRelationships(activityLeafId) } returns emptyList()

        val result = service.getActivityNodesFromUser(userId)

        assertEquals(listOf(activityRoot, activityNested, activityLeaf).toSet(), result)
    }

    @Test
    fun `get activity nodes from user returns empty set when no statics linked`() {
        val userId = UUID.randomUUID()
        val userNode = userNode(userId)

        every { repository.getNode(userId) } returns userNode
        every { repository.getIncomingRelationships(userId) } returns emptyList()

        val result = service.getActivityNodesFromUser(userId)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `get activity nodes from user returns empty set for non user node`() {
        val userId = UUID.randomUUID()
        every { repository.getNode(userId) } returns staticNode(userId)

        val result = service.getActivityNodesFromUser(userId)

        assertTrue(result.isEmpty())
    }

    private fun staticNode(id: UUID) = StaticNode(
        id = id,
        staticType = StaticNodeType.BUILDING,
        name = "Static-$id",
        metadata = emptyMap()
    )

    private fun activityNode(id: UUID) = ActivityNode(
        id = id,
        activityType = ActivityType.TASK,
        name = "Activity-$id",
        status = "open",
        options = emptyList(),
        contract = emptyList(),
        expiresAt = null,
        metadata = emptyMap()
    )

    private fun userNode(id: UUID) = UserNode(
        id = id,
        name = "User-$id",
        displayName = "User $id",
        userId = id,
        metadata = emptyMap()
    )

    private fun relationship(
        fromId: UUID,
        toId: UUID,
        type: RelationshipType = RelationshipType.PART_OF
    ) = GraphRelationship(
        fromId = fromId,
        toId = toId,
        type = type,
        weight = 1.0,
        createdAt = Instant.EPOCH,
        meta = emptyMap()
    )
}
