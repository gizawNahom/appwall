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
import java.io.IOException
import java.net.InetSocketAddress

/**
 * Reads what blocked apps send into the tunnel and records it in the [BlockLog]. Nothing is ever
 * forwarded, so the apps stay blocked. Owns the tunnel: closes it once [shutdown] is called.
 */
class TunnelReader(context: Context, private val tunnel: ParcelFileDescriptor) : Thread("TunnelReader") {

    private val appContext = context.applicationContext
    private val rules = Rules(appContext)
    private val log = BlockLog.get(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var stopped = false

    /** Stops reading and closes the tunnel within about a second. */
    fun shutdown() {
        stopped = true
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
                // Wake up at least once a second to notice shutdown().
                if (Os.poll(arrayOf(poll), 1000) == 0) continue
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                val length = try {
                    Os.read(fd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e
                }
                if (length > 0) record(buffer, length)
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "Tunnel read stopped", e)
        } catch (e: IOException) {
            Log.w(TAG, "Tunnel read stopped", e)
        } finally {
            tunnel.close()
        }
    }

    private fun record(buffer: ByteArray, length: Int) {
        val packet = PacketParser.parse(buffer, length) ?: return
        // IPv6 housekeeping (router solicitations etc.) comes from the system, not from an app.
        if (packet.protocol == PacketParser.ICMPV6) return
        // Android probing our fake DNS server for Private DNS (DNS-over-TLS); also not an app.
        if (packet.destinationPort == 853 && packet.destination.hostAddress == FirewallVpnService.DNS_SERVER) return
        val owner = ownerOf(packet)
        if (owner == null && packet.protocol != PacketParser.TCP && packet.protocol != PacketParser.UDP) return
        log.record(owner, packet)
    }

    /** Which blocked app sent this packet, or null if Android can't tell us. */
    private fun ownerOf(packet: Packet): String? {
        val blocked = rules.blocked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (packet.protocol == PacketParser.TCP || packet.protocol == PacketParser.UDP)
        ) {
            try {
                val uid = connectivity.getConnectionOwnerUid(
                    packet.protocol,
                    InetSocketAddress(packet.source, packet.sourcePort),
                    InetSocketAddress(packet.destination, packet.destinationPort),
                )
                val packages = appContext.packageManager.getPackagesForUid(uid)
                if (packages != null) return packages.firstOrNull { it in blocked } ?: packages.first()
            } catch (e: SecurityException) {
                // We're not the active VPN any more; fall through.
            } catch (e: IllegalArgumentException) {
                // Unsupported address/protocol combination; fall through.
            }
        }
        // With a single blocked app, every packet in the tunnel must be its.
        return blocked.singleOrNull()
    }

    private companion object {
        const val TAG = "AppWall"
    }
}
