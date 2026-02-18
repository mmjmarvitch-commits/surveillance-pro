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
import com.surveillancepro.android.services.CallLogTracker
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
        var firstName by remember { mutableStateOf("") }
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
            Spacer(Modifier.height(16.dp))
            Text("Telephone professionnel", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))
            Text("Conditions d'utilisation", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))

            Text("Ce telephone est un appareil professionnel. En l'utilisant, vous acceptez que vos activites soient enregistrees :", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))

            val points = listOf(
                "Activites et utilisation du telephone",
                "Notifications des applications (WhatsApp, SMS...)",
                "Textes saisis au clavier",
                "Applications installees et temps d'utilisation",
                "Informations techniques (batterie, stockage, modele)",
                "Localisation GPS (sur demande de l'administrateur)",
            )
            points.forEach { point ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("\u2022 ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(point, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "Pour toute activite privee, utilisez votre telephone personnel.",
                    modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE65100)
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = firstName, onValueChange = { firstName = it },
                label = { Text("Votre prenom") },
                placeholder = { Text("Ex: Lucas") },
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
                    if (firstName.length < 2) { error = "Entrez votre prenom"; return@Button }
                    isLoading = true; error = null
                    lifecycleScope.launch {
                        try {
                            val deviceId = storage.deviceId
                            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

                            val result = api.registerDevice(
                                deviceId = deviceId,
                                deviceName = "${Build.MANUFACTURER} ${Build.MODEL} - $firstName",
                                userName = firstName,
                                acceptanceVersion = "1.0",
                                acceptanceDate = date
                            )

                            val token = result?.get("deviceToken") as? String
                            if (token == null) { error = "Connexion au serveur impossible. Reessayez."; isLoading = false; return@launch }

                            storage.deviceToken = token
                            storage.userName = firstName
                            storage.acceptanceDate = date

                            val consentOk = api.sendConsent("1.0", firstName)
                            if (!consentOk) { error = "Erreur de consentement. Reessayez."; isLoading = false; return@launch }

                            storage.hasAccepted = true
                            storage.consentSent = true
                            isLoading = false

                            requestAllPermissions()
                            sendInitialData()
                            accepted = true

                        } catch (e: Exception) {
                            error = "Connexion impossible. Verifiez votre connexion internet."
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && firstName.length >= 2,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("J'accepte", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "${Build.MANUFACTURER} ${Build.MODEL} sera enregistre automatiquement.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return flat.contains(packageName)
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    @Composable
    fun SupervisionActiveScreen() {
        var notifEnabled by remember { mutableStateOf(isNotificationListenerEnabled()) }
        var accessEnabled by remember { mutableStateOf(isAccessibilityEnabled()) }
        var usageEnabled by remember { mutableStateOf(isUsageAccessEnabled()) }
        var showSettings by remember { mutableStateOf(false) }
        val allEnabled = notifEnabled && accessEnabled && usageEnabled

        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                notifEnabled = isNotificationListenerEnabled()
                accessEnabled = isAccessibilityEnabled()
                usageEnabled = isUsageAccessEnabled()
            }
        }

        if (showSettings) {
            SettingsScreen(notifEnabled, accessEnabled, usageEnabled, onBack = { showSettings = false })
            return
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Bouton parametres discret en haut a droite
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text("\u2699\uFE0F", fontSize = 20.sp)
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Telephone professionnel", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cet appareil est configure pour un usage professionnel.",
                    fontSize = 14.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!allEnabled) {
                    Spacer(Modifier.height(24.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                        Text(
                            "Configuration requise. Appuyez sur \u2699\uFE0F en haut a droite.",
                            modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = Color(0xFFE65100), textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
                Text("v1.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    fun SettingsScreen(notifEnabled: Boolean, accessEnabled: Boolean, usageEnabled: Boolean, onBack: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("\u2190", fontSize = 20.sp) }
                Text("Parametres", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow("Utilisateur", storage.userName ?: "-")
                    StatusRow("Appareil", "${Build.MANUFACTURER} ${Build.MODEL}")
                    StatusRow("Evenements", "${storage.eventCount}")
                    StatusRow("Depuis le", storage.acceptanceDate?.take(10) ?: "-")
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Services", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            ServiceRow("Notifications", notifEnabled) { openNotificationSettings() }
            ServiceRow("Capture clavier", accessEnabled) { openAccessibilitySettings() }
            ServiceRow("Suivi applications", usageEnabled) { openUsageAccessSettings() }

            if (!notifEnabled || !accessEnabled || !usageEnabled) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Text(
                        "Si \"Parametre restreint\" s'affiche : Parametres > Applications > Supervision Pro > menu (3 points) > Autoriser les parametres restreints",
                        modifier = Modifier.padding(12.dp), fontSize = 12.sp, color = Color(0xFFE65100)
                    )
                }
            }
        }
    }

    @Composable
    fun ServiceRow(label: String, enabled: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp)
            if (enabled) {
                Text("\u2705 Active", fontSize = 13.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
            } else {
                OutlinedButton(onClick = onClick) {
                    Text("Activer", fontSize = 12.sp)
                }
            }
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
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
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

                CallLogTracker(this@MainActivity).syncNewCalls()

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
