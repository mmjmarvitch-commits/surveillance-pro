package com.securitypro.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securitypro.android.data.*
import com.securitypro.android.scanner.AppScanner
import com.securitypro.android.scanner.PhishingDetector
import com.securitypro.android.services.RealTimeProtectionService
import com.securitypro.android.services.ScanScheduler
import com.securitypro.android.services.ScanService
import com.securitypro.android.services.SimAlertManager
import com.securitypro.android.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private lateinit var scanner: AppScanner
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = AppScanner(this)
        
        setContent {
            SecurityProTheme {
                SecurityProApp(
                    scanner = scanner,
                    onStartQuickScan = { startScan(false) },
                    onStartFullScan = { startScan(true) },
                    onToggleProtection = { enabled -> toggleProtection(enabled) },
                    isProtectionRunning = { RealTimeProtectionService.isRunning },
                    onUninstallApp = { packageName -> requestUninstall(packageName) }
                )
            }
        }
        
        startProtectionIfNeeded()
    }
    
    private fun startScan(fullScan: Boolean) {
        val intent = Intent(this, ScanService::class.java).apply {
            action = if (fullScan) ScanService.ACTION_FULL_SCAN else ScanService.ACTION_QUICK_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun toggleProtection(enabled: Boolean) {
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("protection_enabled", enabled).apply()
        
        if (enabled) {
            val intent = Intent(this, RealTimeProtectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(Intent(this, RealTimeProtectionService::class.java))
        }
    }
    
    private fun startProtectionIfNeeded() {
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("protection_enabled", true) && !RealTimeProtectionService.isRunning) {
            toggleProtection(true)
        }
    }
    
    private fun requestUninstall(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Désinstallation de $packageName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de désinstaller: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// Messages animés pendant le scan
val scanMessages = listOf(
    "🔍 Analyse des applications installées...",
    "🛡️ Vérification des signatures malware...",
    "🔐 Analyse des permissions dangereuses...",
    "📱 Détection des spywares connus...",
    "🎭 Recherche de faux contrôles parentaux...",
    "👁️ Détection des apps cachées...",
    "🔓 Vérification des accès root...",
    "📡 Analyse des fuites de données...",
    "🎤 Détection des apps d'écoute...",
    "📍 Vérification du tracking GPS...",
    "🔔 Analyse des listeners de notifications...",
    "⚙️ Vérification des services système...",
    "🧹 Finalisation de l'analyse..."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityProApp(
    scanner: AppScanner,
    onStartQuickScan: () -> Unit,
    onStartFullScan: () -> Unit,
    onToggleProtection: (Boolean) -> Unit,
    isProtectionRunning: () -> Boolean,
    onUninstallApp: (String) -> Unit = {}
) {
    var isScanning by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    var systemStatus by remember { mutableStateOf<SystemStatus?>(null) }
    var protectionEnabled by remember { mutableStateOf(isProtectionRunning()) }
    var currentScanMessage by remember { mutableStateOf("") }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var appsScanned by remember { mutableIntStateOf(0) }
    var threatsFound by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        systemStatus = scanner.checkSystem()
    }
    
    // Animation des messages pendant le scan
    LaunchedEffect(isScanning) {
        if (isScanning) {
            var index = 0
            while (isScanning) {
                currentScanMessage = scanMessages[index % scanMessages.size]
                scanProgress = (index % scanMessages.size).toFloat() / scanMessages.size
                index++
                delay(1500)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("Security Pro", fontWeight = FontWeight.Bold) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Settings, "Paramètres", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AnimatedScanCard(
                    isProtected = protectionEnabled && (systemStatus?.isSecure() ?: true),
                    isScanning = isScanning,
                    threatsCount = scanResult?.threats?.size ?: 0,
                    currentMessage = currentScanMessage,
                    progress = scanProgress,
                    onScanClick = {
                        if (!isScanning) {
                            isScanning = true
                            threatsFound = 0
                            appsScanned = 0
                            scope.launch {
                                val result = scanner.quickScan()
                                scanResult = result
                                threatsFound = result.threats.size
                                appsScanned = result.scannedApps
                                isScanning = false
                            }
                            onStartQuickScan()
                        }
                    }
                )
            }
            
            item {
                ProtectionToggleCard(
                    enabled = protectionEnabled,
                    onToggle = { 
                        protectionEnabled = it
                        onToggleProtection(it)
                    }
                )
            }
            
            item {
                ScanOptionsCard(
                    isScanning = isScanning,
                    onQuickScan = {
                        isScanning = true
                        scope.launch {
                            val result = scanner.quickScan()
                            scanResult = result
                            isScanning = false
                        }
                        onStartQuickScan()
                    },
                    onFullScan = {
                        isScanning = true
                        scope.launch {
                            val result = scanner.fullScan()
                            scanResult = result
                            isScanning = false
                        }
                        onStartFullScan()
                    }
                )
            }
            
            systemStatus?.let { status ->
                item {
                    SystemStatusCard(status)
                }
            }
            
            // Section Outils de sécurité
            item {
                SecurityToolsCard()
            }
            
            scanResult?.let { result ->
                if (result.threats.isNotEmpty()) {
                    item {
                        Text(
                            "Menaces détectées (${result.threats.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryRed
                        )
                    }
                    items(result.threats) { threat ->
                        ThreatCardWithUninstall(
                            threat = threat,
                            onUninstall = { onUninstallApp(threat.packageName) }
                        )
                    }
                } else {
                    // Aucune menace - Afficher un rapport positif
                    item {
                        SecureDeviceCard(
                            appsScanned = result.scannedApps,
                            scanDuration = result.scanDurationMs,
                            systemStatus = result.systemStatus
                        )
                    }
                }
            }
        }
    }
}

// Couleurs cyber pour le thème
val CyberBlue = Color(0xFF00D4FF)
val CyberDarkBlue = Color(0xFF0A1628)
val CyberGlow = Color(0xFF00D4FF)

@Composable
fun AnimatedScanCard(
    isProtected: Boolean,
    isScanning: Boolean,
    threatsCount: Int,
    currentMessage: String,
    progress: Float,
    onScanClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    
    // Rotation du bouclier pendant le scan
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // Pulsation du cercle
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Glow effect
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    // Animation du cercle de progression
    val sweepAngle by animateFloatAsState(
        targetValue = if (isScanning) progress * 360f else 0f,
        animationSpec = tween(500),
        label = "sweep"
    )
    
    val cardColor = when {
        isScanning -> CyberDarkBlue
        threatsCount > 0 -> SecondaryRed
        isProtected -> SecondaryGreen
        else -> Color.Gray
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isScanning) CyberBlue.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                // Cercle animé avec bouclier
                Box(
                    modifier = Modifier.size(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Cercle de glow externe
                    if (isScanning) {
                        Canvas(
                            modifier = Modifier
                                .size(150.dp)
                                .scale(pulse)
                        ) {
                            drawCircle(
                                color = CyberBlue.copy(alpha = glowAlpha * 0.3f),
                                radius = size.minDimension / 2
                            )
                        }
                    }
                    
                    // Cercle de progression
                    Canvas(
                        modifier = Modifier
                            .size(140.dp)
                            .then(if (isScanning) Modifier.rotate(rotation) else Modifier)
                    ) {
                        // Cercle de fond
                        drawCircle(
                            color = Color.White.copy(alpha = 0.1f),
                            style = Stroke(width = 8f)
                        )
                        
                        // Arc de progression
                        if (isScanning) {
                            drawArc(
                                color = CyberBlue,
                                startAngle = -90f,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                style = Stroke(width = 8f, cap = StrokeCap.Round)
                            )
                        }
                    }
                    
                    // Cercle intérieur avec icône
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(
                                if (isScanning) 
                                    Brush.linearGradient(listOf(CyberBlue.copy(alpha = 0.3f), CyberDarkBlue))
                                else 
                                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.1f)))
                            )
                            .border(2.dp, if (isScanning) CyberBlue else Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isScanning -> Icons.Default.Security
                                threatsCount > 0 -> Icons.Default.Warning
                                isProtected -> Icons.Default.VerifiedUser
                                else -> Icons.Default.Shield
                            },
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .then(if (isScanning) Modifier.scale(pulse) else Modifier),
                            tint = if (isScanning) CyberBlue else Color.White
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Titre principal
                Text(
                    text = when {
                        isScanning -> "Analyse en cours"
                        threatsCount > 0 -> "⚠️ $threatsCount menace(s) détectée(s)"
                        isProtected -> "✓ Appareil protégé"
                        else -> "Protection désactivée"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                // Message animé pendant le scan
                AnimatedVisibility(
                    visible = isScanning && currentMessage.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Text(
                        text = currentMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberBlue,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                // Sous-titre quand pas en scan
                if (!isScanning) {
                    Text(
                        text = if (threatsCount > 0) 
                            "Action requise" 
                        else if (isProtected) 
                            "Protection temps réel active"
                        else 
                            "Activez la protection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Bouton de scan
                Button(
                    onClick = onScanClick,
                    enabled = !isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) CyberBlue else Color.White,
                        contentColor = if (isScanning) Color.White else cardColor,
                        disabledContainerColor = CyberBlue.copy(alpha = 0.5f),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Analyse en cours...", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lancer l'analyse", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProtectionToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = if (enabled) SecondaryGreen else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Protection temps réel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (enabled) "Active" else "Désactivée",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) SecondaryGreen else Color.Gray
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SecondaryGreen
                )
            )
        }
    }
}

@Composable
fun ScanOptionsCard(
    isScanning: Boolean,
    onQuickScan: () -> Unit,
    onFullScan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                "Options de scan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScanOptionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FlashOn,
                    title = "Rapide",
                    subtitle = "Apps récentes",
                    enabled = !isScanning,
                    onClick = onQuickScan
                )
                ScanOptionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    title = "Complet",
                    subtitle = "Toutes les apps",
                    enabled = !isScanning,
                    onClick = onFullScan
                )
            }
        }
    }
}

@Composable
fun ScanOptionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null)
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 10.sp, color = Color.Gray)
        }
    }
}

@Composable
fun SystemStatusCard(status: SystemStatus) {
    val riskScore = status.getRiskScore()
    val color = when {
        riskScore >= 50 -> SecondaryRed
        riskScore >= 25 -> SecondaryOrange
        else -> SecondaryGreen
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "État du système",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Score: ${100 - riskScore}/100",
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            StatusRow("Appareil rooté", !status.isRooted)
            StatusRow("Sources inconnues", !status.unknownSourcesEnabled)
            StatusRow("Options développeur", !status.developerOptionsEnabled)
            StatusRow("ADB désactivé", !status.adbEnabled)
            StatusRow("Verrouillage écran", status.screenLockEnabled)
            
            if (status.securityPatchLevel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Patch sécurité: ${status.securityPatchLevel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun StatusRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (isOk) SecondaryGreen else SecondaryRed,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ThreatCard(threat: ThreatInfo) {
    val color = when (threat.threatLevel) {
        ThreatLevel.CRITICAL -> SecondaryRed
        ThreatLevel.HIGH -> SecondaryOrange
        ThreatLevel.MEDIUM -> Color(0xFFFFB300)
        else -> Color.Gray
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    threat.appName,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    threat.threatType.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    threat.description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    threat.recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ThreatCardWithUninstall(
    threat: ThreatInfo,
    onUninstall: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    
    val color = when (threat.threatLevel) {
        ThreatLevel.CRITICAL -> SecondaryRed
        ThreatLevel.HIGH -> SecondaryOrange
        ThreatLevel.MEDIUM -> Color(0xFFFFB300)
        else -> Color.Gray
    }
    
    // Dialog de confirmation
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "Désinstaller ${threat.appName} ?",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        "Cette application a été identifiée comme:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "🔴 ${threat.threatType.name}",
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        threat.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Voulez-vous la désinstaller maintenant ?",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDialog = false
                        onUninstall()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryRed)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Désinstaller")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Icône de menace
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (threat.threatType) {
                            ThreatType.SPYWARE -> Icons.Default.Visibility
                            ThreatType.MALWARE -> Icons.Default.BugReport
                            ThreatType.ADWARE -> Icons.Default.Notifications
                            else -> Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        threat.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = color
                    )
                    Text(
                        threat.threatType.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = color.copy(alpha = 0.8f)
                    )
                }
                
                // Badge de niveau de menace
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color
                ) {
                    Text(
                        text = when (threat.threatLevel) {
                            ThreatLevel.CRITICAL -> "CRITIQUE"
                            ThreatLevel.HIGH -> "ÉLEVÉ"
                            ThreatLevel.MEDIUM -> "MOYEN"
                            else -> "FAIBLE"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                threat.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                threat.recommendation,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bouton de désinstallation
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Désinstaller cette application", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// CARTE DES OUTILS DE SÉCURITÉ
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SecurityToolsCard() {
    var showPhishingDialog by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var urlToCheck by remember { mutableStateOf("") }
    var phishingResult by remember { mutableStateOf<PhishingDetector.PhishingResult?>(null) }
    
    // Dialog vérification phishing
    if (showPhishingDialog) {
        AlertDialog(
            onDismissRequest = { showPhishingDialog = false },
            title = { Text("Vérifier un lien", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Collez un lien suspect pour vérifier s'il est dangereux")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = urlToCheck,
                        onValueChange = { urlToCheck = it },
                        label = { Text("URL à vérifier") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    phishingResult?.let { result ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    result.riskScore >= 70 -> SecondaryRed.copy(alpha = 0.1f)
                                    result.riskScore >= 40 -> SecondaryOrange.copy(alpha = 0.1f)
                                    else -> SecondaryGreen.copy(alpha = 0.1f)
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    result.recommendation,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        result.riskScore >= 70 -> SecondaryRed
                                        result.riskScore >= 40 -> SecondaryOrange
                                        else -> SecondaryGreen
                                    }
                                )
                                Text("Score de risque: ${result.riskScore}/100")
                                if (result.reasons.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    result.reasons.forEach { reason ->
                                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (urlToCheck.isNotEmpty()) {
                            val detector = PhishingDetector()
                            phishingResult = detector.analyzeUrl(urlToCheck)
                        }
                    }
                ) {
                    Text("Analyser")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { 
                    showPhishingDialog = false
                    urlToCheck = ""
                    phishingResult = null
                }) {
                    Text("Fermer")
                }
            }
        )
    }
    
    // Dialog scan programmé
    if (showScheduleDialog) {
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = { Text("Scan automatique", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Programmez un scan automatique de votre appareil")
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    ScheduleOption("Toutes les 12 heures", Icons.Default.Schedule) { }
                    ScheduleOption("Quotidien", Icons.Default.Today) { }
                    ScheduleOption("Hebdomadaire", Icons.Default.DateRange) { }
                }
            },
            confirmButton = {
                Button(onClick = { showScheduleDialog = false }) {
                    Text("Activer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showScheduleDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Outils de sécurité",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Grille d'outils
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecurityToolButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Link,
                    title = "Anti-Phishing",
                    onClick = { showPhishingDialog = true }
                )
                SecurityToolButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Schedule,
                    title = "Scan Auto",
                    onClick = { showScheduleDialog = true }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecurityToolButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SimCard,
                    title = "Alerte SIM",
                    onClick = { }
                )
                SecurityToolButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.History,
                    title = "Historique",
                    onClick = { }
                )
            }
        }
    }
}

@Composable
fun SecurityToolButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = PrimaryBlue)
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ScheduleOption(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryBlue)
        Spacer(modifier = Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f))
        RadioButton(selected = false, onClick = onClick)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// CARTE "TÉLÉPHONE SÉCURISÉ" - Quand aucune menace n'est trouvée
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SecureDeviceCard(
    appsScanned: Int,
    scanDuration: Long,
    systemStatus: SystemStatus?
) {
    val infiniteTransition = rememberInfiniteTransition(label = "secure")
    
    // Animation de pulsation douce
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val securityScore = if (systemStatus != null) 100 - systemStatus.getRiskScore() else 100
    val durationSeconds = scanDuration / 1000
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SecondaryGreen
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Grande icône de succès avec animation
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Titre principal
            Text(
                "✓ TÉLÉPHONE SÉCURISÉ",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Aucune menace détectée sur votre appareil",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Statistiques du scan
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.15f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Rapport de sécurité",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Ligne 1: Apps analysées
                    SecurityStatRow(
                        icon = Icons.Default.Apps,
                        label = "Applications analysées",
                        value = "$appsScanned apps"
                    )
                    
                    // Ligne 2: Durée
                    SecurityStatRow(
                        icon = Icons.Default.Timer,
                        label = "Durée de l'analyse",
                        value = "${durationSeconds}s"
                    )
                    
                    // Ligne 3: Score de sécurité
                    SecurityStatRow(
                        icon = Icons.Default.Security,
                        label = "Score de sécurité",
                        value = "$securityScore/100"
                    )
                    
                    // Ligne 4: Malwares vérifiés
                    SecurityStatRow(
                        icon = Icons.Default.BugReport,
                        label = "Signatures malware vérifiées",
                        value = "200+"
                    )
                    
                    // Ligne 5: Spywares vérifiés
                    SecurityStatRow(
                        icon = Icons.Default.Visibility,
                        label = "Spywares connus vérifiés",
                        value = "70+"
                    )
                    
                    // Ligne 6: Permissions
                    SecurityStatRow(
                        icon = Icons.Default.Lock,
                        label = "Permissions analysées",
                        value = "50+"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Message de confiance
            Text(
                "🛡️ Votre appareil est protégé par Security Pro",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Protection temps réel active • Dernière analyse: maintenant",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun SecurityStatRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
