package de.tedoki.amelia

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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

class MainActivity : ComponentActivity() {

    private val vm: AmeliaViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val theme by vm.settings.theme.collectAsState()
            val dynamic by vm.settings.dynamicColor.collectAsState()
            AmeliaTheme(theme, dynamic) {
                AmeliaRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
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
