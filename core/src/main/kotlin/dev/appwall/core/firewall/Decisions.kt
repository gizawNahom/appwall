package dev.appwall.core.firewall

import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress

/** AppWall's fake DNS server inside the tunnel: blocked apps' lookups land there, unanswered. */
const val DNS_SERVER = "10.111.0.2"

/** What to do when a new app that uses the internet is installed. */
enum class NewAppMode { ASK, ALLOW, BLOCK }

/** What the tunnel should route: nothing at all, or exactly these apps. */
sealed interface TunnelPlan {
    /**
     * No tunnel. Android routes *every* app into a VPN whose allow-list is empty, so with nothing
     * to block, no tunnel must exist at all.
     */
    data object NoTunnel : TunnelPlan

    /** Route these apps (and only these) into the tunnel, where nothing answers. */
    data class Route(val packages: List<String>) : TunnelPlan
}

/** Blocked apps that are still installed go into the tunnel; with none, there's no tunnel. */
fun tunnelPlan(blocked: Set<String>, isInstalled: (String) -> Boolean): TunnelPlan {
    val packages = blocked.filter(isInstalled).sorted()
    return if (packages.isEmpty()) TunnelPlan.NoTunnel else TunnelPlan.Route(packages)
}

/**
 * Whether a packet read from the tunnel counts as an app's try. Skips what Android itself sends
 * there, and what can't be tied to an app.
 */
fun shouldCount(packet: Packet, blockedCount: Int): Boolean = when {
    // IPv6 housekeeping (router solicitations etc.) comes from the system, not from an app.
    packet.protocol == PacketParser.ICMPV6 -> false
    // Multicast, broadcast and link-local (ff02::16, 224.0.0.251, fe80::…) stay on the local
    // network: Android's own chatter, never a place on the internet an app tried to reach.
    isLocalOnly(packet.destination) -> false
    // Android probing the fake DNS server for Private DNS (DNS-over-TLS); also not an app.
    packet.destinationPort == 853 && packet.destination.hostAddress == DNS_SERVER -> false
    // Android can only name the app for TCP and UDP; anything else is attributable only when a
    // single app is blocked.
    packet.protocol != PacketParser.TCP && packet.protocol != PacketParser.UDP -> blockedCount == 1
    else -> true
}

private fun isLocalOnly(address: InetAddress): Boolean =
    address.isMulticastAddress || address.isLinkLocalAddress || address.hostAddress == "255.255.255.255"

/**
 * Which app a packet belongs to, given the packages Android reports for its connection's owner
 * (null when Android can't tell). Apps sharing a user ID share an owner, so the blocked one wins.
 * With no answer, a single blocked app must be the sender: only blocked apps reach the tunnel.
 */
fun pickOwner(ownerPackages: List<String>?, blocked: Set<String>): String? {
    if (!ownerPackages.isNullOrEmpty()) return ownerPackages.firstOrNull { it in blocked } ?: ownerPackages.first()
    return blocked.singleOrNull()
}

/** What happens when an app is installed. */
enum class NewAppDecision { IGNORE, ASK, AUTO_BLOCK }

/**
 * Decides how to treat a freshly installed app. [usesInternet] is null when the app can't be
 * looked up (e.g. it was uninstalled again already).
 */
fun newAppDecision(mode: NewAppMode, isSelf: Boolean, alreadyBlocked: Boolean, usesInternet: Boolean?): NewAppDecision = when {
    mode == NewAppMode.ALLOW || isSelf || alreadyBlocked || usesInternet != true -> NewAppDecision.IGNORE
    mode == NewAppMode.BLOCK -> NewAppDecision.AUTO_BLOCK
    else -> NewAppDecision.ASK
}

/**
 * After the policy was saved: rebuild the tunnel only if the firewall is running, still meant to
 * be on, and the blocked apps differ from what the tunnel was built for ([appliedBlocked], null
 * before the first build). So repeats, and the service's own writes, never cause a rebuild, and a
 * real change is never left unapplied.
 */
fun shouldReapply(running: Boolean, enabled: Boolean, blocked: Set<String>, appliedBlocked: Set<String>?): Boolean =
    running && enabled && blocked != appliedBlocked
