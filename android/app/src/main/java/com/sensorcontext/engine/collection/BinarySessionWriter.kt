package com.sensorcontext.engine.collection

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Streaming writer for the versioned format documented in docs/binary_log_spec.md. */
class BinarySessionWriter(
    file: File,
    private val metadata: CollectionSessionMetadata,
) : Closeable {
    private val ownerThread = Thread.currentThread()
    private val fileStream: FileOutputStream
    private val output: DataOutputStream
    private var closed = false

    var recordCount: Long = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        fileStream = FileOutputStream(file, false)
        output = DataOutputStream(BufferedOutputStream(fileStream, BUFFER_SIZE_BYTES))
        writeHeader()
    }

    fun writeSensorEvent(
        elapsedRealtimeNanos: Long,
        sensorId: Int,
        accuracy: Int,
        values: FloatArray,
    ) {
        checkWritable()
        require(sensorId in 0..MAX_UNSIGNED_SHORT) { "sensorId out of range" }
        require(values.size <= MAX_VALUE_COUNT) { "too many sensor values" }
        writeFrame(RECORD_SENSOR_EVENT) {
            writeLong(elapsedRealtimeNanos)
            writeShort(sensorId)
            writeByte(accuracy.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()))
            writeByte(values.size)
            values.forEach(::writeFloat)
        }
        recordCount += 1
    }

    fun writeMarker(elapsedRealtimeNanos: Long, marker: String, note: String = "") {
        checkWritable()
        writeFrame(RECORD_MARKER) {
            writeLong(elapsedRealtimeNanos)
            writeUtf8(marker)
            writeUtf8(note)
        }
        recordCount += 1
    }

    fun flush(syncToDisk: Boolean = false) {
        checkThread()
        if (closed) return
        output.flush()
        if (syncToDisk) fileStream.fd.sync()
    }

    fun closeCleanly(endElapsedNanos: Long, endWallMillis: Long) {
        checkWritable()
        writeFrame(RECORD_CLEAN_END) {
            writeLong(endElapsedNanos)
            writeLong(endWallMillis)
            writeLong(recordCount)
        }
        finishClose()
    }

    override fun close() {
        checkThread()
        if (!closed) finishClose()
    }

    private fun writeHeader() {
        val payload = bytePayload {
            writeLong(metadata.sessionStartElapsedNanos)
            writeLong(metadata.sessionStartWallMillis)
            writeUtf8(metadata.sessionId)
            writeUtf8(metadata.labels.participantId)
            writeUtf8(metadata.labels.activity)
            writeUtf8(metadata.labels.posture)
            writeUtf8(metadata.labels.environment)
            writeUtf8(metadata.labels.light)
            writeUtf8(metadata.labels.note)
            writeUtf8(metadata.manufacturer)
            writeUtf8(metadata.model)
            writeUtf8(metadata.device)
            writeUtf8(metadata.buildFingerprint)
            writeUtf8(metadata.appVersion)
            require(metadata.sensors.size <= MAX_UNSIGNED_SHORT) { "too many sensors" }
            writeShort(metadata.sensors.size)
            metadata.sensors.forEach { descriptor -> writeDescriptor(descriptor) }
        }

        output.write(MAGIC)
        output.writeShort(FORMAT_VERSION)
        output.writeShort(0)
        output.writeInt(payload.size)
        output.write(payload)
    }

    private fun DataOutputStream.writeDescriptor(descriptor: RecordedSensorDescriptor) {
        require(descriptor.sensorId in 0..MAX_UNSIGNED_SHORT) { "sensorId out of range" }
        writeShort(descriptor.sensorId)
        writeInt(descriptor.androidType)
        writeInt(descriptor.reportingMode)
        writeInt(descriptor.minDelayUs)
        writeInt(descriptor.maxDelayUs)
        writeInt(descriptor.fifoMaxEventCount)
        writeFloat(descriptor.resolution)
        writeFloat(descriptor.maxRange)
        writeByte(if (descriptor.isWakeUp) FLAG_WAKE_UP else 0)
        writeUtf8(descriptor.name)
        writeUtf8(descriptor.vendor)
        writeUtf8(descriptor.stringType)
    }

    private fun writeFrame(recordType: Int, writePayload: DataOutputStream.() -> Unit) {
        val typePayload = bytePayload(writePayload)
        val frameLength = 1 + typePayload.size
        require(frameLength <= MAX_FRAME_BYTES) { "frame exceeds $MAX_FRAME_BYTES bytes" }
        output.writeInt(frameLength)
        output.writeByte(recordType)
        output.write(typePayload)
    }

    private fun bytePayload(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream -> stream.block() }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_UNSIGNED_SHORT) { "UTF-8 string is too long" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun checkWritable() {
        checkThread()
        check(!closed) { "writer is closed" }
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) {
            "BinarySessionWriter must only be used from its owner thread"
        }
    }

    private fun finishClose() {
        output.flush()
        fileStream.fd.sync()
        closed = true
        output.close()
    }

    companion object {
        val MAGIC: ByteArray = "SCELOG01".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION = 1
        const val RECORD_SENSOR_EVENT = 1
        const val RECORD_MARKER = 2
        const val RECORD_CLEAN_END = 3

        private const val FLAG_WAKE_UP = 1
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val MAX_UNSIGNED_SHORT = 65_535
        private const val MAX_VALUE_COUNT = 255
    }
}
