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
