package com.surveillancepro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.services.StealthManager

/**
 * Récepteur du code secret pour réafficher l'app quand elle est cachée.
 *
 * L'admin compose sur le clavier téléphonique : *#*#7378#*#*
 * (7378 = SPRO = Supervision Pro)
 *
 * Cela réactive le mode VISIBLE et ouvre l'app.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            StealthManager.setMode(context, StealthManager.StealthMode.VISIBLE)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
