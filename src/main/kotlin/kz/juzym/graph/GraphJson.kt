package kz.juzym.graph

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object GraphJson {
    private val mapper = jacksonObjectMapper()

    private val mapType = object : TypeReference<Map<String, Any?>>() {}
    private val listOfMapsType = object : TypeReference<List<Map<String, Any?>>>() {}

    fun encodeMap(value: Map<String, Any?>): String = mapper.writeValueAsString(value)

    fun decodeMap(value: String?): Map<String, Any?> {
        if (value.isNullOrBlank()) return emptyMap()
        return mapper.readValue(value, mapType)
    }

    fun encodeListOfMaps(value: List<Map<String, Any?>>): String = mapper.writeValueAsString(value)

    fun decodeListOfMaps(value: String?): List<Map<String, Any?>> {
        if (value.isNullOrBlank()) return emptyList()
        return mapper.readValue(value, listOfMapsType)
    }
}
