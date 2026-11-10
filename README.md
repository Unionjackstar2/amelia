# Amelia

E-Mail-App für Android im Stil der webOS-Kartenansicht – ein Projekt von **Tedoki Labs**.
Ohne Google-Dienste, ohne Firebase, ohne Tracking.

## Funktionen

- **Schreibtisch:** Neue Mails liegen als Glas-Karten nebeneinander. Nach oben wischen = Papierkorb, nach unten wischen = parken (kommt zum gewählten Zeitpunkt mit Erinnerung zurück).
- **Posteingang:** Klassische Liste mit Ordnern, Sammel-Posteingang, Mehrfachauswahl, Wischen zum Löschen/Gelesen, Archivieren und Verschieben. Vom Schreibtisch aus nach oben ziehen.
- **IMAP** mit Push (IDLE), CONDSTORE-Schnellabgleich, Sonderordner-Erkennung, „Gesendet“-Ablage auf dem Server.
- **POP3** mit UIDL (Nachrichten werden eindeutig erkannt), wahlweise auf dem Server lassen.
- **SMTP** mit SSL/TLS oder STARTTLS, Anhänge, korrekte Antwort-Verkettung.
- Rückgängig für Löschen/Verschieben, Offline-Warteschlange, externe Bilder standardmäßig blockiert.
- Passwörter verschlüsselt im Android-Keystore (AES-256-GCM).

## Bauen

Der Workflow `.github/workflows/build.yml` entpackt eine ins Repository geladene ZIP, übernimmt den Quellcode und baut eine signierte APK.

Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` (optional `KEY_ALIAS`, `KEY_PASSWORD`). Ohne Secrets wird mit dem Debug-Schlüssel signiert.
