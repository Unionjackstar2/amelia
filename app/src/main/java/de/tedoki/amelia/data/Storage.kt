package de.tedoki.amelia.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AES-256-GCM mit Schlüssel im Android-Keystore. Kein Klartext-Fallback. */
object CredentialVault {
    private const val ALIAS = "amelia.credentials.v2"
    private const val PREFIX = "v2:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(data)
    }

    /** Liefert null, wenn das Passwort nicht entschlüsselt werden kann (z. B. nach Gerätewechsel). */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val data = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

enum class ThemeMode(val label: String) { SYSTEM("Wie System"), LIGHT("Hell"), DARK("Dunkel") }

enum class SyncMode(val label: String, val minutes: Long) {
    PUSH("Sofort (Push)", 30),
    M15("Alle 15 Minuten", 15),
    M30("Alle 30 Minuten", 30),
    H1("Stündlich", 60),
    H3("Alle 3 Stunden", 180),
    MANUAL("Nur manuell", 0)
}

enum class DeskRange(val label: String, val days: Int) {
    TODAY("Heute", 0), TWO_DAYS("2 Tage", 2), WEEK("Woche", 7), MONTH("Monat", 30), ALL("Alle", -1)
}

/** Was eine Wischgeste auslöst. */
enum class SwipeAction(val label: String) {
    TRASH("Papierkorb"),
    ARCHIVE("Archivieren"),
    SNOOZE("Parken"),
    TOGGLE_READ("Gelesen / ungelesen"),
    FLAG("Markieren"),
    SPAM("Spam"),
    MOVE("Verschieben"),
    NONE("Nichts")
}

/** Aussehen der Karten auf dem Schreibtisch. */
enum class CardStyle(val label: String) {
    GLASS("Schlicht"),
    LINED("Linienpapier"),
    SPIRAL("Ringbuch"),
    DECKLE("Büttenpapier")
}

enum class NotificationContent(val label: String) {
    FULL("Absender und Betreff"),
    SENDER("Nur Absender"),
    MINIMAL("Nur „Neue E-Mail“")
}

enum class LinkCheck(val label: String) {
    ALWAYS("Immer vorher anzeigen"),
    SUSPICIOUS("Nur bei Verdacht"),
    NEVER("Direkt öffnen")
}

class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("amelia_settings", Context.MODE_PRIVATE)

    private fun <E : Enum<E>> enumPref(key: String, default: E, values: Array<E>): MutableStateFlow<E> =
        MutableStateFlow(values.firstOrNull { it.name == prefs.getString(key, null) } ?: default)

    private val _theme = enumPref("theme", ThemeMode.SYSTEM, ThemeMode.values())
    private val _dynamic = MutableStateFlow(prefs.getBoolean("dynamic", false))
    private val _sync = enumPref("sync", SyncMode.PUSH, SyncMode.values())
    private val _images = MutableStateFlow(prefs.getBoolean("images", false))
    private val _startInbox = MutableStateFlow(prefs.getBoolean("startInbox", false))
    private val _range = enumPref("range", DeskRange.WEEK, DeskRange.values())
    private val _unreadOnly = MutableStateFlow(prefs.getBoolean("unreadOnly", false))

    private fun intPref(key: String, default: Int) = MutableStateFlow(prefs.getInt(key, default))
    private fun boolPref(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _swipeLeft = enumPref("swipeLeft", SwipeAction.TRASH, SwipeAction.values())
    private val _swipeRight = enumPref("swipeRight", SwipeAction.TOGGLE_READ, SwipeAction.values())
    private val _swipeUp = enumPref("swipeUp", SwipeAction.TRASH, SwipeAction.values())
    private val _swipeDown = enumPref("swipeDown", SwipeAction.SNOOZE, SwipeAction.values())
    private val _morning = intPref("snoozeMorning", 8)
    private val _evening = intPref("snoozeEvening", 18)
    private val _weekendDay = intPref("snoozeWeekendDay", java.util.Calendar.SATURDAY)
    private val _snoozeUnread = boolPref("snoozeUnread", true)
    private val _trusted = MutableStateFlow(prefs.getStringSet("trustedSenders", emptySet())!!.toSet())
    private val _notifContent = enumPref("notifContent", NotificationContent.FULL, NotificationContent.values())
    private val _quiet = boolPref("quiet", false)
    private val _quietStart = intPref("quietStart", 22)
    private val _quietEnd = intPref("quietEnd", 7)
    private val _appLock = boolPref("appLock", false)
    private val _linkCheck = enumPref("linkCheck", LinkCheck.ALWAYS, LinkCheck.values())
    private val _cardStyle = enumPref("cardStyle", CardStyle.GLASS, CardStyle.values())
    val cardStyle: StateFlow<CardStyle> = _cardStyle.asStateFlow()
    fun setCardStyle(v: CardStyle) = putEnum("cardStyle", _cardStyle, v)

    val swipeLeft: StateFlow<SwipeAction> = _swipeLeft.asStateFlow()
    val swipeRight: StateFlow<SwipeAction> = _swipeRight.asStateFlow()
    val swipeUp: StateFlow<SwipeAction> = _swipeUp.asStateFlow()
    val swipeDown: StateFlow<SwipeAction> = _swipeDown.asStateFlow()
    val snoozeMorning: StateFlow<Int> = _morning.asStateFlow()
    val snoozeEvening: StateFlow<Int> = _evening.asStateFlow()
    val snoozeWeekendDay: StateFlow<Int> = _weekendDay.asStateFlow()
    val snoozeUnread: StateFlow<Boolean> = _snoozeUnread.asStateFlow()
    val trustedSenders: StateFlow<Set<String>> = _trusted.asStateFlow()
    val notificationContent: StateFlow<NotificationContent> = _notifContent.asStateFlow()
    val quietHours: StateFlow<Boolean> = _quiet.asStateFlow()
    val quietStart: StateFlow<Int> = _quietStart.asStateFlow()
    val quietEnd: StateFlow<Int> = _quietEnd.asStateFlow()
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()
    val linkCheck: StateFlow<LinkCheck> = _linkCheck.asStateFlow()

    private fun <E : Enum<E>> putEnum(key: String, flow: MutableStateFlow<E>, v: E) { prefs.edit().putString(key, v.name).apply(); flow.value = v }
    private fun putInt(key: String, flow: MutableStateFlow<Int>, v: Int) { prefs.edit().putInt(key, v).apply(); flow.value = v }
    private fun putBool(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) { prefs.edit().putBoolean(key, v).apply(); flow.value = v }

    fun setSwipeLeft(v: SwipeAction) = putEnum("swipeLeft", _swipeLeft, v)
    fun setSwipeRight(v: SwipeAction) = putEnum("swipeRight", _swipeRight, v)
    fun setSwipeUp(v: SwipeAction) = putEnum("swipeUp", _swipeUp, v)
    fun setSwipeDown(v: SwipeAction) = putEnum("swipeDown", _swipeDown, v)
    fun setSnoozeMorning(v: Int) = putInt("snoozeMorning", _morning, v)
    fun setSnoozeEvening(v: Int) = putInt("snoozeEvening", _evening, v)
    fun setSnoozeWeekendDay(v: Int) = putInt("snoozeWeekendDay", _weekendDay, v)
    fun setSnoozeUnread(v: Boolean) = putBool("snoozeUnread", _snoozeUnread, v)
    fun setNotificationContent(v: NotificationContent) = putEnum("notifContent", _notifContent, v)
    fun setQuiet(v: Boolean) = putBool("quiet", _quiet, v)
    fun setQuietStart(v: Int) = putInt("quietStart", _quietStart, v)
    fun setQuietEnd(v: Int) = putInt("quietEnd", _quietEnd, v)
    fun setAppLock(v: Boolean) = putBool("appLock", _appLock, v)
    fun setLinkCheck(v: LinkCheck) = putEnum("linkCheck", _linkCheck, v)

    fun isTrusted(email: String) = email.trim().lowercase() in _trusted.value
    fun setTrusted(email: String, trusted: Boolean) {
        val key = email.trim().lowercase()
        if (key.isEmpty()) return
        val next = if (trusted) _trusted.value + key else _trusted.value - key
        prefs.edit().putStringSet("trustedSenders", next).apply()
        _trusted.value = next
    }

    /** Liegt die aktuelle Uhrzeit in den Ruhezeiten? */
    fun inQuietHours(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        if (!_quiet.value) return false
        val h = now.get(java.util.Calendar.HOUR_OF_DAY)
        val start = _quietStart.value
        val end = _quietEnd.value
        return if (start <= end) h in start until end else h >= start || h < end
    }

    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamic.asStateFlow()
    val syncMode: StateFlow<SyncMode> = _sync.asStateFlow()
    val loadImages: StateFlow<Boolean> = _images.asStateFlow()
    val startInInbox: StateFlow<Boolean> = _startInbox.asStateFlow()
    val deskRange: StateFlow<DeskRange> = _range.asStateFlow()
    val deskUnreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()

    fun setTheme(v: ThemeMode) { prefs.edit().putString("theme", v.name).apply(); _theme.value = v }
    fun setDynamic(v: Boolean) { prefs.edit().putBoolean("dynamic", v).apply(); _dynamic.value = v }
    fun setSync(v: SyncMode) { prefs.edit().putString("sync", v.name).apply(); _sync.value = v }
    fun setLoadImages(v: Boolean) { prefs.edit().putBoolean("images", v).apply(); _images.value = v }
    fun setStartInInbox(v: Boolean) { prefs.edit().putBoolean("startInbox", v).apply(); _startInbox.value = v }
    fun setRange(v: DeskRange) { prefs.edit().putString("range", v.name).apply(); _range.value = v }
    fun setUnreadOnly(v: Boolean) { prefs.edit().putBoolean("unreadOnly", v).apply(); _unreadOnly.value = v }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}

/** Mail-Inhalte liegen als Dateien vor (große Newsletter sprengen sonst die Datenbank). */
class BodyStore(context: Context) {
    private val bodies = File(context.filesDir, "bodies").apply { mkdirs() }
    private val raw = File(context.filesDir, "raw").apply { mkdirs() }
    private val attachments = File(context.cacheDir, "attachments").apply { mkdirs() }

    private fun html(id: Long) = File(bodies, "$id.html")
    private fun text(id: Long) = File(bodies, "$id.txt")
    fun rawFile(id: Long) = File(raw, "$id.eml")

    fun saveHtml(id: Long, value: String) = html(id).writeText(value, Charsets.UTF_8)
    fun saveText(id: Long, value: String) = text(id).writeText(value, Charsets.UTF_8)
    fun readHtml(id: Long): String? = html(id).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    fun readText(id: Long): String? = text(id).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    fun attachmentDir(id: Long, section: String) = File(attachments, "$id/${section.replace('.', '_')}").apply { mkdirs() }

    fun delete(ids: Collection<Long>) {
        for (id in ids) {
            html(id).delete()
            text(id).delete()
            rawFile(id).delete()
            File(attachments, "$id").deleteRecursively()
        }
    }
}
