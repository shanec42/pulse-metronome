package com.austinlinux.pulsemetronome.wear.sensing

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
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
    // Bumped on every start and stop so a late capabilities callback from an older start is ignored.
    private var generation = 0

    /** Marks the sensor not running (so the next start() retries) and reports the failure. */
    private fun fail(listener: HeartRateSensor.Listener) {
        running = false
        callback = null
        listener.onFailed()
    }

    override fun start(listener: HeartRateSensor.Listener) {
        // A running exercise is kept: ending and restarting it races the pending end against the new start.
        if (running) return
        running = true
        val gen = ++generation
        val cb = object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) {
                Log.w(TAG, "Exercise callback registration failed", throwable)
                if (gen == generation) fail(listener)
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                if (update.exerciseStateInfo.state.isEnded && gen == generation) {
                    // Health Services ended the exercise (e.g. permission lost); allow a retry on the next session.
                    Log.w(TAG, "Exercise ended by the system: ${update.exerciseStateInfo.state}")
                    fail(listener)
                    return
                }
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
        try {
            val caps = client.getCapabilitiesAsync()
            caps.addListener({
                if (!running || gen != generation) return@addListener
                // With the screen off Health Services may batch HR; ask for 5 s delivery when supported.
                val batching = try {
                    val supported = caps.get().supportedBatchingModeOverrides
                    if (BatchingMode.HEART_RATE_5_SECONDS in supported) setOf(BatchingMode.HEART_RATE_5_SECONDS) else emptySet()
                } catch (e: Exception) {
                    Log.w(TAG, "capabilities unavailable; starting without a batching override", e)
                    emptySet()
                }
                startExercise(listener, batching, gen)
            }, executor)
        } catch (e: RuntimeException) {
            Log.w(TAG, "getCapabilities threw", e)
            fail(listener)
        }
    }

    private fun startExercise(listener: HeartRateSensor.Listener, batching: Set<BatchingMode>, gen: Int) {
        val config = ExerciseConfig.builder(ExerciseType.WORKOUT)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .setBatchingModeOverrides(batching)
            .build()
        val executor = appContext.mainExecutor
        try {
            val future = client.startExerciseAsync(config)
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "startExercise failed", e)
                    if (running && gen == generation) fail(listener)
                }
            }, executor)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startExercise threw", e)
            fail(listener)
        }
    }

    override fun stop() {
        running = false
        generation++
        val cb = callback
        callback = null
        try {
            // Always end: an exercise outlives its client process, so there may be one with no callback registered here.
            client.endExerciseAsync()
            if (cb != null) client.clearUpdateCallbackAsync(cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopping the exercise failed", e)
        }
    }

    companion object {
        private const val TAG = "HeartRateSensor"

        /**
         * Ends an exercise this app left running (the process died without onDestroy). Call only when no
         * session is active; does nothing if no owned exercise is in progress.
         */
        fun endOrphanExercise(context: Context) {
            val appContext = context.applicationContext
            try {
                val client = HealthServices.getClient(appContext).exerciseClient
                val info = client.getCurrentExerciseInfoAsync()
                info.addListener({
                    try {
                        if (info.get().exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                            Log.w(TAG, "Ending an orphaned exercise")
                            client.endExerciseAsync()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "orphan check failed", e)
                    }
                }, appContext.mainExecutor)
            } catch (e: RuntimeException) {
                Log.w(TAG, "orphan check threw", e)
            }
        }
    }
}
