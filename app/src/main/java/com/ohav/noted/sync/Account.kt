package com.ohav.noted.sync

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The Google account behind sync. Optional: without it everything stays on this device. */
class Account(private val ctx: Context, private val scope: CoroutineScope) {
    data class User(val uid: String, val name: String, val email: String, val photoUrl: String?) {
        val first: String get() = name.trim().split(' ').firstOrNull().orEmpty().ifEmpty { email.substringBefore('@') }
        val letter: String get() = first.take(1).ifEmpty { "?" }
    }

    sealed interface SignIn {
        data object Ok : SignIn
        data object Cancelled : SignIn
        data class Failed(val message: String) : SignIn
    }

    /** Firebase is configured in this build (CI found the project's google-services.json). */
    val available: Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo
    private val photoFile = File(ctx.filesDir, "avatar.jpg")

    init {
        if (available) {
            val auth = FirebaseAuth.getInstance()
            _user.value = auth.currentUser?.toUser()
            if (_user.value != null) loadCachedPhoto()
            auth.addAuthStateListener { a ->
                val u = a.currentUser?.toUser()
                if (u?.uid != _user.value?.uid || u?.photoUrl != _user.value?.photoUrl) refreshPhoto(u)
                _user.value = u
            }
        }
    }

    private fun FirebaseUser.toUser() = User(uid, displayName.orEmpty(), email.orEmpty(), photoUrl?.toString())

    /** Web client id the google-services plugin generates once Google sign-in is enabled in Firebase. */
    private fun webClientId(): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (id == 0) null else ctx.getString(id).ifBlank { null }
    }

    val ready: Boolean get() = available && webClientId() != null

    suspend fun signIn(activity: Activity): SignIn {
        if (!available) return SignIn.Failed("החיבור לענן עוד לא מוגדר בגרסה הזו")
        val clientId = webClientId() ?: return SignIn.Failed("ההתחברות עם גוגל עוד לא הופעלה בפרויקט")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        return try {
            val cred = CredentialManager.create(activity).getCredential(activity, request).credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignIn.Failed("גוגל החזירה תשובה לא צפויה")
            }
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            SignIn.Ok
        } catch (_: GetCredentialCancellationException) {
            SignIn.Cancelled
        } catch (_: NoCredentialException) {
            SignIn.Failed("אין חשבון גוגל במכשיר")
        } catch (e: GetCredentialException) {
            SignIn.Failed(e.message ?: "ההתחברות נכשלה")
        } catch (e: Exception) {
            SignIn.Failed(if (e is java.io.IOException) "אין חיבור לרשת" else "ההתחברות נכשלה")
        }
    }

    suspend fun signOut() {
        if (!available) return
        FirebaseAuth.getInstance().currentUser?.uid?.let { Push.unregister(it) }
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(ctx).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /**
     * Deletes the account and everything of it in the cloud (a function does the work, the
     * sign-in included); the notes on this device stay. Returns an error message, or null.
     */
    suspend fun deleteAccount(): String? {
        if (!available) return "אין חשבון מחובר"
        return try {
            com.google.firebase.functions.FirebaseFunctions.getInstance("me-west1")
                .getHttpsCallable("deleteAccount")
                .apply { setTimeout(300, java.util.concurrent.TimeUnit.SECONDS) }
                .call().await()
            FirebaseAuth.getInstance().signOut()
            runCatching { CredentialManager.create(ctx).clearCredentialState(ClearCredentialStateRequest()) }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is java.io.IOException || e.cause is java.io.IOException) "אין חיבור לרשת" else "המחיקה לא הצליחה — נסה שוב"
        }
    }

    private fun loadCachedPhoto() {
        scope.launch(Dispatchers.IO) {
            if (photoFile.exists()) _photo.value = runCatching { BitmapFactory.decodeFile(photoFile.path)?.asImageBitmap() }.getOrNull()
        }
    }

    private fun refreshPhoto(u: User?) {
        if (u?.photoUrl == null) { _photo.value = null; photoFile.delete(); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    // Google serves avatars at any size: ask for one that stays sharp at 51dp
                    val url = u.photoUrl.replace(Regex("=s\\d+(-c)?$"), "=s192-c")
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.inputStream.use { inp -> photoFile.outputStream().use { inp.copyTo(it) } }
                    BitmapFactory.decodeFile(photoFile.path)?.asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) _photo.value = bmp
        }
    }
}
