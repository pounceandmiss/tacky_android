package com.example.tackyapk

import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileCache over a fake transport: the download notify frame, <Update> fan-out
 * with tolerant (string-typed) loaded/total, terminal "done" paths, and that an
 * <Update> for another url leaves the tracked entry alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileCacheTest {

    private val acc = "me@x"
    private val url = "https://up.example/file.bin"

    @Test
    fun trackSendsDownloadNotify() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val cache = FileCache(client, backgroundScope).apply { start() }

        cache.track(acc, url) {}

        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size()) // notify: no token
        assertEquals("file", frame[0].asString)
        assertEquals("download", frame[1].asString)
        val a = frame[2].asJsonObject
        assertEquals(acc, a.get("acc").asString)
        assertEquals(url, a.get("url").asString)
    }

    @Test
    fun updatesDriveTrackedState() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val cache = FileCache(client, backgroundScope).apply { start() }

        val seen = mutableListOf<FileState>()
        cache.track(acc, url) { seen.add(it) }

        // loaded/total may arrive string-typed (outside the active jsonify schema).
        t.emit(
            """["event","file","<Update>",
                {"acc":"$acc","url":"$url","direction":"down","state":"active",
                 "loaded":"512","total":"1024"}]""",
        )
        val active = seen.last()
        assertEquals("active", active.state)
        assertEquals(512L, active.loaded)
        assertEquals(1024L, active.total)

        t.emit(
            """["event","file","<Update>",
                {"acc":"$acc","url":"$url","direction":"down","state":"done",
                 "loaded":1024,"total":1024,
                 "localpath":"/data/file.bin","thumbpath":"/data/file.thumb"}]""",
        )
        val done = seen.last()
        assertEquals("done", done.state)
        assertEquals("/data/file.bin", done.localPath)
        assertEquals("/data/file.thumb", done.thumbPath)
    }

    @Test
    fun updateForOtherUrlIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val cache = FileCache(client, backgroundScope).apply { start() }

        val seen = mutableListOf<FileState>()
        cache.track(acc, url) { seen.add(it) }
        val countBefore = seen.size

        t.emit(
            """["event","file","<Update>",
                {"acc":"$acc","url":"https://up.example/other.bin","state":"done",
                 "localpath":"/data/other.bin"}]""",
        )

        assertEquals(countBefore, seen.size)
        assertEquals("idle", seen.last().state)
        assertNull(seen.last().localPath)
    }

    @Test
    fun doneStateRepaintsOnRetrackWithoutRedownload() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val cache = FileCache(client, backgroundScope).apply { start() }

        val sub = cache.track(acc, url) {}
        t.emit(
            """["event","file","<Update>",
                {"acc":"$acc","url":"$url","state":"done","localpath":"/data/file.bin"}]""",
        )
        cache.untrack(sub)

        val sentBefore = t.sent.size
        var seeded: FileState? = null
        cache.track(acc, url) { seeded = it }

        assertEquals("done", seeded?.state)
        assertEquals("/data/file.bin", seeded?.localPath)
        assertEquals(sentBefore, t.sent.size) // no new download notify
    }

    @Test
    fun firstTrackKicksDownloadOnceForSharedSlots() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val cache = FileCache(client, backgroundScope).apply { start() }

        cache.track(acc, url) {}
        val afterFirst = t.sent.size
        cache.track(acc, url) {}

        assertTrue(afterFirst >= 1)
        assertEquals(afterFirst, t.sent.size) // second slot shares, no extra notify
    }
}
