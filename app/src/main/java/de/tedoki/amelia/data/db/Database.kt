package de.tedoki.amelia.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import de.tedoki.amelia.mail.imap.FolderRole
import de.tedoki.amelia.mail.net.Security
import kotlinx.coroutines.flow.Flow

enum class Protocol { IMAP, POP3 }

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val senderName: String,
    val email: String,
    val protocol: Protocol,
    val inHost: String,
    val inPort: Int,
    val inSecurity: Security,
    val inUser: String,
    val outHost: String,
    val outPort: Int,
    val outSecurity: Security,
    val outUser: String,
    val passwordEnc: String = "",
    /** Leer = gleiches Passwort wie Posteingang. */
    val outPasswordEnc: String = "",
    val color: Int,
    val saveSentCopy: Boolean = true,
    val popLeaveOnServer: Boolean = true,
    val popDeleteOnServer: Boolean = true,
    val signature: String = "",
    val sortOrder: Int = 0,
    val lastSync: Long = 0,
    val lastError: String? = null,
    val supportsIdle: Boolean = false
)

@Entity(tableName = "folders", indices = [Index("accountId")])
data class Folder(
    @PrimaryKey val id: String,
    val accountId: Long,
    val path: String,
    val name: String,
    val role: FolderRole,
    val delimiter: String? = null,
    val selectable: Boolean = true,
    val isLocal: Boolean = false,
    val uidValidity: Long = 0,
    val uidNext: Long = 0,
    val highestModSeq: Long = 0,
    val messageCount: Int = 0,
    val lastSync: Long = 0,
    val hasMore: Boolean = false
) {
    companion object {
        fun idFor(accountId: Long, path: String) = "$accountId:$path"
        const val LOCAL_TRASH = "#trash"
        const val LOCAL_SENT = "#sent"
    }
}

object Pending {
    const val NONE = 0
    const val TRASH = 1
    const val DELETE = 2
    const val MOVE = 3
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["folderId", "uid", "remoteId"], unique = true),
        Index("accountId"),
        Index("date")
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: String,
    val uid: Long = 0,
    /** POP3-UIDL, bei IMAP leer. */
    val remoteId: String = "",
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val fromName: String = "",
    val fromEmail: String = "",
    val replyTo: String = "",
    val toList: String = "",
    val ccList: String = "",
    val subject: String = "",
    val date: Long = 0,
    val size: Long = 0,
    val snippet: String = "",
    /** Gekürzter Klartext für die Suche. */
    val preview: String = "",
    val bodyLoaded: Boolean = false,
    /** IMAP-BODYSTRUCTURE in Textform. */
    val structure: String = "",
    val hasAttachments: Boolean = false,
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val flagsDirty: Boolean = false,
    val pendingAction: Int = Pending.NONE,
    val pendingTarget: String? = null,
    val pendingAt: Long = 0,
    /** 0 = nicht geparkt. */
    val snoozedUntil: Long = 0,
    val listUnsubscribe: String = ""
)

val Message.senderDisplay: String get() = fromName.ifBlank { fromEmail.ifBlank { "Unbekannt" } }

data class LocalState(
    val id: Long,
    val uid: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val flagsDirty: Boolean,
    val pendingAction: Int
)

data class FolderCount(val folderId: String, val count: Int)

data class Contact(val name: String, val email: String, val uses: Int)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun flowAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    suspend fun all(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): Account?

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE accounts SET lastSync = :time, lastError = :error WHERE id = :id")
    suspend fun setSyncResult(id: Long, time: Long, error: String?)

    @Query("UPDATE accounts SET supportsIdle = :value WHERE id = :id")
    suspend fun setSupportsIdle(id: Long, value: Boolean)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY accountId")
    fun flowAll(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE accountId = :accountId")
    suspend fun forAccount(accountId: Long): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): Folder?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(folder: Folder): Long

    @Query("UPDATE folders SET name = :name, role = :role, delimiter = :delimiter, selectable = :selectable WHERE id = :id")
    suspend fun updateMeta(id: String, name: String, role: String, delimiter: String?, selectable: Boolean)

    @Query(
        """UPDATE folders SET uidValidity = :validity, uidNext = :uidNext, highestModSeq = :modSeq,
           messageCount = :count, lastSync = :time, hasMore = :hasMore WHERE id = :id"""
    )
    suspend fun updateState(id: String, validity: Long, uidNext: Long, modSeq: Long, count: Int, time: Long, hasMore: Boolean)

    @Query("UPDATE folders SET hasMore = :hasMore WHERE id = :id")
    suspend fun setHasMore(id: String, hasMore: Boolean)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)
}

@Dao
interface MessageDao {

    // ------------------------------------------------------------- Listen

    @Query(
        """SELECT m.* FROM messages m JOIN folders f ON f.id = m.folderId
           WHERE f.role = 'INBOX' AND m.pendingAction = 0 AND m.date >= :since
             AND (m.snoozedUntil = 0 OR m.snoozedUntil <= :now)
             AND (:unreadOnly = 0 OR m.seen = 0)
           ORDER BY m.date DESC LIMIT 400"""
    )
    fun flowDesk(since: Long, now: Long, unreadOnly: Boolean): Flow<List<Message>>

    @Query(
        """SELECT m.* FROM messages m JOIN folders f ON f.id = m.folderId
           WHERE f.role = 'INBOX' AND m.pendingAction = 0
             AND (m.snoozedUntil = 0 OR m.snoozedUntil <= :now)
           ORDER BY m.date DESC LIMIT :limit"""
    )
    fun flowUnifiedInbox(now: Long, limit: Int): Flow<List<Message>>

    @Query(
        """SELECT * FROM messages WHERE folderId = :folderId AND pendingAction = 0
             AND (snoozedUntil = 0 OR snoozedUntil <= :now)
           ORDER BY date DESC LIMIT :limit"""
    )
    fun flowFolder(folderId: String, now: Long, limit: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE snoozedUntil > :now AND pendingAction = 0 ORDER BY snoozedUntil")
    fun flowSnoozed(now: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE flagged = 1 AND pendingAction = 0 ORDER BY date DESC LIMIT 300")
    fun flowFlagged(): Flow<List<Message>>

    @Query(
        """SELECT * FROM messages WHERE pendingAction = 0 AND (
             subject LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%' OR fromEmail LIKE '%' || :q || '%'
             OR toList LIKE '%' || :q || '%' OR snippet LIKE '%' || :q || '%' OR preview LIKE '%' || :q || '%')
           ORDER BY date DESC LIMIT 200"""
    )
    fun flowSearch(q: String): Flow<List<Message>>

    @Query("SELECT folderId, COUNT(*) AS count FROM messages WHERE seen = 0 AND pendingAction = 0 GROUP BY folderId")
    fun flowUnreadCounts(): Flow<List<FolderCount>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun flowById(id: Long): Flow<Message?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): Message?

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMany(ids: Collection<Long>): List<Message>

    // ------------------------------------------------------------- Sync

    @Query("SELECT id, uid, seen, flagged, answered, flagsDirty, pendingAction FROM messages WHERE folderId = :folderId AND uid > 0")
    suspend fun localStates(folderId: String): List<LocalState>

    @Query("SELECT remoteId FROM messages WHERE accountId = :accountId AND remoteId != ''")
    suspend fun knownRemoteIds(accountId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: Message): Long

    @Query("UPDATE messages SET seen = :seen, flagged = :flagged, answered = :answered WHERE id = :id AND flagsDirty = 0")
    suspend fun applyServerFlags(id: Long, seen: Boolean, flagged: Boolean, answered: Boolean)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT id FROM messages WHERE folderId = :folderId")
    suspend fun idsInFolder(folderId: String): List<Long>

    @Query("SELECT id FROM messages WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: Long): List<Long>

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)

    @Query("SELECT MIN(uid) FROM messages WHERE folderId = :folderId AND uid > 0")
    suspend fun minUid(folderId: String): Long?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND flagsDirty = 1")
    suspend fun dirty(accountId: Long): List<Message>

    @Query("UPDATE messages SET flagsDirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND pendingAction != 0 AND pendingAt <= :before")
    suspend fun pendingFor(accountId: Long, before: Long): List<Message>

    @Query("SELECT DISTINCT accountId FROM messages WHERE pendingAction != 0")
    suspend fun accountsWithPending(): List<Long>

    @Query(
        """UPDATE messages SET bodyLoaded = 1, snippet = CASE WHEN :snippet = '' THEN snippet ELSE :snippet END,
           preview = CASE WHEN :preview = '' THEN preview ELSE :preview END WHERE id = :id"""
    )
    suspend fun markBodyLoaded(id: Long, snippet: String, preview: String)

    @Query("UPDATE messages SET folderId = :folderId, uid = :uid, pendingAction = 0, pendingTarget = NULL, pendingAt = 0 WHERE id = :id")
    suspend fun relocate(id: Long, folderId: String, uid: Long)

    // ------------------------------------------------------------- Aktionen

    @Query("UPDATE messages SET seen = :seen, flagsDirty = 1 WHERE id IN (:ids)")
    suspend fun setSeen(ids: List<Long>, seen: Boolean)

    @Query("UPDATE messages SET flagged = :flagged, flagsDirty = 1 WHERE id IN (:ids)")
    suspend fun setFlagged(ids: List<Long>, flagged: Boolean)

    @Query("UPDATE messages SET answered = 1, flagsDirty = 1 WHERE id = :id")
    suspend fun setAnswered(id: Long)

    @Query("UPDATE messages SET pendingAction = :action, pendingTarget = :target, pendingAt = :at WHERE id IN (:ids)")
    suspend fun setPending(ids: List<Long>, action: Int, target: String?, at: Long)

    @Query("UPDATE messages SET snoozedUntil = :until WHERE id = :id")
    suspend fun setSnooze(id: Long, until: Long)

    @Query(
        """SELECT fromName AS name, fromEmail AS email, COUNT(*) AS uses FROM messages
           WHERE fromEmail != '' AND (fromEmail LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%')
           GROUP BY LOWER(fromEmail) ORDER BY uses DESC LIMIT 6"""
    )
    suspend fun contacts(q: String): List<Contact>
}

@Database(entities = [Account::class, Folder::class, Message::class], version = 1, exportSchema = false)
abstract class AmeliaDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao

    companion object {
        @Volatile
        private var instance: AmeliaDatabase? = null

        fun get(context: Context): AmeliaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AmeliaDatabase::class.java, "amelia2.db")
                .fallbackToDestructiveMigration(true)
                .build()
                .also { instance = it }
        }
    }
}
