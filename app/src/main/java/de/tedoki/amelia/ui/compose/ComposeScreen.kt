package de.tedoki.amelia.ui.compose

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.data.Draft
import de.tedoki.amelia.data.DraftAttachment
import de.tedoki.amelia.data.db.Contact
import de.tedoki.amelia.data.db.identityList
import de.tedoki.amelia.mail.mime.Address
import de.tedoki.amelia.mail.mime.MimeUtil
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.ComposeArgs
import de.tedoki.amelia.ui.ComposeMode
import de.tedoki.amelia.ui.components.AccountDot
import de.tedoki.amelia.ui.components.Fmt
import de.tedoki.amelia.ui.components.GlassIconButton
import de.tedoki.amelia.ui.components.MorphFab
import de.tedoki.amelia.ui.components.MorphLoader
import de.tedoki.amelia.ui.components.Pill
import de.tedoki.amelia.ui.theme.glass
import kotlinx.coroutines.delay

private fun parseRecipients(text: String): List<Address> =
    MimeUtil.parseAddressList(text.replace(';', ',').replace('\n', ','))

@Composable
fun ComposeScreen(vm: AmeliaViewModel, args: ComposeArgs) {
    val accounts by vm.accounts.collectAsState()
    val context = LocalContext.current

    var loaded by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf(0L) }
    var fromEmail by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    var showCc by rememberSaveable { mutableStateOf(false) }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var replyToId by rememberSaveable { mutableStateOf<Long?>(null) }
    val attachments = remember { mutableStateListOf<DraftAttachment>() }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmNoSubject by remember { mutableStateOf(false) }
    var focusField by remember { mutableStateOf("to") }

    LaunchedEffect(args) {
        if (!loaded) {
            val init = vm.prepareCompose(args)
            accountId = init.accountId
            fromEmail = init.fromEmail
            to = init.to
            cc = init.cc
            showCc = init.cc.isNotBlank()
            subject = init.subject
            body = init.body
            replyToId = init.replyToId
            attachments.clear()
            attachments.addAll(init.attachments)
            loaded = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        for (uri in uris) {
            var name = "Anhang"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            attachments.add(DraftAttachment(name, mime, size, uri = uri.toString()))
        }
    }

    val dirty = loaded && (to.isNotBlank() || subject.isNotBlank() || attachments.isNotEmpty() || body.trim().length > 40)
    BackHandler { if (dirty) confirmDiscard = true else vm.pop() }

    fun doSend() {
        val toList = parseRecipients(to)
        val ccList = parseRecipients(cc)
        val bccList = parseRecipients(bcc)
        val invalid = (toList + ccList + bccList).filter { !MimeUtil.isValidEmail(it.email) }
        when {
            toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty() -> { error = "Bitte mindestens einen Empfänger angeben."; return }
            invalid.isNotEmpty() -> { error = "Ungültige Adresse: ${invalid.joinToString { it.email.ifBlank { it.name } }}"; return }
        }
        sending = true
        val account = accounts.orEmpty().firstOrNull { it.id == accountId }
        val identity = account?.identityList()?.firstOrNull { it.email.equals(fromEmail, true) }
        val draft = Draft(
            accountId, toList, ccList, bccList, subject.trim(), body, attachments.toList(), replyToId,
            fromName = identity?.name?.ifBlank { null },
            fromEmail = identity?.email
        )
        vm.send(draft) { result ->
            sending = false
            if (result == null) vm.pop() else error = result
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Outlined.Close, "Schließen") { if (dirty) confirmDiscard = true else vm.pop() }
            Spacer(Modifier.width(12.dp))
            Text(
                when (args.mode) {
                    ComposeMode.NEW -> "Neue Nachricht"
                    ComposeMode.REPLY, ComposeMode.REPLY_ALL -> "Antwort"
                    ComposeMode.FORWARD -> "Weiterleiten"
                },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
            )
            GlassIconButton(Icons.Outlined.Attachment, "Datei anhängen") { picker.launch(arrayOf("*/*")) }
            Spacer(Modifier.width(10.dp))
            if (sending) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { MorphLoader(size = 34.dp) }
            } else {
                MorphFab(Icons.AutoMirrored.Outlined.Send, null) {
                    if (subject.isBlank()) confirmNoSubject = true else doSend()
                }
            }
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MorphLoader() }
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
                val senders = accounts.orEmpty().flatMap { a -> a.identityList().map { a to it } }
                if (senders.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Von", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically).width(52.dp))
                        senders.forEach { (a, identity) ->
                            val selected = a.id == accountId && (identity.email.equals(fromEmail, true) || fromEmail.isBlank() && identity.email == a.email)
                            Pill(identity.email, selected) {
                                accountId = a.id
                                fromEmail = identity.email
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Field("An", to, { to = it; focusField = "to" }, KeyboardType.Email, trailing = {
                    if (!showCc) Text("Cc/Bcc", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showCc = true }.padding(6.dp))
                })
                Suggestions(vm, if (focusField == "to") to else "") { to = it }
                if (showCc) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Field("Cc", cc, { cc = it; focusField = "cc" }, KeyboardType.Email)
                    Suggestions(vm, if (focusField == "cc") cc else "") { cc = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Field("Bcc", bcc, { bcc = it; focusField = "bcc" }, KeyboardType.Email)
                    Suggestions(vm, if (focusField == "bcc") bcc else "") { bcc = it }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Field("Betreff", subject, { subject = it }, KeyboardType.Text, capitalize = true)
            }

            if (attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEachIndexed { index, a ->
                        Row(
                            Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Attachment, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (a.size > 0) Text(Fmt.size(a.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Icon(Icons.Outlined.Close, "Entfernen", tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { attachments.removeAt(index) }.padding(6.dp).size(16.dp))
                        }
                    }
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }

            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp).glass(RoundedCornerShape(28.dp)).padding(18.dp)) {
                if (body.isEmpty()) Text("Nachricht schreiben", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)
                )
            }
            Spacer(Modifier.navigationBarsPadding().size(24.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Entwurf verwerfen?") },
            text = { Text("Die Nachricht wird nicht gespeichert.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.pop() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter schreiben") } }
        )
    }
    if (confirmNoSubject) {
        AlertDialog(
            onDismissRequest = { confirmNoSubject = false },
            title = { Text("Ohne Betreff senden?") },
            confirmButton = { TextButton(onClick = { confirmNoSubject = false; doSend() }) { Text("Senden") } },
            dismissButton = { TextButton(onClick = { confirmNoSubject = false }) { Text("Betreff ergänzen") } }
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Nicht gesendet") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = scheme.onSurface).merge(MaterialTheme.typography.bodyLarge),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = type,
                imeAction = ImeAction.Next,
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None
            ),
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** Vorschläge aus bekannten Absendern für das zuletzt getippte Wort. */
@Composable
private fun Suggestions(vm: AmeliaViewModel, fieldText: String, onReplace: (String) -> Unit) {
    val token = fieldText.substringAfterLast(',').trim()
    var found by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(token) {
        delay(200)
        found = if (token.length < 2 || token.contains('@') && MimeUtil.isValidEmail(token)) emptyList() else vm.contacts(token)
    }
    if (found.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        found.forEach { c ->
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        val prefix = fieldText.substringBeforeLast(',', "").trim()
                        val entry = if (c.name.isBlank()) c.email else "\"${c.name.replace("\"", "")}\" <${c.email}>"
                        onReplace((if (prefix.isEmpty()) "" else "$prefix, ") + entry + ", ")
                        found = emptyList()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Person, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(6.dp))
                Text(c.name.ifBlank { c.email }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
