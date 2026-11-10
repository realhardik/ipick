package app.ipick.voicemail.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder


class OpusOggWriter(
    fd: FileDescriptor,
    opusHead: ByteArray,
) : AutoCloseable, OggSink {

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    private val track: Int
    private var started = false

    /** Packets written. Zero means the call produced nothing. The timestamp
     *  handed to the muxer is COUNTED from this, not clocked: packet N sits at
     *  N x 20ms. See write() for why nothing else works here. */
    override var packets: Long = 0L
        private set

    init {
        val head = OpusHead.parse(opusHead)
        Log.i(TAG, "ogg: channels=${head.channels} preSkip=${head.preSkip} rate=${head.inputRate} delay=${head.codecDelayNs}ns")

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, head.channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
        format.setByteBuffer("csd-1", littleEndianLong(head.codecDelayNs))
        format.setByteBuffer("csd-2", littleEndianLong(SEEK_PRE_ROLL_NS))

        track = muxer.addTrack(format)
        muxer.start()
        started = true
    }


    override fun write(data: ByteArray, @Suppress("UNUSED_PARAMETER") pts: Long) {
        if (!started) return

        val ptsUs = packets * FRAME_US

        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = ptsUs
            flags = 0
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
        packets++
    }

    /** Length of what was written, in seconds. Every packet is a frame, the
     *  last one included: this was the last packet's timestamp, which is one
     *  frame short of the audio written. */
    override val durationSeconds: Double
        get() = packets * FRAME_US / 1_000_000.0

    /**
     * Finalises the file. Skipping this leaves an .ogg that no player will
     * open, so it is what "correct file finalize on hangup" actually means -
     * the muxer has to be stopped, not merely abandoned.
     */
    override fun close() {
        finish()
    }

    /**
     * Finalise the file, and say whether it worked. A muxer that could not
     * finish - the storage filled up, typically - leaves a file no player can
     * open; the saver must then keep the daemon's copy, not delete it as
     * saved.
     */
    override fun finish(): Boolean {
        if (!started) {
            runCatching { muxer.release() }
            return true
        }
        started = false
        return try {
            if (packets > 0) {
                muxer.stop()
            } else {
                // Stopping a muxer that was never written to throws. A call
                // that produced no audio should leave no file rather than an
                // exception and a zero-byte one.
                Log.w(TAG, "ogg: no packets written, nothing to finalise")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ogg: could not finalise", t)
            false
        } finally {
            runCatching { muxer.release() }
        }
    }

    private companion object {
        const val TAG = "IPick"
        const val SAMPLE_RATE = 48_000

        /** 80 ms, the value Opus and Android both use by convention. */
        const val SEEK_PRE_ROLL_NS = 80_000_000L

        /** One Opus frame is 20 ms. The encoder emits exactly one per packet, so
         *  packet index times this is the packet's place on the timeline. */
        const val FRAME_US = 20_000L

        fun littleEndianLong(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }
    }
}

/**
 * What the saver writes a part of a recording through: [OpusOggWriter], or a
 * test's own (RecordingSaverTest), which needs no MediaMuxer.
 */
interface OggSink {
    /** Packets written. */
    val packets: Long

    /** Length of what was written, in seconds. */
    val durationSeconds: Double

    fun write(data: ByteArray, pts: Long)

    /** Finalise the file, and say whether it worked. */
    fun finish(): Boolean
}
