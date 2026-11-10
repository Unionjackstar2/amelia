package de.tedoki.amelia.mail.imap

import de.tedoki.amelia.mail.net.MailSocket
import java.util.Locale

sealed class ImapValue {
    data class Atom(val text: String) : ImapValue()
    class Str(val bytes: ByteArray) : ImapValue() {
        val text: String get() = String(bytes, Charsets.UTF_8)
        override fun toString(): String = "Str($text)"
    }
    object Nil : ImapValue() {
        override fun toString(): String = "NIL"
    }
    class ListV(val items: List<ImapValue>) : ImapValue() {
        override fun toString(): String = items.joinToString(" ", "(", ")")
    }

    /** Text eines Atoms oder Strings, sonst null. */
    val asText: String?
        get() = when (this) {
            is Atom -> text
            is Str -> text
            else -> null
        }

    /** Zurück in IMAP-Syntax (Literale werden zu quoted Strings), z. B. zum Speichern der BODYSTRUCTURE. */
    fun toImapString(): String = when (this) {
        is Atom -> text
        is Nil -> "NIL"
        is Str -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ") + "\""
        is ListV -> items.joinToString(" ", "(", ")") { it.toImapString() }
    }
}

/**
 * Eine vollständige Antwortzeile vom Server (inklusive eingebetteter Literale).
 * [tag] ist "*", "+" oder das Kommando-Tag.
 */
class ImapResponse(private val segments: List<Any>) {

    val text: String = buildString {
        for (s in segments) if (s is String) append(s) else append(" {lit} ")
    }

    val tag: String = text.substringBefore(' ')

    val isUntagged: Boolean get() = tag == "*"
    val isContinuation: Boolean get() = tag == "+"

    /** Status bei getaggten Antworten: OK, NO oder BAD. */
    val status: String get() = text.substringAfter(' ', "").substringBefore(' ').uppercase(Locale.US)

    /** Alle Werte nach dem Tag. */
    val values: List<ImapValue> by lazy { tokenize(segments, tag.length + 1) }

    /** Für "* 12 FETCH (...)": die Sequenznummer und die Schlüssel-Wert-Paare. */
    fun fetchData(): Pair<Int, Map<String, ImapValue>>? {
        val v = values
        if (v.size < 3) return null
        val seq = (v[0] as? ImapValue.Atom)?.text?.toIntOrNull() ?: return null
        if ((v[1] as? ImapValue.Atom)?.text?.equals("FETCH", true) != true) return null
        val list = v[2] as? ImapValue.ListV ?: return null
        val map = LinkedHashMap<String, ImapValue>()
        var i = 0
        while (i + 1 < list.items.size) {
            val key = list.items[i].asText?.uppercase(Locale.US)
            if (key != null) map[normalizeKey(key)] = list.items[i + 1]
            i += 2
        }
        return seq to map
    }

    override fun toString(): String = text

    companion object {
        private val LITERAL_AT_END = Regex("\\{(\\d+)\\+?}$")

        fun read(socket: MailSocket): ImapResponse {
            val segments = mutableListOf<Any>()
            while (true) {
                val line = socket.readLine()
                val m = LITERAL_AT_END.find(line)
                if (m != null) {
                    segments.add(line.substring(0, m.range.first))
                    segments.add(socket.readExact(m.groupValues[1].toInt()))
                    continue
                }
                segments.add(line)
                return ImapResponse(segments)
            }
        }

        /** "BODY[1.2]<0>" → "BODY[1.2]", "BODY[HEADER.FIELDS (FROM)]" → "BODY[HEADER.FIELDS]" */
        fun normalizeKey(key: String): String {
            var k = key
            val lt = k.lastIndexOf('<')
            if (lt > 0 && k.endsWith(">") && k.lastIndexOf(']') < lt) k = k.substring(0, lt)
            val sp = k.indexOf(" (")
            if (k.startsWith("BODY[") && sp > 0) k = k.substring(0, sp) + "]"
            return k
        }

        fun parse(text: String): List<ImapValue> = tokenize(listOf(text), 0)

        fun tokenize(segments: List<Any>, startOffset: Int): List<ImapValue> {
            val root = mutableListOf<ImapValue>()
            val stack = ArrayDeque<MutableList<ImapValue>>()
            stack.addLast(root)
            for ((index, segment) in segments.withIndex()) {
                if (segment is ByteArray) {
                    stack.last().add(ImapValue.Str(segment))
                    continue
                }
                val s = segment as String
                var i = if (index == 0) minOf(startOffset, s.length) else 0
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == ' ' || c == '\t' -> i++
                        c == '(' -> {
                            val list = mutableListOf<ImapValue>()
                            stack.last().add(ImapValue.ListV(list))
                            stack.addLast(list)
                            i++
                        }
                        c == ')' -> {
                            if (stack.size > 1) stack.removeLast()
                            i++
                        }
                        c == '"' -> {
                            val sb = StringBuilder()
                            i++
                            while (i < s.length && s[i] != '"') {
                                if (s[i] == '\\' && i + 1 < s.length) i++
                                sb.append(s[i])
                                i++
                            }
                            i++ // schließendes "
                            stack.last().add(ImapValue.Str(sb.toString().toByteArray(Charsets.UTF_8)))
                        }
                        else -> {
                            val start = i
                            while (i < s.length) {
                                val ch = s[i]
                                if (ch == '[') {
                                    val close = s.indexOf(']', i)
                                    i = if (close < 0) s.length else close + 1
                                    continue
                                }
                                if (ch == ' ' || ch == '(' || ch == ')' || ch == '"') break
                                i++
                            }
                            val atom = s.substring(start, i)
                            if (atom.isEmpty()) {
                                i++
                            } else {
                                stack.last().add(if (atom.equals("NIL", true)) ImapValue.Nil else ImapValue.Atom(atom))
                            }
                        }
                    }
                }
            }
            return root
        }
    }
}
