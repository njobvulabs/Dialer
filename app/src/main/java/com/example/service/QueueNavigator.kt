package com.example.service

/**
 * The one queue-advance rule behind the Audio Player's track-end / Next / Previous
 * and behind the background engine's lock-screen + notification + mini-bar controls.
 *
 * It is a plain class with no Android or Compose dependency on purpose: the previous
 * version of this logic was duplicated in [AudioPlayerScreen] and [AudioEngine], the
 * two copies disagreed, and both were wrong in ways that made shuffle and repeat
 * behave unlike every other player.
 *
 * The mistakes this replaces, all of which were live for months:
 *  - **Shuffle was ignored whenever repeat was ALL.** Both copies had
 *    `shuffle && repeat != ALL`, so "shuffle + repeat all" played a plain in-order
 *    loop.
 *  - **Repeat OFF stopped after a single track.** The screen's completion handler
 *    called `stopPlayback()` for `NONE` no matter where in the list it was, so the
 *    queue ended after one song instead of running to the end.
 *  - **Shuffle replayed the same tracks.** The pick came from the whole queue rather
 *    than from the complement of what had already played, so favourites came back
 *    over and over while the rest of the album never did.
 *  - **Previous in shuffle jumped somewhere random** (and popped two entries from the
 *    history instead of one), instead of returning to the track just played.
 *  - **A one-track queue crashed** a shuffle Next/Previous: the "no candidates"
 *    fallback called `random()` on an empty list.
 *
 * Shuffle is modelled as a **bag**: [played] is the ordered list of tracks already
 * played in this pass, seeded with whatever was playing when the pass started, and a

 * pick only comes from its complement. A pass therefore plays every track once before
 * repeating anything, and Previous can walk back through it. When the bag empties,
 * repeat ALL starts a fresh pass and repeat OFF ends the queue.
 *
 * Repeat ONE is deliberately *not* handled here: a track that ended has to restart the
 * same file, which is a replay rather than an "index to play next", so callers deal
 * with it before asking a question here.
 */
class QueueNavigator {

    /** Indices played so far in the current shuffled pass, oldest first. */
    private val played = ArrayList<Int>()

    /** The current shuffled pass, oldest first — exposed for tests/diagnostics. */
    val playedOrder: List<Int> get() = played.toList()

    /** Forget the current pass: the queue changed, or shuffle/repeat was toggled. */
    fun reset() {
        played.clear()
    }

    /**
     * The index that plays after [current], or `null` when playback should stop.
     *
     * @param size queue length
     * @param current index now playing; anything outside `0 until size` means
     *   "unknown" (nothing loaded, or the track is not in this list) and starts at 0
     * @param shuffle shuffle is on
     * @param repeatAll repeat-all is on (repeat-one never reaches here)
     * @param wrapAtEnd `false` **only** for the automatic track-ended path, where a
     *   finished queue with repeat off stops instead of wrapping. A manual press
     *   passes `true` so the button is never dead.
     */
    fun next(
        size: Int,
        current: Int,
        shuffle: Boolean,
        repeatAll: Boolean,
        wrapAtEnd: Boolean,
    ): Int? {
        if (size <= 0) return null
        val idx = current.takeIf { it in 0 until size } ?: 0
        if (size == 1) {
            // Nothing to advance to. Auto + repeat off = the queue is over.
            return if (!wrapAtEnd && !repeatAll) null else 0
        }

        if (shuffle) {
            seed(idx)
            val pick = (0 until size).filter { it != idx && it !in played }.randomOrNull()
            if (pick != null) {
                played.add(pick)
                return pick
            }
            // Bag exhausted — this shuffled pass is over.
            if (!wrapAtEnd && !repeatAll) return null
            played.clear()
            played.add(idx)
            val fresh = (0 until size).filter { it != idx }.random()
            played.add(fresh)
            return fresh
        }

        val nxt = idx + 1
        if (nxt < size) return nxt
        if (!wrapAtEnd && !repeatAll) return null
        return 0
    }

    /**
     * The index [Previous] should play, or `null` when there is nothing before the
     * current track in this shuffled pass and the current one should restart instead.
     *
     * Without shuffle this steps back one entry and wraps to the end of the list at
     * the start, which is what the player has always done.
     */
    fun previous(size: Int, current: Int, shuffle: Boolean): Int? {
        if (size <= 0) return null
        val idx = current.takeIf { it in 0 until size } ?: 0
        if (size == 1) return 0

        if (shuffle) {
            seed(idx)
            if (played.size >= 2 && played.last() == idx) {
                played.removeAt(played.lastIndex)
                return played.last()
            }
            return null
        }

        val prev = idx - 1
        return if (prev >= 0) prev else size - 1
    }

    /**
     * A track started outside the advance path (tapped in the list, opened from the
     * File Manager) is not in the bag, and a pick from the complement could then
     * choose it again immediately. Re-seeding on that mismatch keeps a shuffled pass
     * free of repeats without the call sites having to report how playback started.
     */
    private fun seed(idx: Int) {
        if (idx in played) return
        played.clear()
        played.add(idx)
    }
}
