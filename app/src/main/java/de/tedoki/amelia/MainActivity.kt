package de.tedoki.amelia

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import de.tedoki.amelia.ui.LockScreen
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import de.tedoki.amelia.notify.Notifier
import de.tedoki.amelia.ui.AmeliaRoot
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.theme.AmeliaTheme

class AmeliaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // Absturzbericht sichern, damit er beim nächsten Start angezeigt werden kann
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "Amelia ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "${java.util.Date()}\nThread: ${thread.name}\n\n${error.stackTraceToString()}"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}

class MainActivity : FragmentActivity() {

    private val vm: AmeliaViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** App-Sperre: gesperrt beim Start und nach mehr als einer Minute im Hintergrund. */
    private var locked by mutableStateOf(false)
    private var lastStop = 0L
    private var authenticating = false

    private fun authenticate() {
        if (authenticating) return
        authenticating = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                locked = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Amelia entsperren")
            .setSubtitle("Deine Mails sind geschützt")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            authenticating = false
            locked = false // Ohne verfügbare Sperre nicht aussperren
        }
    }

    private fun applyRecentsPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!vm.settings.appLock.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        locked = vm.settings.appLock.value
        applyRecentsPrivacy()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val theme by vm.settings.theme.collectAsState()
            val dynamic by vm.settings.dynamicColor.collectAsState()
            AmeliaTheme(theme, dynamic) {
                Box {
                    AmeliaRoot(vm)
                    if (locked) {
                        LockScreen(onUnlock = ::authenticate, onLeave = { finish() })
                        LaunchedEffect(Unit) { authenticate() }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyRecentsPrivacy()
        if (vm.settings.appLock.value && lastStop > 0 && System.currentTimeMillis() - lastStop > 60_000) locked = true
        vm.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!authenticating) lastStop = System.currentTimeMillis()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_MESSAGE_ID, -1L) ?: -1L
        if (id > 0) vm.openById(id)
        if (intent?.action == Intent.ACTION_SENDTO || intent?.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme.equals("mailto", true)) {
                vm.push(de.tedoki.amelia.ui.Screen.Compose(de.tedoki.amelia.ui.ComposeArgs(to = data?.schemeSpecificPart?.substringBefore('?'))))
            }
        }
    }
}
