package com.austinlinux.pulsemetronome.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.austinlinux.pulsemetronome.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File

/** Debug and watchtest builds: the Firebase uploader, or null until firebase.properties was filled in at build time. */
object CaptureUploaders {
    fun create(context: Context): CaptureUploader? =
        if (BuildConfig.UPLOAD_CONFIGURED) FirebaseCaptureUploader(context.applicationContext) else null
}

/** Anonymous Firebase Auth sign-in, then a Storage putFile into the create-only uploads/ tree. */
private class FirebaseCaptureUploader(private val context: Context) : CaptureUploader {
    override suspend fun upload(zip: File): UploadResult {
        if (!isOnline()) return UploadResult.Offline
        return try {
            val app = firebaseApp()
            val auth = FirebaseAuth.getInstance(app)
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val prefs = context.getSharedPreferences("capture_upload", Context.MODE_PRIVATE)
            val id = installId({ prefs.getString("installId", null) }, { prefs.edit().putString("installId", it).apply() })
            val path = uploadPath(context.packageName, BuildConfig.VERSION_NAME, id, System.currentTimeMillis())
            FirebaseStorage.getInstance(app).reference.child(path).putFile(android.net.Uri.fromFile(zip)).await()
            UploadResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A connection that dropped mid-call is "offline" to the user, not a generic failure.
            if (!isOnline()) UploadResult.Offline else UploadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Initialises Firebase from BuildConfig values (no google-services plugin); reuses the instance if it exists. */
    private fun firebaseApp(): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        val options = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            .build()
        return FirebaseApp.initializeApp(context, options)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
