package com.github.damontecres.stashapp.suppliers

import com.github.damontecres.stashapp.api.fragment.SavedFilter
import com.github.damontecres.stashapp.api.type.StashDataFilter
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.data.SortAndDirection
import com.github.damontecres.stashapp.data.StashFindFilter
import com.github.damontecres.stashapp.util.FilterParser
import com.github.damontecres.stashapp.util.toReadableString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import com.github.damontecres.stashapp.filter.output.FilterWriter
import com.github.damontecres.stashapp.util.Version
import kotlinx.coroutines.runBlocking
/**
 * Represents a filter that can be used to create a [StashPagingSource.DataSupplier].
 *
 * Optionally, a find filter and/or object filter can be provided, otherwise they will default the data supplier's implementation.
 *
 * Optionally, a [DataSupplierOverride] can be provided which always overrides which data supplier to use.
 *
 * Note: Custom serializer used because Apollo-generated StashDataFilter lacks serializer support.
 * The objectFilter field is now serialized to/from JSON.
 */
@Serializable(with = FilterArgsSerializer::class)
data class FilterArgs(
    val dataType: DataType,
    val name: String? = null,
    val findFilter: StashFindFilter? = null,
    val objectFilter: StashDataFilter? = null,
    val override: DataSupplierOverride? = null,
) {
    val sortAndDirection: SortAndDirection
        get() {
            return findFilter?.sortAndDirection
                ?: dataType.defaultSort
        }

    /**
     * Returns this [FilterArgs] with the specified [SortAndDirection]
     */
    fun with(newSortAndDirection: SortAndDirection): FilterArgs =
        this.copy(
            findFilter =
                this.findFilter?.copy(sortAndDirection = newSortAndDirection)
                    ?: StashFindFilter(sortAndDirection = newSortAndDirection),
        )

    /**
     * If the [sortAndDirection] is random, resolve it and return an updated [FilterArgs]
     */
    fun withResolvedRandom(): FilterArgs =
        if (sortAndDirection.isRandom) {
            with(sortAndDirection.withResolvedRandom())
        } else {
            this
        }

    fun withQuery(query: String?): FilterArgs =
        this.copy(
            findFilter =
                this.findFilter?.copy(q = query)
                    ?: StashFindFilter(q = query),
        )

    override fun toString(): String =
        "FilterArgs(dataType=$dataType, name=$name, override=$override, findFilter=$findFilter, objectFilter=${objectFilter?.toReadableString()})"
}

fun SavedFilter.toFilterArgs(filterParser: FilterParser): FilterArgs {
    val dataType = DataType.fromFilterMode(mode)!!
    val findFilter =
        if (find_filter != null) {
            StashFindFilter(
                find_filter.q,
                SortAndDirection.create(dataType, find_filter.sort, find_filter.direction),
            )
        } else {
            StashFindFilter(null, dataType.defaultSort)
        }.withResolvedRandom()
    val objectFilter = filterParser.convertFilter(dataType, object_filter)
    return FilterArgs(dataType, name.ifBlank { null }, findFilter, objectFilter)
}

/**
 * Surrogate class for FilterArgs serialization.
 * objectFilter is excluded because Apollo-generated types don't support kotlinx.serialization.
 */
@Serializable
private data class FilterArgsSurrogate(
    val dataType: DataType,
    val name: String? = null,
    val findFilter: StashFindFilter? = null,
    val override: DataSupplierOverride? = null,
    val serializedObjectFilter: String? = null,
)

/**
 * Custom serializer for FilterArgs using surrogate pattern.
 * The objectFilter field is not serialized.
 */
object FilterArgsSerializer : KSerializer<FilterArgs> {
    override val descriptor: SerialDescriptor = FilterArgsSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FilterArgs) {
        val serializedObjectFilter = value.objectFilter?.let { filter ->
            // Use runBlocking because FilterWriter is suspend
            runBlocking {
                val writer = FilterWriter(value.dataType) { _, _ -> emptyMap() }
                val filterMap = writer.convertFilter(filter)
                Json.encodeToJsonElement(filterMap).toString()
            }
        }

        val surrogate = FilterArgsSurrogate(
            dataType = value.dataType,
            name = value.name,
            findFilter = value.findFilter,
            override = value.override,
            serializedObjectFilter = serializedObjectFilter
        )
        encoder.encodeSerializableValue(FilterArgsSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): FilterArgs {
        val surrogate = decoder.decodeSerializableValue(FilterArgsSurrogate.serializer())
        val objectFilter = surrogate.serializedObjectFilter?.let { jsonString ->
            try {
                val json = Json.parseToJsonElement(jsonString).jsonObject
                val filterMap = jsonToMap(json)
                FilterParser(Version(0, 0, 0)).convertFilter(surrogate.dataType, filterMap)
            } catch (ex: Exception) {
                null
            }
        }

        return FilterArgs(
            dataType = surrogate.dataType,
            name = surrogate.name,
            findFilter = surrogate.findFilter,
            objectFilter = objectFilter,
            override = surrogate.override
        )
    }

    private fun jsonToMap(jsonObject: JsonObject): Map<String, Any?> {
        return jsonObject.mapValues { (_, value) ->
            // Simple conversion from JsonElement back to common types
            // This is a bit naive but should work for FilterArgs
            when {
                value is kotlinx.serialization.json.JsonPrimitive -> {
                    if (value.isString) value.content
                    else value.content.toBooleanStrictOrNull() ?: value.content.toDoubleOrNull() ?: value.content
                }
                value is kotlinx.serialization.json.JsonArray -> {
                    value.map { it.toString() } // MultiCriterion usually just needs IDs or strings
                }
                value is JsonObject -> jsonToMap(value)
                else -> value.toString()
            }
        }
    }
}
