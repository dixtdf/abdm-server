package dev.abdm.server.engine.native

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntervalSetTest {

    @Test
    fun `merges adjacent and overlapping intervals`() {
        val set = IntervalSet()
        set.add(0, 10)
        set.add(10, 20)
        set.add(15, 25)
        assertEquals(25, set.total())
        assertEquals(listOf(0L until 25L), set.snapshot())
    }

    @Test
    fun `keeps gaps and reports them as missing`() {
        val set = IntervalSet()
        set.add(10, 20)
        set.add(30, 40)
        assertEquals(listOf(0L until 10L, 20L until 30L, 40L until 100L), set.missing(100))
        assertEquals(20, set.total())
    }

    @Test
    fun `out of order inserts still collapse`() {
        val set = IntervalSet()
        set.add(50, 60)
        set.add(0, 50)
        set.add(60, 100)
        assertEquals(100, set.total())
        assertEquals(listOf(0L until 100L), set.snapshot())
    }

    @Test
    fun `round trips through the codec used by sqlite`() {
        val ranges = listOf(0L until 1024L, 2048L until 4096L)
        val encoded = IntervalSet.encode(ranges)
        assertEquals("0-1024,2048-4096", encoded)
        assertEquals(ranges, IntervalSet.decode(encoded))
        assertTrue(IntervalSet.decode(null).isEmpty())
    }
}

class ChunkPlannerTest {

    @Test
    fun `splits missing ranges into bounded chunks that cover the file`() {
        val planner = ChunkPlanner(contentLength = 100L * 1024 * 1024, rangeSupport = true)
        val chunks = planner.plan(listOf(0L until 100L * 1024 * 1024))
        assertTrue(chunks.size > 1)
        assertEquals(100L * 1024 * 1024, chunks.sumOf { it.last - it.first + 1 })
        // chunks must tile the file contiguously
        var cursor = 0L
        chunks.forEach { chunk ->
            assertEquals(cursor, chunk.first)
            cursor = chunk.last + 1
        }
    }

    @Test
    fun `one chunk when the server has no range support`() {
        val planner = ChunkPlanner(contentLength = 1234, rangeSupport = false)
        val chunks = planner.plan(listOf(0L until 1234))
        assertEquals(1, chunks.size)
        assertEquals(0L..1233L, chunks.first())
    }

    @Test
    fun `recommended connections never exceeds available megabytes`() {
        assertEquals(1, ChunkPlanner.recommendedConnections(100_000, 64, true))
        assertEquals(64, ChunkPlanner.recommendedConnections(10L * 1024 * 1024 * 1024, 64, true))
        assertEquals(1, ChunkPlanner.recommendedConnections(10L * 1024 * 1024 * 1024, 64, false))
    }
}

class FileNameResolverTest {

    @Test
    fun `prefers the rfc 5987 form of content disposition`() {
        val header = "attachment; filename=\"fallback.iso\"; filename*=UTF-8''%E4%B8%AD%E6%96%87.iso"
        assertEquals("中文.iso", FileNameResolver.fromContentDisposition(header))
    }

    @Test
    fun `strips directories and invalid characters`() {
        assertEquals("passwd", FileNameResolver.sanitize("../../etc/passwd"))
        // backslashes and slashes are treated as directory separators, ':' is not allowed on Windows
        assertEquals("c.iso", FileNameResolver.sanitize("a:b\\c.iso"))
        assertEquals("a_b.iso", FileNameResolver.sanitize("a:b.iso"))
        assertEquals("name.iso", FileNameResolver.sanitize("dir/sub/name.iso"))
        assertEquals(null, FileNameResolver.sanitize(".."))
    }

    @Test
    fun `falls back to the url path`() {
        assertEquals("ubuntu.iso", FileNameResolver.fromUrl("https://example.com/releases/ubuntu.iso?a=1"))
        assertEquals("download.bin", FileNameResolver.fromUrl("https://example.com/"))
    }
}

class SpeedMeterTest {

    @Test
    fun `average is computed from the transferred bytes`() {
        val now = longArrayOf(0)
        val meter = SpeedMeter(clock = { now[0] })
        meter.onBytes(1000)
        now[0] = 1000
        assertEquals(1000, meter.current(), "1000 bytes in one second")
        now[0] = 2000
        assertEquals(500, meter.average(), "1000 bytes over two seconds")
    }

    @Test
    fun `reports zero before any byte arrives`() {
        val meter = SpeedMeter(clock = { 0 })
        assertEquals(0, meter.current())
        assertEquals(0, meter.average())
    }
}

class IntervalSetPropertyTest {

    /** Fuzz the interval set: whatever the insert order, the union must be correct. */
    @Test
    fun `random inserts equal the union of the ranges`() {
        val random = Random(7)
        repeat(200) { iteration ->
            val ranges = (0 until 20).map {
                val start = random.nextInt(1000).toLong()
                val length = random.nextInt(50).toLong()
                start until start + length
            }.filter { !it.isEmpty() }
            val set = IntervalSet()
            ranges.forEach { set.add(it.first, it.last + 1) }
            val covered = ranges.flatMap { it.toList() }.toSet()
            assertEquals(
                covered.size.toLong(),
                set.total(),
                "iteration $iteration: ranges=$ranges snapshot=${set.snapshot()}",
            )
            val missing = set.missing(1100).flatMap { it.toList() }.toSet()
            assertEquals((0L until 1100L).toSet() - covered, missing, "iteration $iteration gaps")
        }
    }
}
