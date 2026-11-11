package app.onyx.core

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredPc(
    val name: String,
    val host: String,
    val port: Int,
    val paired: Boolean,
)

/**
 * Finds ONYX hubs on the local network. The desktop app answers UDP broadcasts
 * on port 8788, so the phone never needs a hand-typed IP.
 */
object PcDiscovery {
    private const val DISCOVERY_PORT = 8788
    private const val PAYLOAD = "ONYX_DISCOVER"

    fun find(timeoutMs: Long = 2500L): List<DiscoveredPc> {
        val socket = runCatching {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
        }.getOrNull() ?: return emptyList()

        val results = linkedMapOf<String, DiscoveredPc>()
        try {
            val data = PAYLOAD.toByteArray(Charsets.UTF_8)
            val targets = buildList {
                runCatching { add(InetAddress.getByName("255.255.255.255")) }
                runCatching {
                    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                        nif.interfaceAddresses.forEach { address ->
                            address.broadcast?.let { add(it) }
                        }
                    }
                }
            }.distinct()
            targets.forEach { target ->
                runCatching {
                    socket.send(DatagramPacket(data, data.size, target, DISCOVERY_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (json.optString("app") != "onyx") continue
                val host = packet.address?.hostAddress ?: continue
                val port = json.optInt("port", 8787)
                results[host] = DiscoveredPc(
                    name = json.optString("name").ifBlank { host },
                    host = host,
                    port = port,
                    paired = json.optBoolean("paired"),
                )
            }
        } finally {
            runCatching { socket.close() }
        }
        return results.values.toList()
    }
}
