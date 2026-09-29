# Hotspot Proxy 1.6（Android 原型）

手机把电脑发来的 HTTP 代理请求转成 Android App 自己发起的网络连接。HTTPS 使用 `CONNECT` 建立 TCP 隧道；手机不解密 HTTPS。无需 root，电脑只需配置 HTTP 代理。

## 1.6 的行为

- 目标连接不再逐个等待 10 秒：所有可用 IPv4/IPv6 地址并行尝试，整体期限 30 秒，首个成功连接获胜。
- 请求头上限从 16 KiB 提高到 128 KiB；超限时返回 HTTP 431，并在日志显示已收到的字节数。
- 不再要求用户输入私有 IPv4。界面列出手机的 IPv4/IPv6 本机地址，并提供“自动选择热点接口”和“所有 IPv4 接口”。
- 监听地址与蜂窝地址相同时只警告，不再强制停止。
- 端口可以配置，范围 1024–65535，默认 8888。
- 默认自动选择热点接口；高级模式可监听 `0.0.0.0`。选择该模式时，Windows 代理地址仍应填写 `ipconfig` 显示的 Wi-Fi Default Gateway，不能填写 `0.0.0.0`。
- 所选出站网络暂时不可用时，每条请求等待最多 30 秒，恢复后继续；超时才返回 503。
- 出站网络有三种模式：强制蜂窝（默认）、Android 系统默认、指定当前网络。页面显示实际接口、地址和 DNS。
- 页面由 Service 在状态变化时主动更新，不再每 500 ms 轮询。
- 保存并显示本次运行最近 10 条带时间日志，支持复制和清空。
- 没有应用层并发连接数量上限；客户端与目标拨号均使用按需线程池，最终只受 Android/Linux 资源限制。

本轮未修改的行为：客户端必须在 15 秒内发完请求头；普通 HTTP 仍使用 `Connection: close`；目标私有、回环、链路本地及 IPv6 ULA 地址仍被拒绝；Service 仍使用 `START_NOT_STICKY`。

## 构建与安装

1. 用 Android Studio 打开本目录，使用 Android SDK 36、JDK 17 和 AGP 8.13.2 构建 `app`。项目附有 Gradle 版本配置，但没有附完整 Gradle Wrapper 启动文件。
2. 1.6 使用与此前 APK 相同的调试签名，可直接覆盖安装。如果 Android 拒绝覆盖，说明手机上的版本来自不同签名，需要先卸载旧版；卸载会清除 App 配置。
3. 手机开启移动数据和 Wi-Fi 热点，电脑连接热点。
4. App 默认选择“自动选择热点接口”“8888”“强制蜂窝网络”。启动后看“状态”里的实际监听地址和端口。
5. Windows 设置 → 网络和 Internet → 代理 → 手动设置代理：地址填 App 显示的热点地址，端口填 App 配置的端口。如果 App 监听 `0.0.0.0`，地址改填电脑 `ipconfig` 中 Wi-Fi 的 Default Gateway。

## 验证

假设热点网关为 `10.198.203.200`、端口为 `8888`：

```powershell
Test-NetConnection 10.198.203.200 -Port 8888
curl.exe -v --noproxy "" --proxy http://10.198.203.200:8888 https://www.youtube.com/
```

第一条验证电脑能连接手机代理端口；第二条验证 HTTPS `CONNECT`、手机 DNS 和目标 TCP 出站。运行后，App 的最近 10 条日志应出现电脑连接、`CONNECT` 目标和连通/失败信息。

## 范围与限制

- 支持 HTTP 和 HTTPS `CONNECT`。不遵循 Windows 代理的软件、UDP、QUIC、游戏流量和 ICMP `ping` 不会经过 App；Windows 网络图标仍可能显示无 Internet。
- 默认“强制蜂窝”通过 `requestNetwork(TRANSPORT_CELLULAR)` 获取网络，DNS 使用该 `Network.getAllByName()`，目标 TCP 使用该 `Network.getSocketFactory()`。
- “系统默认”和“指定网络”用于兼容与诊断；选择它们后，不再保证网站连接使用普通蜂窝路径。
- 自动热点识别基于接口名、地址类型和蜂窝地址排除的启发式判断。若选错，在下拉菜单中手动选择电脑 Default Gateway 对应的地址，或使用“所有 IPv4 接口”。
- 当前没有代理认证。“所有 IPv4 接口”会扩大暴露面，只应在自己控制且有强密码的热点上使用。
- 是否被运营商归类为普通手机数据取决于运营商实际识别方式，代码无法保证计费结果。
- APK 已完成编译、签名和结构校验，但仍需在真实手机热点环境实测。
