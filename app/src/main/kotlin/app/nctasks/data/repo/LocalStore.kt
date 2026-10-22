// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.repo

import app.nctasks.data.auth.Crypto
import app.nctasks.data.ical.ICalComponent
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.ICalWriter
import app.nctasks.domain.TaskList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A change made locally that the server has not seen yet. Ops for one task are kept in order. */
sealed interface PendingOp {
    val url: String
    val listUrl: String

    data class Create(override val url: String, override val listUrl: String, val ics: ICalComponent) : PendingOp

    /** [baseEtag]/[baseIcs] describe the server version [ics] was derived from (used for 3-way merge). */
    data class Update(
        override val url: String,
        override val listUrl: String,
        val baseEtag: String?,
        val baseIcs: ICalComponent,
        val ics: ICalComponent,
    ) : PendingOp

    data class Delete(override val url: String, override val listUrl: String, val etag: String?, val baseIcs: ICalComponent?) : PendingOp
}

class StoredTask(val url: String, val etag: String?, val listUrl: String, val ics: ICalComponent)

/** Everything that survives a restart: the last known server state plus the unsynced changes. */
class PersistedState(val lists: List<TaskList>, val tasks: List<StoredTask>, val ops: List<PendingOp>)

interface LocalStore {
    fun load(): PersistedState?
    fun save(state: PersistedState)
    fun clear()
}

/** Non-persistent store for tests and previews. */
class InMemoryLocalStore : LocalStore {
    var state: PersistedState? = null
    override fun load() = state
    override fun save(state: PersistedState) { this.state = state }
    override fun clear() { state = null }
}

object CacheSerializer {
    private const val VERSION = 1

    fun toJson(state: PersistedState): String = JSONObject()
        .put("v", VERSION)
        .put("lists", JSONArray().also { a ->
            state.lists.forEach { l -> a.put(JSONObject().put("url", l.url).put("name", l.name).put("color", l.color ?: JSONObject.NULL).put("canWrite", l.canWrite)) }
        })
        .put("tasks", JSONArray().also { a ->
            state.tasks.forEach { t -> a.put(JSONObject().put("url", t.url).put("etag", t.etag ?: JSONObject.NULL).put("listUrl", t.listUrl).put("ics", ICalWriter.write(t.ics))) }
        })
        .put("ops", JSONArray().also { a -> state.ops.forEach { a.put(opToJson(it)) } })
        .toString()

    fun fromJson(text: String): PersistedState? = try {
        val o = JSONObject(text)
        if (o.getInt("v") != VERSION) null else PersistedState(
            lists = o.getJSONArray("lists").objects().map {
                TaskList(it.getString("url"), it.getString("name"), if (it.isNull("color")) null else it.getInt("color"), it.getBoolean("canWrite"))
            },
            tasks = o.getJSONArray("tasks").objects().map {
                StoredTask(it.getString("url"), it.optStringOrNull("etag"), it.getString("listUrl"), ICalParser.parse(it.getString("ics")))
            },
            ops = o.getJSONArray("ops").objects().map(::opFromJson),
        )
    } catch (_: Exception) {
        null // unreadable cache is simply dropped and re-downloaded; pending ops in it are lost only if corrupt
    }

    private fun opToJson(op: PendingOp): JSONObject = when (op) {
        is PendingOp.Create -> JSONObject().put("t", "create").put("url", op.url).put("listUrl", op.listUrl).put("ics", ICalWriter.write(op.ics))
        is PendingOp.Update -> JSONObject().put("t", "update").put("url", op.url).put("listUrl", op.listUrl)
            .put("baseEtag", op.baseEtag ?: JSONObject.NULL).put("baseIcs", ICalWriter.write(op.baseIcs)).put("ics", ICalWriter.write(op.ics))
        is PendingOp.Delete -> JSONObject().put("t", "delete").put("url", op.url).put("listUrl", op.listUrl)
            .put("etag", op.etag ?: JSONObject.NULL).put("baseIcs", op.baseIcs?.let(ICalWriter::write) ?: JSONObject.NULL)
    }

    private fun opFromJson(o: JSONObject): PendingOp = when (val type = o.getString("t")) {
        "create" -> PendingOp.Create(o.getString("url"), o.getString("listUrl"), ICalParser.parse(o.getString("ics")))
        "update" -> PendingOp.Update(o.getString("url"), o.getString("listUrl"), o.optStringOrNull("baseEtag"), ICalParser.parse(o.getString("baseIcs")), ICalParser.parse(o.getString("ics")))
        "delete" -> PendingOp.Delete(o.getString("url"), o.getString("listUrl"), o.optStringOrNull("etag"), o.optStringOrNull("baseIcs")?.let(ICalParser::parse))
        else -> throw IllegalArgumentException("unknown op $type")
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

/**
 * The cache as one AES-GCM encrypted file. Writes go to a temporary file that is atomically renamed,
 * so a crash can never leave a half-written cache. Place the file in a directory that is excluded
 * from backup (Context.noBackupFilesDir).
 */
class EncryptedFileLocalStore(private val file: File, private val crypto: Crypto) : LocalStore {
    override fun load(): PersistedState? {
        if (!file.exists()) return null
        val plain = try { crypto.decrypt(file.readBytes()) } catch (_: IOException) { null } ?: return null
        return CacheSerializer.fromJson(plain.toString(Charsets.UTF_8))
    }

    override fun save(state: PersistedState) {
        val blob = crypto.encrypt(CacheSerializer.toJson(state).toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
