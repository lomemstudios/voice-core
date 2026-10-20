// The microphone's frames to every consumer that wants them: the recognizer pipe, the keyword spotter, later the
// voice gate. Each consumer has a bounded queue and a thread of its own, so a slow one drops its own frames and never
// holds up the microphone or another consumer (as VoiceService's recognizer and keyword queues did before 0.7).

package com.lomemstudios.voice.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FrameFanOut {
    private val taps = CopyOnWriteArrayList<Tap>()

    /**
     * A consumer of frames on its own thread, named [name], with [capacity] frames queued at most. [accept] is asked
     * on the microphone's thread for each frame (a recognizer that is muted takes none); [onClose] runs on the tap's
     * thread when it ends.
     */
    fun tap(name: String, capacity: Int, accept: () -> Boolean = { true }, onClose: () -> Unit = {},
            consumer: (ShortArray) -> Unit): Tap = Tap(name, capacity, accept, onClose, consumer).also { taps += it }

    /** One frame from the microphone's thread: offered to every tap, dropped by those whose queue is full. */
    fun offer(frame: ShortArray) {
        for (t in taps) t.offer(frame)
    }

    /** Ends every tap. */
    fun close() {
        for (t in taps) t.close()
    }

    inner class Tap internal constructor(
        val name: String,
        capacity: Int,
        private val accept: () -> Boolean,
        private val onClose: () -> Unit,
        private val consumer: (ShortArray) -> Unit,
    ) {
        private val queue = ArrayBlockingQueue<ShortArray>(capacity)
        private val drops = AtomicInteger()
        private val thread = Thread {
            try {
                while (true) consumer(queue.take())
            } catch (_: InterruptedException) {
            } finally {
                onClose()
            }
        }.apply { name = this@Tap.name; isDaemon = true; start() }

        /** Frames this tap dropped because its consumer fell behind. */
        val dropped: Int get() = drops.get()

        internal fun offer(frame: ShortArray) {
            if (accept() && !queue.offer(frame)) drops.incrementAndGet()
        }

        /** Forgets the frames not yet consumed (a new recognizer pipe starts from now). */
        fun clear() = queue.clear()

        fun close() {
            taps.remove(this)
            thread.interrupt()
        }

        /** Waits for the tap's thread to end, after [close]. */
        fun join(ms: Long) = thread.join(ms)
    }
}
