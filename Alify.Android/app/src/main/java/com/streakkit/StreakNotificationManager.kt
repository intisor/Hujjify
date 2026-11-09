package com.streakkit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * BroadcastReceiver for handling scheduled streak notifications (Nudge, Urgent, Panic, Lost)
 */
class StreakNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Your Streak is in Danger!"
        val body = intent.getStringExtra("body") ?: "Open the app now to keep your daily streak alive."
        val ruleSlug = intent.getStringExtra("ruleSlug") ?: "streak"
        val urgency = intent.getStringExtra("urgency") ?: "NUDGE"

        StreakNotificationManager.showNotification(context, title, body, ruleSlug, urgency)
    }
}

/**
 * StreakNotificationManager: Escalating, Duolingo-Grade Push Notification Engine
 */
object StreakNotificationManager {
    const val CHANNEL_ID = "streakkit_urgency_alerts"
    const val CHANNEL_NAME = "Streak Protection Alerts"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "High-priority alerts when your daily streak is about to expire"
                enableLights(true)
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        ruleSlug: String,
        urgency: String,
        targetTimestampMs: Long = 0L
    ) {
        createNotificationChannel(context)

        // Launch the host application when user taps the notification
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_STREAKKIT_EMBED", true)
            putExtra("RULE_SLUG", ruleSlug)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            ruleSlug.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val smallIcon = context.applicationInfo.icon
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(0xFFEF4444.toInt()) // Red accent color for urgency / Duolingo style
            .setColorized(urgency == "PANIC_30M" || urgency == "URGENT_2H")

        // Duolingo Live Countdown Chronometer Feature
        if (targetTimestampMs > System.currentTimeMillis()) {
            builder.setWhen(targetTimestampMs)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
        }

        val notification = builder.build()

        try {
            NotificationManagerCompat.from(context).notify(ruleSlug.hashCode(), notification)
        } catch (_: SecurityException) {
            // Android 13+ permission not granted
        }
    }

    /**
     * Behavioral Contrast / Counter-Habit Nudge
     * e.g. "You listened to music for 45 mins, but did not protect your deen?"
     */
    fun showContrastNotification(
        context: Context,
        title: String,
        body: String,
        neglectedRuleSlug: String,
        triggerAction: String = ""
    ) {
        showNotification(
            context = context,
            title = title,
            body = body,
            ruleSlug = neglectedRuleSlug,
            urgency = "BEHAVIORAL_CONTRAST"
        )
    }

    /**
     * Evaluates local streak state and immediately alerts if urgent (< 2 hours or < 30 mins)
     */
    fun checkAndAlertIfUrgent(
        context: Context,
        ruleName: String,
        ruleSlug: String,
        streak: Int,
        secondsLeft: Long,
        completedToday: Boolean,
        freezes: Int,
        tone: String = "UNHINGED"
    ) {
        if (completedToday || secondsLeft <= 0) return

        val hoursLeft = secondsLeft / 3600.0

        val targetExpiryMs = System.currentTimeMillis() + (secondsLeft * 1000L)

        if (secondsLeft <= 1800) { // 30 minutes left
            val (t, b) = when (tone) {
                "CONSCIOUS" -> "Final 30 Minutes" to "30 minutes remain to preserve your $streak-day commitment to $ruleName."
                else -> "30 MINUTES LEFT. CODE RED." to "Say goodbye to your $streak days in 30 minutes unless you open the app RIGHT NOW."
            }
            showNotification(context, t, b, ruleSlug, "PANIC_30M", targetExpiryMs)
        } else if (hoursLeft <= 2.0) { // 2 hours left
            val (t, b) = when (tone) {
                "CONSCIOUS" -> "2 Hours Left Today" to "Protect your $streak-day conscious habit before midnight."
                else -> "CARDIAC ARREST: $streak Days in Danger!" to "Your streak flatlines in under 2 hours. Don't throw away $streak days of dedication!"
            }
            showNotification(context, t, b, ruleSlug, "URGENT_2H", targetExpiryMs)
        }
    }
}
