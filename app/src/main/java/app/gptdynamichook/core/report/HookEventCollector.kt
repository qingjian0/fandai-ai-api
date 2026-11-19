package app.gptdynamichook.core.report

import app.gptdynamichook.core.model.HookProfile
import app.gptdynamichook.core.model.HookProfileSignature

/** Bounded, thread-safe, metadata-only event accumulator. */
class HookEventCollector(
    @Suppress("UNUSED_PARAMETER") private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

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
    private var dirty = false

    fun record(
        profile: HookProfile,
        elapsedMs: Long,
        outcome: String,
        result: HookResultParser.HookResult?
    ) {
        synchronized(lock) {
            val event = Event(
                seq = ++sequence,
                profileId = profile.id,
                signature = HookProfileSignature.of(profile),
                elapsedMs = elapsedMs.coerceAtLeast(0L),
                outcome = outcome.take(MAX_OUTCOME_LENGTH),
                result = result
            )
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(event)

            val key = "${profile.id}|${event.outcome}"
            if (counters.containsKey(key) || counters.size < MAX_COUNTER_KEYS) {
                counters[key] = (counters[key] ?: 0L) + 1L
            } else {
                counters[OVERFLOW_COUNTER] = (counters[OVERFLOW_COUNTER] ?: 0L) + 1L
            }
            dirty = true
        }
    }

    fun drain(): Snapshot = synchronized(lock) {
        val snapshot = Snapshot(buffer.toList(), counters.toMap())
        buffer.clear()
        counters.clear()
        dirty = false
        snapshot
    }

    fun isEmpty(): Boolean = synchronized(lock) { !dirty }

    data class Snapshot(val events: List<Event>, val counts: Map<String, Long>)

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_FIELDS = 16
        const val maxEncodedBytes = 32 * 1024
        private const val MAX_COUNTER_KEYS = 128
        private const val MAX_OUTCOME_LENGTH = 80
        private const val OVERFLOW_COUNTER = "_overflow_|other"
    }
}
