package com.pitichicken.instaguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Watches Instagram from the outside and blocks everything except:
 *  - private messages (Messages tab + conversations)
 *  - saved posts/reels (reached through the Profile tab)
 *
 * Strategy:
 *  1. Find the selected tab of Instagram's bottom bar.
 *  2. Home / Search / Reels tab -> BLOCKED (overlay covers everything above the tab bar, so the user can still tap the Messages or Profile tab).
 *  3. Messages / Profile tabs -> ALLOWED.
 *  4. No tab bar visible (full-screen conversation, full-screen reel opened from a DM or from Saved) -> keep the previous decision ("sticky" zone).
 */
class InstaGuardService : AccessibilityService() {

    private enum class Tab { HOME, SEARCH, REELS, MESSAGES, PROFILE }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var overlay: TextView? = null
    private var isBlocked = false
    private var lastBackActionAt = 0L

    private val evaluateRunnable = Runnable { evaluate() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != INSTAGRAM_PACKAGE) {
            val ignored=pkg == "com.android.systemui" || pkg == packageName
            if (!ignored && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handler.removeCallbacks(evaluateRunnable)
                isBlocked=false
                hideOverlay()
            }
            return
        }

        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    //Detection

    private fun evaluate() {
        val root=rootInActiveWindow ?: return
        if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return

        val bounds=Rect()
        val tab=findSelectedTab(root, bounds)

        if (tab!=null)
            isBlocked=tab == Tab.HOME || tab == Tab.SEARCH || tab == Tab.REELS

        // if tab null => no tab bar on screen so keep previous decision

        when {
            !isBlocked -> hideOverlay()
            tab!=null -> showOverlay(bounds.top - dpToPx(TAB_BAR_MARGIN_DP)) // leave the tab bar tappable // leave the tab bar tappable
            else -> {
                hideOverlay()
                val now=System.currentTimeMillis()
                if (now-lastBackActionAt > BACK_COOLDOWN_MS) {
                    lastBackActionAt=now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    private fun findSelectedTab(root: AccessibilityNodeInfo, outBounds: Rect): Tab? {
        val queue=ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node=queue.removeFirst()
            if (node.isSelected) {
                val id=node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                val desc=node.contentDescription?.toString().orEmpty()
                val tab=classifyTab(id, desc)
                if (tab!=null) {
                    Log.d(TAG, "Selected tab=$tab id=$id desc=$desc")
                    node.getBoundsInScreen(outBounds)
                    return tab
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Maps a view id / content description to a tab. Adjust after checking logcat. */
    private fun classifyTab(id: String, desc: String): Tab? {
        when (id) {
            "feed_tab" -> return Tab.HOME
            "search_tab" -> return Tab.SEARCH
            "clips_tab" -> return Tab.REELS
            "direct_tab" -> return Tab.MESSAGES
            "profile_tab" -> return Tab.PROFILE
        }
        if (!id.endsWith("_tab")) return null
        val d= desc.lowercase()
        return when {
            d.startsWith("home")||d.startsWith("accueil") -> Tab.HOME
            d.startsWith("search")||d.startsWith("recherche") -> Tab.SEARCH
            d.startsWith("reels")->Tab.REELS
            d.startsWith("message")->Tab.MESSAGES
            d.startsWith("profile")||d.startsWith("profil") -> Tab.PROFILE
            else -> null
        }
    }

    // Overlay
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun showOverlay(heightPx: Int) {
        if (heightPx <= 0) return
        val params=WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply { gravity=Gravity.TOP }

        val existing=overlay
        if (existing!=null) {
            windowManager.updateViewLayout(existing, params)
            return
        }

        val view=TextView(this).apply {
            text="Instagram bloqué\n\nUtilise l'onglet Messages\nou Profil/Enregistrés"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#121212"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        windowManager.addView(view, params)
        overlay = view
    }

    private fun hideOverlay() {
        overlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay already removed", e)
            }
        }
        overlay=null
    }

    private companion object {
        const val TAG="InstaGuard"
        const val INSTAGRAM_PACKAGE="com.instagram.android"
        const val DEBOUNCE_MS=120L
        const val BACK_COOLDOWN_MS=600L
        const val TAB_BAR_MARGIN_DP=30
    }
}
