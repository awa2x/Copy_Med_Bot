package com.example.medbot_watch_app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import kotlin.reflect.KClass

class MainActivity : ComponentActivity() {

    private val healthPermissions = setOf(
        androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(StepsRecord::class),
        androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(OxygenSaturationRecord::class),
        androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(SkinTemperatureRecord::class),
        androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(BodyFatRecord::class)
    )

    private lateinit var status: TextView
    private var healthConnectClient: HealthConnectClient? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPermissions)) {
            syncSteps()
        } else {
            status.text = "Health Connect permission was not granted."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Ready"
        }

        val button = Button(this).apply {
            text = "Sync steps to Pi"
            setOnClickListener {
                requestPermissionOrSync()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(button)
        }

        setContentView(layout)

        try {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        } catch (_: Exception) {
            status.text = "Health Connect is not available on this device."
        }
    }

    private fun requestPermissionOrSync() {
        val client = healthConnectClient ?: run {
            status.text = "Health Connect unavailable. Use a supported Android device."
            return
        }

        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(healthPermissions)) {
                syncSteps()
            } else {
                permissionLauncher.launch(healthPermissions)
            }
        }
    }

    private suspend fun <T : Record> readAllRecords(
        client: HealthConnectClient,
        recordType: KClass<T>,
        start: Instant,
        end: Instant
    ): List<T> {
        val allRecords = mutableListOf<T>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken
                )
            )
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords
    }

    private fun syncSteps() {
        val client = healthConnectClient ?: return

        lifecycleScope.launch {
            status.text = "Reading health data..."

            try {
                val now = Instant.now()
                val start = now.minus(24, ChronoUnit.HOURS)

                val steps = readAllRecords(client, StepsRecord::class, start, now)
                val heartRates = readAllRecords(client, HeartRateRecord::class, start, now)
                val oxygenReadings =
                    readAllRecords(client, OxygenSaturationRecord::class, start, now)
                val skinTemperatures =
                    readAllRecords(client, SkinTemperatureRecord::class, start, now)
                val bodyFatReadings =
                    readAllRecords(client, BodyFatRecord::class, start, now)

                val payload = JSONObject().apply {
                    put("from", start.toString())
                    put("to", now.toString())

                    put("steps", JSONArray().apply {
                        steps.forEach { record ->
                            put(JSONObject().apply {
                                put("count", record.count)
                                put("start", record.startTime.toString())
                                put("end", record.endTime.toString())
                                put("source", record.metadata.dataOrigin.packageName)
                            })
                        }
                    })

                    put("heart_rate", JSONArray().apply {
                        heartRates.forEach { record ->
                            record.samples.forEach { sample ->
                                put(JSONObject().apply {
                                    put("bpm", sample.beatsPerMinute)
                                    put("time", sample.time.toString())
                                    put("source", record.metadata.dataOrigin.packageName)
                                })
                            }
                        }
                    })

                    put("blood_oxygen", JSONArray().apply {
                        oxygenReadings.forEach { record ->
                            put(JSONObject().apply {
                                // Health Connect percentage value is a fraction:
                                // for example, 0.98 means 98%.
                                put("fraction", record.percentage.value)
                                put("start", record.time.toString())
                                put("source", record.metadata.dataOrigin.packageName)
                            })
                        }
                    })

                    put("skin_temperature", JSONArray().apply {
                        skinTemperatures.forEach { record ->
                            put(JSONObject().apply {
                                put("start", record.startTime.toString())
                                put("end", record.endTime.toString())
                                put("measurement_location", record.measurementLocation)
                                put(
                                    "baseline_celsius",
                                    record.baseline?.inCelsius ?: JSONObject.NULL
                                )
                                put("deltas", JSONArray().apply {
                                    record.deltas.forEach { delta ->
                                        put(JSONObject().apply {
                                            put("time", delta.time.toString())
                                            put("delta_celsius", delta.delta.inCelsius)
                                        })
                                    }
                                })
                                put("source", record.metadata.dataOrigin.packageName)
                            })
                        }
                    })

                    put("body_fat", JSONArray().apply {
                        bodyFatReadings.forEach { record ->
                            put(JSONObject().apply {
                                // This is a body-fat percentage, not raw BIA sensor data.
                                put("fraction", record.percentage.value)
                                put("time", record.time.toString())
                                put("source", record.metadata.dataOrigin.packageName)
                            })
                        }
                    })
                }

                withContext(Dispatchers.IO) {
                    val url = URL("http://192.168.1.50:8000/healthdata")//change 192.168.1.50 to actual Pi IP address
                    val connection = url.openConnection() as HttpURLConnection

                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer replace-this-with-a-long-random-secret"//Replace with token from server.py start after bearer
                    )

                    connection.outputStream.use { stream ->
                        stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = connection.responseCode
                    connection.disconnect()

                    if (responseCode !in 200..299) {
                        throw Exception("Pi request failed with HTTP $responseCode")
                    }
                }

                status.text =
                    "Sent ${steps.size} step records, ${heartRates.size} heart-rate records, " +
                            "${oxygenReadings.size} oxygen records, ${skinTemperatures.size} skin-temperature " +
                            "records, and ${bodyFatReadings.size} body-fat records."
            } catch (e: Exception) {
                status.text = "Sync failed: ${e.message}"
            }
        }
    }
}