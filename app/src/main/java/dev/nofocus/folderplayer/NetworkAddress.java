package dev.nofocus.folderplayer;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

final class NetworkAddress {
    private NetworkAddress() {
    }

    static String localIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // The UI gives an actionable fallback instead of failing startup.
        }
        return "Connect phone and PC to the same Wi-Fi";
    }
}
