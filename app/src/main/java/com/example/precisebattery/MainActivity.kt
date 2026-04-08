package com.example.precisebattery

import android.content.Context
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

    LaunchedEffect(Unit) {
        while (true) {
            batteryPercentage = getPreciseBatteryPercentage(context)
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
                .size(250.dp),
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
                }
            }
        }
    }
}

fun getPreciseBatteryPercentage(context: Context): String {
    try {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // charge counter is in microampere-hours (uAh)
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        
        var maxCapacity = 0.0

        // Attempt 1: Read full capacity in uAh from sysfs charge_full
        val chargeFullFile = File("/sys/class/power_supply/battery/charge_full")
        if (chargeFullFile.exists()) {
            val text = chargeFullFile.readText().trim()
            val capacityFromFile = text.toDoubleOrNull() ?: 0.0
            if (capacityFromFile > 0) {
                maxCapacity = capacityFromFile
            }
        }

        // Attempt 2: Use PowerProfile reflection (returns mAh, so multiply by 1000 for uAh)
        if (maxCapacity == 0.0) {
            try {
                val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
                val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
                val getBatteryCapacityMethod = powerProfileClass.getMethod("getBatteryCapacity")
                val capacityMah = getBatteryCapacityMethod.invoke(powerProfile) as Double
                maxCapacity = capacityMah * 1000.0
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (maxCapacity > 0.0 && chargeCounter > 0) {
            val percentage = (chargeCounter.toDouble() / maxCapacity) * 100.0
            return String.format(Locale.US, "%.2f%%", percentage.coerceIn(0.0, 100.0))
        }

        // Fallback: standard integer battery percentage
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level != -1 && scale != -1) {
            val pct = level * 100f / scale.toFloat()
            return String.format(Locale.US, "%.2f%%", pct)
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Unknown"
}
