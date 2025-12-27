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
/**
 * Represents a filter that can be used to create a [StashPagingSource.DataSupplier].
 *
 * Optionally, a find filter and/or object filter can be provided, otherwise they will default the data supplier's implementation.
 *
 * Optionally, a [DataSupplierOverride] can be provided which always overrides which data supplier to use.
 *
 * Note: Custom serializer used because Apollo-generated StashDataFilter lacks serializer support.
 * The objectFilter field is not serialized.
 */
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
)

/**
 * Custom serializer for FilterArgs using surrogate pattern.
 * The objectFilter field is not serialized.
 */
object FilterArgsSerializer : KSerializer<FilterArgs> {
    override val descriptor: SerialDescriptor = FilterArgsSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FilterArgs) {
        val surrogate = FilterArgsSurrogate(
            dataType = value.dataType,
            name = value.name,
            findFilter = value.findFilter,
            override = value.override
        )
        encoder.encodeSerializableValue(FilterArgsSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): FilterArgs {
        val surrogate = decoder.decodeSerializableValue(FilterArgsSurrogate.serializer())
        return FilterArgs(
            dataType = surrogate.dataType,
            name = surrogate.name,
            findFilter = surrogate.findFilter,
            objectFilter = null,
            override = surrogate.override
        )
    }
}
