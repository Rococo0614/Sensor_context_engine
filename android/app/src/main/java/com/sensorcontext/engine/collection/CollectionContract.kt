package com.sensorcontext.engine.collection

object CollectionContract {
    const val ACTION_START = "com.sensorcontext.engine.collection.START"
    const val ACTION_STOP = "com.sensorcontext.engine.collection.STOP"
    const val ACTION_MARKER = "com.sensorcontext.engine.collection.MARKER"
    const val ACTION_STATUS = "com.sensorcontext.engine.collection.STATUS"

    const val EXTRA_PARTICIPANT = "participant"
    const val EXTRA_ACTIVITY = "activity"
    const val EXTRA_POSTURE = "posture"
    const val EXTRA_ENVIRONMENT = "environment"
    const val EXTRA_LIGHT = "light"
    const val EXTRA_NOTE = "note"
    const val EXTRA_MARKER = "marker"
    const val EXTRA_ELAPSED_NANOS = "elapsed_nanos"
    const val EXTRA_DURATION_SECONDS = "duration_seconds"
    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_FILE_NAME = "file_name"
    const val EXTRA_RECORD_COUNT = "record_count"
}
