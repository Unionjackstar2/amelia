<p align="center">
  <img src="app/src/main/res/drawable/amelia_logo.png" alt="Amelia" width="140">
</p>

<h1 align="center">Amelia</h1>

<p align="center">
  <strong>Deine Post als Kartenstapel.</strong><br>
  Die E-Mail-App für Android, die den Kartenstil von webOS mit einem vollwertigen Posteingang verbindet.
</p>

<p align="center">
  <a href="https://github.com/Unionjackstar2/tedoki-Amelia/releases/latest"><strong>⬇ Neueste Version herunterladen</strong></a>
</p>

---

Schluss mit der endlosen Liste. Amelia legt dir deine Mails als Karten auf den Schreibtisch – eine nach der anderen, bis alles erledigt ist. Und wenn du doch einmal suchen, sortieren oder aufräumen willst, liegt der klassische Posteingang nur einen Wisch darunter.

Kein Google-Konto, kein Firebase, keine Werbung, kein Tracking. Amelia spricht mit genau einem Gegenüber: deinem Mailserver.

<!-- Screenshots: Bilder in einen Ordner "docs/" legen und hier einbinden, z. B.
<p align="center">
  <img src="docs/schreibtisch.png" width="240">
  <img src="docs/posteingang.png" width="240">
  <img src="docs/lesen.png" width="240">
</p>
-->

## Der Schreibtisch

Neue Mails liegen als Karten nebeneinander: vorne die aktuelle, links und rechts lugen die nächsten hervor. Jede Karte zeigt Absender, Betreff, den Anfang des Textes und ob ein Anhang dabei ist.

| Geste | Was passiert |
| --- | --- |
| Wisch zur Seite | Die nächste Karte kommt nach vorn |
| Tippen | Die Mail öffnet sich |
| Wisch nach oben | Ab in den Papierkorb – die Karte glüht rot |
| Wisch nach unten | Geparkt bis heute Abend, morgen früh, am Wochenende … und kommt mit Erinnerung zurück |

Du bestimmst, was auf dem Tisch liegt – heute, zwei Tage, eine Woche, ein Monat oder alles, auf Wunsch nur Ungelesenes. Einfach lostippen durchsucht alle Mails, die Treffer erscheinen sofort als Karten. Ist der Stapel leer, sagt Amelia schlicht: **Alles erledigt.**

## Der Posteingang – einen Wisch entfernt

Manchmal braucht man eben doch eine Liste. Zieh das Dock nach oben, und der Posteingang folgt deinem Finger.

- Sammel-Posteingang für alle Konten, dazu jedes Konto mit seinen Ordnern
- Eigene Ansichten für **Markiert** und **Geparkt**
- Wischen zum Löschen oder Als-gelesen-Markieren
- Mehrfachauswahl zum Archivieren, Verschieben und Aufräumen in einem Rutsch
- Ältere Mails jederzeit vom Server nachladen

Zwei Ansichten, ein Postfach: der Schreibtisch zum Abarbeiten, der Posteingang zum Verwalten.

## Verspielt aussehen, ernsthaft arbeiten

- **Sofort informiert** – Push über IMAP IDLE. Neue Mails kommen an, sobald sie auf dem Server liegen, auch nach einem Wechsel zwischen WLAN und Mobilfunk.
- **Blitzschneller Abgleich** – Amelia fragt zuerst nur, ob sich etwas geändert hat, und holt neue Mails gebündelt. Öffnest du eine Mail, ist sie meistens schon da.
- **POP3, wie es sein soll** – jede Nachricht wird über ihre feste Server-ID erkannt. Nichts doppelt, nichts versehentlich gelöscht.
- **Gesendet bleibt gesendet** – verschickte Mails landen im „Gesendet“-Ordner auf dem Server und sind auch am PC sichtbar.
- **Rückgängig, immer** – gelöscht oder verschoben? Fünf Sekunden lang genügt ein Tipp. Ohne Netz merkt sich Amelia jede Aktion und holt sie später nach.
- **In einer Minute eingerichtet** – E-Mail-Adresse und Passwort eingeben. Für GMX, WEB.DE, T-Online, Gmail, Posteo, mailbox.org, IONOS, Strato, freenet und viele mehr füllt Amelia den Rest selbst aus und prüft die Verbindung, bevor gespeichert wird.

## Deine Post gehört dir

- **Passwörter im Tresor** – verschlüsselt im Hardware-Schlüsselspeicher deines Handys (AES-256), nie im Klartext.
- **Geprüfte Verbindungen** – Amelia kontrolliert, ob das Zertifikat wirklich zu deinem Server gehört.
- **Tracking-Bilder blockiert** – Newsletter erfahren nicht heimlich, wann du sie geöffnet hast. Ein Tipp lädt die Bilder, wenn du es willst.
- **Keine Skripte** – Code in HTML-Mails wird nie ausgeführt.

## Glas trifft Material

Karten, Leisten und Menüs wirken wie mattes Glas mit feinem Lichtrand, dahinter schimmert ein ruhiger Farbschein in Tintenblau, Salbeigrün und einem Funken Bernstein. Dazu kommen kräftige Überschriften, großzügige Rundungen und Bewegung mit Charakter aus Material 3 Expressive: Die Schreiben-Taste morpht beim Drücken, Karten federn zurück, wenn du sie loslässt.

Newsletter stehen auf einem hellen Briefbogen und bleiben auch im Dunkelmodus lesbar. Wer mag, übernimmt die Farben aus dem eigenen Hintergrundbild (Material You).

## Installation

1. Die neueste APK unter [**Releases**](https://github.com/Unionjackstar2/tedoki-Amelia/releases/latest) herunterladen.
2. Auf dem Handy öffnen und die Installation aus dieser Quelle erlauben.
3. Amelia starten, E-Mail-Adresse und Passwort eingeben – fertig.

**Voraussetzung:** Android 8.0 oder neuer. Funktioniert mit jedem Anbieter, der IMAP oder POP3 und SMTP anbietet, auch mit eigenen Mailservern.

> **Tipp für Push:** Damit neue Mails sofort ankommen, in den Einstellungen von Amelia die Akku-Optimierung ausschalten.

## Hinweise

- Bei **Gmail, iCloud, Yahoo und Fastmail** wird ein App-Passwort benötigt. Amelia zeigt bei der Einrichtung einen passenden Hinweis.
- Bei **GMX und WEB.DE** muss der Abruf per IMAP/POP3 einmal im Webmailer freigeschaltet werden.
- Private **Outlook- und Hotmail-Konten** verlangen bei Microsoft zunehmend eine Anmeldung per OAuth, die Amelia noch nicht unterstützt.

## Geplant

- Entwürfe auf dem Server speichern
- Konversationsansicht
- Mails mit Formatierung schreiben

---

<p align="center">
  Amelia ist ein Projekt von <a href="https://tedoki.de"><strong>Tedoki Labs</strong></a> –<br>
  Werkzeuge für alle, die ihre Technik selbst in der Hand haben wollen.
</p>
