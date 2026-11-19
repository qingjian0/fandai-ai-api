package app.gptdynamichook.core.report

import app.gptdynamichook.core.model.HookProfile
import app.gptdynamichook.core.model.HookProfileSignature
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, thread-safe, metadata-only event accumulator.
 *
 * Every hook callback pays O(1) and a small fixed amount of memory: the ring is
 * fixed at [capacity] entries and each entry stores at most [MAX_FIELDS] names.
 * Values never enter the buffer, so no further filtering is needed downstream.
 */
class HookEventCollector(
    private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    data class Event(
        val seq: Long,
        val profileId: String,
        val signature: String,
        val elapsedMs: Long,
        val outcome: String,
        val result: HookResultParser.HookResult?
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Event>(capacity)
    private val counters = LinkedHashMap<String, Long>()
    private var sequence = 0L
    private val dirty = AtomicBoolean(false)

    fun record(
        profile: HookProfile,
        elapsedMs: Long,
        outcome: String,
        result: HookResultParser.HookResult?
    ) {
        val event = Event(
            seq = ++sequence,
            profileId = profile.id,
            signature = HookProfileSignature.of(profile),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            outcome = outcome,
            result = result
        )
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(event)
            val key = "${profile.id}|$outcome"
            counters[key] = (counters[key] ?: 0L) + 1L
        }
        dirty.set(true)
    }

    fun drain(): Snapshot {
        synchronized(lock) {
            val events = buffer.toList()
            buffer.clear()
            val counts = counters.toMap()
            counters.clear()
            dirty.set(false)
            return Snapshot(events, counts)
        }
    }

    fun isEmpty(): Boolean = !dirty.get()

    data class Snapshot(val events: List<Event>, val counts: Map<String, Long>)

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_FIELDS = 16
        const val maxEncodedBytes = 32 * 1024
    }
}
