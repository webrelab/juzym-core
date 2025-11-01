package kz.juzym.graph

import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.Values
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Relationship
import kz.juzym.graph.GraphJson
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GraphRepository(private val driver: Driver) {

    fun createNode(dto: GraphNodeDto) {
        when (dto) {
            is StaticNodeDto -> createStaticNode(dto)
            is ActivityNodeDto -> createActivityNode(dto)
            is UserNodeDto -> createUserNode(dto)
        }
    }

    fun getNode(id: UUID): GraphNode? = driver.session(SessionConfig.defaultConfig()).use { session ->
        session.executeRead { tx ->
            val records = tx.run(
                """
                MATCH (n {id: ${"$"}id})
                RETURN n, labels(n) AS labels
                """.trimIndent(),
                Values.parameters("id", id.toString())
            ).list()
            val record = records.singleOrNull() ?: return@executeRead null
            val node = record["n"].asNode()
            val label = record["labels"].asList { value -> value.asString() }.singleOrNull()
                ?: error("Node must have exactly one label")
            when (label) {
                "Static" -> node.toStaticNode()
                "Activity" -> node.toActivityNode()
                "User" -> node.toUserNode()
                else -> null
            }
        }
    }

    fun createRelationship(dto: GraphRelationshipDto) {
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                val summary = tx.run(
                    """
                    MATCH (from {id: ${"$"}fromId}), (to {id: ${"$"}toId})
                    CREATE (from)-[r:RELATION {type: ${"$"}type, weight: ${"$"}weight, createdAt: ${"$"}createdAt, meta: ${"$"}meta}]->(to)
                    RETURN r
                    """.trimIndent(),
                    Values.parameters(
                        "fromId", dto.fromId.toString(),
                        "toId", dto.toId.toString(),
                        "type", dto.type.value,
                        "weight", dto.weight,
                        "createdAt", dto.createdAt.atOffset(ZoneOffset.UTC),
                        "meta", GraphJson.encodeMap(dto.meta)
                    )
                ).consume()
                if (summary.counters().relationshipsCreated() == 0) {
                    throw IllegalArgumentException("Both nodes must exist before creating a relationship")
                }
            }
        }
    }

    fun getRelationship(fromId: UUID, toId: UUID, type: RelationshipType): GraphRelationship? =
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeRead { tx ->
                val record = tx.run(
                    """
                    MATCH (from {id: ${"$"}fromId})-[r:RELATION {type: ${"$"}type}]->(to {id: ${"$"}toId})
                    RETURN r
                    LIMIT 1
                    """.trimIndent(),
                    Values.parameters(
                        "fromId", fromId.toString(),
                        "toId", toId.toString(),
                        "type", type.value
                    )
                ).list().singleOrNull() ?: return@executeRead null
                val relationship = record["r"].asRelationship()
                relationship.toGraphRelationship(fromId = fromId, toId = toId)
            }
        }

    fun getOutgoingRelationships(id: UUID): List<GraphRelationship> = driver.session(SessionConfig.defaultConfig()).use { session ->
        session.executeRead { tx ->
            tx.run(
                """
                MATCH (n {id: ${"$"}id})-[r:RELATION]->(m)
                RETURN r, m.id AS toId
                """.trimIndent(),
                Values.parameters("id", id.toString())
            ).list { record ->
                val relationship = record["r"].asRelationship()
                val toId = UUID.fromString(record["toId"].asString())
                relationship.toGraphRelationship(fromId = id, toId = toId)
            }
        }
    }

    fun getIncomingRelationships(id: UUID): List<GraphRelationship> = driver.session(SessionConfig.defaultConfig()).use { session ->
        session.executeRead { tx ->
            tx.run(
                """
                MATCH (n {id: ${"$"}id})<-[r:RELATION]-(m)
                RETURN r, m.id AS fromId
                """.trimIndent(),
                Values.parameters("id", id.toString())
            ).list { record ->
                val relationship = record["r"].asRelationship()
                val fromId = UUID.fromString(record["fromId"].asString())
                relationship.toGraphRelationship(fromId = fromId, toId = id)
            }
        }
    }

    fun getChildStaticNodes(parentId: UUID): List<StaticNode> = driver.session(SessionConfig.defaultConfig()).use { session ->
        session.executeRead { tx ->
            tx.run(
                """
                MATCH (parent:Static {id: ${"$"}parentId})<-[:RELATION {type: ${"$"}type}]-(child:Static)
                RETURN child
                """.trimIndent(),
                Values.parameters(
                    "parentId", parentId.toString(),
                    "type", RelationshipType.PART_OF.value
                )
            ).list { record ->
                record["child"].asNode().toStaticNode()
            }
        }
    }

    fun clear() {
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                tx.run("MATCH (n) DETACH DELETE n").consume()
            }
        }
    }

    fun deleteNode(id: UUID): Boolean {
        return driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                val summary = tx.run(
                    """
                    MATCH (n {id: ${"$"}id})
                    DETACH DELETE n
                    """.trimIndent(),
                    Values.parameters("id", id.toString())
                ).consume()
                summary.counters().nodesDeleted() > 0
            }
        }
    }

    fun deleteRelationship(fromId: UUID, toId: UUID, type: RelationshipType): Boolean {
        return driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                val summary = tx.run(
                    """
                    MATCH (from {id: ${"$"}fromId})-[r:RELATION {type: ${"$"}type}]->(to {id: ${"$"}toId})
                    WITH r LIMIT 1
                    DELETE r
                    """.trimIndent(),
                    Values.parameters(
                        "fromId", fromId.toString(),
                        "toId", toId.toString(),
                        "type", type.value
                    )
                ).consume()
                summary.counters().relationshipsDeleted() > 0
            }
        }
    }

    fun updateRelationshipEndpoints(
        fromId: UUID,
        toId: UUID,
        type: RelationshipType,
        newFromId: UUID,
        newToId: UUID
    ): Boolean {
        return driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                val summary = tx.run(
                    """
                    MATCH (oldFrom {id: ${"$"}fromId})-[r:RELATION {type: ${"$"}type}]->(oldTo {id: ${"$"}toId})
                    MATCH (newFrom {id: ${"$"}newFromId}), (newTo {id: ${"$"}newToId})
                    CREATE (newFrom)-[newRel:RELATION]->(newTo)
                    SET newRel = r
                    DELETE r
                    """.trimIndent(),
                    Values.parameters(
                        "fromId", fromId.toString(),
                        "toId", toId.toString(),
                        "type", type.value,
                        "newFromId", newFromId.toString(),
                        "newToId", newToId.toString()
                    )
                ).consume()
                summary.counters().relationshipsCreated() > 0 && summary.counters().relationshipsDeleted() > 0
            }
        }
    }

    private fun createStaticNode(dto: StaticNodeDto) {
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    CREATE (n:Static {id: ${"$"}id, kind: ${"$"}kind, type: ${"$"}type, name: ${"$"}name, metadata: ${"$"}metadata})
                    """.trimIndent(),
                    Values.parameters(
                        "id", dto.id.toString(),
                        "kind", dto.kind.value,
                        "type", dto.type,
                        "name", dto.name,
                        "metadata", GraphJson.encodeMap(dto.metadata)
                    )
                ).consume()
            }
        }
    }

    private fun createActivityNode(dto: ActivityNodeDto) {
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    CREATE (n:Activity {id: ${"$"}id, kind: ${"$"}kind, type: ${"$"}type, name: ${"$"}name, status: ${"$"}status, options: ${"$"}options, contract: ${"$"}contract, expiresAt: ${"$"}expiresAt, metadata: ${"$"}metadata})
                    """.trimIndent(),
                    Values.parameters(
                        "id", dto.id.toString(),
                        "kind", dto.kind.value,
                        "type", dto.type,
                        "name", dto.name,
                        "status", dto.status.value,
                        "options", dto.options,
                        "contract", GraphJson.encodeListOfMaps(dto.contract),
                        "expiresAt", dto.expiresAt?.atOffset(ZoneOffset.UTC),
                        "metadata", GraphJson.encodeMap(dto.metadata)
                    )
                ).consume()
            }
        }
    }

    private fun createUserNode(dto: UserNodeDto) {
        driver.session(SessionConfig.defaultConfig()).use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    CREATE (n:User {id: ${"$"}id, kind: ${"$"}kind, type: ${"$"}type, name: ${"$"}name, displayName: ${"$"}displayName, userId: ${"$"}userId, metadata: ${"$"}metadata})
                    """.trimIndent(),
                    Values.parameters(
                        "id", dto.id.toString(),
                        "kind", dto.kind.value,
                        "type", dto.type,
                        "name", dto.name,
                        "displayName", dto.displayName,
                        "userId", dto.userId.toString(),
                        "metadata", GraphJson.encodeMap(dto.metadata)
                    )
                ).consume()
            }
        }
    }

    private fun Node.toStaticNode(): StaticNode {
        return StaticNode(
            id = UUID.fromString(get("id").asString()),
            staticType = StaticNodeType.entries.first { it.value == get("type").asString() },
            name = get("name").asString(),
            metadata = GraphJson.decodeMap(get("metadata").asString())
        )
    }

    private fun Node.toActivityNode(): ActivityNode {
        val expiresAtValue = if (containsKey("expiresAt") && !get("expiresAt").isNull) {
            get("expiresAt").asOffsetDateTime().toInstant()
        } else {
            null
        }
        return ActivityNode(
            id = UUID.fromString(get("id").asString()),
            activityType = ActivityType.entries.first { it.value == get("type").asString() },
            name = get("name").asString(),
            status = get("status").asString(),
            options = get("options").asList { it.asString() },
            contract = GraphJson.decodeListOfMaps(get("contract").asString()),
            expiresAt = expiresAtValue,
            metadata = GraphJson.decodeMap(get("metadata").asString())
        )
    }

    private fun Node.toUserNode(): UserNode {
        return UserNode(
            id = UUID.fromString(get("id").asString()),
            name = get("name").asString(),
            displayName = get("displayName").asString(),
            userId = UUID.fromString(get("userId").asString()),
            metadata = GraphJson.decodeMap(get("metadata").asString())
        )
    }

    private fun Relationship.toGraphRelationship(fromId: UUID, toId: UUID): GraphRelationship {
        val createdAtValue = if (!get("createdAt").isNull) {
            get("createdAt").asOffsetDateTime().toInstant()
        } else {
            Instant.EPOCH
        }
        return GraphRelationship(
            fromId = fromId,
            toId = toId,
            type = RelationshipType.entries.first { it.value == get("type").asString() },
            weight = get("weight").asDouble(),
            createdAt = createdAtValue,
            meta = GraphJson.decodeMap(get("meta").asString())
        )
    }
}
