package de.tedoki.amelia.ui.detail

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.mail.mime.MimeUtil
import java.net.URLEncoder
import java.util.Locale

/** Ein angeklickter Link samt sichtbarem Linktext (falls bekannt). */
data class LinkRequest(val url: String, val text: String?)

object LinkSafety {
    const val LINK_HOST = "amelia.link"

    private val ANCHOR = Regex("(?is)<a\\b([^>]*?)\\bhref\\s*=\\s*([\"'])(.*?)\\2([^>]*)>(.*?)</a\\s*>")
    private val DOMAIN_IN_TEXT = Regex("(?i)(?:https?://)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(?:[/:?#]|\\s|$)")
    private val IP_HOST = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * Schreibt Links in HTML so um, dass beim Antippen auch der sichtbare Text bekannt ist.
     * Nur so lässt sich erkennen, ob „sparkasse.de“ in Wahrheit woandershin führt.
     */
    fun rewriteAnchors(html: String): String = ANCHOR.replace(html) { m ->
        val href = MimeUtil.decodeEntities(m.groupValues[3]).trim()
        if (!href.startsWith("http://", true) && !href.startsWith("https://", true)) return@replace m.value
        val text = MimeUtil.htmlToText(m.groupValues[5]).replace(Regex("\\s+"), " ").trim().take(200)
        val target = "https://$LINK_HOST/?u=" + enc(href) + "&t=" + enc(text)
        "<a${m.groupValues[1]}href=\"$target\"${m.groupValues[4]}>${m.groupValues[5]}</a>"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Liest einen umgeschriebenen Link wieder aus. */
    fun parse(uri: Uri): LinkRequest? {
        if (uri.host != LINK_HOST) return null
        val url = uri.getQueryParameter("u") ?: return null
        return LinkRequest(url, uri.getQueryParameter("t")?.takeIf { it.isNotBlank() })
    }

    /** Grobe Bestimmung der „eigentlichen“ Domain (beispiel.de, beispiel.co.uk). */
    fun registrable(host: String): String {
        val labels = host.lowercase(Locale.US).trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val second = labels[labels.size - 2]
        val tld = labels.last()
        val take = if (tld.length == 2 && second in setOf("co", "com", "org", "net", "gv", "ac", "or", "ne", "gov", "edu")) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    fun host(url: String): String = try { Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }

    /** Liefert Warnungen zu einem Link; leer = unauffällig. */
    fun warnings(link: LinkRequest): List<String> {
        val result = mutableListOf<String>()
        val uri = try { Uri.parse(link.url) } catch (_: Exception) { null }
        val host = uri?.host.orEmpty().lowercase(Locale.US)
        if (host.isEmpty()) {
            result.add("Die Adresse ist ungewöhnlich aufgebaut.")
            return result
        }
        link.text?.let { text ->
            val shown = DOMAIN_IN_TEXT.find(text)?.groupValues?.get(1)
            if (shown != null && shown.contains('.') && registrable(shown) != registrable(host)) {
                result.add("Der Linktext zeigt „$shown“, der Link führt aber zu „${registrable(host)}“.")
            }
        }
        if (uri?.scheme.equals("http", true)) result.add("Die Verbindung ist nicht verschlüsselt (http).")
        if (host.split('.').any { it.startsWith("xn--") }) result.add("Die Adresse enthält ungewöhnliche Schriftzeichen, die bekannten Namen ähneln können.")
        if (IP_HOST.matches(host)) result.add("Der Link führt zu einer reinen IP-Adresse statt zu einem Namen.")
        if (uri?.encodedUserInfo != null) result.add("Die Adresse enthält ein „@“ und täuscht womöglich ein anderes Ziel vor.")
        return result
    }

    private val BRANDS = mapOf(
        "paypal" to listOf("paypal"), "amazon" to listOf("amazon"), "sparkasse" to listOf("sparkasse", "s-"),
        "volksbank" to listOf("volksbank", "vr"), "raiffeisen" to listOf("raiffeisen", "vr"), "dhl" to listOf("dhl", "deutschepost"),
        "deutsche post" to listOf("deutschepost", "dhl"), "telekom" to listOf("telekom", "t-online"), "apple" to listOf("apple", "icloud"),
        "microsoft" to listOf("microsoft", "outlook", "office"), "google" to listOf("google", "gmail"), "netflix" to listOf("netflix"),
        "ebay" to listOf("ebay"), "postbank" to listOf("postbank"), "commerzbank" to listOf("commerzbank"),
        "deutsche bank" to listOf("deutsche-bank", "db.com"), "dkb" to listOf("dkb"), "n26" to listOf("n26"),
        "vodafone" to listOf("vodafone"), "elster" to listOf("elster"), "comdirect" to listOf("comdirect"),
        "klarna" to listOf("klarna"), "hermes" to listOf("hermes", "myhermes"), "dpd" to listOf("dpd")
    )

    /** Hinweise zum Absender: fehlgeschlagene Echtheitsprüfung, verdächtige Anzeigenamen. */
    fun senderWarnings(m: Message): List<String> {
        val result = mutableListOf<String>()
        val domain = m.fromEmail.substringAfter('@', "").lowercase(Locale.US)
        if (m.authResult == "fail") {
            result.add("Dein Mailanbieter konnte nicht bestätigen, dass diese Mail wirklich von „$domain“ stammt. Vorsicht bei Links und Anhängen.")
        }
        val nameEmail = Regex("[^\\s<>\"]+@[^\\s<>\"]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE).find(m.fromName)?.value
        if (nameEmail != null && !nameEmail.equals(m.fromEmail, true)) {
            result.add("Der Anzeigename nennt „$nameEmail“, gesendet wurde aber von „${m.fromEmail}“.")
        }
        val name = m.fromName.lowercase(Locale.GERMAN)
        if (domain.isNotEmpty()) {
            for ((brand, keys) in BRANDS) {
                val inName = Regex("(^|[^a-z])${Regex.escape(brand)}([^a-z]|$)").containsMatchIn(name)
                if (inName && keys.none { domain.contains(it) }) {
                    result.add("Der Name nennt „${brand.replaceFirstChar { it.uppercase() }}“, die Adresse gehört aber zu „${registrable(domain)}“.")
                    break
                }
            }
        }
        return result
    }
}

@Composable
fun SenderWarningCard(warnings: List<String>) {
    if (warnings.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Zeigt das echte Ziel eines Links, bevor er geöffnet wird. */
@Composable
fun LinkDialog(link: LinkRequest, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val warnings = LinkSafety.warnings(link)
    val host = LinkSafety.host(link.url)
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (warnings.isEmpty()) Icons.Outlined.Lock else Icons.Outlined.WarningAmber, null,
                tint = if (warnings.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        title = { Text(if (warnings.isEmpty()) "Link öffnen?" else "Vorsicht bei diesem Link") },
        text = {
            Column {
                Text("Ziel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(LinkSafety.registrable(host).ifBlank { host }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        link.url.take(400),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).padding(10.dp)
                    ) {
                        warnings.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text(if (warnings.isEmpty()) "Öffnen" else "Trotzdem öffnen", color = if (warnings.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(link.url)); onDismiss() }) { Text("Kopieren") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}
