package app.ipick.voicemail.capture

import app.ipick.voicemail.calls.CallTracking


internal class PacketClock(private val start: Long, private val offset: Long?) {

    private var last = -1L

    /** The least lead - stamp less count, in slots - seen since the last
     *  gap: the capture's delay, which a gap is measured over. */
    private var baseline = 0L

    /** Where the first packet's stamp put it, in ms from the start: null
     *  without one. For the log. */
    var firstMs: Long? = null
        private set

    /** Gaps after the first packet, and the time they cover. For the log. */
    var gaps = 0
        private set
    var lostMs = 0L
        private set

    /** The slot of the next packet, which the daemon stamped [ptsUs]. */
    fun next(ptsUs: Long): Long {
        val counted = last + 1
        val stampedMs = stampedMs(ptsUs)
        val stamped = stampedMs?.div(CallTracking.PACKET_MS)
        val gap = GAP_MS / CallTracking.PACKET_MS
        val slot = when {
            stamped == null -> counted
            last < 0 -> {
                // The first: at its stamp if the capture started late - the
                // Pixel's ringing - else counted from the start, its lead the
                // delay the rest is measured against.
                firstMs = stampedMs
                val lead = stamped - counted
                if (lead > gap) {
                    baseline = 0
                    stamped
                } else {
                    baseline = lead
                    counted
                }
            }
            stamped - counted - baseline > gap -> {
                val jump = stamped - counted - baseline
                gaps++
                lostMs += jump * CallTracking.PACKET_MS
                counted + jump
            }
            else -> {
                baseline = minOf(baseline, stamped - counted)
                counted
            }
        }
        last = slot
        return slot
    }

    /** A stamp in ms from the start; null without the offset, and for one
     *  that cannot be right - before the start, or half a day after it. */
    private fun stampedMs(ptsUs: Long): Long? {
        val ms = ptsUs / 1000 + (offset ?: return null) - start
        return ms.takeIf { it in 0..MAX_MS }
    }

    companion object {
        /** Further past the count than any stamp jitters - the encoder's
         *  output thread waiting, a read held up - and well short of the
         *  ringing that went missing. */
        const val GAP_MS = 500L

        private const val MAX_MS = 12 * 60 * 60 * 1000L
    }
}
