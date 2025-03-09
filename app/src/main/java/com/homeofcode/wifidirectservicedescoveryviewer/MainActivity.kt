package com.homeofcode.wifidirectservicedescoveryviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.homeofcode.wifidirectservicedescoveryviewer.ui.theme.WifiDirectServiceDescoveryViewerTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MainActivity : ComponentActivity() {
    private lateinit var wifiDirectHelper: WiFiDirectHelper
    private var accepterThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirectHelper = WiFiDirectHelper(this)
        wifiDirectHelper.setConnectCallback({i -> connected(i)})
        requestNecessaryPermissions()

        setContent {
            WifiDirectServiceDescoveryViewerTheme {
                WiFiDirectScreen(wifiDirectHelper)
            }
        }
    }

    private fun connected(ip: InetAddress) {
        Toast.makeText(this, "Connecting to ${ip}", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val client = Socket(ip, 8888)
                client.getOutputStream().write("Hello from client".toByteArray())
                val input = client.getInputStream()
                val buffer = ByteArray(1024)
                val bytesRead = input.read(buffer)
                if (bytesRead > 0) {
                    val receivedData = String(buffer, 0, bytesRead)
                    runOnUiThread {
                        Toast.makeText(this, "Received data: $receivedData", Toast.LENGTH_SHORT).show()
                    }
                    Toast.makeText(this, "Received data: $receivedData", Toast.LENGTH_SHORT).show()
                }
                client.close()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error connecting to ${ip}: ${e}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    private fun requestNecessaryPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        accepterThread = Thread {
            var ad = ServerSocket(8888)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    runOnUiThread() {
                        Toast.makeText(this, "Server socket started", Toast.LENGTH_SHORT).show()
                    }
                    val client = ad.accept()
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    val buffer = ByteArray(1024)
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val receivedData = String(buffer, 0, bytesRead)
                        runOnUiThread() {
                            Toast.makeText(this, "Received data: $receivedData", Toast.LENGTH_SHORT)
                                .show()
                        }
                        output.write("Hello from server".toByteArray())
                    }
                    client.close()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    runOnUiThread() {
                        Toast.makeText(this, "Server socket closed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        accepterThread?.start()
        wifiDirectHelper.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        wifiDirectHelper.unregisterReceiver()
        accepterThread?.interrupt()
        accepterThread = null
    }
}


@Composable
fun WiFiDirectScreen(wifiDirectHelper: WiFiDirectHelper) {
    var status by remember { mutableStateOf("Status: Idle") }
    val discoveredServices = remember { mutableStateMapOf<WiFiDirectHelper.HelperServiceInfo, WiFiDirectHelper.HelperServiceInfo>() }
    val txtDiscoveredRecords = remember { mutableStateMapOf<WiFiDirectHelper.HelperDnsServiceInfo, WiFiDirectHelper.HelperDnsServiceInfo>() }

    wifiDirectHelper.setCallbacks(
        {
            status = "Discovery active: ${wifiDirectHelper.discovering}"
        },
        {
            wifiDirectHelper.discoveredServices.forEach { service ->
                discoveredServices[service] = service
            }
        },
        {
            wifiDirectHelper.discoveredDnsServices.forEach { service ->
                txtDiscoveredRecords[service] = service
            }
        },
        {
            status = "Wifi active: ${wifiDirectHelper.wifiManagerActive}"
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = status, style = MaterialTheme.typography.bodyLarge)
        var requiredPermissions = if (wifiDirectHelper.hasRequiredPermissions()) "✅" else "❌"
        Text(text = "Has required permissions ${requiredPermissions}", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(10.dp))

        Button(onClick = {
            wifiDirectHelper.registerService("TestService", "_testservice._tcp", 8888)
            status = "Service registered"
        }) {
            Text("Register Service")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(onClick = {
            discoveredServices.clear()
            wifiDirectHelper.discoverServices("_testservice._tcp")
            status = "Discovering services..."
        }) {
            Text("Discover Services")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Discovered Services:", style = MaterialTheme.typography.bodyLarge)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            items(discoveredServices.keys.toTypedArray()) { service ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = {
                      wifiDirectHelper.connectToDevice(service.device)
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = "${service.name} ${service.type} ${service.device.deviceName} ${service.device.deviceAddress}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Text(text = "Discovered TxtServices:", style = MaterialTheme.typography.bodyLarge)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            items(txtDiscoveredRecords.keys.toTypedArray()) { service ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = {
                        wifiDirectHelper.connectToDevice(service.device)
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = "${service.protocolType} ${service.txtRecordMap} ${service.device.deviceName} ${service.device.deviceAddress}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

