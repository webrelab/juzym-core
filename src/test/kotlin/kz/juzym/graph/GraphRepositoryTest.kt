package kz.juzym.graph

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertDoesNotThrow
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Driver
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphRepositoryTest {

    private lateinit var neo4j: Neo4j
    private lateinit var driver: Driver
    private lateinit var repository: GraphRepository

    @BeforeAll
    fun setup() {
        neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build()
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())
        repository = GraphRepository(driver)
    }

    @AfterEach
    fun cleanGraph() {
        repository.clear()
    }

    @AfterAll
    fun tearDown() {
        driver.close()
        neo4j.close()
    }

    @Test
    fun `should create and read static node`() {
        val nodeId = UUID.randomUUID()
        val dto = StaticNodeDto(
            id = nodeId,
            staticType = StaticNodeType.BUILDING,
            name = "Residential Complex",
            metadata = mapOf("floors" to 12, "address" to "Main st. 1")
        )

        repository.createNode(dto)

        val stored = repository.getNode(nodeId)
        val staticNode = assertIs<StaticNode>(stored)
        assertEquals(NodeKind.STATIC, staticNode.kind)
        assertEquals(StaticNodeType.BUILDING, staticNode.staticType)
        assertEquals("Residential Complex", staticNode.name)
        assertEquals(12, staticNode.metadata["floors"])
    }

    @Test
    fun `should create and read activity node with contract`() {
        val nodeId = UUID.randomUUID()
        val expiresAt = Instant.now().plusSeconds(3600)
        val dto = ActivityNodeDto(
            id = nodeId,
            activityType = ActivityType.TASK,
            name = "Repair entrance door",
            status = ActivityStatus.OPEN,
            options = listOf("yes", "no"),
            contract = listOf(mapOf("name" to "main", "budget" to 1000, "currency" to "USDT")),
            expiresAt = expiresAt,
            metadata = mapOf("priority" to "high")
        )

        repository.createNode(dto)

        val stored = repository.getNode(nodeId)
        val activityNode = assertIs<ActivityNode>(stored)
        assertEquals(NodeKind.ACTIVITY, activityNode.kind)
        assertEquals(ActivityType.TASK, activityNode.activityType)
        assertEquals("Repair entrance door", activityNode.name)
        assertEquals(listOf("yes", "no"), activityNode.options)
        assertEquals("main", activityNode.contract.first()["name"])
        assertEquals(expiresAt.epochSecond, activityNode.expiresAt?.epochSecond)
        assertEquals("high", activityNode.metadata["priority"])
    }

    @Test
    fun `should create relationship with metadata`() {
        val staticId = UUID.randomUUID()
        val activityId = UUID.randomUUID()

        repository.createNode(
            StaticNodeDto(
                id = staticId,
                staticType = StaticNodeType.ENTRANCE,
                name = "Entrance A"
            )
        )
        repository.createNode(
            ActivityNodeDto(
                id = activityId,
                activityType = ActivityType.TASK_APPROVAL,
                name = "Approve budget",
                status = ActivityStatus.READY_FOR_REVIEW
            )
        )

        val createdAt = Instant.now()
        val relationshipDto = GraphRelationshipDto(
            fromId = activityId,
            toId = staticId,
            type = RelationshipType.TARGET,
            weight = 2.5,
            createdAt = createdAt,
            meta = mapOf("voteOption" to "yes")
        )

        repository.createRelationship(relationshipDto)

        val outgoing = repository.getOutgoingRelationships(activityId)
        assertEquals(1, outgoing.size)
        val stored = outgoing.first()
        assertEquals(RelationshipType.TARGET, stored.type)
        assertEquals(activityId, stored.fromId)
        assertEquals(staticId, stored.toId)
        assertEquals(2.5, stored.weight)
        assertEquals("yes", stored.meta["voteOption"])

        val incoming = repository.getIncomingRelationships(staticId)
        assertEquals(1, incoming.size)
        assertEquals(activityId, incoming.first().fromId)
    }

    @Test
    fun `should load child static nodes`() {
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val secondChildId = UUID.randomUUID()

        repository.createNode(
            StaticNodeDto(id = parentId, staticType = StaticNodeType.BUILDING, name = "Building")
        )
        repository.createNode(
            StaticNodeDto(id = childId, staticType = StaticNodeType.ENTRANCE, name = "Entrance 1")
        )
        repository.createNode(
            StaticNodeDto(id = secondChildId, staticType = StaticNodeType.ENTRANCE, name = "Entrance 2")
        )

        repository.createRelationship(
            GraphRelationshipDto(
                fromId = childId,
                toId = parentId,
                type = RelationshipType.PART_OF,
                createdAt = Instant.now()
            )
        )
        repository.createRelationship(
            GraphRelationshipDto(
                fromId = secondChildId,
                toId = parentId,
                type = RelationshipType.PART_OF,
                createdAt = Instant.now()
            )
        )

        val children = repository.getChildStaticNodes(parentId)
        assertEquals(2, children.size)
        val ids = children.map { it.id }.toSet()
        assertEquals(setOf(childId, secondChildId), ids)
    }

    @Test
    fun `should create node with special characters using parameters`() {
        val nodeId = UUID.randomUUID()
        val dto = StaticNodeDto(
            id = nodeId,
            staticType = StaticNodeType.FLOOR,
            name = "Floor \"1\"",
            metadata = mapOf("notes" to "Curly braces {} and dollar $100 should not break queries")
        )

        assertDoesNotThrow { repository.createNode(dto) }

        val stored = repository.getNode(nodeId)
        val staticNode = assertIs<StaticNode>(stored)
        assertEquals("Curly braces {} and dollar $100 should not break queries", staticNode.metadata["notes"])
    }
}
