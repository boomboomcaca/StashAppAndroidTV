package com.github.damontecres.stashapp.util

import android.util.Log
import com.apollographql.apollo.api.Optional
import com.chrynan.parcelable.core.Parcelable
import com.github.damontecres.stashapp.api.type.CriterionModifier
import com.github.damontecres.stashapp.api.type.CustomFieldCriterionInput
import com.github.damontecres.stashapp.suppliers.FilterArgs
import com.github.damontecres.stashapp.suppliers.FilterArgsSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import org.json.JSONArray
import org.json.JSONObject

val OptionalSerializersModule =
    SerializersModule {
        contextual(Optional::class) { args -> 
            // Special handling for Optional<Any?>
            val typeArg = args.getOrNull(0)
            if (typeArg != null) {
                val typeArgStr = typeArg.toString()
                // Check if the type argument is Any? or contains Any
                if (typeArgStr == "kotlin.Any?" || typeArgStr == "Any?" || 
                    typeArgStr.contains("Any") || typeArgStr.contains("kotlin.Any")) {
                    OptionalAnySerializer()
                } else {
                    OptionalSerializer(args[0])
                }
            } else {
                OptionalSerializer(args[0])
            }
        }
        contextual(CustomFieldCriterionInput::class) { _ -> CustomFieldCriterionInputSerializer() }
        contextual(Any::class) { _ -> AnySerializer() }
        contextual(FilterArgs::class) { _ -> FilterArgsSerializer }
    }

/**
 * Contextual [Parcelable] with [OptionalSerializer]
 */
@OptIn(ExperimentalSerializationApi::class)
val StashParcelable =
    Parcelable {
        serializersModule = OptionalSerializersModule
    }

/**
 * Serializes [Optional]s
 *
 * Basically just writes a boolean for whether the [Optional] is present or absent before the value
 */
class OptionalSerializer<T>(
    private val dataSerializer: KSerializer<T>,
) : KSerializer<Optional<T>> {
    override val descriptor: SerialDescriptor
        get() = dataSerializer.descriptor

    override fun deserialize(decoder: Decoder): Optional<T> =
        if (decoder.decodeBoolean()) {
            Optional.present(dataSerializer.deserialize(decoder))
        } else {
            Optional.Absent
        }

    override fun serialize(
        encoder: Encoder,
        value: Optional<T>,
    ) {
        if (value == Optional.Absent) {
            encoder.encodeBoolean(false)
        } else {
            encoder.encodeBoolean(true)
            dataSerializer.serialize(encoder, value.getOrNull()!!)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
class CustomFieldCriterionInputSerializer : KSerializer<CustomFieldCriterionInput?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(CustomFieldCriterionInput::class.qualifiedName!!) {
            element<String>("field")
            element<CriterionModifier>("modifier")
            // TODO
            // element<List<Any>?>("value")
        }

    override fun deserialize(decoder: Decoder): CustomFieldCriterionInput? =
        if (decoder.decodeNotNullMark()) {
            val field = decoder.decodeString()
            val modifier = CriterionModifier.entries[decoder.decodeInt()]
            val value =
                if (decoder.decodeBoolean()) {
                    val size = decoder.decodeInt()
                    val list =
                        buildList {
                            for (i in 0..<size) {
                                val type = decoder.decodeInt()
                                val itemValue =
                                    when (type) {
                                        STRING_TYPE -> {
                                            decoder.decodeString()
                                        }

                                        INT_TYPE -> {
                                            decoder.decodeInt()
                                        }

                                        DOUBLE_TYPE -> {
                                            decoder.decodeDouble()
                                        }

                                        FLOAT_TYPE -> {
                                            decoder.decodeFloat()
                                        }

                                        else -> {
                                            val msg = "Unknown decoding type: $type"
                                            Log.e(TAG, msg)
                                            throw UnsupportedOperationException(msg)
                                        }
                                    }
                                add(itemValue)
                            }
                        }
                    list
                } else {
                    null
                }
            CustomFieldCriterionInput(
                field = field,
                modifier = modifier,
                value = Optional.presentIfNotNull(value),
            )
        } else {
            decoder.decodeNull()
        }

    override fun serialize(
        encoder: Encoder,
        value: CustomFieldCriterionInput?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeNotNullMark()
            encoder.encodeString(value.field)
            encoder.encodeInt(value.modifier.ordinal)
            if (value.value.getOrNull() == null) {
                encoder.encodeBoolean(false)
            } else {
                encoder.encodeBoolean(true)
                encoder.encodeInt(value.value.getOrNull()!!.size)
                value.value.getOrNull()!!.forEach {
                    when (it) {
                        is String -> {
                            encoder.encodeInt(STRING_TYPE)
                            encoder.encodeString(it)
                        }

                        is Int -> {
                            encoder.encodeInt(INT_TYPE)
                            encoder.encodeInt(it)
                        }

                        is Double -> {
                            encoder.encodeInt(DOUBLE_TYPE)
                            encoder.encodeDouble(it)
                        }

                        is Float -> {
                            encoder.encodeInt(FLOAT_TYPE)
                            encoder.encodeFloat(it)
                        }

                        else -> {
                            val msg = "Unknown custom field type: ${it::class}"
                            Log.e(TAG, msg)
                            throw UnsupportedOperationException(msg)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CustomFieldCriterionInputSerializer"

        private const val STRING_TYPE = 0
        private const val INT_TYPE = 1
        private const val DOUBLE_TYPE = 2
        private const val FLOAT_TYPE = 3
    }
}

/**
 * Special serializer for Optional<Any?> to handle Map/JSON types in GraphQL
 * This is registered in SerializersModule to handle Optional<Any?> at runtime
 */
@OptIn(ExperimentalSerializationApi::class)
class OptionalAnySerializer : KSerializer<Optional<Any?>> {
    private val anySerializer = AnySerializer()
    
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Optional<Any?>") {
            // Dynamic type, no fixed structure
        }

    override fun deserialize(decoder: Decoder): Optional<Any?> {
        return if (decoder.decodeBoolean()) {
            Optional.present(anySerializer.deserialize(decoder))
        } else {
            Optional.Absent
        }
    }

    override fun serialize(encoder: Encoder, value: Optional<Any?>) {
        if (value == Optional.Absent) {
            encoder.encodeBoolean(false)
        } else {
            encoder.encodeBoolean(true)
            anySerializer.serialize(encoder, value.getOrNull())
        }
    }
}

/**
 * Serializer for Any type (used for Map/JSON types in GraphQL)
 * Serializes as JSON using org.json for compatibility
 */
@OptIn(ExperimentalSerializationApi::class)
class AnySerializer : KSerializer<Any?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Any") {
            // Dynamic type, no fixed structure
        }

    override fun deserialize(decoder: Decoder): Any? {
        // Decode as JSON string and parse
        val jsonString = decoder.decodeString()
        return try {
            when {
                jsonString.trimStart().startsWith("{") -> JSONObject(jsonString)
                jsonString.trimStart().startsWith("[") -> JSONArray(jsonString)
                else -> jsonString
            }
        } catch (e: Exception) {
            jsonString // Fallback to string if parsing fails
        }
    }

    override fun serialize(encoder: Encoder, value: Any?) {
        when (value) {
            null -> encoder.encodeNull()
            is String -> encoder.encodeString(value)
            is Number -> encoder.encodeString(value.toString())
            is Boolean -> encoder.encodeString(value.toString())
            is JSONObject -> encoder.encodeString(value.toString())
            is JSONArray -> encoder.encodeString(value.toString())
            is Map<*, *> -> {
                // Convert Map to JSONObject
                val jsonObj = JSONObject()
                value.forEach { (k, v) ->
                    jsonObj.put(k.toString(), v)
                }
                encoder.encodeString(jsonObj.toString())
            }
            is List<*> -> {
                // Convert List to JSONArray
                val jsonArray = JSONArray()
                value.forEach { jsonArray.put(it) }
                encoder.encodeString(jsonArray.toString())
            }
            else -> encoder.encodeString(value.toString())
        }
    }
}
