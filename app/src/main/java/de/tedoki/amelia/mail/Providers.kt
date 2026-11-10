package de.tedoki.amelia.mail

import de.tedoki.amelia.mail.net.Security

data class ServerPreset(val host: String, val port: Int, val security: Security)

data class Provider(
    val name: String,
    val domains: List<String>,
    val imap: ServerPreset,
    val pop3: ServerPreset?,
    val smtp: ServerPreset,
    /** Server legt gesendete Mails selbst im Gesendet-Ordner ab. */
    val savesSentCopy: Boolean = false,
    /** Anmeldename ist nur der Teil vor dem @. */
    val localPartLogin: Boolean = false,
    val hint: String? = null
)

object Providers {

    private fun s(host: String, port: Int, sec: Security) = ServerPreset(host, port, sec)
    private val TLS = Security.SSL_TLS
    private val START = Security.STARTTLS

    val all: List<Provider> = listOf(
        Provider(
            "GMX", listOf("gmx.de", "gmx.net", "gmx.at", "gmx.ch", "gmx.com"),
            s("imap.gmx.net", 993, TLS), s("pop.gmx.net", 995, TLS), s("mail.gmx.net", 587, START),
            hint = "IMAP/POP3 muss im GMX-Webmailer unter Einstellungen → POP3/IMAP Abruf einmal erlaubt werden."
        ),
        Provider(
            "WEB.DE", listOf("web.de"),
            s("imap.web.de", 993, TLS), s("pop3.web.de", 995, TLS), s("smtp.web.de", 587, START),
            hint = "IMAP/POP3 muss im WEB.DE-Webmailer unter Einstellungen → POP3/IMAP Abruf einmal erlaubt werden."
        ),
        Provider(
            "T-Online", listOf("t-online.de", "magenta.de"),
            s("secureimap.t-online.de", 993, TLS), s("securepop.t-online.de", 995, TLS), s("securesmtp.t-online.de", 465, TLS),
            hint = "T-Online verlangt ein eigenes E-Mail-Passwort (nicht das Kundencenter-Passwort)."
        ),
        Provider(
            "Gmail", listOf("gmail.com", "googlemail.com"),
            s("imap.gmail.com", 993, TLS), s("pop.gmail.com", 995, TLS), s("smtp.gmail.com", 465, TLS),
            savesSentCopy = true,
            hint = "Gmail funktioniert nur mit einem App-Passwort (Google-Konto → Sicherheit → 2-Faktor → App-Passwörter)."
        ),
        Provider(
            "Outlook / Hotmail", listOf("outlook.com", "outlook.de", "hotmail.com", "hotmail.de", "live.com", "live.de", "msn.com"),
            s("outlook.office365.com", 993, TLS), s("outlook.office365.com", 995, TLS), s("smtp-mail.outlook.com", 587, START),
            savesSentCopy = true,
            hint = "Microsoft erlaubt bei privaten Konten oft nur noch OAuth-Anmeldung. Passwort-Anmeldung kann scheitern."
        ),
        Provider(
            "iCloud", listOf("icloud.com", "me.com", "mac.com"),
            s("imap.mail.me.com", 993, TLS), null, s("smtp.mail.me.com", 587, START),
            savesSentCopy = true,
            hint = "iCloud benötigt ein app-spezifisches Passwort (appleid.apple.com)."
        ),
        Provider(
            "Yahoo", listOf("yahoo.com", "yahoo.de", "ymail.com"),
            s("imap.mail.yahoo.com", 993, TLS), s("pop.mail.yahoo.com", 995, TLS), s("smtp.mail.yahoo.com", 465, TLS),
            hint = "Yahoo benötigt ein App-Passwort."
        ),
        Provider(
            "AOL", listOf("aol.com", "aol.de"),
            s("imap.aol.com", 993, TLS), s("pop.aol.com", 995, TLS), s("smtp.aol.com", 465, TLS),
            hint = "AOL benötigt ein App-Passwort."
        ),
        Provider(
            "mailbox.org", listOf("mailbox.org"),
            s("imap.mailbox.org", 993, TLS), s("pop3.mailbox.org", 995, TLS), s("smtp.mailbox.org", 465, TLS)
        ),
        Provider(
            "Posteo", listOf("posteo.de", "posteo.net", "posteo.org", "posteo.eu"),
            s("posteo.de", 993, TLS), s("posteo.de", 995, TLS), s("posteo.de", 465, TLS)
        ),
        Provider(
            "IONOS / 1&1", listOf("ionos.de", "online.de", "onlinehome.de", "1und1.de"),
            s("imap.ionos.de", 993, TLS), s("pop.ionos.de", 995, TLS), s("smtp.ionos.de", 465, TLS)
        ),
        Provider(
            "Strato", listOf("strato.de"),
            s("imap.strato.de", 993, TLS), s("pop3.strato.de", 995, TLS), s("smtp.strato.de", 465, TLS)
        ),
        Provider(
            "freenet", listOf("freenet.de"),
            s("mx.freenet.de", 993, TLS), s("mx.freenet.de", 995, TLS), s("mx.freenet.de", 587, START)
        ),
        Provider(
            "mail.de", listOf("mail.de"),
            s("imap.mail.de", 993, TLS), s("pop.mail.de", 995, TLS), s("smtp.mail.de", 465, TLS)
        ),
        Provider(
            "Fastmail", listOf("fastmail.com", "fastmail.fm"),
            s("imap.fastmail.com", 993, TLS), s("pop.fastmail.com", 995, TLS), s("smtp.fastmail.com", 465, TLS),
            hint = "Fastmail benötigt ein App-Passwort."
        ),
        Provider(
            "Vodafone", listOf("vodafone.de", "arcor.de", "vodafonemail.de"),
            s("imap.vodafonemail.de", 993, TLS), s("pop.vodafonemail.de", 995, TLS), s("smtp.vodafonemail.de", 465, TLS)
        )
    )

    fun forEmail(email: String): Provider? {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (domain.isEmpty()) return null
        return all.firstOrNull { p -> p.domains.any { it == domain } }
    }

    /** Vermutung für eigene Domains (z. B. beim Webhoster). */
    fun guess(email: String): Provider {
        val domain = email.substringAfter('@', "").trim().lowercase().ifBlank { "example.org" }
        return Provider(
            name = "Eigene Domain",
            domains = listOf(domain),
            imap = s("imap.$domain", 993, TLS),
            pop3 = s("pop.$domain", 995, TLS),
            smtp = s("smtp.$domain", 465, TLS),
            hint = "Die Serveradressen sind geschätzt. Die richtigen Daten stehen beim E-Mail-Anbieter oder Webhoster."
        )
    }
}
