# HotspotOverride

HotspotOverride is a small Android HTTP/HTTPS proxy for routing proxy-aware traffic from devices connected to your phone's hotspot through a network selected by the Android app.

The typical setup is a laptop connected to the phone's Wi-Fi hotspot while HotspotOverride accepts proxy connections on the hotspot interface and opens the corresponding Internet connections through the phone's cellular network.

No root access is required.

> HotspotOverride is an application-layer proxy. It does not transparently intercept all hotspot traffic. Applications on the client device must use the configured HTTP proxy.

## How it works

```text
Laptop / other hotspot client
        |
        | HTTP proxy requests
        | HTTPS CONNECT tunnels
        v
HotspotOverride on Android
        |
        | Android Network API
        | (cellular / system default / selected network)
        v
      Internet
```

For HTTPS, HotspotOverride only creates a TCP tunnel using the standard `CONNECT` method. TLS remains end-to-end between the client and the destination server; the app does not decrypt HTTPS traffic.

## Features

- HTTP proxying and HTTPS `CONNECT` tunneling
- No root required
- Automatic hotspot-interface selection
- Manual bind-address selection, including `0.0.0.0`
- Configurable proxy port (default: `8888`)
- Three outbound-network modes:
  - **Force cellular** — recommended default
  - **Android system default**
  - **Specific Android network**
- DNS resolution performed on the selected Android network
- Parallel IPv4/IPv6 connection attempts to destination hosts
- Foreground service with current proxy status
- Live network/interface information
- Recent connection log with the latest 10 entries
- 128 KiB maximum HTTP request header size

## Requirements

### Android device

- Android 8.0 (API 26) or newer
- Mobile data
- Wi-Fi hotspot capability

### Building from source

The project currently targets:

- Android SDK 36
- Java 17
- Android Gradle Plugin 8.13.2
- Gradle 8.13

The easiest way to build it is to open the repository in Android Studio, let Gradle sync, and build the `app` module.

The repository contains the Gradle wrapper configuration but does not currently include the complete `gradlew` / `gradlew.bat` wrapper launcher files, so Android Studio is the recommended build path.

## Usage

1. Enable **mobile data** and the phone's **Wi-Fi hotspot**.
2. Connect the computer to the phone's hotspot.
3. Open HotspotOverride.
4. The default configuration is usually sufficient:
   - **Bind address:** automatically select hotspot interface
   - **Port:** `8888`
   - **Outbound network:** force cellular
5. Tap **Start HTTP/HTTPS Proxy**.
6. Check the status shown in the app. It will display the address and port on which the proxy is listening.
7. Configure the computer to use that address as its manual HTTP proxy.

On Windows:

`Settings -> Network & internet -> Proxy -> Manual proxy setup`

Use the listening IP shown by HotspotOverride and the configured port.

If HotspotOverride is listening on `0.0.0.0`, do **not** enter `0.0.0.0` as the proxy address. Use the phone's hotspot address instead, normally the **Default Gateway** shown for the Wi-Fi adapter by `ipconfig`.

### Example

If the phone's hotspot address is `10.198.203.200` and HotspotOverride is listening on port `8888`:

```powershell
Test-NetConnection 10.198.203.200 -Port 8888

curl.exe -v --noproxy "" \
  --proxy http://10.198.203.200:8888 \
  https://www.example.com/
```

The first command checks whether the proxy port is reachable. The second tests an HTTPS `CONNECT` request through the proxy.

## Outbound network modes

### Force cellular

This is the default mode. HotspotOverride requests a cellular network from Android and uses that `Network` object for both DNS resolution and outbound sockets.

If cellular connectivity temporarily disappears, a new proxy request waits for up to 30 seconds for the selected network to become available before returning HTTP `503`.

### Android system default

Uses Android's current active network. This is useful for testing, but it does not guarantee that traffic leaves through cellular.

### Specific network

Lets you select one of the networks currently exposed by Android. This is mainly useful for debugging or unusual multi-network setups.

## Network and proxy behavior

HotspotOverride accepts ordinary HTTP proxy requests and HTTPS `CONNECT` requests.

For normal HTTP requests, proxy-specific connection headers are removed, the request is forwarded to the destination, and the remote connection is closed after the response.

For destination connections, DNS is resolved through the selected Android network. All allowed IPv4 and IPv6 results are attempted in parallel, with the first successful connection used. The overall destination connection deadline is 30 seconds.

The proxy rejects destination addresses that resolve only to local or non-public address ranges, including loopback, link-local, private IPv4, multicast, and IPv6 ULA addresses.

Clients have 15 seconds to send a complete HTTP request header. Request headers are limited to 128 KiB.

## Limitations

HotspotOverride only carries traffic from software that actually uses the configured HTTP proxy. It does not provide transparent packet forwarding.

As a result, the following traffic normally does not pass through the app:

- Applications that ignore the system HTTP proxy
- UDP traffic
- QUIC / HTTP/3 when an application does not fall back through the proxy
- ICMP / `ping`
- Games and other software using custom networking that does not support HTTP proxies

The operating system may therefore still report that the hotspot has no Internet connection even while proxy-aware applications can access the Internet through HotspotOverride.

The proxy currently has **no authentication**. Binding to `0.0.0.0` exposes it on every IPv4 interface, so that mode should only be used on networks you control.

Whether a carrier treats traffic generated through this application differently from normal tethering traffic depends on the carrier and network configuration. HotspotOverride only controls the Android network used for its own outbound sockets and cannot guarantee carrier-side classification or billing behavior.

## Project structure

```text
app/src/main/java/com/example/hotspotproxy/
├── MainActivity.java   # Configuration UI and status display
└── ProxyService.java   # Proxy listener, network selection, and traffic relay
```

The app intentionally uses a small native Android/Java implementation without a separate proxy library.
