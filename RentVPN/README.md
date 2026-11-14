# RENT VPN — Android client

Kotlin + Jetpack Compose. Dark interface with lime accent, home connection screen, server list, traffic counters, speed test and profile.

## Implemented engines and formats

- **Xray-core:** VLESS (TLS / Reality; TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP and HTTP transports supported by the parser), VMess, Trojan, Shadowsocks, SOCKS5 and HTTP/HTTPS proxy URI adapters.
- **WireGuard:** official WireGuard Android tunnel library, importing standard `.conf` files.
- **Imports:** HTTPS subscriptions, Base64 subscription payloads, individual proxy links, text files, clipboard, and QR codes from an image selected on the device.
- **Connection lifecycle:** one VPN controller, foreground VPN service for Xray, state updates from the service, and delayed DISCONNECTED confirmation until the Xray service closes its tunnel.

## Important limitations

OpenVPN, IKEv2 and SSH tunnelling are **not implemented** in this project and are not shown as supported. They require separate VPN/tunnel engines; they must not be represented as working merely by accepting their links.

QR import currently decodes a QR image selected from the device. It does not run a live camera preview.

## Build

The GitHub Actions workflow downloads `libv2ray.aar` from `2dust/AndroidLibXrayLite`, then builds the debug APK with JDK 17 and Gradle 8.9.

## Device verification still required

A successful CI build confirms compilation and APK packaging only. It does not prove that a server can be reached or that every transport works with a particular provider configuration. Verify on real Android devices:
- VPN permission, connect/disconnect and reconnect;
- VLESS TLS / Reality and Vision flow against compatible servers;
- VMess, Trojan, Shadowsocks, SOCKS5 and HTTP proxy configurations;
- WireGuard tunnel establishment and route/DNS behaviour;
- QR image decoding and subscription refresh.

The app stores imported server links in the app's private files directory. Treat subscription URLs and proxy credentials as secrets.
