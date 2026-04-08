package com.example.precisebattery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatteryScreen(context = this)
                }
            }
        }
    }
}

@Composable
fun BatteryScreen(context: Context) {
    var batteryPercentage by remember { mutableStateOf("Loading...") }
    var debugInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val result = getPreciseBatteryData(context)
            batteryPercentage = result.first
            debugInfo = result.second
            delay(1000) // Update every second
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .padding(16.dp)
                .size(280.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Precise Battery",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = batteryPercentage,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = debugInfo,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

fun getPreciseBatteryData(context: Context): Pair<String, String> {
    try {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val prefs = context.getSharedPreferences("BatteryPrefs", Context.MODE_PRIVATE)

        // charge counter is in microampere-hours (uAh)
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        
        var systemPct = 0.0
        if (level != -1 && scale != -1) {
            systemPct = level.toDouble() / scale.toDouble()
        }

        // 1. Try to read from sysfs (usually blocked by SELinux on modern Android)
        var maxCapacity = 0.0
        val chargeFullFile = File("/sys/class/power_supply/battery/charge_full")
        if (chargeFullFile.exists() && chargeFullFile.canRead()) {
            val text = chargeFullFile.readText().trim()
            val capacityFromFile = text.toDoubleOrNull() ?: 0.0
            if (capacityFromFile > 0) maxCapacity = capacityFromFile
        }

        // 2. If sysfs fails, we use a Calibration approach.
        // Because PowerProfile gives the "Design Capacity" (brand new battery),
        // we instead estimate the current Degraded Capacity using the system percentage.
        var calibratedMax = prefs.getFloat("calibrated_max_capacity", 0f).toDouble()

        if (maxCapacity == 0.0 && chargeCounter > 0 && systemPct > 0.0) {
            // Android often floors the integer percentage. So 33% means between 33.0 and 33.99.
            // We use the system percentage to estimate the true max capacity.
            val currentEstimatedMax = chargeCounter.toDouble() / systemPct
            
            // If we don't have a calibration yet, or if the current charge implies a higher capacity,
            // we update our calibration.
            if (calibratedMax == 0.0 || currentEstimatedMax > calibratedMax) {
                calibratedMax = currentEstimatedMax
                prefs.edit().putFloat("calibrated_max_capacity", calibratedMax.toFloat()).apply()
            }
            maxCapacity = calibratedMax
        }

        if (maxCapacity > 0.0 && chargeCounter > 0) {
            val percentage = (chargeCounter.toDouble() / maxCapacity) * 100.0
            
            // If the calculated percentage drifts too far from system percentage (e.g. system is 33%, but we calculate 24%),
            // or if the battery degrades further, adjust calibration
            if (percentage > (systemPct * 100.0 + 2.0) || percentage < (systemPct * 100.0 - 2.0)) {
               val newCalibratedMax = chargeCounter.toDouble() / systemPct
               prefs.edit().putFloat("calibrated_max_capacity", newCalibratedMax.toFloat()).apply()
               maxCapacity = newCalibratedMax
            }

            val fixedPercentage = (chargeCounter.toDouble() / maxCapacity) * 100.0
            return Pair(
                String.format(Locale.US, "%.2f%%", fixedPercentage.coerceIn(0.0, 100.0)),
                "Calibrated True Max: ${"%.0f".format(maxCapacity / 1000)} mAh"
            )
        }

        // Fallback: standard integer battery percentage
        if (systemPct > 0.0) {
            return Pair(String.format(Locale.US, "%.2f%%", systemPct * 100), "Fallback System %")
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Pair("Unknown", "Error calculating")
}
