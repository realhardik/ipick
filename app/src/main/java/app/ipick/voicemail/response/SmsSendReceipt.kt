package app.ipick.voicemail.response

/**
 * Telecom and the manifest SMS receiver run on the app's main thread. Only one ringing
 * call can start an auto-reply. Keep the live Call instance until all parts are sent.
 * Losing that instance (including process death) forfeits the right to reject the call.
 */
internal class SmsSendReceipt<T : Any> {
    private data class Reply<T>(val key: String, val call: T, val generation: Long, val outstanding: MutableSet<Int>)
    private var pending: Reply<T>? = null

    fun start(key: String, call: T, generation: Long, parts: Int) {
        require(parts > 0)
        pending = Reply(key, call, generation, (0 until parts).toMutableSet())
    }

    fun forget(call: T) {
        if (pending?.call === call) pending = null
    }

    fun result(key: String, part: Int, success: Boolean, currentGeneration: Long): T? {
        val reply = pending ?: return null
        if (key != reply.key || part !in reply.outstanding) return null
        if (!success || currentGeneration != reply.generation) {
            pending = null
            return null
        }
        reply.outstanding.remove(part)
        if (reply.outstanding.isNotEmpty()) return null
        pending = null
        return reply.call
    }
}
