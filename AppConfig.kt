package com.sidilahcen.watererp

/**
 * Deployment constants for the Google Apps Script WATER ERP wrapper.
 * Change [TARGET_URL] if the Apps Script web app is redeployed.
 */
object AppConfig {
    const val TARGET_URL =
        "https://script.google.com/macros/s/AKfycbwbCbsoPcFVBPfWjVgQbaGmDqmCrmqq_8lMrN4P7ANlG6eVUNN-eMEas1NQQMN0F_8p/exec"

    /**
     * Mobile Chrome UA so the ERP serves its mobile CSS (sidebar drawer,
     * stacked tables, full-screen login). Do not send a desktop UA.
     */
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36 WATER-ERP-Android/1.0"

    val INTERNAL_HOST_SUFFIXES = arrayOf(
        "script.google.com",
        "googleusercontent.com",
        "accounts.google.com",
        "accounts.google.fr",
        "google.com",
        "gstatic.com",
        "googleapis.com",
        "googleusercontent.com",
    )

    val EXTERNAL_HOST_HINTS = arrayOf(
        "wa.me",
        "whatsapp.com",
        "api.whatsapp.com",
    )
}
