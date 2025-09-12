package com.ebani.sinage.net.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
/* ---- Helper serializer if you embed arbitrary objects in 'resolution' ---- */
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** ===== OUTGOING (device -> server) ===== */

@Serializable
data class DeviceHandshake(
    val type: String,
    val deviceId: String
)

@Serializable
data class PairingStatusOut(
    val type: String = "pairing_status",
    val active: Boolean,
    val code: String,
    val ttlSec: Int,
    val deviceId: String,
    // optional extras your server might use (e.g., resolution blob)
    val resolution: Map<String, @Serializable(with = AnyAsString::class) Any>? = null
)

/** ===== INCOMING (server -> device), sealed by "type" ===== */

@Serializable
sealed interface PairingMessage

@Serializable @SerialName("ping")
data class MsgPing(val at: Long) : PairingMessage

@Serializable @SerialName("handshake_ok")
data class MsgHandshakeOk(val socketId: String) : PairingMessage

@Serializable @SerialName("handshake_error")
data class MsgHandshakeError(val reason: String) : PairingMessage


@Serializable @SerialName("pairing_status_ok")
class MsgPairingStatusOk : PairingMessage

@Serializable @SerialName("pairing_status_error")
data class MsgPairingStatusError(val reason: String) : PairingMessage

/** If the server ever echoes/shows a code */
@Serializable @SerialName("pairing_code")
data class MsgPairingCode(val code: String) : PairingMessage

/** Tell device it’s registered (or that content changed) */
@Serializable @SerialName("registered")
class MsgRegistered : PairingMessage

@Serializable @SerialName("content_update")
class MsgContentUpdate : PairingMessage





object AnyAsString : KSerializer<Any> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AnyAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Any) {
        encoder.encodeString(value.toString())
    }
    override fun deserialize(decoder: Decoder): Any = decoder.decodeString()
}
