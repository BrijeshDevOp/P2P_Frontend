package com.codewithkael.webrtcdatachannelty.utils

import android.graphics.BitmapFactory
import android.util.Log
import org.webrtc.DataChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ReceivedFile(
    val bytes: ByteArray,
    val mimeType: String?,
    val fileName: String?
)

class DataConverter {
    private val TAG = "DataConverter"

    // Conservative chunk size to avoid exceeding typical SCTP message limits
    private val CHUNK_SIZE_BYTES = 16_000

    // In-memory assemblies for concurrently received messages keyed by messageId
    private val incomingAssemblies = mutableMapOf<String, IncomingAssembly>()

    private data class IncomingAssembly(
        val type: String,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var receivedCount: Int = 0,
        var mimeType: String? = null,
        var fileName: String? = null
    )

    // Public API: build one or more framed buffers for sending text
    fun buildFramesForText(text: String): List<DataChannel.Buffer> {
        val data = text.toByteArray(Charsets.UTF_8)
        return buildFramedBuffers("TEXT", data)
    }

    // Public API: build one or more framed buffers for sending image file path
    fun buildFramesForImage(path: String): List<DataChannel.Buffer> {
        val data = File(path).readBytes()
        return buildFramedBuffers("IMAGE", data, mimeType = "image/*", fileName = File(path).name)
    }

    fun buildFramesForBinary(type: String, data: ByteArray, mimeType: String?, fileName: String?): List<DataChannel.Buffer> {
        return buildFramedBuffers(type, data, mimeType, fileName)
    }

    // Parse a single framed incoming buffer. Returns a completed message when all chunks arrive.
    fun consumeFrame(buffer: DataChannel.Buffer): Pair<String, Any>? {
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)

        // Find header terminator (first \n)
        val newlineIndex = bytes.indexOf('\n'.code.toByte())
        if (newlineIndex <= 0) {
            Log.e(TAG, "Malformed frame: missing header terminator")
            return null
        }

        val headerString = String(bytes, 0, newlineIndex, Charsets.UTF_8)
        if (!headerString.startsWith("HDR|")) {
            Log.e(TAG, "Malformed frame: header prefix not found")
            return null
        }

        val parts = headerString.split('|')
        if (parts.size != 6) {
            Log.e(TAG, "Malformed frame header: $headerString")
            return null
        }

        val type = parts[1] // TEXT or IMAGE
        val messageId = parts[2]
        val chunkIndex = parts[3].toIntOrNull() ?: return null
        val totalChunks = parts[4].toIntOrNull() ?: return null
        val flags = parts[5] // may include mime and name
        val flagMap = parseFlags(flags)

        val payload = bytes.copyOfRange(newlineIndex + 1, bytes.size)

        val assembly = incomingAssemblies.getOrPut(messageId) {
            IncomingAssembly(type = type, totalChunks = totalChunks).apply {
                mimeType = flagMap["mime"]
                fileName = flagMap["name"]
            }
        }

        if (!assembly.chunks.containsKey(chunkIndex)) {
            assembly.chunks[chunkIndex] = payload
            assembly.receivedCount += 1
        }

        if (assembly.receivedCount == assembly.totalChunks) {
            // Assemble in order
            val output = ByteArrayOutputStream()
            for (i in 0 until assembly.totalChunks) {
                val chunk = assembly.chunks[i]
                if (chunk == null) {
                    Log.e(TAG, "Missing chunk $i for message $messageId")
                    incomingAssemblies.remove(messageId)
                    return null
                }
                output.write(chunk)
            }
            incomingAssemblies.remove(messageId)
            val completeBytes = output.toByteArray()
            return when (assembly.type) {
                "TEXT" -> "TEXT" to String(completeBytes, Charsets.UTF_8)
                "IMAGE" -> {
                    val bitmap = BitmapFactory.decodeByteArray(completeBytes, 0, completeBytes.size)
                    "IMAGE" to bitmap
                }
                "VIDEO" -> "VIDEO" to ReceivedFile(completeBytes, assembly.mimeType, assembly.fileName)
                "FILE" -> "FILE" to ReceivedFile(completeBytes, assembly.mimeType, assembly.fileName)
                else -> null
            }
        }

        return null
    }

    // Internal: split into chunks and frame each with a compact text header
    private fun buildFramedBuffers(type: String, data: ByteArray, mimeType: String? = null, fileName: String? = null): List<DataChannel.Buffer> {
        val messageId = UUID.randomUUID().toString()
        val totalChunks = if (data.isEmpty()) 1 else ((data.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES)
        val buffers = ArrayList<DataChannel.Buffer>(totalChunks)

        var offset = 0
        var index = 0
        while (offset < data.size || (data.isEmpty() && index == 0)) {
            val remaining = data.size - offset
            val take = if (data.isEmpty()) 0 else minOf(CHUNK_SIZE_BYTES, remaining)
            val payload = if (take > 0) data.copyOfRange(offset, offset + take) else ByteArray(0)

            val flags = buildFlags(mimeType, fileName)
            val header = "HDR|$type|$messageId|$index|$totalChunks|$flags\n"
            val headerBytes = header.toByteArray(Charsets.UTF_8)

            val frame = ByteBuffer.allocate(headerBytes.size + payload.size)
            frame.put(headerBytes)
            if (payload.isNotEmpty()) frame.put(payload)
            frame.flip()

            buffers.add(DataChannel.Buffer(frame, false))

            offset += take
            index += 1
            if (data.isEmpty()) break
        }

        return buffers
    }

    private fun buildFlags(mimeType: String?, fileName: String?): String {
        val parts = mutableListOf<String>()
        mimeType?.let { parts.add("mime=" + it.replace('|', '_').replace('\n', ' ')) }
        fileName?.let { parts.add("name=" + it.replace('|', '_').replace('\n', ' ')) }
        return if (parts.isEmpty()) "0" else parts.joinToString(";")
    }

    private fun parseFlags(flags: String): Map<String, String> {
        if (flags == "0" || flags.isEmpty()) return emptyMap()
        return flags.split(';').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
    }
}