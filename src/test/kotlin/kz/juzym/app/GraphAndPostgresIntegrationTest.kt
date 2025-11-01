package kz.juzym.app

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.PostgresConfig
import kz.juzym.graph.GraphRelationshipDto
import kz.juzym.graph.GraphRepository
import kz.juzym.graph.RelationshipType
import kz.juzym.graph.StaticNodeDto
import kz.juzym.graph.StaticNodeType
import kz.juzym.graph.UserNode
import kz.juzym.graph.UserNodeDto
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.user.NewUser
import kz.juzym.user.UsersRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphAndPostgresIntegrationTest {

    private lateinit var neo4j: Neo4j
    private lateinit var driver: Driver
    private lateinit var graphRepository: GraphRepository

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var postgresContext: PostgresDatabaseContext
    private lateinit var usersRepository: UsersRepository

    @BeforeAll
    fun setup() {
        neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build()
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())
        graphRepository = GraphRepository(driver)

        postgres = EmbeddedPostgres.builder().start()
        val config = PostgresConfig(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = ""
        )
        val factory = DatabaseFactory(config)
        postgresContext = factory.connect()
        factory.verifyConnection(postgresContext)
        factory.ensureSchema(postgresContext)
        usersRepository = UsersRepository(postgresContext.database)
    }

    @AfterEach
    fun cleanupGraph() {
        graphRepository.clear()
    }

    @AfterAll
    fun tearDown() {
        postgresContext.close()
        postgres.close()
        driver.close()
        neo4j.close()
    }

    @Test
    fun `should create graph user linked to sql user`() {
        val newUser = NewUser(
            id = UUID.randomUUID(),
            email = "graph-user@example.com",
            passwordHash = "hash",
            displayName = "Graph User",
            status = "active",
            createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        )
        usersRepository.create(newUser)

        val userNodeId = UUID.randomUUID()
        val userNodeDto = UserNodeDto(
            id = userNodeId,
            name = "graph-user",
            displayName = newUser.displayName,
            userId = newUser.id,
            metadata = mapOf("role" to "owner")
        )

        graphRepository.createNode(userNodeDto)

        val stored = graphRepository.getNode(userNodeId)
        val graphUser = assertIs<UserNode>(stored)
        assertEquals(newUser.id, graphUser.userId)
        assertEquals("owner", graphUser.metadata["role"])
    }

    @Test
    fun `should connect static node with representative relationship`() {
        val parentId = UUID.randomUUID()
        graphRepository.createNode(
            StaticNodeDto(
                id = parentId,
                staticType = StaticNodeType.COMMUNITY,
                name = "Community"
            )
        )
        val userId = UUID.randomUUID()
        val sqlUser = NewUser(
            id = userId,
            email = "rep@example.com",
            passwordHash = "hash",
            displayName = "Representative",
            status = "active",
            createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        )
        usersRepository.create(sqlUser)
        val graphUserId = UUID.randomUUID()
        graphRepository.createNode(
            UserNodeDto(
                id = graphUserId,
                name = "rep",
                displayName = sqlUser.displayName,
                userId = sqlUser.id
            )
        )

        val relationshipDto = GraphRelationshipDto(
            fromId = graphUserId,
            toId = parentId,
            type = RelationshipType.REPRESENTATIVE,
            createdAt = Instant.now(),
            meta = mapOf("scope" to "subtree")
        )
        graphRepository.createRelationship(relationshipDto)

        val relationships = graphRepository.getOutgoingRelationships(graphUserId)
        assertEquals(1, relationships.size)
        assertEquals("subtree", relationships.first().meta["scope"])
    }
}
