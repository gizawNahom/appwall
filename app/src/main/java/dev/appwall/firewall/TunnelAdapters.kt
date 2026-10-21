package dev.appwall.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import dev.appwall.core.tunnel.ConnectionOwners
import dev.appwall.core.tunnel.PacketSource
import dev.appwall.core.tunnel.Tunnel
import java.net.InetSocketAddress

/** An established VPN tunnel. Only [dev.appwall.core.tunnel.TunnelSession] closes it. */
class VpnTunnel(private val fd: ParcelFileDescriptor) : Tunnel {
    override val packets: PacketSource = TunFdPacketSource(fd)
    override fun close() = fd.close()
}

/** Reads packets from the tunnel's file descriptor. Never closes it. */
private class TunFdPacketSource(fd: ParcelFileDescriptor) : PacketSource {
    private val descriptor = fd.fileDescriptor
    private val poll = StructPollfd().apply {
        this.fd = descriptor
        events = OsConstants.POLLIN.toShort()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = try {
        when {
            Os.poll(arrayOf(poll), timeoutMs) == 0 -> 0
            poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0 -> -1
            else -> Os.read(descriptor, buffer, 0, buffer.size).coerceAtLeast(0)
        }
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) 0 else -1
    }
}

/** Asks Android which app owns a connection (Android 10 and newer; older versions can't say). */
class ConnectivityOwners(context: Context) : ConnectionOwners {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    override fun packagesOf(packet: Packet): List<String>? {
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
}
