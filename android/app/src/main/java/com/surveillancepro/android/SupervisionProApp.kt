package com.surveillancepro.android

import android.app.Application
import android.util.Log

/**
 * Application class pour initialiser les services globaux.
 * Firebase est désactivé temporairement.
 */
class SupervisionProApp : Application() {
    
    companion object {
        private const val TAG = "SupervisionProApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application démarrée")
    }
}
