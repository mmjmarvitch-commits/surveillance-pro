package com.surveillancepro.android

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Application class pour initialiser Firebase et autres services globaux.
 */
class SupervisionProApp : Application() {
    
    companion object {
        private const val TAG = "SupervisionProApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialiser Firebase
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "Firebase initialisé avec succès")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur initialisation Firebase: ${e.message}")
        }
    }
}
