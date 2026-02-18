package com.surveillancepro.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.services.AppUsageTracker
import com.surveillancepro.android.services.LocationService
import com.surveillancepro.android.services.SupervisionNotificationListener
import com.surveillancepro.android.ui.theme.SupervisionProTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var storage: DeviceStorage
    private lateinit var api: ApiClient

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startLocationService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = DeviceStorage.getInstance(this)
        api = ApiClient.getInstance(storage)

        setContent {
            SupervisionProTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (storage.hasAccepted) {
                        SupervisionActiveScreen()
                    } else {
                        ConsentScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun ConsentScreen() {
        var userName by remember { mutableStateOf("") }
        var serverURL by remember { mutableStateOf(storage.serverURL) }
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var accepted by remember { mutableStateOf(false) }

        if (accepted) {
            SupervisionActiveScreen()
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("Telephone professionnel", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Information sur la surveillance", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Text("Ce telephone est un appareil professionnel fourni par l'entreprise. En activant cette application, vous acceptez que :")
            Spacer(Modifier.height(12.dp))

            val points = listOf(
                "Vos activites sur ce telephone sont enregistrees et consultees par l'entreprise",
                "Les notifications (WhatsApp, SMS, Telegram...) sont capturees",
                "Les textes tapes au clavier sont enregistres",
                "La localisation GPS est suivie en continu",
                "Les applications installees et leur temps d'utilisation sont suivis",
                "Les informations techniques (batterie, stockage) sont collectees",
            )
            points.forEach { point ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("\u2022 ", fontWeight = FontWeight.Bold)
                    Text(point, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "Pour toute activite privee, utilisez votre telephone personnel. Sur cet appareil professionnel, aucune activite n'est consideree comme privee.",
                    modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = Color(0xFFE65100)
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = userName, onValueChange = { userName = it },
                label = { Text("Votre nom complet") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = serverURL, onValueChange = { serverURL = it },
                label = { Text("URL du serveur") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (userName.length < 2) { error = "Entrez votre nom"; return@Button }
                    isLoading = true; error = null
                    storage.serverURL = serverURL.trimEnd('/')
                    lifecycleScope.launch {
                        try {
                            val deviceId = storage.deviceId
                            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

                            val result = api.registerDevice(
                                deviceId = deviceId,
                                deviceName = "Android - $userName - ${Build.MODEL}",
                                userName = userName,
                                acceptanceVersion = "1.0",
                                acceptanceDate = date
                            )

                            val token = result?.get("deviceToken") as? String
                            if (token == null) { error = "Serveur injoignable ou pas de token"; isLoading = false; return@launch }

                            storage.deviceToken = token
                            storage.userName = userName
                            storage.acceptanceDate = date

                            val consentOk = api.sendConsent("1.0", userName)
                            if (!consentOk) { error = "Erreur lors de l'envoi du consentement"; isLoading = false; return@launch }

                            storage.hasAccepted = true
                            storage.consentSent = true
                            isLoading = false

                            requestAllPermissions()
                            sendInitialData()
                            accepted = true

                        } catch (e: Exception) {
                            error = "Erreur: ${e.message}"
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && userName.length >= 2,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("J'accepte et j'active la supervision", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun SupervisionActiveScreen() {
        var eventCount by remember { mutableIntStateOf(storage.eventCount) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                eventCount = storage.eventCount
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("\uD83D\uDEE1\uFE0F", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text("Telephone professionnel surveille", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Les activites sur cet appareil sont enregistrees. Pour toute activite privee, utilisez votre telephone personnel.",
                fontSize = 14.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow("Utilisateur", storage.userName ?: "-")
                    StatusRow("Serveur", storage.serverURL)
                    StatusRow("Evenements envoyes", "$eventCount")
                    StatusRow("Accepte le", storage.acceptanceDate?.take(10) ?: "-")
                    StatusRow("Appareil", Build.MODEL)
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { openNotificationSettings() }) {
                Text("Activer la lecture des notifications")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openAccessibilitySettings() }) {
                Text("Activer la capture du clavier")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openUsageAccessSettings() }) {
                Text("Activer le suivi des applications")
            }

            Spacer(Modifier.height(16.dp))
            Text("Supervision Pro v1.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun StatusRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        locationPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun startLocationService() {
        // GPS sur demande uniquement -- ne se lance pas automatiquement
    }

    private fun sendInitialData() {
        lifecycleScope.launch {
            try {
                api.sendEvent("device_info", mapOf(
                    "model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "system" to "Android ${Build.VERSION.RELEASE}",
                    "sdk" to Build.VERSION.SDK_INT,
                ))

                val tracker = AppUsageTracker(this@MainActivity)
                val apps = tracker.getInstalledApps()
                api.sendEvent("apps_installed", mapOf("apps" to apps, "count" to apps.size))

            } catch (_: Exception) {}
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Ouvrez Parametres > Notifications > Acces aux notifications", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Ouvrez Parametres > Accessibilite", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Ouvrez Parametres > Acces aux donnees d'utilisation", Toast.LENGTH_LONG).show()
        }
    }
}
