package xyz.nulldev.ts.api.http.serializer

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.asLog
import tachiyomi.core.common.util.system.logcat
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf

class FilterSerializer {
    private val serializers = listOf<Serializer<*>>(
        // SY -->
        AutoCompleteSerializer(this),
        // SY <--
        HeaderSerializer(this),
        SeparatorSerializer(this),
        SelectSerializer(this),
        TextSerializer(this),
        CheckboxSerializer(this),
        TriStateSerializer(this),
        GroupSerializer(this),
        SortSerializer(this),
    )

    fun serialize(filters: FilterList) = buildJsonArray {
        filters.filterIsInstance<Filter<Any?>>().forEach {
            add(serialize(it))
        }
    }

    fun serialize(filter: Filter<Any?>): JsonObject {
        return serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull {
                filter::class.isSubclassOf(it.clazz)
            }?.let { serializer ->
                buildJsonObject {
                    with(serializer) { serialize(filter) }

                    val classMappings = mutableListOf<Pair<String, Any>>()

                    serializer.mappings().forEach {
                        val res = it.second.get(filter)
                        put(it.first, res.toString())
                        classMappings += it.first to (res?.javaClass?.name ?: "null")
                    }

                    putJsonObject(CLASS_MAPPINGS) {
                        classMappings.forEach { (t, u) ->
                            put(t, u.toString())
                        }
                    }

                    put(TYPE, serializer.type)
                }
            } ?: throw IllegalArgumentException("Cannot serialize this Filter object!")
    }

    fun deserialize(filters: FilterList, json: JsonArray) {
        filters.filterIsInstance<Filter<Any?>>().zip(json).forEach { (filter, obj) ->
            // KMK -->
            try {
                // KMK <--
                deserialize(filter, obj.jsonObject)
                // KMK -->
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { e.asLog() }
            }
            // KMK <--
        }
    }

    fun deserializeIfCompatible(filters: FilterList, json: JsonArray): Boolean {
        val defaults = runCatching { serialize(filters) }.getOrNull() ?: return false
        if (!compatibleSnapshot(defaults, json)) return false

        return try {
            deserializeSnapshot(filters, json)
            true
        } catch (_: Exception) {
            runCatching { deserializeSnapshot(filters, defaults) }
            false
        }
    }

    private fun deserializeSnapshot(filters: FilterList, json: JsonArray) {
        filters.filterIsInstance<Filter<Any?>>().zip(json).forEach { (filter, value) ->
            deserialize(filter, value.jsonObject)
        }
    }

    fun deserialize(filter: Filter<Any?>, json: JsonObject) {
        val serializer = serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull {
                it.type == json[TYPE]!!.jsonPrimitive.content
            } ?: throw IllegalArgumentException("Cannot deserialize this type!")

        serializer.deserialize(json, filter)

        serializer.mappings().forEach {
            if (it.second is KMutableProperty1) {
                val obj = json[it.first]!!.jsonPrimitive
                val res: Any? = when (json[CLASS_MAPPINGS]!!.jsonObject[it.first]!!.jsonPrimitive.content) {
                    java.lang.Integer::class.java.name -> obj.int
                    java.lang.Long::class.java.name -> obj.long
                    java.lang.Float::class.java.name -> obj.float
                    java.lang.Double::class.java.name -> obj.double
                    java.lang.String::class.java.name -> obj.content
                    java.lang.Boolean::class.java.name -> obj.boolean
                    java.lang.Byte::class.java.name -> obj.content.toByte()
                    java.lang.Short::class.java.name -> obj.content.toShort()
                    java.lang.Character::class.java.name -> obj.content[0]
                    "null" -> null
                    else -> throw IllegalArgumentException("Cannot deserialize this type!")
                }
                @Suppress("UNCHECKED_CAST")
                (it.second as KMutableProperty1<in Filter<Any?>, in Any?>).set(filter, res)
            }
        }
    }

    companion object {
        const val TYPE = "_type"
        const val CLASS_MAPPINGS = "_cmaps"
    }

    private fun compatibleSnapshot(current: JsonArray, saved: JsonArray): Boolean =
        current.size == saved.size && current.zip(saved).all { (currentFilter, savedFilter) ->
            if (currentFilter is JsonNull || savedFilter is JsonNull) {
                currentFilter == savedFilter
            } else {
                compatibleFilter(currentFilter.jsonObject, savedFilter.jsonObject)
            }
        }

    private fun compatibleFilter(current: JsonObject, saved: JsonObject): Boolean {
        val currentFields = current.filterKeys { it != "state" && it != CLASS_MAPPINGS }
        val savedFields = saved.filterKeys { it != "state" && it != CLASS_MAPPINGS }
        if (currentFields != savedFields) return false

        val currentMappings = current[CLASS_MAPPINGS]?.jsonObject ?: return false
        val savedMappings = saved[CLASS_MAPPINGS]?.jsonObject ?: return false
        if (currentMappings.keys != savedMappings.keys) return false
        if (currentMappings.filterKeys { it != "state" } != savedMappings.filterKeys { it != "state" }) return false
        if (currentMappings["state"] != savedMappings["state"]) return false

        val currentState = current["state"] ?: return saved["state"] == null
        val savedState = saved["state"] ?: return false
        return when (current[TYPE]?.jsonPrimitive?.content) {
            "GROUP" -> compatibleGroupState(currentState, savedState)
            "SELECT" -> compatibleIndexState(current, savedState)
            "TRISTATE" -> savedState.jsonPrimitive.intOrNull in 0..2
            "CHECKBOX" -> savedState.jsonPrimitive.content.toBooleanStrictOrNull() != null
            "TEXT" -> savedState.jsonPrimitive.isString
            "AUTOCOMPLETE" -> savedState.jsonArray.all { it.jsonPrimitive.isString }
            "SORT" -> compatibleSortState(current, savedState)
            else -> currentState::class == savedState::class
        }
    }

    private fun compatibleGroupState(current: JsonElement, saved: JsonElement): Boolean {
        val currentChildren = current.jsonArray
        val savedChildren = saved.jsonArray
        return currentChildren.size == savedChildren.size && currentChildren.zip(savedChildren).all { (left, right) ->
            when {
                left is JsonNull || right is JsonNull -> left == right
                else -> compatibleFilter(left.jsonObject, right.jsonObject)
            }
        }
    }

    private fun compatibleIndexState(current: JsonObject, saved: JsonElement): Boolean {
        val index = saved.jsonPrimitive.intOrNull ?: return false
        val values = current["values"]?.jsonArray ?: return false
        return index in values.indices
    }

    private fun compatibleSortState(current: JsonObject, saved: JsonElement): Boolean {
        if (saved is JsonNull) return true
        val selection = saved.jsonObject
        val index = selection["index"]?.jsonPrimitive?.intOrNull ?: return false
        if (selection["ascending"]?.jsonPrimitive?.booleanOrNull == null) return false
        val values = current["values"]?.jsonArray ?: return false
        return index in values.indices
    }
}
