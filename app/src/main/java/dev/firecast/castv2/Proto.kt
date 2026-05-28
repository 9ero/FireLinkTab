package dev.firecast.castv2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Minimal protobuf codec for Cast v2 messages — avoids the protobuf gradle plugin.
// Only covers the field types used by the Cast protocol.

data class CastMessage(
    val protocolVersion: Int = 0,
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadType: Int,           // 0 = STRING, 1 = BINARY
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
) {
    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            val fields = ProtoReader(bytes).readAll()
            return CastMessage(
                protocolVersion = (fields[1] as? Long)?.toInt() ?: 0,
                sourceId        = fields[2] as? String ?: "",
                destinationId   = fields[3] as? String ?: "",
                namespace       = fields[4] as? String ?: "",
                payloadType     = (fields[5] as? Long)?.toInt() ?: 0,
                payloadUtf8     = fields[6] as? String,
                payloadBinary   = fields[7] as? ByteArray,
            )
        }
    }

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeVarint(1, protocolVersion.toLong())
        w.writeString(2, sourceId)
        w.writeString(3, destinationId)
        w.writeString(4, namespace)
        w.writeVarint(5, payloadType.toLong())
        payloadUtf8?.let   { w.writeString(6, it) }
        payloadBinary?.let { w.writeBytes(7, it) }
        return w.toByteArray()
    }
}

// DeviceAuthMessage — used in urn:x-cast:com.google.cast.tp.deviceauth namespace
data class DeviceAuthMessage(
    val challenge: AuthChallenge? = null,
    val response: AuthResponse?   = null,
    val error: AuthError?         = null,
) {
    companion object {
        fun decode(bytes: ByteArray): DeviceAuthMessage {
            val fields = ProtoReader(bytes).readAll()
            return DeviceAuthMessage(
                challenge = (fields[1] as? ByteArray)?.let { AuthChallenge.decode(it) },
                response  = (fields[2] as? ByteArray)?.let { AuthResponse.decode(it) },
                error     = (fields[3] as? ByteArray)?.let { AuthError.decode(it) },
            )
        }
    }
    fun encode(): ByteArray {
        val w = ProtoWriter()
        challenge?.let { w.writeBytes(1, it.encode()) }
        response?.let  { w.writeBytes(2, it.encode()) }
        error?.let     { w.writeBytes(3, it.encode()) }
        return w.toByteArray()
    }
}

data class AuthChallenge(
    val signatureAlgorithm: Int = 0,  // 0 = RSASSA_PKCS1v15
    val senderNonce: ByteArray? = null,
    val hashAlgorithm: Int = 0,       // 0 = SHA1
) {
    companion object {
        fun decode(bytes: ByteArray): AuthChallenge {
            val f = ProtoReader(bytes).readAll()
            return AuthChallenge(
                signatureAlgorithm = (f[1] as? Long)?.toInt() ?: 0,
                senderNonce        = f[2] as? ByteArray,
                hashAlgorithm      = (f[3] as? Long)?.toInt() ?: 0,
            )
        }
    }
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeVarint(1, signatureAlgorithm.toLong())
        senderNonce?.let { w.writeBytes(2, it) }
        w.writeVarint(3, hashAlgorithm.toLong())
        return w.toByteArray()
    }
}

data class AuthResponse(
    val signature: ByteArray,
    val clientAuthCertificate: ByteArray,
    val intermediateCertificates: List<ByteArray> = emptyList(),
    val signatureAlgorithm: Int = 0,
    val senderNonce: ByteArray? = null,
    val hashAlgorithm: Int = 0,
) {
    companion object {
        fun decode(bytes: ByteArray): AuthResponse {
            val f = ProtoReader(bytes).readAllList()
            return AuthResponse(
                signature                 = (f[1]?.firstOrNull() as? ByteArray) ?: ByteArray(0),
                clientAuthCertificate     = (f[2]?.firstOrNull() as? ByteArray) ?: ByteArray(0),
                intermediateCertificates  = f[3]?.filterIsInstance<ByteArray>() ?: emptyList(),
                signatureAlgorithm        = ((f[4]?.firstOrNull() as? Long) ?: 0L).toInt(),
                senderNonce               = f[5]?.firstOrNull() as? ByteArray,
                hashAlgorithm             = ((f[6]?.firstOrNull() as? Long) ?: 0L).toInt(),
            )
        }
    }
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeBytes(1, signature)
        w.writeBytes(2, clientAuthCertificate)
        intermediateCertificates.forEach { w.writeBytes(3, it) }
        w.writeVarint(4, signatureAlgorithm.toLong())
        senderNonce?.let { w.writeBytes(5, it) }
        w.writeVarint(6, hashAlgorithm.toLong())
        return w.toByteArray()
    }
}

data class AuthError(val errorType: Int = 0) {
    companion object {
        fun decode(bytes: ByteArray): AuthError {
            val f = ProtoReader(bytes).readAll()
            return AuthError((f[1] as? Long)?.toInt() ?: 0)
        }
    }
    fun encode(): ByteArray = ProtoWriter().also { it.writeVarint(1, errorType.toLong()) }.toByteArray()
}

// ── Proto reader ──────────────────────────────────────────────────────────────

private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun readAll(): Map<Int, Any> {
        val map = mutableMapOf<Int, Any>()
        while (pos < bytes.size) {
            val tag     = readVarint().toInt()
            val field   = tag ushr 3
            val wire    = tag and 7
            when (wire) {
                0 -> map[field] = readVarint()
                2 -> {
                    val len  = readVarint().toInt()
                    val data = bytes.copyOfRange(pos, pos + len)
                    pos += len
                    map[field] = tryString(data) ?: data
                }
                else -> break
            }
        }
        return map
    }

    fun readAllList(): Map<Int, MutableList<Any>> {
        val map = mutableMapOf<Int, MutableList<Any>>()
        while (pos < bytes.size) {
            val tag   = readVarint().toInt()
            val field = tag ushr 3
            val wire  = tag and 7
            when (wire) {
                0 -> map.getOrPut(field) { mutableListOf() }.add(readVarint())
                2 -> {
                    val len  = readVarint().toInt()
                    val data = bytes.copyOfRange(pos, pos + len)
                    pos += len
                    map.getOrPut(field) { mutableListOf() }.add(tryString(data) ?: data)
                }
                else -> break
            }
        }
        return map
    }

    private fun readVarint(): Long {
        var result = 0L; var shift = 0
        while (true) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun tryString(data: ByteArray): String? = try {
        val s = String(data, Charsets.UTF_8)
        if (s.all { it.code in 9..126 || it.code > 127 }) s else null
    } catch (e: Exception) { null }
}

// ── Proto writer ──────────────────────────────────────────────────────────────

class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    fun writeVarint(field: Int, value: Long) {
        writeRawVarint((field.toLong() shl 3) or 0L)
        writeRawVarint(value)
    }

    fun writeString(field: Int, value: String) = writeBytes(field, value.toByteArray(Charsets.UTF_8))

    fun writeBytes(field: Int, value: ByteArray) {
        writeRawVarint((field.toLong() shl 3) or 2L)
        writeRawVarint(value.size.toLong())
        buf.write(value)
    }

    private fun writeRawVarint(v: Long) {
        var value = v
        while (value and 0x7F.toLong().inv() != 0L) {
            buf.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
        buf.write(value.toInt())
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}

// ── Frame helpers ─────────────────────────────────────────────────────────────

fun ByteArray.toFrame(): ByteArray {
    val frame = ByteArray(4 + size)
    ByteBuffer.wrap(frame).putInt(size)
    System.arraycopy(this, 0, frame, 4, size)
    return frame
}
