package com.example.gaze_nav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class GazeAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "GazeNavService"
        const val ACTION_UPDATE_CURSOR = "com.gaze_nav.UPDATE_CURSOR"
        const val ACTION_DOUBLE_BLINK = "com.gaze_nav.DOUBLE_BLINK"
        const val ACTION_OVERLAY_START = "com.gaze_nav.OVERLAY_START"
        const val ACTION_OVERLAY_STOP = "com.gaze_nav.OVERLAY_STOP"
        const val EXTRA_CURSOR_X = "cursor_x"
        const val EXTRA_CURSOR_Y = "cursor_y"

        var instance: GazeAccessibilityService? = null
            private set
        fun isRunning(): Boolean = instance != null
    }

    private lateinit var windowManager: WindowManager
    private var overlayManager: GazeOverlayManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 0
    private var screenHeight = 0
    private var overlayActive = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_CURSOR -> {
                    val x = intent.getFloatExtra(EXTRA_CURSOR_X, 0f)
                    val y = intent.getFloatExtra(EXTRA_CURSOR_Y, 0f)
                    overlayManager?.updateCursorPosition(x, y)
                }
                ACTION_DOUBLE_BLINK -> overlayManager?.onDoubleBlink()
                ACTION_OVERLAY_START -> startOverlay()
                ACTION_OVERLAY_STOP -> stopOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        Log.d(TAG, "Service created. Screen: ${screenWidth}x${screenHeight}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_CURSOR)
            addAction(ACTION_DOUBLE_BLINK)
            addAction(ACTION_OVERLAY_START)
            addAction(ACTION_OVERLAY_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    private fun startOverlay() {
        if (overlayActive) return
        overlayActive = true
        handler.post {
            overlayManager = GazeOverlayManager(
                service = this,
                windowManager = windowManager,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onGestureRequest = { gestureType -> performGazeGesture(gestureType) }
            )
            overlayManager?.showOverlay()
        }
    }

    private fun stopOverlay() {
        overlayActive = false
        handler.post {
            overlayManager?.hideOverlay()
            overlayManager = null
        }
    }

    fun performGazeGesture(type: GestureType) {
        Log.d(TAG, "Gesture: $type")

        when (type) {

            // ── System actions ──────────────────────────────────────────────
            GestureType.GO_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.HOME_SCREEN) }, 400)
                return
            }
            GestureType.GO_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            GestureType.GO_RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                // Switch to IN_APP so user can dwell-tap a recent app card
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.IN_APP) }, 600)
                return
            }

            // ── Toolbar mode-switches ───────────────────────────────────────
            GestureType.TB_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.HOME_SCREEN) }, 400)
                return
            }
            GestureType.TB_IN_APP -> {
                overlayManager?.setMode(NavigationMode.IN_APP)
                return
            }
            GestureType.TB_NOTIF -> {
                dispatchSwipe(screenPath(screenWidth/2f, 10f, screenWidth/2f, screenHeight*2f/3f), 300L)
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.QUICK_SETTINGS) }, 500)
                return
            }

            // ── Tap at cursor position ──────────────────────────────────────
            // Works in ALL modes: home screen icons, app drawer apps, in-app buttons
            GestureType.TAP -> {
                val pos = overlayManager?.getCursorPosition() ?: return
                val path = Path().apply { moveTo(pos.first, pos.second); lineTo(pos.first + 1f, pos.second + 1f) }
                dispatchSwipe(path, 50L)
                return
            }

            // ── Long-press at cursor position ───────────────────────────────
            // Opens context menus, selects text, triggers drag
            GestureType.LONG_PRESS -> {
                val pos = overlayManager?.getCursorPosition() ?: return
                val path = Path().apply { moveTo(pos.first, pos.second); lineTo(pos.first + 1f, pos.second + 1f) }
                dispatchSwipe(path, 1000L)
                return
            }

            // ── Swipes ──────────────────────────────────────────────────────
            GestureType.SWIPE_UP -> {
                dispatchSwipe(screenPath(screenWidth/2f, screenHeight-50f, screenWidth/2f, screenHeight/3f), 300L)
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.APPS_DRAWER) }, 500)
                return
            }
            GestureType.SWIPE_DOWN -> {
                dispatchSwipe(screenPath(screenWidth/2f, 10f, screenWidth/2f, screenHeight*2f/3f), 300L)
                handler.postDelayed({ overlayManager?.setMode(NavigationMode.QUICK_SETTINGS) }, 500)
                return
            }
            GestureType.SWIPE_DOWN_SHORT -> {
                dispatchSwipe(screenPath(screenWidth/2f, screenHeight/4f, screenWidth/2f, screenHeight/2f), 250L)
                return
            }
            GestureType.SWIPE_UP_SHORT -> {
                dispatchSwipe(screenPath(screenWidth/2f, screenHeight/2f, screenWidth/2f, screenHeight/4f), 250L)
                return
            }
            GestureType.SWIPE_LEFT -> {
                dispatchSwipe(screenPath(screenWidth-50f, screenHeight/2f, 50f, screenHeight/2f), 300L)
                return
            }
            GestureType.SWIPE_RIGHT -> {
                dispatchSwipe(screenPath(50f, screenHeight/2f, screenWidth-50f, screenHeight/2f), 300L)
                return
            }

            // ── In-app scroll ─────────────────────────────────────────────
            // Always swipe at screen CENTER X — the cursor sits on the overlay
            // button at the far edge, so using cursor X would start the swipe
            // outside the scrollable content area and nothing would happen.
            GestureType.SCROLL_UP -> {
                val cx = screenWidth / 2f
                dispatchSwipe(screenPath(cx, screenHeight * 0.70f, cx, screenHeight * 0.25f), 400L)
                return
            }
            GestureType.SCROLL_DOWN -> {
                val cx = screenWidth / 2f
                dispatchSwipe(screenPath(cx, screenHeight * 0.25f, cx, screenHeight * 0.70f), 400L)
                return
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun screenPath(x1: Float, y1: Float, x2: Float, y2: Float): Path =
        Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    private fun dispatchSwipe(path: Path, duration: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture cancelled")
            }
        }, null)
    }
}

enum class GestureType {
    // Swipes (home screen navigation + in-app)
    SWIPE_UP, SWIPE_DOWN,
    SWIPE_DOWN_SHORT, SWIPE_UP_SHORT,
    SWIPE_LEFT, SWIPE_RIGHT,

    // System nav
    GO_HOME, GO_BACK, GO_RECENTS,

    // In-app scroll (repeating)
    SCROLL_UP, SCROLL_DOWN,

    // Tap / long-press at cursor position
    TAP, LONG_PRESS,

    // Toolbar mode-switch buttons
    TB_HOME, TB_IN_APP, TB_NOTIF
}

enum class NavigationMode {
    HOME_SCREEN,      // launcher d-pad
    APPS_DRAWER,      // app-drawer scroll + tap to open
    QUICK_SETTINGS,   // notification shade
    IN_APP            // free-cursor control inside any open app
}