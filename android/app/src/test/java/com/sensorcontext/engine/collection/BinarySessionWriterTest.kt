package com.sensorcontext.engine.collection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

class BinarySessionWriterTest {
    @Test
    fun `writer emits versioned header framed records and clean end`() {
        val file = File.createTempFile("session", ".scelog")
        try {
            val writer = BinarySessionWriter(file, metadata())
            writer.writeSensorEvent(
                elapsedRealtimeNanos = 1_100L,
                sensorId = 0,
                accuracy = 3,
                values = floatArrayOf(1f, 2f, 3f),
            )
            writer.writeMarker(1_200L, "pickup", "test")
            writer.closeCleanly(1_300L, 2_300L)

            DataInputStream(FileInputStream(file)).use { input ->
                val magic = ByteArray(BinarySessionWriter.MAGIC.size)
                input.readFully(magic)
                assertArrayEquals(BinarySessionWriter.MAGIC, magic)
                assertEquals(BinarySessionWriter.FORMAT_VERSION, input.readUnsignedShort())
                assertEquals(0, input.readUnsignedShort())
                val headerLength = input.readInt()
                assertEquals(headerLength, input.skipBytes(headerLength))

                val sensorFrameLength = input.readInt()
                assertEquals(25, sensorFrameLength)
                assertEquals(BinarySessionWriter.RECORD_SENSOR_EVENT, input.readUnsignedByte())
                assertEquals(1_100L, input.readLong())
                assertEquals(0, input.readUnsignedShort())
                assertEquals(3, input.readByte().toInt())
                assertEquals(3, input.readUnsignedByte())
                assertEquals(1f, input.readFloat())
                assertEquals(2f, input.readFloat())
                assertEquals(3f, input.readFloat())

                val markerLength = input.readInt()
                assertEquals(1 + 8 + 2 + 6 + 2 + 4, markerLength)
                assertEquals(BinarySessionWriter.RECORD_MARKER, input.readUnsignedByte())
                assertEquals(1_200L, input.readLong())
                assertEquals("pickup", readUtf8(input))
                assertEquals("test", readUtf8(input))

                assertEquals(25, input.readInt())
                assertEquals(BinarySessionWriter.RECORD_CLEAN_END, input.readUnsignedByte())
                assertEquals(1_300L, input.readLong())
                assertEquals(2_300L, input.readLong())
                assertEquals(2L, input.readLong())
                assertEquals(-1, input.read())
            }
        } finally {
            file.delete()
        }
    }

    private fun metadata(): CollectionSessionMetadata = CollectionSessionMetadata(
        sessionStartElapsedNanos = 1_000L,
        sessionStartWallMillis = 2_000L,
        sessionId = "session-1",
        labels = CollectionLabels(
            participantId = "p01",
            activity = "still",
            posture = "in_hand",
            environment = "indoor",
            light = "normal",
            note = "fixture",
        ),
        manufacturer = "vivo",
        model = "V2547A",
        device = "PD2547",
        buildFingerprint = "fixture/fingerprint",
        appVersion = "test",
        sensors = listOf(
            RecordedSensorDescriptor(
                sensorId = 0,
                androidType = 1,
                reportingMode = 0,
                minDelayUs = 1_000,
                maxDelayUs = 1_000_000,
                fifoMaxEventCount = 10_000,
                resolution = 0.001f,
                maxRange = 78.4f,
                isWakeUp = false,
                name = "accelerometer",
                vendor = "ST",
                stringType = "android.sensor.accelerometer",
            ),
        ),
    )

    private fun readUtf8(input: DataInputStream): String {
        val size = input.readUnsignedShort()
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
