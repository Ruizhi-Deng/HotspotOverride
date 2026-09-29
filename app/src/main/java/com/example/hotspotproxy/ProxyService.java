package com.example.hotspotproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProxyService extends Service {
    static final String EXTRA_BIND = "bind";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_NETWORK_MODE = "network_mode";
    static final String EXTRA_NETWORK_HANDLE = "network_handle";
    static final String ACTION_STOP = "com.example.hotspotproxy.STOP";
    static final String ACTION_STATE_CHANGED = "com.example.hotspotproxy.STATE_CHANGED";
    static final String BIND_AUTO = "__AUTO__";
    static final String BIND_ANY_IPV4 = "__ANY_IPV4__";
    static final String MODE_CELLULAR = "cellular";
    static final String MODE_DEFAULT = "default";
    static final String MODE_SELECTED = "selected";

    private static final String CHANNEL = "proxy";
    private static final int HEADER_LIMIT = 128 * 1024;
    private static final long NETWORK_WAIT_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final Object LOG_LOCK = new Object();

    private final ExecutorService clients = Executors.newCachedThreadPool(r -> daemonThread(r, "proxy-client"));
    private final ExecutorService dialers = Executors.newCachedThreadPool(r -> daemonThread(r, "proxy-dial"));
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();
    private final Object networkLock = new Object();
    private volatile Network cellular;
    private volatile ServerSocket server;
    private ConnectivityManager manager;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback allNetworksCallback;
    private String bindSpec;
    private String bindIp;
    private int port;
    private String networkMode;
    private long selectedNetworkHandle;
    private volatile String bindIface = "未知接口";
    private volatile boolean running;
    private volatile boolean failed;
    private volatile String lastNetworkInfo = "";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null || running) {
            show(server == null ? "正在启动；若要更改配置，请先停止代理"
                    : "代理已运行在 " + bindIp + ":" + port + "；更改配置前请先停止");
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        bindSpec = intent.getStringExtra(EXTRA_BIND);
        if (bindSpec == null) bindSpec = BIND_AUTO;
        port = intent.getIntExtra(EXTRA_PORT, 8888);
        networkMode = intent.getStringExtra(EXTRA_NETWORK_MODE);
        if (networkMode == null) networkMode = MODE_CELLULAR;
        selectedNetworkHandle = intent.getLongExtra(EXTRA_NETWORK_HANDLE, -1L);
        if (port < 1024 || port > 65535) {
            publishStatus("端口无效：必须为 1024–65535");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        failed = false;
        connections.set(0);
        clearTrafficLog();
        appendLog("开始启动代理；监听配置=" + bindSpec + "，端口=" + port);
        publishNetworkInfo("等待选择网络");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hotspot Proxy", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification("启动中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        publishStatus("正在启动…");

        manager = getSystemService(ConnectivityManager.class);
        registerNetworkCallbacks();
        new Thread(this::listen, "proxy-listener").start();
        return START_NOT_STICKY;
    }

    private void registerNetworkCallbacks() {
        allNetworksCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { signalNetworkChange(); }
            @Override public void onLost(Network network) { signalNetworkChange(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { signalNetworkChange(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties props) { signalNetworkChange(); }
        };
        try {
            manager.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), allNetworksCallback);
        } catch (RuntimeException e) {
            Log.w("HotspotProxy", "all-networks callback", e);
            allNetworksCallback = null;
        }

        if (!MODE_CELLULAR.equals(networkMode)) return;
        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (!running) return;
                cellular = n;
                publishNetworkInfo(describeNetwork("强制蜂窝", n));
                signalNetworkChange();
                show(server == null ? "蜂窝网络已就绪；正在建立监听"
                        : "监听中：" + bindIp + ":" + port + " (" + bindIface + ")");
            }

            @Override public void onLinkPropertiesChanged(Network n, LinkProperties properties) {
                if (!running || !n.equals(cellular)) return;
                publishNetworkInfo(describeNetwork("强制蜂窝", n));
                if (bindIp != null) {
                    for (LinkAddress address : properties.getLinkAddresses()) {
                        String host = address.getAddress().getHostAddress();
                        if (bindIp.equals(host)) {
                            appendLog("警告：监听地址 " + bindIp + " 也属于蜂窝接口 "
                                    + properties.getInterfaceName() + "；已继续运行，但热点电脑可能无法访问");
                            show("警告：监听地址属于蜂窝接口；代理仍在运行");
                            break;
                        }
                    }
                }
                signalNetworkChange();
            }

            @Override public void onLost(Network n) {
                if (!running) return;
                if (n.equals(cellular)) cellular = null;
                publishNetworkInfo("强制蜂窝：暂不可用，连接会等待最多 30 秒");
                signalNetworkChange();
                show("蜂窝网络断开；新请求将等待恢复");
            }
        };
        try {
            manager.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cellularCallback);
        } catch (RuntimeException e) {
            cellularCallback = null;
            failed = true;
            Log.e("HotspotProxy", "requestNetwork", e);
            show("蜂窝网络请求失败：" + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void listen() {
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            InetAddress address = resolveBindAddress();
            bindIp = address.getHostAddress();
            listener.bind(new InetSocketAddress(address, port));
            server = listener;
            if (!running) return;
            if (BIND_ANY_IPV4.equals(bindSpec)) {
                bindIface = "所有 IPv4 接口";
                appendLog("警告：正在所有 IPv4 接口上监听；当前代理没有身份验证");
            } else {
                NetworkInterface iface = NetworkInterface.getByInetAddress(address);
                bindIface = iface == null ? "未知接口" : iface.getName();
            }
            warnIfCellularBind();
            show("监听中：" + bindIp + ":" + port + " (" + bindIface + ")；等待电脑连接");
            while (running && !listener.isClosed()) {
                Socket client = listener.accept();
                appendLog("电脑 " + client.getInetAddress().getHostAddress() + " 已连接端口；累计 "
                        + connections.incrementAndGet() + " 次");
                openSockets.add(client);
                clients.execute(() -> {
                    try (client) { handle(client); }
                    catch (Exception e) { Log.d("HotspotProxy", "client", e); }
                    finally { openSockets.remove(client); }
                });
            }
        } catch (IOException e) {
            if (running) {
                failed = true;
                Log.e("HotspotProxy", "listen", e);
                show("监听失败：" + e.getMessage());
                appendLog("监听失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
                stopSelf();
            }
        }
    }

    private InetAddress resolveBindAddress() throws IOException {
        if (BIND_ANY_IPV4.equals(bindSpec)) return InetAddress.getByName("0.0.0.0");
        if (!BIND_AUTO.equals(bindSpec)) {
            InetAddress address = InetAddress.getByName(bindSpec);
            if (!isLocalAddress(address)) throw new IOException("所选地址已不属于手机本机，请刷新接口列表");
            return address;
        }
        Set<String> cellularAddresses = currentCellularAddresses();
        InetAddress best = null;
        int bestScore = Integer.MIN_VALUE;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            String name = iface.getName().toLowerCase(Locale.ROOT);
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isMulticastAddress() || address.isLinkLocalAddress()) continue;
                String host = address.getHostAddress();
                int score = address instanceof Inet4Address ? 30 : 5;
                if (address.isSiteLocalAddress()) score += 30;
                if (isCarrierGradeNat(address)) score += 15;
                if (name.contains("swlan") || name.contains("softap") || name.contains("tether")
                        || name.equals("ap0") || name.startsWith("ap")) score += 100;
                else if (name.contains("wlan") || name.contains("wifi")) score += 50;
                if (cellularAddresses.contains(host)) score -= 500;
                if (score > bestScore) { best = address; bestScore = score; }
            }
        }
        if (best == null) throw new IOException("没有找到可监听的本机网络地址；请手动选择接口");
        appendLog("自动选择监听地址 " + best.getHostAddress());
        return best;
    }

    private boolean isLocalAddress(InetAddress wanted) throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
            while (addresses.hasMoreElements()) if (wanted.equals(addresses.nextElement())) return true;
        }
        return false;
    }

    private Set<String> currentCellularAddresses() {
        Set<String> result = new HashSet<>();
        if (manager == null) return result;
        for (Network n : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
            LinkProperties props = manager.getLinkProperties(n);
            if (props == null) continue;
            for (LinkAddress address : props.getLinkAddresses()) {
                String host = address.getAddress().getHostAddress();
                if (host != null) result.add(host);
            }
        }
        return result;
    }

    private void warnIfCellularBind() {
        if (bindIp == null || BIND_ANY_IPV4.equals(bindSpec) || !currentCellularAddresses().contains(bindIp)) return;
        appendLog("警告：监听地址 " + bindIp + " 属于蜂窝接口；已继续运行，但热点电脑可能无法访问");
        show("警告：监听地址属于蜂窝接口；代理仍在运行");
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] b = address.getAddress();
        int first = b[0] & 0xff, second = b[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ProxyService.class).setAction(ACTION_STOP);
        PendingIntent off = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Hotspot Proxy")
                .setContentText(message)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "停止", off)
                .setOngoing(true).build();
    }

    private void show(String s) {
        publishStatus(s);
        getSystemService(NotificationManager.class).notify(1, notification(s));
    }

    private void publishStatus(String s) {
        getSharedPreferences("proxy_state", MODE_PRIVATE).edit().putString("status", s).apply();
        broadcastState();
    }

    private void publishNetworkInfo(String s) {
        if (s.equals(lastNetworkInfo)) return;
        lastNetworkInfo = s;
        getSharedPreferences("proxy_state", MODE_PRIVATE).edit().putString("network_info", s).apply();
        broadcastState();
    }

    private void clearTrafficLog() {
        synchronized (LOG_LOCK) {
            getSharedPreferences("proxy_state", MODE_PRIVATE).edit().remove("traffic_log").apply();
        }
        broadcastState();
    }

    private void appendLog(String s) {
        synchronized (LOG_LOCK) {
            String old = getSharedPreferences("proxy_state", MODE_PRIVATE).getString("traffic_log", "");
            List<String> lines = new ArrayList<>();
            if (!old.isEmpty()) Collections.addAll(lines, old.split("\\n"));
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            lines.add(time + "  " + s.replace('\n', ' '));
            while (lines.size() > 10) lines.remove(0);
            getSharedPreferences("proxy_state", MODE_PRIVATE).edit()
                    .putString("traffic_log", String.join("\n", lines)).apply();
        }
        broadcastState();
    }

    private void broadcastState() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(15000);
        InputStream in = client.getInputStream();
        byte[] header;
        try {
            header = readHeader(in);
        } catch (HeaderTooLargeException e) {
            appendLog("请求头过大：已收到 " + e.receivedBytes + " bytes，上限 " + HEADER_LIMIT + " bytes");
            reply(client, 431, "请求头超过 " + HEADER_LIMIT + " bytes；已收到至少 " + e.receivedBytes + " bytes");
            return;
        } catch (IOException e) {
            appendLog("客户端未发来完整 HTTP 请求：" + e.getMessage());
            reply(client, 400, "无效 HTTP 请求");
            return;
        }
        String raw = new String(header, StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\r\n");
        String[] first = lines[0].split(" ", 3);
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) {
            reply(client, 400, "无效请求行"); return;
        }
        boolean tunnel = first[0].equalsIgnoreCase("CONNECT");
        URI uri;
        try { uri = new URI((tunnel ? "http://" : "") + first[1]); }
        catch (Exception e) { reply(client, 400, "无效目标地址"); return; }
        if ((!tunnel && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            reply(client, 400, "只支持 HTTP 和 HTTPS CONNECT"); return;
        }
        int targetPort = uri.getPort() == -1 ? (tunnel ? 443 : 80) : uri.getPort();
        if (targetPort < 1 || targetPort > 65535) { reply(client, 400, "无效端口"); return; }
        String target = uri.getHost() + ":" + targetPort;
        appendLog("收到 " + first[0] + " " + target + "；选择出站网络");
        Network net = awaitOutboundNetwork(NETWORK_WAIT_MS);
        if (net == null) {
            appendLog("失败：等待出站网络 30 秒后仍不可用；目标 " + target);
            reply(client, 503, "所选手机网络等待 30 秒后仍未就绪");
            return;
        }
        publishNetworkInfo(describeNetwork(modeLabel(), net));

        boolean responseStarted = false;
        try (Socket remote = openOnNetwork(net, uri.getHost(), targetPort)) {
            appendLog("已连通 " + target + "；正在转发 " + first[0]);
            openSockets.add(remote);
            try {
                client.setSoTimeout(0);
                OutputStream dest = remote.getOutputStream();
                if (tunnel) {
                    client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    responseStarted = true;
                } else {
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    StringBuilder forwarded = new StringBuilder(first[0] + " " + path + " " + first[2] + "\r\n");
                    boolean hasHost = false;
                    for (int i = 1; i < lines.length; i++) {
                        String lower = lines[i].toLowerCase(Locale.ROOT);
                        if (lower.startsWith("proxy-authorization:") || lower.startsWith("proxy-connection:")
                                || lower.startsWith("connection:") || lower.startsWith("keep-alive:")) continue;
                        if (lower.startsWith("host:")) hasHost = true;
                        forwarded.append(lines[i]).append("\r\n");
                    }
                    if (!hasHost) forwarded.append("Host: ").append(uri.getRawAuthority()).append("\r\n");
                    forwarded.append("Connection: close\r\n\r\n");
                    dest.write(forwarded.toString().getBytes(StandardCharsets.ISO_8859_1));
                    responseStarted = true;
                }
                Thread upload = daemonThread(() -> pump(in, remote), "proxy-upload");
                upload.start();
                pump(remote.getInputStream(), client);
            } finally { openSockets.remove(remote); }
        } catch (IOException e) {
            Log.d("HotspotProxy", "remote", e);
            appendLog("出站失败 " + target + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
            if (!responseStarted) reply(client, 502, "目标网站连接失败");
        }
    }

    private Network awaitOutboundNetwork(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (running) {
            Network n = chooseOutboundNetwork();
            if (n != null) return n;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            synchronized (networkLock) {
                try { TimeUnit.NANOSECONDS.timedWait(networkLock, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
        }
        return null;
    }

    private Network chooseOutboundNetwork() {
        if (MODE_CELLULAR.equals(networkMode)) return cellular;
        if (MODE_DEFAULT.equals(networkMode)) return manager.getActiveNetwork();
        if (MODE_SELECTED.equals(networkMode)) {
            for (Network n : manager.getAllNetworks()) {
                if (n.getNetworkHandle() == selectedNetworkHandle) return n;
            }
        }
        return null;
    }

    private void signalNetworkChange() {
        synchronized (networkLock) { networkLock.notifyAll(); }
    }

    private Socket openOnNetwork(Network net, String host, int targetPort) throws IOException {
        InetAddress[] addresses;
        try { addresses = net.getAllByName(host); }
        catch (IOException e) {
            appendLog("DNS 失败 " + host + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
            throw e;
        }
        List<InetAddress> allowed = new ArrayList<>();
        for (InetAddress ip : addresses) {
            // 本轮未选择修改目标地址过滤，保持 1.5 的行为。
            if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress()
                    || (ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc)) continue;
            allowed.add(ip);
        }
        if (allowed.isEmpty()) throw new IOException("DNS 没有返回允许连接的公网地址：" + host);

        CompletionService<Socket> completion = new ExecutorCompletionService<>(dialers);
        List<Future<Socket>> futures = new ArrayList<>();
        Set<Socket> dialing = ConcurrentHashMap.newKeySet();
        AtomicBoolean winnerChosen = new AtomicBoolean(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
        for (InetAddress ip : allowed) {
            futures.add(completion.submit(() -> {
                Socket socket = net.getSocketFactory().createSocket();
                dialing.add(socket);
                try {
                    long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    socket.connect(new InetSocketAddress(ip, targetPort), (int) Math.min(Integer.MAX_VALUE, remainingMs));
                    if (winnerChosen.compareAndSet(false, true)) return socket;
                    throw new IOException("另一个 IPv4/IPv6 地址已先连通");
                } catch (IOException e) {
                    dialing.remove(socket);
                    try { socket.close(); } catch (IOException ignored) { }
                    throw e;
                }
            }));
        }

        IOException last = null;
        Socket winner = null;
        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<Socket> completed;
                try { completed = completion.poll(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("连接目标时被中断", e);
                }
                if (completed == null) break;
                try {
                    winner = completed.get();
                    if (winner != null) return winner;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    last = cause instanceof IOException ? (IOException) cause : new IOException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("连接目标时被中断", e);
                }
            }
            throw last != null ? last : new IOException("IPv4/IPv6 并行连接在 30 秒内均未成功");
        } finally {
            winnerChosen.set(true);
            for (Socket socket : dialing) {
                if (socket != winner) try { socket.close(); } catch (IOException ignored) { }
            }
            for (Future<Socket> future : futures) if (!future.isDone()) future.cancel(true);
        }
    }

    private String describeNetwork(String prefix, Network n) {
        if (manager == null || n == null) return prefix + "：不可用";
        LinkProperties props = manager.getLinkProperties(n);
        String iface = props == null || props.getInterfaceName() == null ? "未知接口" : props.getInterfaceName();
        List<String> addresses = new ArrayList<>(), dns = new ArrayList<>();
        if (props != null) {
            for (LinkAddress a : props.getLinkAddresses()) addresses.add(a.getAddress().getHostAddress());
            for (InetAddress d : props.getDnsServers()) dns.add(d.getHostAddress());
        }
        return prefix + " / " + iface + " / 地址=" + String.join(",", addresses)
                + " / DNS=" + String.join(",", dns);
    }

    private String modeLabel() {
        if (MODE_CELLULAR.equals(networkMode)) return "强制蜂窝";
        if (MODE_DEFAULT.equals(networkMode)) return "系统默认";
        return "指定网络 #" + selectedNetworkHandle;
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < HEADER_LIMIT) {
            int c = in.read();
            if (c < 0) throw new IOException("EOF（已收到 " + b.size() + " bytes）");
            b.write(c);
            state = (state == 0 && c == '\r') || (state == 2 && c == '\r') ? state + 1
                    : (state == 1 && c == '\n') || (state == 3 && c == '\n') ? state + 1
                    : c == '\r' ? 1 : 0;
            if (state == 4) return b.toByteArray();
        }
        throw new HeaderTooLargeException(b.size());
    }

    private static void pump(InputStream from, Socket to) {
        try {
            byte[] buf = new byte[16384];
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = from.read(buf)) != -1) out.write(buf, 0, n);
            to.shutdownOutput();
        } catch (IOException ignored) { }
    }

    private static void reply(Socket client, int code, String msg) {
        try {
            byte[] body = (msg + "\n").getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().write(body);
        } catch (IOException ignored) { }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    private static final class HeaderTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
        final int receivedBytes;
        HeaderTooLargeException(int receivedBytes) {
            super("Headers exceeded " + HEADER_LIMIT + " bytes");
            this.receivedBytes = receivedBytes;
        }
    }

    @Override public void onDestroy() {
        running = false;
        signalNetworkChange();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : openSockets) try { s.close(); } catch (IOException ignored) { }
        if (manager != null && cellularCallback != null) {
            try { manager.unregisterNetworkCallback(cellularCallback); } catch (RuntimeException ignored) { }
        }
        if (manager != null && allNetworksCallback != null) {
            try { manager.unregisterNetworkCallback(allNetworksCallback); } catch (RuntimeException ignored) { }
        }
        clients.shutdownNow();
        dialers.shutdownNow();
        if (!failed) publishStatus("已停止");
        super.onDestroy();
    }
}
