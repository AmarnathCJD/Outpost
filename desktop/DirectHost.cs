using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace Outpost;

internal sealed record LaptopAddress(string InterfaceId, string Network, string Address, bool HasGateway, bool IsVpn)
{
    public override string ToString() => $"{Network} — {Address}{(IsVpn ? " (VPN)" : "")}";
}

internal static class DirectHost
{
    public static List<LaptopAddress> Addresses()
    {
        var result = new List<LaptopAddress>();
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up || adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            try
            {
                var properties = adapter.GetIPProperties();
                foreach (var address in properties.UnicastAddresses)
                {
                    if (address.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(address.Address)) continue;
                    var ip = address.Address.ToString();
                    if (ip.StartsWith("169.254.")) continue;
                    var vpn = adapter.Name.Contains("tailscale", StringComparison.OrdinalIgnoreCase) || adapter.Description.Contains("tailscale", StringComparison.OrdinalIgnoreCase) || adapter.NetworkInterfaceType == NetworkInterfaceType.Ppp;
                    result.Add(new(adapter.Id, adapter.Name, ip, properties.GatewayAddresses.Any(g => !g.Address.Equals(IPAddress.Any)), vpn));
                }
            }
            catch (NetworkInformationException) { }
        }
        return result.OrderByDescending(x => x.IsVpn).ThenByDescending(x => x.HasGateway).ThenBy(x => x.Network).ToList();
    }

    public static async Task<bool> SshListening(int port)
    {
        try
        {
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            using var client = new TcpClient();
            await client.ConnectAsync(IPAddress.Loopback, port, deadline.Token);
            var buffer = new byte[256];
            var length = await client.GetStream().ReadAsync(buffer, deadline.Token);
            return Encoding.ASCII.GetString(buffer, 0, length).StartsWith("SSH-2.0-Outpost_");
        }
        catch (Exception e) when (e is SocketException or IOException or OperationCanceledException) { return false; }
    }
}
