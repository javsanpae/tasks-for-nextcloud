// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.auth.Crypto
import app.nctasks.data.ical.ICalComponent
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.repo.CacheSerializer
import app.nctasks.data.repo.EncryptedFileLocalStore
import app.nctasks.data.repo.PendingOp
import app.nctasks.data.repo.PersistedState
import app.nctasks.data.repo.StoredTask
import app.nctasks.domain.TaskList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TestCrypto(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : Crypto {
    var destroyed = false
    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12)) }
        c.doFinal(blob, 12, blob.size - 12)
    } catch (_: Exception) { null }
    override fun destroyKey() { destroyed = true }
}

class CacheStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ics(title: String) = ICalParser.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$title\r\nSUMMARY:$title\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")

    private fun state() = PersistedState(
        lists = listOf(TaskList("https://c/l/", "Léa’s list", 0xFFFF9500.toInt()), TaskList("https://c/r/", "RO", null, canWrite = false)),
        tasks = listOf(StoredTask("https://c/l/a.ics", "\"e1\"", "https://c/l/", ics("Secret title")), StoredTask("https://c/l/b.ics", null, "https://c/l/", ics("B"))),
        ops = listOf(
            PendingOp.Create("https://c/l/b.ics", "https://c/l/", ics("B")),
            PendingOp.Update("https://c/l/a.ics", "https://c/l/", "\"e1\"", ics("Secret title"), ics("Secret title 2")),
            PendingOp.Delete("https://c/l/c.ics", "https://c/l/", null, null),
            PendingOp.Delete("https://c/l/d.ics", "https://c/l/", "\"e9\"", ics("D")),
        ),
    )

    @Test fun `serializer round-trips everything`() {
        val back = CacheSerializer.fromJson(CacheSerializer.toJson(state()))!!
        assertEquals(listOf("Léa’s list", "RO"), back.lists.map { it.name })
        assertEquals(0xFFFF9500.toInt(), back.lists[0].color); assertNull(back.lists[1].color); assertFalse(back.lists[1].canWrite)
        assertEquals(listOf("\"e1\"", null), back.tasks.map { it.etag })
        assertEquals("Secret title", back.tasks[0].ics.component("VTODO")!!.property("SUMMARY")!!.value)
        assertTrue(back.ops[0] is PendingOp.Create)
        val u = back.ops[1] as PendingOp.Update
        assertEquals("Secret title 2", u.ics.component("VTODO")!!.property("SUMMARY")!!.value)
        assertEquals("\"e1\"", u.baseEtag)
        val d1 = back.ops[2] as PendingOp.Delete; assertNull(d1.etag); assertNull(d1.baseIcs)
        val d2 = back.ops[3] as PendingOp.Delete; assertEquals("\"e9\"", d2.etag); assertNotNull(d2.baseIcs)
    }

    @Test fun `garbage and unknown versions are rejected not crashed on`() {
        assertNull(CacheSerializer.fromJson("not json"))
        assertNull(CacheSerializer.fromJson("""{"v":99,"lists":[],"tasks":[],"ops":[]}"""))
        assertNull(CacheSerializer.fromJson("""{"v":1,"lists":[],"tasks":[],"ops":[{"t":"explode","url":"x","listUrl":"y"}]}"""))
    }

    @Test fun `file is encrypted, round-trips, and leaves no temp file`() {
        val file = tmp.newFile("cache.bin"); file.delete()
        val store = EncryptedFileLocalStore(file, TestCrypto())
        store.save(state())
        val bytes = file.readBytes()
        assertFalse("plaintext must not appear on disk", String(bytes, Charsets.ISO_8859_1).contains("Secret title"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("https://c/l/"))
        assertFalse(java.io.File(file.parentFile, "cache.bin.tmp").exists())
        assertEquals(2, store.load()!!.tasks.size)
        store.save(state())          // overwriting works
        assertEquals(4, store.load()!!.ops.size)
    }

    @Test fun `wrong key or tampered file reads as empty`() {
        val file = tmp.newFile("cache.bin"); file.delete()
        EncryptedFileLocalStore(file, TestCrypto()).save(state())
        assertNull(EncryptedFileLocalStore(file, TestCrypto()).load())          // different key
        val crypto = TestCrypto()
        val store = EncryptedFileLocalStore(file, crypto)
        store.save(state())
        val bytes = file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte(); file.writeBytes(bytes)
        assertNull(store.load())                                                  // GCM tag check fails
    }

    @Test fun `missing file loads null and clear removes the file`() {
        val file = tmp.newFile("cache.bin"); file.delete()
        val store = EncryptedFileLocalStore(file, TestCrypto())
        assertNull(store.load())
        store.save(state()); assertTrue(file.exists())
        store.clear(); assertFalse(file.exists()); assertNull(store.load())
    }

    @Test fun `unused component helper`() = assertEquals("X", ICalComponent("X").name)
}
