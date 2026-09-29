package com.example.hotspotproxy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREF_CONFIG = "proxy_config";
    private Spinner bindAddress;
    private Spinner outboundNetwork;
    private EditText port;
    private TextView status;
    private TextView networkInfo;
    private TextView traffic;
    private final List<Option> bindOptions = new ArrayList<>();
    private final List<NetworkOption> networkOptions = new ArrayList<>();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshState(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 45, 40, 35);
        scroll.addView(layout);

        TextView guide = new TextView(this);
        guide.setText("开启手机移动数据和热点。默认会自动选择热点接口、监听 8888，并强制网站连接走蜂窝网络。电脑代理地址使用状态里显示的监听地址；如果选择 0.0.0.0，请使用电脑 ipconfig 显示的 Wi-Fi Default Gateway。");
        guide.setTextSize(17);
        layout.addView(guide);

        addLabel(layout, "监听接口");
        bindAddress = new Spinner(this);
        layout.addView(bindAddress);

        port = new EditText(this);
        port.setSingleLine(true);
        port.setHint("代理端口（1024–65535）");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(getSharedPreferences(PREF_CONFIG, MODE_PRIVATE).getString("port", "8888"));
        layout.addView(port);

        addLabel(layout, "网站连接使用的手机网络");
        outboundNetwork = new Spinner(this);
        layout.addView(outboundNetwork);

        Button refresh = new Button(this);
        refresh.setText("刷新接口和网络列表");
        refresh.setOnClickListener(v -> refreshOptions());
        layout.addView(refresh);

        Button start = new Button(this);
        start.setText("启动 HTTP/HTTPS 代理");
        start.setOnClickListener(v -> startProxy());
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("停止代理");
        stop.setOnClickListener(v -> {
            status.setText("状态：正在停止…");
            Intent intent = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_STOP);
            startService(intent);
        });
        layout.addView(stop);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 22, 0, 10);
        layout.addView(status);

        networkInfo = new TextView(this);
        networkInfo.setTextSize(15);
        networkInfo.setPadding(0, 0, 0, 16);
        layout.addView(networkInfo);

        traffic = new TextView(this);
        traffic.setTextSize(15);
        traffic.setTextIsSelectable(true);
        traffic.setPadding(0, 0, 0, 12);
        layout.addView(traffic);

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("复制日志");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Hotspot Proxy 日志", traffic.getText()));
            Toast.makeText(this, "已复制最近 10 条日志", Toast.LENGTH_SHORT).show();
        });
        logButtons.addView(copy);
        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(v -> {
            getSharedPreferences("proxy_state", MODE_PRIVATE).edit().remove("traffic_log").apply();
            refreshState();
        });
        logButtons.addView(clear);
        layout.addView(logButtons);

        TextView note = new TextView(this);
        note.setText("“所有 IPv4 接口”会监听 0.0.0.0，Windows 代理里不能填 0.0.0.0，仍应填写 Wi-Fi Default Gateway。该模式暴露面更大；当前没有代理认证，只应在自己控制且有强密码的热点上运行。HTTPS 仍由电脑与网站端到端加密。");
        note.setPadding(0, 22, 0, 0);
        note.setGravity(Gravity.START);
        layout.addView(note);

        setContentView(scroll);
        refreshOptions();
        refreshState();
    }

    private void addLabel(LinearLayout layout, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setPadding(0, 18, 0, 5);
        layout.addView(label);
    }

    private void refreshOptions() {
        String savedBind = getSharedPreferences(PREF_CONFIG, MODE_PRIVATE).getString("bind", ProxyService.BIND_AUTO);
        bindOptions.clear();
        bindOptions.add(new Option("自动选择热点接口（推荐）", ProxyService.BIND_AUTO));
        bindOptions.add(new Option("所有 IPv4 接口：0.0.0.0（高级）", ProxyService.BIND_ANY_IPV4));
        try {
            List<Option> local = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null) continue;
                    local.add(new Option(iface.getName() + " — " + host
                            + (address.isLinkLocalAddress() ? "（链路本地）" : ""), host));
                }
            }
            local.sort(Comparator.comparing(o -> o.label));
            bindOptions.addAll(local);
        } catch (Exception ignored) { }
        ArrayAdapter<Option> bindAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, bindOptions);
        bindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bindAddress.setAdapter(bindAdapter);
        selectOption(bindAddress, bindOptions, savedBind);

        String savedMode = getSharedPreferences(PREF_CONFIG, MODE_PRIVATE)
                .getString("network_mode", ProxyService.MODE_CELLULAR);
        long savedHandle = getSharedPreferences(PREF_CONFIG, MODE_PRIVATE).getLong("network_handle", -1L);
        networkOptions.clear();
        networkOptions.add(new NetworkOption("强制蜂窝网络（推荐）", ProxyService.MODE_CELLULAR, -1L));
        networkOptions.add(new NetworkOption("使用 Android 系统默认网络", ProxyService.MODE_DEFAULT, -1L));
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            networkOptions.add(new NetworkOption("指定网络：" + describeNetwork(cm, n),
                    ProxyService.MODE_SELECTED, n.getNetworkHandle()));
        }
        ArrayAdapter<NetworkOption> networkAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, networkOptions);
        networkAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outboundNetwork.setAdapter(networkAdapter);
        int networkSelection = 0;
        for (int i = 0; i < networkOptions.size(); i++) {
            NetworkOption option = networkOptions.get(i);
            if (option.mode.equals(savedMode)
                    && (!ProxyService.MODE_SELECTED.equals(savedMode) || option.handle == savedHandle)) {
                networkSelection = i;
                break;
            }
        }
        outboundNetwork.setSelection(networkSelection);
    }

    private static String describeNetwork(ConnectivityManager cm, Network n) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        LinkProperties props = cm.getLinkProperties(n);
        String type = "其他";
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "蜂窝";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "以太网";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = "VPN";
        }
        String iface = props == null || props.getInterfaceName() == null ? "未知接口" : props.getInterfaceName();
        return String.format(Locale.ROOT, "%s / %s / #%d", type, iface, n.getNetworkHandle());
    }

    private void startProxy() {
        int selectedBind = bindAddress.getSelectedItemPosition();
        int selectedNetwork = outboundNetwork.getSelectedItemPosition();
        if (selectedBind < 0 || selectedNetwork < 0) return;
        int proxyPort;
        try {
            proxyPort = Integer.parseInt(port.getText().toString().trim());
            if (proxyPort < 1024 || proxyPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            port.setError("端口必须在 1024–65535 之间");
            return;
        }
        Option bind = bindOptions.get(selectedBind);
        NetworkOption network = networkOptions.get(selectedNetwork);
        getSharedPreferences(PREF_CONFIG, MODE_PRIVATE).edit()
                .putString("bind", bind.value)
                .putString("port", Integer.toString(proxyPort))
                .putString("network_mode", network.mode)
                .putLong("network_handle", network.handle).apply();
        Intent intent = new Intent(this, ProxyService.class)
                .putExtra(ProxyService.EXTRA_BIND, bind.value)
                .putExtra(ProxyService.EXTRA_PORT, proxyPort)
                .putExtra(ProxyService.EXTRA_NETWORK_MODE, network.mode)
                .putExtra(ProxyService.EXTRA_NETWORK_HANDLE, network.handle);
        status.setText("状态：正在启动，请稍候…");
        try { startForegroundService(intent); }
        catch (RuntimeException e) {
            String message = "启动失败：" + e;
            getSharedPreferences("proxy_state", MODE_PRIVATE).edit().putString("status", message).apply();
            refreshState();
        }
    }

    private static void selectOption(Spinner spinner, List<Option> options, String value) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value.equals(value)) { spinner.setSelection(i); return; }
        }
        spinner.setSelection(0);
    }

    private void refreshState() {
        android.content.SharedPreferences prefs = getSharedPreferences("proxy_state", MODE_PRIVATE);
        status.setText("状态：" + prefs.getString("status", "未启动"));
        networkInfo.setText("出站网络：" + prefs.getString("network_info", "尚未选择"));
        String log = prefs.getString("traffic_log", "");
        traffic.setText(log.isEmpty() ? "最近 10 条连接日志：\n尚无来自电脑的连接"
                : "最近 10 条连接日志：\n" + log);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ProxyService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
        refreshState();
    }

    @Override protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private static final class Option {
        final String label;
        final String value;
        Option(String label, String value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private static final class NetworkOption {
        final String label;
        final String mode;
        final long handle;
        NetworkOption(String label, String mode, long handle) {
            this.label = label; this.mode = mode; this.handle = handle;
        }
        @Override public String toString() { return label; }
    }
}
