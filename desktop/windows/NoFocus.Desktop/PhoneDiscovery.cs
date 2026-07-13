using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;

namespace NoFocus.Desktop;

internal static class PhoneDiscovery
{
    internal static async Task<IPAddress?> FindAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        using Socket socket = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        socket.EnableBroadcast = true;
        socket.Bind(new IPEndPoint(IPAddress.Any, 0));
        using CancellationTokenSource deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        IPEndPoint[] broadcasts = BroadcastEndpoints().ToArray();
        EndPoint sender = new IPEndPoint(IPAddress.Any, 0);
        byte[] response = new byte[32];

        while (!deadline.IsCancellationRequested)
        {
            foreach (IPEndPoint broadcast in broadcasts)
            {
                try
                {
                    await socket.SendToAsync(Protocol.DiscoveryRequest, SocketFlags.None, broadcast, deadline.Token);
                }
                catch (SocketException)
                {
                    // One disconnected VPN/virtual adapter must not prevent discovery on the home LAN.
                }
            }
            DateTime roundEnds = DateTime.UtcNow.AddMilliseconds(900);
            while (DateTime.UtcNow < roundEnds && !deadline.IsCancellationRequested)
            {
                using CancellationTokenSource receiveTimeout = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token);
                receiveTimeout.CancelAfter(250);
                try
                {
                    SocketReceiveFromResult result = await socket.ReceiveFromAsync(response, SocketFlags.None, sender,
                        receiveTimeout.Token);
                    if (Protocol.IsDiscoveryResponse(response.AsSpan(0, result.ReceivedBytes))
                        && result.RemoteEndPoint is IPEndPoint endpoint)
                        return endpoint.Address;
                }
                catch (OperationCanceledException) when (!deadline.IsCancellationRequested)
                {
                    // Send another probe during this discovery window.
                }
                catch (OperationCanceledException)
                {
                    return null;
                }
            }
        }
        return null;
    }

    private static IEnumerable<IPEndPoint> BroadcastEndpoints()
    {
        yield return new IPEndPoint(IPAddress.Broadcast, Protocol.Port);
        HashSet<uint> seen = [];
        foreach (NetworkInterface network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up || network.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                continue;
            foreach (UnicastIPAddressInformation address in network.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork || address.IPv4Mask == null)
                    continue;
                byte[] ip = address.Address.GetAddressBytes();
                byte[] mask = address.IPv4Mask.GetAddressBytes();
                byte[] broadcast = new byte[4];
                for (int i = 0; i < 4; i++) broadcast[i] = (byte)(ip[i] | ~mask[i]);
                uint key = BitConverter.ToUInt32(broadcast);
                if (seen.Add(key)) yield return new IPEndPoint(new IPAddress(broadcast), Protocol.Port);
            }
        }
    }
}
