package de.tedoki.amelia.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.BuildConfig
import de.tedoki.amelia.R
import de.tedoki.amelia.data.LinkCheck
import de.tedoki.amelia.data.NotificationContent
import de.tedoki.amelia.data.SwipeAction
import de.tedoki.amelia.data.SyncMode
import de.tedoki.amelia.data.ThemeMode
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.Identity
import de.tedoki.amelia.data.db.Protocol
import de.tedoki.amelia.data.db.encodeIdentities
import de.tedoki.amelia.mail.net.CertificateInfo
import de.tedoki.amelia.mail.Provider
import de.tedoki.amelia.mail.Providers
import de.tedoki.amelia.mail.mime.MimeUtil
import de.tedoki.amelia.mail.net.Security
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.Screen
import de.tedoki.amelia.ui.components.AccountDot
import de.tedoki.amelia.ui.components.Fmt
import de.tedoki.amelia.ui.components.GlassIconButton
import de.tedoki.amelia.ui.components.MorphFab
import de.tedoki.amelia.ui.components.MorphLoader
import de.tedoki.amelia.ui.components.Pill
import de.tedoki.amelia.ui.theme.Brand
import de.tedoki.amelia.ui.theme.glass
import kotlinx.coroutines.launch

// ===================================================================== Bausteine

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", onClick = onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 10.dp, top = 22.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(vertical = 6.dp), content = content)
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ===================================================================== Einstellungen

@Composable
fun SettingsScreen(vm: AmeliaViewModel) {
    val accounts by vm.accounts.collectAsState()
    val theme by vm.settings.theme.collectAsState()
    val dynamic by vm.settings.dynamicColor.collectAsState()
    val sync by vm.settings.syncMode.collectAsState()
    val images by vm.settings.loadImages.collectAsState()
    val startInbox by vm.settings.startInInbox.collectAsState()
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    val unrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    val swipeLeft by vm.settings.swipeLeft.collectAsState()
    val swipeRight by vm.settings.swipeRight.collectAsState()
    val swipeUp by vm.settings.swipeUp.collectAsState()
    val swipeDown by vm.settings.swipeDown.collectAsState()
    val snoozeMorning by vm.settings.snoozeMorning.collectAsState()
    val snoozeEvening by vm.settings.snoozeEvening.collectAsState()
    val weekendDay by vm.settings.snoozeWeekendDay.collectAsState()
    val snoozeUnread by vm.settings.snoozeUnread.collectAsState()
    val notifContent by vm.settings.notificationContent.collectAsState()
    val quiet by vm.settings.quietHours.collectAsState()
    val quietStart by vm.settings.quietStart.collectAsState()
    val quietEnd by vm.settings.quietEnd.collectAsState()
    val appLock by vm.settings.appLock.collectAsState()
    val linkCheck by vm.settings.linkCheck.collectAsState()
    val trusted by vm.settings.trustedSenders.collectAsState()
    var showTrusted by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Einstellungen") { vm.pop() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Section("Konten") {
                accounts.orEmpty().forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.push(Screen.AccountEditor(a.id)) }.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccountDot(a.color, size = 12.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.titleMedium)
                            Text("${a.email} (${a.protocol.name})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when {
                                a.lastError != null -> Text(a.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                a.lastSync > 0 -> Text("Zuletzt abgerufen: ${Fmt.short(a.lastSync)}${if (Fmt.short(a.lastSync).contains(':')) "" else ", " + Fmt.timeOnly(a.lastSync)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.push(Screen.AccountEditor(null)) }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Konto hinzufügen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Section("Neue E-Mails abrufen") {
                SyncMode.values().forEach { m -> ChoiceRow(m.label, m == sync) { vm.setSyncMode(m) } }
                if (sync == SyncMode.PUSH) {
                    Text(
                        "Push hält eine Verbindung zum Server offen und zeigt dafür eine dauerhafte, unauffällige Benachrichtigung. " +
                            "Server ohne Push werden alle 5 Minuten abgefragt.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
                if (!unrestricted && sync != SyncMode.MANUAL) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            try {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Akku-Optimierung ausschalten", style = MaterialTheme.typography.titleMedium)
                            Text("Sonst kann Android den Abruf im Hintergrund verzögern.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Section("Gesten") {
                PickerRow("Posteingang: nach links wischen", swipeLeft, SwipeAction.values().toList(), { it.label }) { vm.settings.setSwipeLeft(it) }
                PickerRow("Posteingang: nach rechts wischen", swipeRight, SwipeAction.values().toList(), { it.label }) { vm.settings.setSwipeRight(it) }
                PickerRow("Karten: nach oben wischen", swipeUp, SwipeAction.values().toList(), { it.label }) { vm.settings.setSwipeUp(it) }
                PickerRow("Karten: nach unten wischen", swipeDown, SwipeAction.values().toList(), { it.label }) { vm.settings.setSwipeDown(it) }
            }

            Section("Parken") {
                PickerRow("„Morgen früh“ um", snoozeMorning, (5..11).toList(), { "%02d:00 Uhr".format(it) }) { vm.settings.setSnoozeMorning(it) }
                PickerRow("„Heute Abend“ um", snoozeEvening, (15..22).toList(), { "%02d:00 Uhr".format(it) }) { vm.settings.setSnoozeEvening(it) }
                PickerRow("Wochenende beginnt am", weekendDay, listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY),
                    { if (it == java.util.Calendar.SATURDAY) "Samstag" else "Sonntag" }) { vm.settings.setSnoozeWeekendDay(it) }
                SwitchRow("Als ungelesen zurückholen", "Zurückgekehrte Mails wieder als ungelesen markieren", snoozeUnread) { vm.settings.setSnoozeUnread(it) }
            }

            Section("Benachrichtigungen") {
                PickerRow("Inhalt", notifContent, NotificationContent.values().toList(), { it.label }) { vm.settings.setNotificationContent(it) }
                SwitchRow("Ruhezeiten", "In dieser Zeit ohne Ton und Vibration", quiet) { vm.settings.setQuiet(it) }
                if (quiet) {
                    PickerRow("Ruhe ab", quietStart, (0..23).toList(), { "%02d:00 Uhr".format(it) }) { vm.settings.setQuietStart(it) }
                    PickerRow("Ruhe bis", quietEnd, (0..23).toList(), { "%02d:00 Uhr".format(it) }) { vm.settings.setQuietEnd(it) }
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        } catch (_: Exception) {
                        }
                    }.padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Column {
                        Text("Töne und Vibration", style = MaterialTheme.typography.titleMedium)
                        Text("In den Android-Einstellungen festlegen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Section("Sicherheit") {
                SwitchRow("App-Sperre", "Beim Öffnen per Fingerabdruck, Gesicht oder Displaysperre entsperren", appLock) { on ->
                    if (on) {
                        val bm = androidx.biometric.BiometricManager.from(context)
                        val allowed = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        if (bm.canAuthenticate(allowed) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                            vm.settings.setAppLock(true)
                        } else {
                            vm.toast("Bitte zuerst eine Displaysperre (PIN, Muster oder Fingerabdruck) einrichten.")
                        }
                    } else {
                        vm.settings.setAppLock(false)
                    }
                }
                PickerRow("Links in Mails", linkCheck, LinkCheck.values().toList(), { it.label }) { vm.settings.setLinkCheck(it) }
            }

            Section("Darstellung") {
                ThemeMode.values().forEach { t -> ChoiceRow(t.label, t == theme) { vm.setTheme(t) } }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow("Systemfarben", "Farben aus dem Hintergrundbild übernehmen (Material You)", dynamic, vm::setDynamic)
                }
                SwitchRow("Mit Posteingang starten", "Statt mit der Kartenansicht", startInbox, vm::setStartInInbox)
            }

            Section("Datenschutz") {
                SwitchRow(
                    "Externe Bilder immer laden",
                    "Bilder aus dem Internet können verraten, dass eine Mail geöffnet wurde.",
                    images, vm::setLoadImages
                )
                if (!images) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showTrusted = true }.padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Column {
                            Text("Vertraute Absender", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (trusted.isEmpty()) "Noch keine – in einer Mail „Immer von diesem Absender“ wählen"
                                else "Bilder werden von ${trusted.size} Absender(n) geladen",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Section("Über Amelia") {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.amelia_logo), null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Amelia ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text("Ein Projekt von Tedoki Labs. Ohne Google-Dienste, ohne Tracking – deine Mails bleiben zwischen dir und deinem Server.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
    if (showTrusted) TrustedSendersSheet(vm, trusted) { showTrusted = false }
}

@Composable
private fun TrustedSendersSheet(vm: AmeliaViewModel, trusted: Set<String>, onDismiss: () -> Unit) {
    de.tedoki.amelia.ui.components.GlassSheet("Vertraute Absender", onDismiss) {
        if (trusted.isEmpty()) {
            Text("Keine Einträge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trusted.sorted().forEach { email ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(email, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.settings.setTrusted(email, false) }) { Text("Entfernen", color = Brand.Coral) }
            }
        }
    }
}

/** Zeile mit aktuellem Wert; ein Tipp öffnet die Auswahl. */
@Composable
private fun <T> PickerRow(title: String, value: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(label(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(option); open = false }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (option == value) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Schließen") } }
        )
    }
}

// ===================================================================== Willkommen

@Composable
fun WelcomeScreen(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painterResource(R.drawable.amelia_logo), null,
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(34.dp))
        )
        Spacer(Modifier.height(32.dp))
        Text("Deine Post\nals Kartenstapel.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Neue Mails liegen als Karten auf dem Schreibtisch. Nach oben wischen löscht, nach unten wischen parkt sie für später. " +
                "Der klassische Posteingang ist immer nur einen Wisch entfernt.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        MorphFab(Icons.Outlined.PersonAdd, "E-Mail-Konto hinzufügen", onClick = onAdd)
        Spacer(Modifier.height(16.dp))
        Text("IMAP und POP3, funktioniert mit GMX, WEB.DE, T-Online, Posteo, mailbox.org, eigenen Servern und vielen mehr.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===================================================================== Kontoeditor

private val accountColors = listOf(
    Color(0xFF2A4C8C), Color(0xFF4A7A5C), Color(0xFFE09A2C), Color(0xFFD9534F),
    Color(0xFF7A4E9C), Color(0xFF1F8A99), Color(0xFFB0476E), Color(0xFF5B6B2E)
)

private fun defaultPort(protocol: Protocol, security: Security): Int = when (protocol) {
    Protocol.IMAP -> if (security == Security.SSL_TLS) 993 else 143
    Protocol.POP3 -> if (security == Security.SSL_TLS) 995 else 110
}

private fun defaultSmtpPort(security: Security) = when (security) {
    Security.SSL_TLS -> 465
    Security.STARTTLS -> 587
    Security.NONE -> 25
}

@Composable
fun AccountEditorScreen(vm: AmeliaViewModel, accountId: Long?) {
    val accounts by vm.accounts.collectAsState()
    val existing = accountId?.let { id -> accounts?.firstOrNull { it.id == id } }
    val isNew = accountId == null
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableStateOf(if (isNew) 1 else 2) }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.senderName ?: "") }
    var email by rememberSaveable { mutableStateOf(existing?.email ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var protocol by rememberSaveable { mutableStateOf(existing?.protocol ?: Protocol.IMAP) }
    var inHost by rememberSaveable { mutableStateOf(existing?.inHost ?: "") }
    var inPort by rememberSaveable { mutableStateOf(existing?.inPort?.toString() ?: "993") }
    var inSec by rememberSaveable { mutableStateOf(existing?.inSecurity ?: Security.SSL_TLS) }
    var inUser by rememberSaveable { mutableStateOf(existing?.inUser ?: "") }
    var outHost by rememberSaveable { mutableStateOf(existing?.outHost ?: "") }
    var outPort by rememberSaveable { mutableStateOf(existing?.outPort?.toString() ?: "465") }
    var outSec by rememberSaveable { mutableStateOf(existing?.outSecurity ?: Security.SSL_TLS) }
    var outUser by rememberSaveable { mutableStateOf(existing?.outUser ?: "") }
    var outPassword by rememberSaveable { mutableStateOf("") }
    var saveSent by rememberSaveable { mutableStateOf(existing?.saveSentCopy ?: true) }
    var leaveOnServer by rememberSaveable { mutableStateOf(existing?.popLeaveOnServer ?: true) }
    var deleteOnServer by rememberSaveable { mutableStateOf(existing?.popDeleteOnServer ?: true) }
    var signature by rememberSaveable { mutableStateOf(existing?.signature ?: "") }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: accountColors[(accounts?.size ?: 0) % accountColors.size].toArgb()) }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<Provider?>(null) }
    var gmailMode by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(existing?.pinnedCerts ?: "") }
    var notify by rememberSaveable { mutableStateOf(existing?.notify ?: true) }
    var identities by rememberSaveable { mutableStateOf(existing?.identities ?: "") }
    var certificate by remember { mutableStateOf<CertificateInfo?>(null) }
    val context = LocalContext.current

    BackHandler { if (isNew && step == 2) step = 1 else vm.pop() }

    fun applyProvider() {
        val p = (if (gmailMode) Providers.all.first { it.name == "Gmail" } else null)
            ?: Providers.forEmail(email) ?: Providers.guess(email)
        provider = p
        hint = p.hint
        val preset = if (protocol == Protocol.POP3 && p.pop3 != null) p.pop3 else p.imap
        if (protocol == Protocol.POP3 && p.pop3 == null) protocol = Protocol.IMAP
        inHost = preset.host; inPort = preset.port.toString(); inSec = preset.security
        outHost = p.smtp.host; outPort = p.smtp.port.toString(); outSec = p.smtp.security
        val login = if (p.localPartLogin) email.substringBefore('@') else email.trim()
        inUser = login; outUser = login
        saveSent = !p.savesSentCopy
        if (label.isBlank()) label = p.name.takeIf { it != "Eigene Domain" } ?: email.substringAfter('@')
    }

    fun build(): Account = Account(
        id = existing?.id ?: 0,
        label = label.trim().ifBlank { email.substringAfter('@') },
        senderName = name.trim(),
        email = email.trim(),
        protocol = protocol,
        inHost = inHost.trim(), inPort = inPort.toIntOrNull() ?: defaultPort(protocol, inSec), inSecurity = inSec, inUser = inUser.trim(),
        outHost = outHost.trim(), outPort = outPort.toIntOrNull() ?: defaultSmtpPort(outSec), outSecurity = outSec, outUser = outUser.trim(),
        passwordEnc = existing?.passwordEnc ?: "",
        outPasswordEnc = existing?.outPasswordEnc ?: "",
        color = color,
        saveSentCopy = saveSent,
        popLeaveOnServer = leaveOnServer,
        popDeleteOnServer = deleteOnServer,
        signature = signature.trimEnd(),
        sortOrder = existing?.sortOrder ?: 0,
        lastSync = existing?.lastSync ?: 0,
        lastError = null,
        supportsIdle = existing?.supportsIdle ?: false,
        pinnedCerts = pinned,
        identities = identities,
        notify = notify
    )

    fun save(force: Boolean) {
        val a = build()
        if (!force) {
            testing = true
            error = null
            scope.launch {
                val result = vm.testAccount(a, password.ifEmpty { null }, outPassword.ifEmpty { null })
                testing = false
                when {
                    result.certificate != null -> certificate = result.certificate
                    result.error != null -> error = result.error
                    else -> vm.saveAccount(a, password.ifEmpty { null }, if (outPassword.isEmpty() && existing == null) null else outPassword.ifEmpty { null }) { vm.pop() }
                }
            }
        } else {
            error = null
            vm.saveAccount(a, password.ifEmpty { null }, outPassword.ifEmpty { null }) { vm.pop() }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TopBar(if (isNew) "Konto hinzufügen" else existing?.label ?: "Konto") {
            if (isNew && step == 2) step = 1 else vm.pop()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (step == 1) {
                if (!gmailMode) {
                    GmailButton { gmailMode = true; error = null }
                } else {
                    GmailGuide(onOpen = { openLink(context, "https://myaccount.google.com/apppasswords") }, onCancel = { gmailMode = false })
                }
                Section(if (gmailMode) "Gmail-Zugang" else "Zugangsdaten") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Input("E-Mail-Adresse", email, { email = it.trim() }, KeyboardType.Email)
                        PasswordInput(if (gmailMode) "App-Passwort (16 Zeichen)" else "Passwort", password, showPassword, { password = it.let { p -> if (gmailMode) p.replace(" ", "") else p } }) { showPassword = !showPassword }
                        Input("Dein Name (für Empfänger)", name, { name = it })
                    }
                }
                Text(
                    "Passwörter werden verschlüsselt im Android-Keystore gespeichert und nur an deinen Mailserver gesendet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                MorphFab(Icons.Outlined.Check, "Weiter", modifier = Modifier.align(Alignment.End)) {
                    if (!MimeUtil.isValidEmail(email)) {
                        error = "Bitte eine gültige E-Mail-Adresse eingeben."
                    } else if (password.isEmpty()) {
                        error = "Bitte das Passwort eingeben."
                    } else {
                        error = null
                        applyProvider()
                        step = 2
                    }
                }
                error?.let { ErrorCard(it, null) }
            } else {
                hint?.let {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(14.dp)
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                Section("Konto") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Input("Kontoname (z. B. Privat, Arbeit)", label, { label = it })
                        Input("Dein Name", name, { name = it })
                        Input("E-Mail-Adresse", email, { email = it.trim() }, KeyboardType.Email)
                        PasswordInput(if (isNew) "Passwort" else "Neues Passwort (leer = unverändert)", password, showPassword, { password = it }) { showPassword = !showPassword }
                        Text("Farbe", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            accountColors.forEach { c ->
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(c)
                                        .then(if (c.toArgb() == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                        .clickable { color = c.toArgb() }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Section("Posteingang") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Protocol.values().forEach { p ->
                                Pill(p.name, protocol == p) {
                                    if (protocol != p) {
                                        protocol = p
                                        val prov = provider
                                        val preset = if (p == Protocol.POP3) prov?.pop3 else prov?.imap
                                        if (preset != null) {
                                            inHost = preset.host; inPort = preset.port.toString(); inSec = preset.security
                                        } else {
                                            inPort = defaultPort(p, inSec).toString()
                                        }
                                    }
                                }
                            }
                        }
                        Input("Server", inHost, { inHost = it.trim() }, KeyboardType.Uri)
                        SecurityPicker(inSec) { s -> inSec = s; inPort = defaultPort(protocol, s).toString() }
                        Input("Port", inPort, { inPort = it.filter(Char::isDigit) }, KeyboardType.Number)
                        Input("Benutzername", inUser, { inUser = it.trim() })
                        if (protocol == Protocol.POP3) {
                            SwitchLine("Nachrichten auf dem Server lassen", leaveOnServer) { leaveOnServer = it }
                            SwitchLine("Beim Löschen auch vom Server entfernen", deleteOnServer) { deleteOnServer = it }
                        }
                    }
                }
                Section("Postausgang (SMTP)") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Input("Server", outHost, { outHost = it.trim() }, KeyboardType.Uri)
                        SecurityPicker(outSec) { s -> outSec = s; outPort = defaultSmtpPort(s).toString() }
                        Input("Port", outPort, { outPort = it.filter(Char::isDigit) }, KeyboardType.Number)
                        Input("Benutzername", outUser, { outUser = it.trim() })
                        PasswordInput("Eigenes SMTP-Passwort (optional)", outPassword, showPassword, { outPassword = it }) { showPassword = !showPassword }
                        if (protocol == Protocol.IMAP) {
                            SwitchLine("Kopie in „Gesendet“ ablegen", saveSent) { saveSent = it }
                            Text("Ausschalten, wenn der Anbieter gesendete Mails selbst ablegt (z. B. Gmail), sonst entstehen Doppel.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Section("Weitere Absenderadressen") {
                    IdentityEditor(identities) { identities = it }
                }
                Section("Benachrichtigungen") {
                    SwitchRow("Neue Mails melden", "Für dieses Konto Benachrichtigungen zeigen", notify) { notify = it }
                }
                if (pinned.isNotBlank()) {
                    Section("Bestätigte Zertifikate") {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                            Text("Du hast ${pinned.lines().count { it.isNotBlank() }} Zertifikat(e) dieses Servers ausdrücklich bestätigt.",
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { pinned = "" }) { Text("Bestätigungen zurücksetzen", color = Brand.Coral) }
                        }
                    }
                }
                Section("Signatur") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            signature, { signature = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                            placeholder = { Text("Wird unter neue Nachrichten gesetzt") },
                            shape = RoundedCornerShape(18.dp)
                        )
                    }
                }
                error?.let { ErrorCard(it) { save(force = true) } }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Konto entfernen", color = Brand.Coral) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (testing) {
                        Text("Verbindung wird geprüft …", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                        MorphLoader(size = 40.dp)
                    } else {
                        MorphFab(Icons.Outlined.Check, if (isNew) "Prüfen und hinzufügen" else "Prüfen und speichern") {
                            when {
                                !MimeUtil.isValidEmail(email) -> error = "Bitte eine gültige E-Mail-Adresse eingeben."
                                inHost.isBlank() || outHost.isBlank() -> error = "Bitte beide Server angeben."
                                isNew && password.isEmpty() -> error = "Bitte das Passwort eingeben."
                                else -> save(force = false)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
    }

    certificate?.let { cert ->
        CertificateDialog(
            cert,
            onTrust = {
                pinned = (pinned.lines().filter { it.isNotBlank() } + cert.fingerprint).distinct().joinToString("\n")
                certificate = null
                save(force = false)
            },
            onDismiss = { certificate = null; error = "Zertifikat nicht bestätigt – Verbindung nicht möglich." }
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Konto entfernen?") },
            text = { Text("„${existing.label}“ wird aus Amelia entfernt. Auf dem Server bleiben alle Nachrichten erhalten.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount(existing.id); vm.pop() }) { Text("Entfernen", color = Brand.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun ErrorCard(text: String, onForce: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
    ) {
        Row {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
        if (onForce != null) {
            TextButton(onClick = onForce, modifier = Modifier.align(Alignment.End)) {
                Text("Trotzdem speichern", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun Input(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun PasswordInput(label: String, value: String, visible: Boolean, onChange: (String) -> Unit, onToggle: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Verbergen" else "Anzeigen")
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun SecurityPicker(value: Security, onChange: (Security) -> Unit) {
    var confirmNone by remember { mutableStateOf(false) }
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("SSL/TLS", value == Security.SSL_TLS) { onChange(Security.SSL_TLS) }
        Pill("STARTTLS", value == Security.STARTTLS) { onChange(Security.STARTTLS) }
        Pill("Keine", value == Security.NONE) { if (value != Security.NONE) confirmNone = true }
    }
    AnimatedVisibility(value == Security.NONE) {
        Text("Ohne Verschlüsselung werden Passwort und Mails lesbar übertragen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (confirmNone) {
        AlertDialog(
            onDismissRequest = { confirmNone = false },
            icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Wirklich ohne Verschlüsselung?") },
            text = {
                Text(
                    "Passwort und alle Mails gehen dann lesbar durchs Netz – jeder im selben WLAN kann mitlesen. " +
                        "Das ist nur in einem abgeschotteten Heimnetz vertretbar."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmNone = false; onChange(Security.NONE) }) {
                    Text("Ohne Verschlüsselung", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmNone = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

// ===================================================================== Gmail, Zertifikate, Identitäten

private fun openLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

@Composable
private fun GmailButton(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).glass(RoundedCornerShape(26.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("G", fontWeight = FontWeight.Black, color = Color(0xFF4285F4), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Gmail-Konto einrichten", style = MaterialTheme.typography.titleMedium)
            Text("Auch für Google-Workspace-Adressen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GmailGuide(onOpen: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp)
    ) {
        Text("Gmail mit App-Passwort", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(8.dp))
        val steps = listOf(
            "Die Bestätigung in zwei Schritten muss im Google-Konto eingeschaltet sein.",
            "Auf „App-Passwort erstellen“ tippen, mit Google anmelden und als Namen z. B. „Amelia“ eingeben.",
            "Das angezeigte 16-stellige Passwort kopieren und unten einfügen (Leerzeichen sind egal)."
        )
        steps.forEachIndexed { i, step ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.width(22.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("App-Passwort erstellen", true, onClick = onOpen)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Anderer Anbieter") }
        }
        Text(
            "Dein normales Google-Passwort funktioniert hier nicht. Das App-Passwort gilt nur für den Mailabruf und lässt sich jederzeit widerrufen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun CertificateDialog(cert: CertificateInfo, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val date = java.text.SimpleDateFormat("d. MMMM yyyy", java.util.Locale.GERMANY)
    fun cn(dn: String) = Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text("Unbekanntes Zertifikat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        cert.hostnameMismatch -> "Das Zertifikat von ${cert.host} ist auf einen anderen Namen ausgestellt."
                        cert.selfSigned -> "${cert.host} nutzt ein selbst ausgestelltes Zertifikat – typisch für eigene Server."
                        else -> "Das Zertifikat von ${cert.host} stammt von keiner Stelle, der Android vertraut."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                CertLine("Ausgestellt für", cn(cert.subject))
                CertLine("Aussteller", cn(cert.issuer))
                CertLine("Gültig bis", date.format(cert.validUntil))
                CertLine("Server", "${cert.host}:${cert.port}")
                Spacer(Modifier.height(8.dp))
                Text("SHA-256-Fingerabdruck", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    cert.fingerprint.chunked(24).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nur bestätigen, wenn es dein eigener Server ist und der Fingerabdruck mit dem auf dem Server übereinstimmt. " +
                        "Amelia merkt sich genau dieses Zertifikat und warnt, falls sich es später ändert.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Vertrauen und verbinden") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun CertLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Zusätzliche Absenderadressen (Aliasse) eines Kontos. */
@Composable
private fun IdentityEditor(encoded: String, onChange: (String) -> Unit) {
    val list = encoded.lines().mapNotNull { line ->
        val parts = line.split('|')
        val email = parts.getOrNull(1)?.trim().orEmpty()
        if (email.isEmpty()) null else Identity(parts[0].trim(), email)
    }
    var newName by rememberSaveable { mutableStateOf("") }
    var newEmail by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Zum Beispiel Aliasse wie info@deine-domain.de. Beim Schreiben wählbar; Antworten gehen automatisch von der Adresse raus, an die die Mail ging.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        list.forEach { identity ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(identity.email, style = MaterialTheme.typography.titleSmall)
                    if (identity.name.isNotBlank()) Text(identity.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onChange(encodeIdentities(list - identity)) }) { Text("Entfernen", color = Brand.Coral) }
            }
        }
        Input("Name (optional)", newName, { newName = it })
        Input("Weitere Adresse", newEmail, { newEmail = it.trim() }, KeyboardType.Email)
        TextButton(
            onClick = {
                if (MimeUtil.isValidEmail(newEmail)) {
                    onChange(encodeIdentities(list + Identity(newName.trim(), newEmail.trim())))
                    newName = ""
                    newEmail = ""
                }
            },
            enabled = MimeUtil.isValidEmail(newEmail)
        ) { Text("Adresse hinzufügen") }
    }
}
