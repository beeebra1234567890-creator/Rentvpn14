# RENT VPN — Android client (v1.2)

Kotlin + Jetpack Compose. Тёмный интерфейс с лаймовым акцентом (шар из частиц, кнопка питания, статус, скорость, плашка сервера, нижняя навигация), **VLESS** через Xray-core и **WireGuard** через официальную библиотеку.

## Что сделано
- Дизайн по макету: Главная (статус, PRO, таймер, ↑/↓ скорость, шар + кнопка, сервер с пингом), Скорость, Серверы, Профиль. Состояния кнопки: «Нажмите» → «Подключение» → «Отключить».
- **VLESS**: разбор `vless://` (TCP / WS / gRPC / HTTPUpgrade / XHTTP / h2; TLS / Reality; `flow=xtls-rprx-vision`, SNI, fp, alpn, pbk/sid/spx и т.д.) → JSON-конфиг Xray → `XrayVpnService` (TUN через `VpnService`).
- Подписки: HTTPS-ссылка (в т.ч. `happ://add/…`), base64-список, список ссылок, файл, буфер обмена. Можно вставить одну `vless://` ссылку или WG-конфиг — сервер добавится в список.
- TCP-пинг до VLESS-серверов, флаг берётся из эмодзи в названии сервера.
- Протоколы VMess / Trojan / Shadowsocks по-прежнему только показываются в списке (не подключаются).

## ВАЖНО: нужен движок Xray
Движок не лежит в Maven, поэтому его нужно положить вручную:

1. Скачайте `libv2ray.aar` из релизов проекта **2dust/AndroidLibXrayLite** (или соберите его через `gomobile bind`).
2. Положите файл в `app/libs/libv2ray.aar`.
3. Откройте проект в Android Studio (JDK 17), Sync, затем Build → Build APK(s).

Код написан под API `AndroidLibXrayLite`: `Libv2ray.initCoreEnv(path, key)`, `Libv2ray.newCoreController(CoreCallbackHandler)`, `CoreController.startLoop(config, tunFd)`, `stopLoop()`. Если в вашей версии AAR сигнатуры отличаются — правится только `XrayVpnService.kt`.

## Что не проверено
Проект собран «на сухую» — в среде, где его писали, не было Android SDK, поэтому **сборка и запуск на устройстве не выполнялись**. Особенно стоит проверить на реальном телефоне:
- что ваш `libv2ray.aar` принимает fd TUN-интерфейса в `startLoop` (tun-inbound Xray). Если нет — нужна связка Xray (SOCKS-inbound) + `hev-socks5-tunnel`, как в v2rayNG;
- Reality / Vision на вашем сервере;
- вид шара на слабых устройствах (при просадках FPS уменьшите `PER_GROUP` в `Components.kt`).

## Заметки
- Процесс приложения исключён из VPN (`addDisallowedApplication`), чтобы соединение Xray к серверу не зацикливалось в туннель.
- Скорость на экране — трафик приложения до сервера (с учётом шифрования), а не «чистая» скорость внутри туннеля.
- Серверы хранятся в приватной папке приложения без шифрования. Не делитесь ссылками и PrivateKey.
