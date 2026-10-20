package dev.appwall

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.appwall.core.firewall.pickOwner
import dev.appwall.core.firewall.shouldCount
import dev.appwall.core.log.TryTracker
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.io.IOException
import java.net.InetSocketAddress

/**
 * Reads what blocked apps send into the tunnel and records it in the [BlockLog]. Nothing is ever
 * forwarded, so the apps stay blocked.
 *
 * The tunnel must stay up whatever goes wrong here: closing it would give blocked apps their
 * network back. So a packet that fails to parse or log is skipped, and the tunnel is closed only
 * after a deliberate [shutdown]. If reading itself fails, the tunnel is left open and
 * [onUnexpectedStop] lets the service decide what to do with it.
 */
class TunnelReader(
    context: Context,
    val tunnel: ParcelFileDescriptor,
    private val onUnexpectedStop: (TunnelReader) -> Unit,
) : Thread("TunnelReader") {

    private val appContext = context.applicationContext
    private val rules = Rules(appContext)
    private val log = BlockLog.get(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val tries = TryTracker(::ownerOf) { owner, packet ->
        // A failed log write must never reach the reading loop.
        try {
            log.record(owner, packet)
        } catch (e: Exception) {
            packetFailed(e)
        }
    }

    @Volatile private var stopped = false
    private var failedPackets = 0

    /** Whoever comes second, [shutdown] or the loop ending, closes the tunnel; see [closeIfDone]. */
    private val closeLock = Any()
    private var exited = false
    private var closed = false

    /** Stops reading and closes the tunnel within about a second. */
    fun shutdown() {
        synchronized(closeLock) {
            stopped = true
            closeIfDone()
        }
    }

    override fun run() {
        val fd = tunnel.fileDescriptor
        val buffer = ByteArray(32767)
        val poll = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (!stopped) {
                // Wake up at least once a second to notice shutdown() and settle pending tries.
                if (Os.poll(arrayOf(poll), 1000) == 0) {
                    contained { tries.sweep(System.currentTimeMillis()) }
                    continue
                }
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                    if (!stopped) Log.w(TAG, "Tunnel reported an error or hang-up")
                    break
                }
                val length = try {
                    Os.read(fd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e
                }
                if (length > 0) contained { record(buffer, length) }
            }
        } catch (e: Throwable) {
            // Includes Errors: an uncaught one would crash the app and drop the tunnel.
            if (!stopped) Log.e(TAG, "Tunnel read stopped", e)
        } finally {
            val unexpected = synchronized(closeLock) {
                exited = true
                closeIfDone()
                !stopped
            }
            if (unexpected) onUnexpectedStop(this)
        }
    }

    /** Closes the tunnel once both the loop has ended and [shutdown] was called. Holds [closeLock]. */
    private fun closeIfDone() {
        if (!exited || !stopped || closed) return
        closed = true
        try {
            tunnel.close()
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't close the tunnel", e)
        }
    }

    /** Runs one packet's work; a failure skips that packet instead of ending the loop. */
    private inline fun contained(work: () -> Unit) {
        try {
            work()
        } catch (e: Exception) {
            packetFailed(e)
        }
    }

    private fun packetFailed(e: Exception) {
        // Logged now and then, not per packet: a broken log could fail on every one.
        if (failedPackets++ % 100 == 0) Log.w(TAG, "Skipped a packet ($failedPackets so far)", e)
    }

    private fun record(buffer: ByteArray, length: Int) {
        if (!rules.loggingEnabled) return
        val packet = PacketParser.parse(buffer, length) ?: return
        if (!shouldCount(packet, rules.blocked().size)) return
        tries.onPacket(packet, System.currentTimeMillis())
    }

    /** Which blocked app sent this packet, or null if Android can't tell us. */
    private fun ownerOf(packet: Packet): String? = pickOwner(ownerPackages(packet), rules.blocked())

    /** The packages Android says own the packet's connection, or null if it can't say. */
    private fun ownerPackages(packet: Packet): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (packet.protocol != PacketParser.TCP && packet.protocol != PacketParser.UDP) return null
        return try {
            val uid = connectivity.getConnectionOwnerUid(
                packet.protocol,
                InetSocketAddress(packet.source, packet.sourcePort),
                InetSocketAddress(packet.destination, packet.destinationPort),
            )
            appContext.packageManager.getPackagesForUid(uid)?.toList()
        } catch (e: SecurityException) {
            null // We're not the active VPN any more.
        } catch (e: IllegalArgumentException) {
            null // Unsupported address/protocol combination.
        }
    }

    private companion object {
        const val TAG = "AppWall"
    }
}
