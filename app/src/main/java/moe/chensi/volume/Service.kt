package moe.chensi.volume

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.chensi.volume.compose.AppVolumeList
import moe.chensi.volume.compose.CompactVolumePanel
import moe.chensi.volume.compose.SystemVolumePanel
import moe.chensi.volume.system.ActivityTaskManagerProxy
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import java.util.Objects

@SuppressLint("AccessibilityPolicy")
class Service : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_VIEW = "moe.chensi.volume.ACTION_SHOW_VIEW"

        private const val TAG = "VolumeManager.Service"

        private const val ANIMATION_DURATION = 300L

        private const val IDLE_TIMEOUT = 5000L

        /**
         * The expanded panel is something you read and scroll through, so it shouldn't be taken
         * away as quickly as a slider that just flashes past.
         */
        private const val EXPANDED_IDLE_TIMEOUT = 15000L
        private const val AUTO_REPEAT_DELAY = 100L
        private const val AUTO_REPEAT_INITIAL_DELAY = 500L

        /**
         * Delay between a volume key press and the popup appearing, so that the screenshot chord
         * (power + volume down) captures the screen without the popup on it. Raise it if the popup
         * still sneaks into screenshots, lower it if the popup feels sluggish.
         */
        private const val SHOW_VIEW_DELAY = 500L

        private const val SIDE_MARGIN_DP = 12

        /** Corner radius of the popup, in dp so it doesn't shrink on denser screens. */
        private const val CORNER_RADIUS_DP = 16
    }

    private val windowManager: WindowManager by lazy {
        Objects.requireNonNull(
            getSystemService(
                WindowManager::class.java
            )!!
        )
    }
    private lateinit var manager: Manager

    private val handler = object : Handler(Looper.getMainLooper()) {
        fun hideView() {
            removeCallbacks(showViewRunnable)

            if (viewVisible) {
                Log.i(TAG, "animate out")
                animateAlpha(layoutParams.alpha, 0f, ANIMATION_DURATION) {
                    if (!viewVisible) {
                        removeViewNow()
                    }
                }
                viewVisible = false
            }
        }

        private val hideViewRunnable = Runnable(::hideView)

        private val showViewRunnable = Runnable { this@Service.showView() }

        /**
         * Show the popup, but only after [SHOW_VIEW_DELAY] when it isn't on screen yet. Once it is
         * visible there is nothing left to keep out of a screenshot, so it stays instant.
         */
        fun showViewDelayed() {
            if (view != null) {
                this@Service.showView()
                return
            }

            if (!hasCallbacks(showViewRunnable)) {
                postDelayed(showViewRunnable, SHOW_VIEW_DELAY)
            }
        }

        fun startIdleTimer() {
            removeCallbacks(hideViewRunnable)
            postDelayed(
                hideViewRunnable, if (expanded) EXPANDED_IDLE_TIMEOUT else IDLE_TIMEOUT
            )
        }

        private var repeatAdjustVolumeDirection = 0
        private val repeatAdjustVolumeRunnable: Runnable = Runnable {
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_DELAY)
        }

        private fun adjustVolume() {
            manager.audioManager.adjustSuggestedStreamVolume(
                repeatAdjustVolumeDirection, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
            )
            startIdleTimer()
        }

        fun startRepeatAdjustVolume(direction: Int) {
            repeatAdjustVolumeDirection = direction
            // The first press only brings the popup up, it doesn't change the volume
            if (view != null) {
                adjustVolume()
            }
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_INITIAL_DELAY)
        }

        fun stopRepeatAdjustVolume() {
            removeCallbacks(repeatAdjustVolumeRunnable)
            startIdleTimer()
        }
    }

    private var lifecycle: LifecycleRegistry? = null

    /** Whether the popup shows the full per-app panel instead of the compact slider. */
    private var expanded by mutableStateOf(false)

    private fun createView(): View {
        return object : AbstractComposeView(this) {
            init {
                val owner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)

                    private val savedStateRegistryController =
                        SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.currentState = Lifecycle.State.STARTED
                        this@Service.lifecycle = lifecycleRegistry
                    }

                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry

                    override val savedStateRegistry: SavedStateRegistry
                        get() = savedStateRegistryController.savedStateRegistry
                }

                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }

            /** Blur whatever is behind the popup, in both compact and expanded modes. */
            fun applyBackgroundBlur() {
                if (background != null) {
                    return
                }

                @Suppress("SpellCheckingInspection") if (windowManager.isCrossWindowBlurEnabled && isHardwareAccelerated && Build.MANUFACTURER != "realme") {
                    background =
                        Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                            call("setBlurRadius", 200)
                            call("setCornerRadius", CORNER_RADIUS_DP * resources.displayMetrics.density)
                        }.get()
                }
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()

                Log.i(TAG, "onAttachedToWindow manufacturer: ${Build.MANUFACTURER}")

                applyBackgroundBlur()

                this@Service.handler.startIdleTimer()
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                Log.i(TAG, "onTouchEvent ${event.actionMasked}")

                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    this@Service.handler.hideView()
                    return true
                }

                return super.onTouchEvent(event)
            }

            @Composable
            override fun Content() {
                return VolumeManagerTheme {
                    Surface(
                        color = Color(1f, 1f, 1f, 0.3f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(CORNER_RADIUS_DP.dp)
                    ) {
                        if (expanded) {
                            Column(
                                modifier = Modifier.padding(20.dp, 16.dp)
                            ) {
                                AppVolumeList(
                                    apps = manager.apps,
                                    showAll = false,
                                    onChange = this@Service.handler::startIdleTimer
                                ) {
                                    item("system_volume_panel") {
                                        SystemVolumePanel(
                                            audioManager = manager.audioManager,
                                            notificationManagerProxy = manager.notificationManagerProxy,
                                            showCallVolumeAlways = false,
                                            applyVisibilityFilter = true,
                                            allowVisibilityConfig = false,
                                            isSliderVisible = manager::isSystemSliderVisible,
                                            onSliderVisibilityChange = manager::setSystemSliderVisible,
                                            onChange = this@Service.handler::startIdleTimer
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                CompactVolumePanel(
                                    audioManager = manager.audioManager,
                                    onChange = this@Service.handler::startIdleTimer,
                                    onExpand = {
                                        this@Service.expanded = true
                                        this@Service.handler.startIdleTimer()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT // Make the background translucent
        ).apply {
            // Hug the left edge, vertically centred, like the stock volume panel
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (SIDE_MARGIN_DP * resources.displayMetrics.density).toInt()
        }
    }

    private var view: View? = null
    private var viewVisible = false

    private fun removeViewNow() {
        val current = view ?: return

        Log.i(TAG, "remove view")
        current.background = null
        lifecycle?.currentState = Lifecycle.State.DESTROYED
        windowManager.removeView(current)
        view = null
        viewVisible = false
    }

    private fun showView() {
        if (view == null) {
            Log.i(TAG, "add view")
            expanded = false
            // The view doesn't respond to input events if reused
            val newView = createView()
            layoutParams.alpha = 0f

            try {
                windowManager.addView(newView, layoutParams)
            } catch (e: WindowManager.BadTokenException) {
                // The service has no usable window token while it is between connections, which
                // happens for a moment after the app is reinstalled. Dropping this popup is a lot
                // better than taking the process down with it.
                Log.w(TAG, "can't add the popup window right now", e)
                lifecycle?.currentState = Lifecycle.State.DESTROYED
                lifecycle = null
                return
            }

            view = newView
        }

        if (!viewVisible) {
            Log.i(TAG, "animate in")
            animateAlpha(layoutParams.alpha, 1f, ANIMATION_DURATION)
            viewVisible = true
        }

        handler.startIdleTimer()
    }

    private var currentAnimator: ValueAnimator? = null

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            if (view != null) {
                layoutParams.alpha = animation.animatedValue as Float
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.alpha = to
                windowManager.updateViewLayout(view, layoutParams)

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "onReceive ${intent.action}")
            if (intent.action == ACTION_SHOW_VIEW) {
                showView()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        val application = super.getApplication() as MyApplication
        manager = application.manager

        accessibilityButtonController.registerAccessibilityButtonCallback(object :
            AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                if (manager.shizukuStatus == Manager.ShizukuStatus.Connected) {
                    showView()
                }
            }
        })

        registerReceiver(broadcastReceiver, IntentFilter(ACTION_SHOW_VIEW), RECEIVER_NOT_EXPORTED)

        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.i(TAG, "onDestroy")

        // The service is also stopped when it is simply disabled or the app is reinstalled, so
        // tear the popup down instead of leaving an orphaned window behind
        currentAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        removeViewNow()

        unregisterReceiver(broadcastReceiver)
    }

    val activityTaskManager by lazy { ActivityTaskManagerProxy(this) }

    private var volumeButtonsDisabled = false

    private fun isVolumeButtonsDisabledForForegroundApp(): Boolean {
        val task = activityTaskManager.getForegroundTask()
        Log.i(TAG, "foreground task: $task")

        val app = manager.apps[task?.app ?: return false] ?: return false
        return app.disableVolumeButtons
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Key events can arrive before `onServiceConnected` has assigned `manager`
        if (!::manager.isInitialized) {
            return false
        }

        Log.i(
            TAG,
            "onKeyEvent action = ${event.action}, key code = ${event.keyCode}, shizuku permission = ${manager.shizukuStatus}"
        )

        // Only handle `VOLUME_UP` and `VOLUME_DOWN`
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // Ignore if Shizuku is not ready
        if (manager.shizukuStatus != Manager.ShizukuStatus.Connected) {
            return false
        }

        // Checking the foreground app is a binder call, so ask once per press and reuse the answer
        // for the auto repeats and the release. That also keeps the decision consistent across the
        // whole press instead of possibly consuming the release of a key we let through.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            volumeButtonsDisabled = isVolumeButtonsDisabledForForegroundApp()
        }

        if (volumeButtonsDisabled) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {

                handler.startRepeatAdjustVolume(
                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    }
                )
                handler.showViewDelayed()
            }

            KeyEvent.ACTION_UP -> handler.stopRepeatAdjustVolume()
        }

        return true
    }
}
