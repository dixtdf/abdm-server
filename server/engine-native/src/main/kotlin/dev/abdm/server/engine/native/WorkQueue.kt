package dev.abdm.server.engine.native

/**
 * Explicit work queue for the chunk workers.
 *
 * Deliberately *not* a `Channel`: a worker parked on an empty channel cannot tell
 * "no work right now" from "we are done", and the engine must never guess. Here the
 * three states are explicit, which makes completion detection trivial and exact:
 *
 *  * [Take.Chunk] - here is a range to download (counts as in flight until finished)
 *  * [Take.Wait]  - nothing queued, but other workers are still busy
 *  * [Take.Done]  - nothing queued and nothing in flight: the file is complete
 *
 * Re-queueing a chunk that a cancelled worker had already started keeps the
 * invariant "queued + in flight + written == whole file" true at all times.
 */
internal class WorkQueue(chunks: List<LongRange>) {

    sealed interface Take {
        data class Chunk(val range: LongRange) : Take
        data object Wait : Take
        data object Done : Take
    }

    private val lock = Any()
    private val pending = ArrayDeque(chunks)
    private var inFlight = 0
    private var done = false

    val inFlightCount: Int get() = synchronized(lock) { inFlight }

    val pendingCount: Int get() = synchronized(lock) { pending.size }

    val isDone: Boolean get() = synchronized(lock) { done }

    fun take(): Take = synchronized(lock) {
        if (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            inFlight++
            return Take.Chunk(next)
        }
        if (inFlight == 0) {
            done = true
            return Take.Done
        }
        Take.Wait
    }

    /** The chunk was written (or permanently failed): it no longer counts as in flight. */
    fun complete() = synchronized(lock) {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (pending.isEmpty() && inFlight == 0) {
            done = true
        }
    }

    /** A cancelled worker gives its chunk back so no byte is lost. */
    fun requeue(chunk: LongRange) = synchronized(lock) {
        pending.addFirst(chunk)
        inFlight = (inFlight - 1).coerceAtLeast(0)
    }

    fun snapshot(): String = synchronized(lock) { "pending=$pending inFlight=$inFlight done=$done" }
}
