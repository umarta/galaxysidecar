package dev.galaxysidecar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.view.ViewConfiguration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Matrix
import android.view.Gravity
import android.widget.ScrollView
import android.widget.ProgressBar
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.Surface
import android.view.TextureView
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var home: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var controlPanel: ScrollView
    private lateinit var controlHandle: Button
    private lateinit var address: EditText
    private lateinit var connectButton: Button
    private lateinit var remoteButton: Button
    private lateinit var extendButton: Button
    private lateinit var usbButton: Button
    private lateinit var pairingSummary: TextView
    private lateinit var keyboardButton: Button
    private lateinit var inputOptions: Button
    private lateinit var disconnectButton: Button
    private lateinit var cancelButton: Button
    private lateinit var retryUnlockButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var sessionTitle: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var image: ImageView
    private lateinit var video: TextureView
    private var videoWidth = 0
    private var videoHeight = 0
    private var session: Session? = null
    private var usbPrompt: AlertDialog? = null
    private var activePointer = -1
    private var reconnectTarget: ConnectionTarget? = null
    private var retryCount = 0
    private var foreground = false
    private var waitingForUnlock = false
    private var trackpadMode = false
    private val retry = Runnable {
        val target = reconnectTarget
        if (foreground && session == null && target != null) startSession(target, reconnecting = true)
    }
    private val trackpad by lazy {
        Trackpad(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), resources.displayMetrics.density) { kind, x, y ->
            val packet = ByteBuffer.allocate(MESSAGE_BYTES).put(kind.toByte())
                .putFloat(x).putFloat(y).putFloat(0f).array()
            session?.send(packet, essential = kind >= 12)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = TextureView(this).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Mac display"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, event ->
                if (controlPanel.visibility == View.VISIBLE) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) hideControls()
                    true
                } else handlePointer(event)
            }
            setOnHoverListener { _, event ->
                controlPanel.visibility != View.VISIBLE && handleHover(event)
            }
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        home = buildHome()
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        controlPanel = buildControls()
        root.addView(controlPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controlHandle = action("Controls", primary = false).apply {
            visibility = View.GONE
            setOnClickListener { showControls() }
        }
        root.addView(controlHandle, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                home.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                controlPanel.setPadding(safe.left + dp(16), 0, safe.right + dp(16), safe.bottom + dp(12))
                controlHandle.layoutParams = (controlHandle.layoutParams as FrameLayout.LayoutParams).apply {
                    marginEnd = safe.right + dp(16)
                    bottomMargin = safe.bottom + dp(8)
                }
                insets
            }
        }
        setContentView(root)
        home.requestFocus()
        trackpadMode = getPreferences(MODE_PRIVATE).getBoolean("trackpad", false)
        savedInstanceState?.getString("reconnectHost")?.let { host ->
            val remote = if (savedInstanceState.getBoolean("reconnectRemote")) remoteProfile() else null
            if (remote != null || !savedInstanceState.getBoolean("reconnectRemote")) {
                reconnectTarget = ConnectionTarget(host, remote, savedInstanceState.getBoolean("reconnectExtended"))
                waitingForUnlock = savedInstanceState.getBoolean("waitingForUnlock")
            }
        }
        handleUsbIntent(intent)
    }

    private fun buildHome(): ScrollView {
        val wide = resources.configuration.screenWidthDp >= 840 &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val maxWidth = if (wide) 960 else 560
        homeContent = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.galaxysidecar_mark)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(label("GalaxySidecar", 23, TEXT, bold = true), LinearLayout.LayoutParams(-2, -2))
        }
        homeContent.addView(brand, item())

        val remote = card().apply {
            background = rounded(REMOTE_SURFACE, 22, REMOTE_OUTLINE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        remote.addView(label("Remote desktop", 14, ACCENT, bold = true), item())
        remote.addView(label("Mac mini", 34, TEXT, bold = true), item(8))
        pairingSummary = label("", 14, MUTED)
        remote.addView(pairingSummary, item(6))
        if (wide) remote.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        remoteButton = action("Connect remotely", primary = true).apply {
            setOnClickListener {
                val profile = remoteProfile()
                if (profile != null && session == null) connect(profile.host, remember = false, remote = profile)
            }
        }
        remote.addView(remoteButton, item(24))
        extendButton = action("Extend Mac display", primary = false).apply {
            setOnClickListener {
                val profile = remoteProfile()
                if (profile != null && session == null) {
                    connect(profile.host, remember = false, remote = profile, extend = true)
                }
            }
        }
        remote.addView(extendButton, item(8))

        val local = card()
        local.addView(label("Local connection", 21, TEXT, bold = true), item())
        local.addView(label("Mac IP address", 14, MUTED), item(12))
        address = EditText(this).apply {
            hint = "Enter Mac IPv4 address"
            contentDescription = "Mac IP address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSingleLine()
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
            background = rounded(BACKGROUND, 10, OUTLINE)
            setText(getPreferences(MODE_PRIVATE).getString("host", "")
                ?.takeUnless { it == "127.0.0.1" }.orEmpty())
            setOnEditorActionListener { _, id, _ ->
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_GO && session == null) {
                    connect()
                    true
                } else false
            }
        }
        local.addView(address, item(6))
        connectButton = action("Connect over Wi-Fi", primary = false).apply { setOnClickListener { connect() } }
        usbButton = action("Use USB cable", primary = false).apply {
            setOnClickListener { connect("127.0.0.1", remember = false) }
        }
        local.addView(connectButton, item(10))
        local.addView(View(this).apply { setBackgroundColor(OUTLINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14) })
        local.addView(usbButton, item(12))
        if (wide) {
            val cards = LinearLayout(this).apply {
                addView(remote, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginEnd = dp(12) })
                addView(local, LinearLayout.LayoutParams(0, -2, 1f))
            }
            homeContent.addView(cards, item(24))
        } else {
            homeContent.addView(remote, item(24))
            homeContent.addView(local, item(12))
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            visibility = View.GONE
        }
        status = label("", 14, MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val activityRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        }
        homeContent.addView(activityRow, item(12))
        cancelButton = action("Cancel connection", primary = false).apply {
            visibility = View.GONE
            setOnClickListener { disconnect("Disconnected") }
        }
        retryUnlockButton = action("Check again", primary = false).apply {
            visibility = View.GONE
            setOnClickListener {
                if (waitingForUnlock && session == null) {
                    home.removeCallbacks(retry)
                    retry.run()
                }
            }
        }
        homeContent.addView(retryUnlockButton, item(8))
        homeContent.addView(cancelButton, item(8))
        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            clipToPadding = false
            addView(homeContent, FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, dp(maxWidth)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(1)
                    homeContent.layoutParams = (homeContent.layoutParams as FrameLayout.LayoutParams).apply {
                        width = minOf(available, dp(maxWidth))
                    }
                }
            }
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            updateHomeActions()
        }
    }

    private fun buildControls(): ScrollView {
        controls = card()
        sessionTitle = label("", 20, TEXT, bold = true)
        sessionStatus = label("", 14, MUTED)
        controls.addView(sessionTitle, item())
        controls.addView(sessionStatus, item(4))
        keyboardButton = action("Keyboard", primary = false).apply { setOnClickListener { showKeyboard() } }
        inputOptions = action("Input tools", primary = false).apply { setOnClickListener { showInputTools() } }
        disconnectButton = action("Disconnect", primary = false).apply {
            setOnClickListener { disconnect("Disconnected") }
        }
        controls.addView(keyboardButton, item(12))
        controls.addView(inputOptions, item(8))
        controls.addView(disconnectButton, item(8))
        controls.addView(action("Resume desktop", primary = true).apply { setOnClickListener { hideControls() } }, item(8))
        return ScrollView(this).apply {
            visibility = View.GONE
            clipToPadding = false
            addView(controls, FrameLayout.LayoutParams(
                minOf((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(160)), dp(380)),
                -2, Gravity.END
            ))
        }
    }

    private fun updateHomeActions(connecting: Boolean = false) {
        val profile = remoteProfile()
        pairingSummary.text = if (profile == null) "Pair this tablet over USB to connect from anywhere."
            else "Tailscale · ${profile.host}"
        remoteButton.visibility = if (profile == null) View.GONE else View.VISIBLE
        extendButton.visibility = remoteButton.visibility
        remoteButton.isEnabled = !connecting
        extendButton.isEnabled = !connecting
        address.isEnabled = !connecting
        connectButton.isEnabled = !connecting
        usbButton.isEnabled = !connecting
        for (button in listOf(remoteButton, extendButton, connectButton, usbButton)) {
            button.alpha = if (connecting) 0.55f else 1f
        }
        cancelButton.visibility = if (connecting) View.VISIBLE else View.GONE
    }

    private fun showControls() {
        trackpad.reset()
        controlPanel.visibility = View.VISIBLE
        controlHandle.visibility = View.GONE
    }

    private fun hideControls() {
        controlPanel.visibility = View.GONE
        controlHandle.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = column().apply {
        background = rounded(SURFACE, 16, OUTLINE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun item(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun action(title: String, primary: Boolean) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(if (primary) BACKGROUND else TEXT)
        val fill = if (primary) ACCENT else SURFACE_RAISED
        background = RippleDrawable(ColorStateList.valueOf(0x337C91A0), rounded(fill, 10), null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_USB_ATTACHED, false)) return
        intent.removeExtra(EXTRA_USB_ATTACHED)
        if (usbPrompt != null || session != null) return

        usbPrompt = AlertDialog.Builder(this)
            .setTitle("Pakai Galaxy Tab sebagai monitor?")
            .setMessage("Tampilkan layar Mac lewat kabel USB.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Jadikan monitor") { _, _ -> connect("127.0.0.1", remember = false) }
            .setOnDismissListener { usbPrompt = null }
            .show()
    }

    private fun connect(host: String = address.text.toString().trim(), remember: Boolean = true,
                        remote: RemoteProfile? = null, extend: Boolean = false) {
        if (host.isEmpty()) {
            address.error = "Enter a host"
            return
        }

        if (remember) getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        val target = ConnectionTarget(host, remote, extend)
        reconnectTarget = target
        waitingForUnlock = false
        retryCount = 0
        home.removeCallbacks(retry)
        startSession(target)
    }

    private fun startSession(target: ConnectionTarget, reconnecting: Boolean = false) {
        val (host, remote, extend) = target
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
        val newSession = Session(host, remote, extend)
        session = newSession
        updateHomeActions(connecting = true)
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setTextColor(MUTED)
        retryUnlockButton.isEnabled = false
        status.text = when {
            waitingForUnlock -> LOCKED_STATUS
            reconnecting -> "Reconnecting to Mac…"
            extend -> "Creating an extended Mac display…"
            remote != null -> "Connecting through Tailscale…"
            host == "127.0.0.1" -> "Connecting over USB…"
            else -> "Connecting over Wi-Fi…"
        }
        newSession.start()
        home.post { home.smoothScrollTo(0, cancelButton.bottom) }
    }

    private fun showStream(inputEnabled: Boolean, remote: Boolean) {
        retryCount = 0
        waitingForUnlock = false
        retryUnlockButton.visibility = View.GONE
        keyboardButton.visibility = if (inputEnabled) View.VISIBLE else View.GONE
        inputOptions.visibility = if (inputEnabled) View.VISIBLE else View.GONE
        sessionTitle.text = when {
            session?.extendsDisplay == true -> "Extended Mac display"
            remote -> "Remote desktop"
            session?.isUsb == true -> "USB monitor"
            else -> "Wi-Fi monitor"
        }
        sessionStatus.text = when {
            inputEnabled -> "Connected · keyboard and pointer enabled"
            remote -> "Connected · allow Accessibility on the Mac to enable input"
            else -> "Connected · view only"
        }
        home.visibility = View.GONE
        progress.visibility = View.GONE
        hideControls()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
    }

    private fun disconnect(message: String, keepTarget: Boolean = false) {
        home.removeCallbacks(retry)
        if (!keepTarget) {
            reconnectTarget = null
            retryCount = 0
            waitingForUnlock = false
        }
        trackpad.reset()
        val previous = session
        session = null
        previous?.stop()
        activePointer = -1
        image.setImageDrawable(null)
        video.alpha = 0f
        videoWidth = 0
        videoHeight = 0
        controlPanel.visibility = View.GONE
        controlHandle.visibility = View.GONE
        home.visibility = View.VISIBLE
        home.requestFocus()
        progress.visibility = View.GONE
        retryUnlockButton.visibility = View.GONE
        updateHomeActions()
        keyboardButton.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.setTextColor(if (message == "Disconnected") MUTED else ERROR)
        status.text = message
        home.post { home.smoothScrollTo(0, (status.parent as View).bottom) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (session == null) reconnectTarget?.let { startSession(it, reconnecting = true) }
    }

    override fun onStop() {
        foreground = false
        usbPrompt?.dismiss()
        if (session != null || reconnectTarget != null) disconnect("Paused", keepTarget = true)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        reconnectTarget?.let {
            outState.putString("reconnectHost", it.host)
            outState.putBoolean("reconnectRemote", it.remote != null)
            outState.putBoolean("reconnectExtended", it.extend)
            outState.putBoolean("waitingForUnlock", waitingForUnlock)
        }
        super.onSaveInstanceState(outState)
    }

    private fun connectionLost(message: String, transient: Boolean) {
        waitingForUnlock = false
        if (!transient || reconnectTarget == null || !foreground || retryCount >= 4) {
            disconnect(message)
            return
        }
        val seconds = 1 shl retryCount++
        disconnect("Connection lost. Retrying in ${seconds}s…", keepTarget = true)
        updateHomeActions(connecting = true)
        progress.visibility = View.VISIBLE
        home.postDelayed(retry, seconds * 1000L)
    }

    private fun waitForUnlock() {
        if (!foreground || reconnectTarget == null) return
        waitingForUnlock = true
        retryCount = 0
        disconnect(LOCKED_STATUS, keepTarget = true)
        status.setTextColor(MUTED)
        updateHomeActions(connecting = true)
        retryUnlockButton.visibility = View.VISIBLE
        retryUnlockButton.isEnabled = true
        home.post { home.smoothScrollTo(0, cancelButton.bottom) }
        home.postDelayed(retry, 5_000)
    }

    @Deprecated("Uses legacy back dispatch to reveal the overlay in immersive mode")
    override fun onBackPressed() {
        when {
            session == null && reconnectTarget != null -> disconnect("Disconnected")
            session == null -> super.onBackPressed()
            home.visibility == View.VISIBLE -> disconnect("Disconnected")
            controlPanel.visibility == View.VISIBLE -> hideControls()
            else -> showControls()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val current = session
        if (current?.inputEnabled == true && controlPanel.visibility != View.VISIBLE &&
            event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD) &&
            event.keyCode !in intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE) &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        ) {
            val kind = if (event.action == KeyEvent.ACTION_DOWN) 6 else 7
            current.send(keyPacket(kind, event.keyCode, KeyEvent.normalizeMetaState(event.metaState)), essential = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showKeyboard() {
        val current = session ?: return
        val text = EditText(this).apply {
            hint = "Text to type on Mac"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Mac keyboard")
            .setView(text)
            .setPositiveButton("Send") { _, _ ->
                val codePoints = text.text.toString().codePoints().toArray()
                if (codePoints.isNotEmpty() && session === current) {
                    val packets = ByteBuffer.allocate(codePoints.size * MESSAGE_BYTES)
                    for (codePoint in codePoints) packets.put(keyPacket(5, codePoint))
                    current.send(packets.array(), essential = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        text.requestFocus()
        dialog.show()
    }

    private fun showInputTools() {
        val current = session ?: return
        val actions = mutableListOf("Mac shortcuts")
        if (current.extendedInput) {
            actions.add(if (trackpadMode) "Switch to direct touch" else "Switch to trackpad")
            actions.add("Send Tab clipboard to Mac")
            actions.add("Get Mac clipboard")
        }
        AlertDialog.Builder(this).setTitle("Input tools")
            .setItems(actions.toTypedArray()) { _, choice ->
                if (session !== current) return@setItems
                when (choice) {
                    0 -> showShortcuts(current)
                    1 -> {
                        trackpadMode = !trackpadMode
                        getPreferences(MODE_PRIVATE).edit().putBoolean("trackpad", trackpadMode).apply()
                        sessionStatus.text = if (trackpadMode) "Trackpad · two-finger tap or scroll"
                            else "Direct touch · tap to click"
                        hideControls()
                    }
                    2 -> sendClipboard(current)
                    3 -> current.send(ByteArray(MESSAGE_BYTES).apply { this[0] = 9 }, essential = true)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showShortcuts(current: Session) {
        val labels = arrayOf("⌘ Tab  ·  Switch apps", "⌘ Space  ·  Spotlight", "Esc  ·  Escape")
        AlertDialog.Builder(this).setTitle("Mac shortcuts").setItems(labels) { _, index ->
            if (session !== current) return@setItems
            val key = intArrayOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE)[index]
            val modifiers = if (index < 2) KeyEvent.META_META_ON else 0
            val packet = if (current.extendedInput) keyPacket(16, index)
                else keyPacket(6, key, modifiers) + keyPacket(7, key, modifiers)
            current.send(packet, essential = true)
            hideControls()
        }.setNegativeButton("Close", null).show()
    }

    private fun sendClipboard(current: Session) {
        val manager = getSystemService(ClipboardManager::class.java)
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val bytes = item?.text?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_CLIPBOARD_BYTES) {
            Toast.makeText(this, "Copy up to 64 KiB of text on the Tab first.", Toast.LENGTH_SHORT).show()
            return
        }
        current.send(keyPacket(8, bytes.size) + bytes, essential = true)
        Toast.makeText(this, "Sent clipboard text to Mac.", Toast.LENGTH_SHORT).show()
    }

    private data class ConnectionTarget(val host: String, val remote: RemoteProfile?, val extend: Boolean)
    private class MacLocked : IOException()

    private fun keyPacket(kind: Int, key: Int, modifiers: Int = 0): ByteArray =
        ByteBuffer.allocate(MESSAGE_BYTES).put(kind.toByte()).putInt(key).putInt(modifiers).putInt(0).array()

    private data class RemoteProfile(val host: String, val token: ByteArray)

    private fun remoteProfile(): RemoteProfile? {
        return runCatching {
            openFileInput("remote-pairing").bufferedReader(Charsets.US_ASCII).use { reader ->
                val host = reader.readLine() ?: return null
                val token = reader.readText().toByteArray(Charsets.US_ASCII)
                val parts = host.split('.').map { it.toIntOrNull() ?: return null }
                if (parts.size != 4 || parts[0] != 100 || parts[1] !in 64..127 ||
                    parts.any { it !in 0..255 } || token.size != 64) return null
                RemoteProfile(host, token)
            }
        }.getOrNull()
    }

    private fun handlePointer(event: MotionEvent): Boolean {
        val current = session ?: return false
        if (!current.inputEnabled) return false
        if (trackpadMode && current.extendedInput && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            return trackpad.handle(event, image.width, image.height)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                if (sendPointer(current, event, index, 1, allowOutside = false)) {
                    activePointer = event.getPointerId(index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) sendPointer(current, event, index, 2, allowOutside = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0 && (event.actionMasked == MotionEvent.ACTION_CANCEL || index == event.actionIndex)) {
                    sendPointer(current, event, index, 3, allowOutside = true)
                    activePointer = -1
                }
            }
        }
        return true
    }

    private fun handleHover(event: MotionEvent): Boolean {
        val current = session ?: return false
        if (!current.inputEnabled || event.actionMasked != MotionEvent.ACTION_HOVER_MOVE) return false
        return sendPointer(current, event, event.actionIndex, 4, allowOutside = false)
    }

    private fun sendPointer(current: Session, event: MotionEvent, index: Int, kind: Int, allowOutside: Boolean): Boolean {
        val frame = if (videoWidth > 0 && videoHeight > 0) {
            videoBounds()
        } else {
            val drawable = image.drawable ?: return false
            RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat()).also {
                image.imageMatrix.mapRect(it)
            }
        }
        if (frame.isEmpty || (!allowOutside && !frame.contains(event.getX(index), event.getY(index)))) return false
        val pen = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val pressure = if (kind == 3 || kind == 4) 0f else event.getPressure(index).coerceIn(0f, 1f)
        val message = ByteBuffer.allocate(MESSAGE_BYTES)
            .put((kind or if (pen) 0x80 else 0).toByte())
            .putFloat(((event.getX(index) - frame.left) / frame.width()).coerceIn(0f, 1f))
            .putFloat(((event.getY(index) - frame.top) / frame.height()).coerceIn(0f, 1f))
            .putFloat(pressure)
            .array()
        current.send(message, essential = kind == 1 || kind == 3)
        return true
    }

    private fun videoBounds(): RectF {
        if (videoWidth <= 0 || videoHeight <= 0) return RectF()
        val scale = minOf(video.width.toFloat() / videoWidth, video.height.toFloat() / videoHeight)
        val width = videoWidth * scale
        val height = videoHeight * scale
        val left = (video.width - width) / 2
        val top = (video.height - height) / 2
        return RectF(left, top, left + width, top + height)
    }

    private fun fitVideo() {
        val target = videoBounds()
        if (target.isEmpty) return
        video.setTransform(Matrix().apply {
            setRectToRect(RectF(0f, 0f, video.width.toFloat(), video.height.toFloat()),
                target, Matrix.ScaleToFit.FILL)
        })
    }

    private inner class Session(private val host: String, private val remote: RemoteProfile?, val extendsDisplay: Boolean) {
        private val socket = Socket()
        private val outgoing = ArrayBlockingQueue<ByteArray>(32)
        private var worker: Thread? = null
        val isUsb: Boolean get() = host == "127.0.0.1"
        var inputEnabled = false
            private set
        var extendedInput = false
            private set

        fun start() {
            worker = thread(name = "GalaxySidecar receiver") {
                try {
                    socket.use {
                        it.connect(InetSocketAddress(host, if (remote == null) PORT else REMOTE_PORT), 5_000)
                        it.tcpNoDelay = true
                        val token = remote?.let {
                            if (extendsDisplay) {
                                val digest = MessageDigest.getInstance("SHA-256")
                                digest.update("GalaxySidecar virtual display:".toByteArray(Charsets.US_ASCII))
                                digest.digest(it.token).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                                    .toByteArray(Charsets.US_ASCII)
                            } else it.token
                        } ?: if (host == "127.0.0.1") {
                            runCatching { openFileInput("usb-input-token").use { file -> file.readBytes() } }
                                .getOrNull()?.takeIf { bytes -> bytes.size == 64 }
                        } else null
                        it.getOutputStream().write(token ?: ByteArray(64))
                        val input = DataInputStream(BufferedInputStream(it.getInputStream()))
                        it.soTimeout = 8_000
                        if (input.readInt() != 0x47534332) throw IOException("Update the Mac host and viewer to the same version")
                        val capabilities = input.readUnsignedByte()
                        if (capabilities == 2) throw IOException("Remote pairing expired. Pair this tablet again.")
                        if (capabilities == 16) throw IOException("Mac could not create an extended display. Try Remote desktop.")
                        if (capabilities == 32) throw MacLocked()
                        val supportsInput = capabilities and 1 != 0
                        extendedInput = capabilities and 8 != 0
                        val usesAvc = capabilities and 4 != 0
                        val width = if (usesAvc) input.readUnsignedShort() else 0
                        val height = if (usesAvc) input.readUnsignedShort() else 0
                        val writer = thread(name = "GalaxySidecar sender") {
                            try {
                                val output = socket.getOutputStream()
                                while (!Thread.currentThread().isInterrupted) output.write(outgoing.take())
                            } catch (_: IOException) {
                                runCatching { socket.close() }
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        try {
                            if (usesAvc) readVideo(input, supportsInput, width, height)
                            else readFrames(input, supportsInput)
                        } finally {
                            writer.interrupt()
                        }
                    }
                } catch (_: MacLocked) {
                    runOnUiThread { if (session === this) waitForUnlock() }
                } catch (error: IOException) {
                    runOnUiThread {
                        if (session === this) connectionLost(
                            when (error) {
                                is java.net.SocketTimeoutException -> when {
                                    remote != null -> "Mac didn’t respond. Check Tailscale and the remote host."
                                    isUsb -> "Mac didn’t respond. Check the USB cable and host."
                                    else -> "Mac didn’t respond. Check Wi-Fi and the host address."
                                }
                                is java.net.ConnectException -> "Can’t reach the Mac host. Check that it’s running."
                                is java.net.UnknownHostException -> "Mac address not found. Check the address and try again."
                                is java.io.EOFException -> "The Mac closed the connection. Try connecting again."
                                else -> error.message ?: "Connection closed. Try connecting again."
                            },
                            transient = error is java.net.SocketException ||
                                error is java.net.SocketTimeoutException || error is java.io.EOFException
                        )
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun stop() {
            worker?.interrupt()
            runCatching { socket.close() }
        }

        fun send(message: ByteArray, essential: Boolean) {
            if (!outgoing.offer(message) && essential) runCatching { socket.close() }
        }

        private fun readVideo(input: DataInputStream, supportsInput: Boolean, width: Int, height: Int) {
            if (width !in 2..1920 || height !in 2..1920) throw IOException("Invalid H.264 dimensions")
            val surfaceReady = CountDownLatch(1)
            var surface: Surface? = null
            runOnUiThread {
                try {
                    if (session === this) {
                        videoWidth = width
                        videoHeight = height
                        fitVideo()
                        surface = video.surfaceTexture?.let { Surface(it) }
                    }
                } finally {
                    surfaceReady.countDown()
                }
            }
            if (!surfaceReady.await(3, TimeUnit.SECONDS)) throw IOException("The video surface was not ready.")
            val display = surface ?: throw IOException("The video surface is unavailable. Reconnect to the Mac.")
            try {
                val first = readPacket(input)
                AvcDecoder(width, height, first, display).use { decoder ->
                    decoder.render(first)
                    socket.soTimeout = if (extendedInput) 8_000 else 0
                    val shown = CountDownLatch(1)
                    runOnUiThread {
                        try {
                            if (session === this) {
                                video.alpha = 1f
                                inputEnabled = supportsInput
                                showStream(supportsInput, remote != null)
                            }
                        } finally {
                            shown.countDown()
                        }
                    }
                    shown.await()
                    send(FRAME_ACK, essential = true)
                    while (!Thread.currentThread().isInterrupted) {
                        decoder.render(readPacket(input))
                        send(FRAME_ACK, essential = true)
                    }
                }
            } finally {
                display.release()
            }
        }

        private fun readPacket(input: DataInputStream): ByteArray {
            while (true) {
                val size = input.readInt()
                if (size == 0 && extendedInput) {
                    val length = input.readInt()
                    if (length == -1) continue
                    if (length !in 0..MAX_CLIPBOARD_BYTES) throw IOException("Invalid clipboard message")
                    val bytes = ByteArray(length).also { input.readFully(it) }
                    runOnUiThread {
                        if (session === this) {
                            if (bytes.isNotEmpty()) {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Mac clipboard", bytes.toString(Charsets.UTF_8)))
                            } else {
                                Toast.makeText(this@MainActivity, "No Mac clipboard text under 64 KiB.",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    continue
                }
                if (size !in 1..MAX_FRAME_BYTES) throw IOException("Invalid frame size")
                return ByteArray(size).also { input.readFully(it) }
            }
        }

        private fun readFrames(input: DataInputStream, supportsInput: Boolean) {
            var firstFrame = true
            while (!Thread.currentThread().isInterrupted) {
                val jpeg = readPacket(input)
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, dimensions)
                if (dimensions.outMimeType != "image/jpeg" ||
                    dimensions.outWidth !in 1..1920 || dimensions.outHeight !in 1..1920
                ) throw IOException("Invalid JPEG dimensions or format")
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw IOException("Could not decode JPEG")
                if (firstFrame) socket.soTimeout = if (extendedInput) 8_000 else 0

                val displayed = CountDownLatch(1)
                val revealDisplay = firstFrame
                runOnUiThread {
                    try {
                        if (session === this) {
                            image.setImageBitmap(bitmap)
                            if (revealDisplay) {
                                inputEnabled = supportsInput
                                showStream(supportsInput, remote != null)
                            }
                        } else {
                            bitmap.recycle()
                        }
                    } finally {
                        displayed.countDown()
                    }
                }
                displayed.await()
                send(FRAME_ACK, essential = true)
                firstFrame = false
            }
        }
    }

    private companion object {
        const val EXTRA_USB_ATTACHED = "dev.galaxysidecar.USB_ATTACHED"
        const val LOCKED_STATUS = "Mac is locked. Unlock it on the Mac to reconnect automatically."
        val BACKGROUND = 0xFF10151A.toInt()
        val REMOTE_SURFACE = 0xFF172724.toInt()
        val REMOTE_OUTLINE = 0xFF37554A.toInt()
        val SURFACE = 0xFF1A222B.toInt()
        val SURFACE_RAISED = 0xFF293541.toInt()
        val OUTLINE = 0xFF364451.toInt()
        val TEXT = 0xFFF0F5F8.toInt()
        val MUTED = 0xFFB4C2CD.toInt()
        val ACCENT = 0xFFB9F7DA.toInt()
        val ERROR = 0xFFFFB4A7.toInt()
        const val PORT = 5555
        const val REMOTE_PORT = 5556
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val MAX_CLIPBOARD_BYTES = 65_536
        const val MESSAGE_BYTES = 13
        val FRAME_ACK = ByteArray(MESSAGE_BYTES)
    }
}
