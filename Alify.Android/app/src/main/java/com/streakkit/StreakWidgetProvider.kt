package com.streakkit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * StreakWidgetProvider: Drop-In Android Home Screen Widget for StreakKit
 * - Shows live streak status (Active flame, Pending hourglass, or Frozen shield)
 * - Tapping the widget directly launches the host app into the animated Streak modal
 */
class StreakWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, StreakWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val state = StreakKit.getState(context) ?: JSONObject()
            val currentStreak = state.optInt("currentStreak", 0)
            val status = state.optString("status", "PENDING_TODAY")
            val prog = state.optJSONObject("todayProgress")
            val completed = prog?.optBoolean("completed", false) ?: false
            val cur = prog?.optInt("current", 0) ?: 0
            val target = prog?.optInt("target", 1) ?: 1
            val freezes = state.optJSONObject("freezes")?.optInt("available", 2) ?: 2

            // Use the layout resource ID from host app
            val layoutId = context.resources.getIdentifier("streak_widget_layout", "layout", context.packageName)
            if (layoutId == 0) return

            val views = RemoteViews(context.packageName, layoutId)

            val flameId = context.resources.getIdentifier("widget_flame_icon", "id", context.packageName)
            val countId = context.resources.getIdentifier("widget_streak_count", "id", context.packageName)
            val statusId = context.resources.getIdentifier("widget_streak_status", "id", context.packageName)
            val shieldId = context.resources.getIdentifier("widget_shield_icon", "id", context.packageName)

            if (flameId != 0) {
                val icon = when {
                    status == "FROZEN" -> "❄️"
                    completed -> "🔥"
                    status == "AT_RISK" -> "⚠️"
                    else -> "⌛"
                }
                views.setTextViewText(flameId, icon)
            }

            if (countId != 0) {
                views.setTextViewText(countId, "$currentStreak Day Streak")
                views.setTextColor(countId, if (completed) Color.parseColor("#F59E0B") else Color.parseColor("#94A3B8"))
            }

            if (statusId != 0) {
                val statusText = when {
                    completed -> "Active Today ($cur/$target)"
                    status == "FROZEN" -> "Protected by Freeze Shield"
                    status == "AT_RISK" -> "Expiring Soon! Tap to save"
                    else -> "Pending Today ($cur/$target)"
                }
                views.setTextViewText(statusId, statusText)
            }

            if (shieldId != 0) {
                views.setTextViewText(shieldId, "🛡️ $freezes")
            }

            // Click Intent to open host app into StreakKit modal
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_STREAKKIT_EMBED", true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val containerId = context.resources.getIdentifier("widget_container", "id", context.packageName)
            if (containerId != 0) {
                views.setOnClickPendingIntent(containerId, pendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
