package com.austinlinux.pulsemetronome.wear.sensing

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlin.math.roundToInt

/** Source of heart-rate readings; callbacks arrive on the main thread. */
interface HeartRateSensor {
    interface Listener {
        fun onBpm(bpm: Int)
        fun onContact(lost: Boolean)
        /** The sensor could not start or was torn down by the system. */
        fun onFailed()
    }

    fun start(listener: Listener)
    fun stop()
}

/**
 * Health Services ExerciseClient sensor. Exercise type WORKOUT (the generic type): an active exercise
 * keeps HR sampling continuously with the screen off, and an app-owned exercise is not mirrored into
 * Samsung Health the way a system-recognised walk or run can be. Auto-pause is off so sitting still
 * never pauses sampling.
 */
class HealthServicesHeartRateSensor(context: Context) : HeartRateSensor {
    private val appContext = context.applicationContext
    private val client = HealthServices.getClient(appContext).exerciseClient
    private var callback: ExerciseUpdateCallback? = null
    private var running = false

    override fun start(listener: HeartRateSensor.Listener) {
        stop()
        val cb = object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) {
                Log.w(TAG, "Exercise callback registration failed", throwable)
                listener.onFailed()
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                val point = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull() ?: return
                listener.onBpm(point.value.roundToInt())
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                if (dataType != DataType.HEART_RATE_BPM || availability !is DataTypeAvailability) return
                when (availability) {
                    DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY,
                    DataTypeAvailability.UNAVAILABLE -> listener.onContact(lost = true)
                    DataTypeAvailability.AVAILABLE -> listener.onContact(lost = false)
                    else -> Unit // ACQUIRING and UNKNOWN: still looking, not a loss
                }
            }
        }
        callback = cb
        val executor = appContext.mainExecutor
        client.setUpdateCallback(executor, cb)
        val config = ExerciseConfig.builder(ExerciseType.WORKOUT)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        running = true
        try {
            val future = client.startExerciseAsync(config)
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "startExercise failed", e)
                    if (running) listener.onFailed()
                }
            }, executor)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startExercise threw", e)
            listener.onFailed()
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        running = false
        try {
            client.endExerciseAsync()
            client.clearUpdateCallbackAsync(cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopping the exercise failed", e)
        }
    }

    private companion object {
        const val TAG = "HeartRateSensor"
    }
}
