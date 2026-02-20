package com.surveillancepro.android.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Service de compression d'images.
 * 
 * Reduit la taille des photos avant envoi au serveur pour :
 * - Economiser la bande passante (important sur reseau mobile)
 * - Reduire le stockage serveur
 * - Accelerer les uploads
 * 
 * Parametres configurables :
 * - Qualite JPEG (0-100)
 * - Largeur maximale (redimensionnement proportionnel)
 * - Taille maximale en octets
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"

    data class CompressionResult(
        val success: Boolean,
        val originalSize: Long,
        val compressedSize: Int,
        val width: Int,
        val height: Int,
        val base64: String?,
        val compressionRatio: Float,
    )

    data class CompressionConfig(
        val quality: Int = 70,           // Qualite JPEG (0-100)
        val maxWidth: Int = 1280,        // Largeur max en pixels
        val maxHeight: Int = 1280,       // Hauteur max en pixels
        val maxSizeBytes: Int = 500_000, // Taille max en octets (500 Ko)
        val preserveExif: Boolean = false,
    )

    /**
     * Compresse une image depuis un fichier.
     */
    fun compressFile(filePath: String, config: CompressionConfig = CompressionConfig()): CompressionResult {
        val file = File(filePath)
        if (!file.exists()) {
            return CompressionResult(false, 0, 0, 0, 0, null, 0f)
        }

        val originalSize = file.length()

        try {
            // Lire les dimensions sans charger l'image en memoire
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // Calculer le facteur de sous-echantillonnage
            val sampleSize = calculateSampleSize(originalWidth, originalHeight, config.maxWidth, config.maxHeight)

            // Charger l'image avec sous-echantillonnage
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Moins de memoire
            }
            var bitmap = BitmapFactory.decodeFile(filePath, loadOptions) ?: return CompressionResult(false, originalSize, 0, 0, 0, null, 0f)

            // Corriger l'orientation EXIF
            bitmap = correctOrientation(filePath, bitmap)

            // Redimensionner si necessaire
            bitmap = resizeIfNeeded(bitmap, config.maxWidth, config.maxHeight)

            // Compresser en JPEG
            val result = compressBitmap(bitmap, config)
            bitmap.recycle()

            return result.copy(originalSize = originalSize)

        } catch (e: Exception) {
            Log.e(TAG, "Compression error: ${e.message}")
            return CompressionResult(false, originalSize, 0, 0, 0, null, 0f)
        }
    }

    /**
     * Compresse une image depuis des bytes.
     */
    fun compressBytes(imageBytes: ByteArray, config: CompressionConfig = CompressionConfig()): CompressionResult {
        val originalSize = imageBytes.size.toLong()

        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, config.maxWidth, config.maxHeight)

            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, loadOptions)
                ?: return CompressionResult(false, originalSize, 0, 0, 0, null, 0f)

            bitmap = resizeIfNeeded(bitmap, config.maxWidth, config.maxHeight)

            val result = compressBitmap(bitmap, config)
            bitmap.recycle()

            return result.copy(originalSize = originalSize)

        } catch (e: Exception) {
            Log.e(TAG, "Compression error: ${e.message}")
            return CompressionResult(false, originalSize, 0, 0, 0, null, 0f)
        }
    }

    /**
     * Compresse un Bitmap et retourne le resultat en Base64.
     */
    private fun compressBitmap(bitmap: Bitmap, config: CompressionConfig): CompressionResult {
        var quality = config.quality
        var outputBytes: ByteArray

        // Compression iterative pour atteindre la taille cible
        do {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputBytes = outputStream.toByteArray()

            if (outputBytes.size <= config.maxSizeBytes || quality <= 20) {
                break
            }

            quality -= 10
        } while (quality > 10)

        val base64 = Base64.encodeToString(outputBytes, Base64.NO_WRAP)
        val compressionRatio = if (outputBytes.isNotEmpty()) {
            (bitmap.byteCount.toFloat() / outputBytes.size)
        } else 0f

        return CompressionResult(
            success = true,
            originalSize = 0, // Sera rempli par l'appelant
            compressedSize = outputBytes.size,
            width = bitmap.width,
            height = bitmap.height,
            base64 = base64,
            compressionRatio = compressionRatio,
        )
    }

    /**
     * Calcule le facteur de sous-echantillonnage optimal.
     */
    private fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sampleSize = 1
        if (width > maxWidth || height > maxHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / sampleSize) >= maxWidth && (halfHeight / sampleSize) >= maxHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Redimensionne le bitmap si necessaire.
     */
    private fun resizeIfNeeded(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Corrige l'orientation de l'image selon les donnees EXIF.
     */
    private fun correctOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF correction failed: ${e.message}")
            bitmap
        }
    }

    /**
     * Presets de compression.
     */
    val PRESET_HIGH_QUALITY = CompressionConfig(
        quality = 85,
        maxWidth = 1920,
        maxHeight = 1920,
        maxSizeBytes = 1_000_000, // 1 Mo
    )

    val PRESET_BALANCED = CompressionConfig(
        quality = 70,
        maxWidth = 1280,
        maxHeight = 1280,
        maxSizeBytes = 500_000, // 500 Ko
    )

    val PRESET_LOW_BANDWIDTH = CompressionConfig(
        quality = 50,
        maxWidth = 800,
        maxHeight = 800,
        maxSizeBytes = 200_000, // 200 Ko
    )

    val PRESET_THUMBNAIL = CompressionConfig(
        quality = 60,
        maxWidth = 320,
        maxHeight = 320,
        maxSizeBytes = 50_000, // 50 Ko
    )
}
