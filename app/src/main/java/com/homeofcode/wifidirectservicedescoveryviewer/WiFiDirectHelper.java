package com.homeofcode.wifidirectservicedescoveryviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class WiFiDirectHelper {
    private final Context context;
    private Runnable serviceDiscovered = () -> {};
    private Runnable dnsServiceDiscovered = () -> {};
    private Runnable discoveryStateChange = () -> {};
    private Runnable wifiStateChange = () -> {};
    private Consumer<InetAddress> connectCallback = i -> {};

    int wifiManagerActive = -1;
    boolean channelOpen = true;
    boolean discovering = false;

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final IntentFilter intentFilter;

    public static class HelperServiceInfo {
        public final String name;
        public final String type;
        public final WifiP2pDevice device;

        public HelperServiceInfo(String name, String type, WifiP2pDevice device) {
            this.name = name;
            this.type = type;
            this.device = device;
        }
    }

    public static class HelperDnsServiceInfo {
        public final String protocolType;
        public final Map<String, String> txtRecordMap;
        public final WifiP2pDevice device;

        public HelperDnsServiceInfo(String protocolType, Map<String, String> txtRecordMap, WifiP2pDevice device) {
            this.protocolType = protocolType;
            this.txtRecordMap = txtRecordMap;
            this.device = device;
        }
    }

    final Set<HelperServiceInfo> discoveredServices = new HashSet<>();
    final Set<HelperDnsServiceInfo> discoveredDnsServices = new HashSet<>();

    public WiFiDirectHelper(Context context) {
        this.context = context;
        this.manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel = manager.initialize(context, context.getMainLooper(), () -> channelOpen = false);
        this.intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) switch (intent.getAction()) {
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                    String discoveringStream;
                    if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                        discovering = true;
                        discoveringStream = "started";
                    } else if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                        discovering = false;
                        discoveringStream = "stopped";
                    } else {
                        discovering = false;
                        discoveringStream = "unknown";
                    }
                    discoveryStateChange.run();
                    Toast.makeText(context, "Discovery now " + discoveringStream, Toast.LENGTH_SHORT).show();
                    break;
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                    wifiManagerActive = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    String stateString;
                    if (wifiManagerActive == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        stateString = "Enabled";
                    } else if (wifiManagerActive == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        stateString = "Disabled";
                    } else {
                        stateString = "Unknown";
                    }
                    wifiStateChange.run();
                    Toast.makeText(context, "WiFi P2P State Changed: " + stateString, Toast.LENGTH_SHORT).show();
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    var info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                    if (info != null && info.groupFormed) {
                        connectCallback.accept(info.groupOwnerAddress);
                        Toast.makeText(context, "Connected to a device", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Disconnected from a device", Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    private boolean serviceAdded = false;

    @SuppressLint("MissingPermission")
    public void registerService(String serviceName, String serviceType, int port) {
        if (serviceAdded) {
            Toast.makeText(context, "Service already registered", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, String> record = new HashMap<>();
        record.put("serviceName", serviceName);
        record.put("port", Integer.toString(port));
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Needed permissions missing", Toast.LENGTH_SHORT).show();
            return;
        }
        WifiP2pServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(serviceName, serviceType, record);

        manager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                serviceAdded = true;
                Toast.makeText(context, "Service registered successfully", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(context, "Service registration failed: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void setCallbacks(Runnable discoveryCallback, Runnable serviceDiscoveryCallback, Runnable txtRecordListener, Runnable wifiStateListener) {
        this.discoveryStateChange = discoveryCallback;
        this.serviceDiscovered = serviceDiscoveryCallback;
        this.dnsServiceDiscovered = txtRecordListener;
        this.wifiStateChange = wifiStateListener;
    }

    public void setConnectCallback(Consumer<InetAddress> callback) {
        this.connectCallback = callback;
    }

    @SuppressLint("MissingPermission")
    public void discoverServices(String serviceType) {
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Missing necessary permissions", Toast.LENGTH_SHORT).show();
            return;
        }
        discoveredServices.clear();
        discoveredDnsServices.clear();

        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, srcDevice) -> {
                    HelperServiceInfo serviceInfo = new HelperServiceInfo(instanceName, registrationType, srcDevice);
                    discoveredServices.add(serviceInfo);
                    serviceDiscovered.run();
                    Toast.makeText(context, "Service discovered: " + instanceName, Toast.LENGTH_SHORT).show();
                },
                (protocolType, txtRecordMap, srcDevice) -> {
                    HelperDnsServiceInfo dnsServiceInfo = new HelperDnsServiceInfo(protocolType, txtRecordMap, srcDevice);
                    discoveredDnsServices.add(dnsServiceInfo);
                    dnsServiceDiscovered.run();
                    Toast.makeText(context, "DNS Service discovered: " + protocolType, Toast.LENGTH_SHORT).show();
                }
        );

        WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d("WiFiDirectHelper", "Service request added");
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(context, "Failed to add service request: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d("WiFiDirectHelper", "Service discovery started");
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(context, "Service discovery failed: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void registerReceiver() {
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    public void unregisterReceiver() {
        context.unregisterReceiver(broadcastReceiver);
    }

    public boolean hasRequiredPermissions() {
        String[] requiredPermissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES,
        };
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    public void connectToDevice(WifiP2pDevice device) {
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Missing necessary permissions", Toast.LENGTH_SHORT).show();
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.requestConnectionInfo(channel, info -> {
                    if (info == null) connectCallback.accept(null);
                    else connectCallback.accept(info.groupOwnerAddress);
                });
                Toast.makeText(context, "Connection initiated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(context, "Connection failed: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }
}