package com.homeofcode.wifidirectservicedescoveryviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

class WiFiDirectHelper(private val context: Context) {
    private var txtServiceDiscovered: () -> Unit = {}
    private var serviceDiscovered: () -> Unit = {}
    private var discoveryStateChange: () -> Unit = {}
    private var wifiStateChange: () -> Unit = {}

    var wifiManagerActive: Int = -1
        private set
    var channelOpen: Boolean = true
        private set
    var discovering: Boolean = false
        private set

    private val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, {
        channelOpen = false
    })
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
    }

    data class HelperServiceInfo(val name: String, val type: String, val device: WifiP2pDevice)
    data class HelperDnsServiceInfo(val protocolType: String, val txtRecordMap: Map<String, String>, val device: WifiP2pDevice)

    val discoveredServices: MutableSet<HelperServiceInfo> = mutableSetOf()
    val discoveredDnsServices: MutableSet<HelperDnsServiceInfo> = mutableSetOf()

    @SuppressLint("MissingPermission")
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    val discoveringStream = when (state) {
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> {
                            discovering = true
                            "started"
                        }
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> {
                            discovering = false
                            "stopped"
                        }
                        else -> {
                            discovering = false
                            "unknown"
                        }
                    }
                    discoveryStateChange.invoke()
                    Toast.makeText(context, "Discovery now $discoveringStream", Toast.LENGTH_SHORT).show()
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    wifiManagerActive = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val stateString = when (wifiManagerActive) {
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "Enabled"
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "Disabled"
                        else -> "Unknown"
                    }
                    wifiStateChange.invoke()
                    Toast.makeText(context, "WiFi P2P State Changed: $stateString", Toast.LENGTH_SHORT).show()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        Toast.makeText(context, "Connected to a device", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Disconnected from a device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var serviceAdded = false

    @SuppressLint("MissingPermission")
    fun registerService(serviceName: String, serviceType: String, port: Int) {
        if (serviceAdded) {
            Toast.makeText(context, "Service already registered", Toast.LENGTH_SHORT).show()
            return
        }
        val record = mutableMapOf(
            "serviceName" to serviceName,
            "port" to port.toString()
        )
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Needed permissions missing", Toast.LENGTH_SHORT).show()
            return
        }
        val serviceInfo: WifiP2pServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(serviceName, serviceType, record)

        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(context, "Service registered successfully", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(context, "Service registration failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun setCallbacks(discoveryCallback: () -> Unit, serviceDiscoveryCallback: () -> Unit, txtRecordListener: () -> Unit, wifiStateListener: () -> Unit) {
        this.discoveryStateChange = discoveryCallback
        this.serviceDiscovered = serviceDiscoveryCallback
        this.txtServiceDiscovered = txtRecordListener
        this.wifiStateChange = wifiStateListener
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(serviceType: String) {
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Missing necessary permissions", Toast.LENGTH_SHORT).show()
            return
        }
        discoveredServices.clear()
        discoveredDnsServices.clear()

        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                val serviceInfo = HelperServiceInfo(instanceName, registrationType, srcDevice)
                discoveredServices.add(serviceInfo)
                serviceDiscovered.invoke()
                Toast.makeText(context, "Service discovered: $instanceName", Toast.LENGTH_SHORT).show()
            },
            { protocolType, txtRecordMap, srcDevice ->
                val dnsServiceInfo = HelperDnsServiceInfo(protocolType, txtRecordMap, srcDevice)
                discoveredDnsServices.add(dnsServiceInfo)
                txtServiceDiscovered.invoke()
                Toast.makeText(context, "DNS Service discovered: $protocolType", Toast.LENGTH_SHORT)
                    .show()
            }
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirectHelper", "Service request added")
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(context, "Failed to add service request: $reason", Toast.LENGTH_SHORT).show()
            }
        })
        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirectHelper", "Service discovery started")
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(context, "Service discovery failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun registerReceiver() {
        context.registerReceiver(broadcastReceiver, intentFilter)
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(broadcastReceiver)
    }

    fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            Toast.makeText(context, "Missing necessary permissions", Toast.LENGTH_SHORT).show()
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(context, "Connection initiated", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(context, "Connection failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })

    }
}