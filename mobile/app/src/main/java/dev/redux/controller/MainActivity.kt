// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.redux.controller.dish.hidButtonsOf
import dev.redux.controller.dish.hidHatOf
import dev.redux.controller.dish.xusbToHid
import kotlin.math.abs

class MainActivity : Activity(), InputManager.InputDeviceListener {
    private val state = ControllerState()
    private lateinit var link: ReduxLink
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var statusText: TextView? = null
    private var deviceText: TextView? = null
    private var inputText: TextView? = null
    private var keypad: OverlayKeypad? = null
    private val mouseButtons = ArrayList<HoldButton>()
    private val motion = MouseMotion()
    private var heldMouse = 0
    private var physicalMouse = 0
    private var pulsedMouse = 0
    private var connectionText = "Not connected · open Redux Receiver on your PC"
    private var page = "connections"
    private var xusb = 0
    private var hatX = 0; private var hatY = 0
    private var selectedDeviceId: Int? = null
    private lateinit var profiles: List<Profile>
    private var profileId = Profiles.DEFAULT
    private var plateIndex = 0
    private val prefs by lazy { getSharedPreferences("redux", MODE_PRIVATE) }
    private val inputs by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val main = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(15, 21, 28)
    private val panel = Color.rgb(25, 34, 44)
    private val ink = Color.rgb(239, 243, 247)
    private val muted = Color.rgb(153, 170, 188)
    private val accent = Color.rgb(255, 106, 80)

    private val profile: Profile get() = profiles.firstOrNull { it.id == profileId } ?: profiles.first { it.id == Profiles.DEFAULT }
    private val plate: Plate get() = profile.plates[plateIndex.coerceIn(0, profile.plates.size - 1)]
    private val followPc: Boolean get() = prefs.getBoolean("followPc", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = Profiles.load(this)
        page = savedInstanceState?.getString("page") ?: "connections"
        profileId = savedInstanceState?.getString("profile") ?: prefs.getString("profile", Profiles.DEFAULT) ?: Profiles.DEFAULT
        plateIndex = savedInstanceState?.getInt("plate") ?: 0
        motion.speed = prefs.getInt("mouseSpeed", MouseMotion.DEFAULT_SPEED)
        link = ReduxLink(this, state) { message -> main.post { connectionText = message; statusText?.text = message } }
        link.onProfile = { id -> main.post { if (id != null) followPcProfile(id) } }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inputs.registerInputDeviceListener(this, main)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("profile", profileId); outState.putInt("plate", plateIndex)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() { super.onResume(); link.enabled = true; link.visible = true; refreshDevice() }
    override fun onPause() { releaseInputs(); link.enabled = false; link.visible = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (::link.isInitialized) { link.enabled = hasFocus; if (!hasFocus) releaseInputs() } }
    override fun onDestroy() { inputs.unregisterInputDeviceListener(this); link.close(); super.onDestroy() }
    private fun releaseInputs() { keypad?.releaseAll(); releaseMouse(); synchronized(state) { state.release() }; xusb = 0; hatX = 0; hatY = 0 }
    private fun releaseMouse() {
        for (button in mouseButtons) button.release()
        heldMouse = 0; physicalMouse = 0; pulsedMouse = 0
        synchronized(state) { state.releaseMouse() }
    }

    private fun navigate(destination: String) {
        keypad?.releaseAll(); releaseMouse()
        synchronized(state) { state.keypad = 0 }
        page = destination
        requestedOrientation = if (page == "play" || page == "mouse") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        render()
    }

    private fun updateMouseButtons() { synchronized(state) { state.mouseButtons = heldMouse or physicalMouse or pulsedMouse } }
    /** A tap is a press that lasts a few packets, then releases unless the same button is still held. */
    private fun clickMouse(bit: Int) {
        pulsedMouse = pulsedMouse or bit; updateMouseButtons()
        main.postDelayed({ pulsedMouse = pulsedMouse and bit.inv(); updateMouseButtons() }, 60)
    }

    private fun selectProfile(id: String, plate: Int = 0, remember: Boolean = true) {
        if (profiles.none { it.id == id }) return
        profileId = id; plateIndex = plate
        if (remember) prefs.edit().putString("profile", id).apply()
        synchronized(state) { state.keypad = 0 }
    }

    /** The PC (Redux front end) launched a game: show its overlay while the user has Follow the PC on. */
    private fun followPcProfile(id: String) {
        if (!followPc || id == profileId || profiles.none { it.id == id }) return
        selectProfile(id, remember = false)
        if (page == "play") render() else if (page == "profiles") render()
    }

    private fun render() {
        statusText = null; deviceText = null; inputText = null; keypad = null; mouseButtons.clear()
        root = column().apply { setBackgroundColor(bg); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop, dp(20) + insets.systemWindowInsetRight, dp(12) + insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = row()
        header.addView(label("REDUX", 22, accent, true), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (page == "play") {
            header.addView(button("Mouse") { navigate("mouse") }, LinearLayout.LayoutParams(dp(100), -2))
            header.addView(button("Connections") { navigate("connections") }, LinearLayout.LayoutParams(dp(130), -2))
            header.addView(button("Game Profiles") { navigate("profiles") }, LinearLayout.LayoutParams(dp(130), -2))
        } else if (page == "mouse") {
            header.addView(button("Back to gamepad", true) { navigate("play") }, LinearLayout.LayoutParams(dp(160), -2))
            header.addView(button("Connections") { navigate("connections") }, LinearLayout.LayoutParams(dp(130), -2))
        } else header.addView(label("CONTROLLER", 12, muted, true))
        root.addView(header)
        if (page != "play" && page != "mouse") {
            val nav = row()
            nav.addView(button("Connections", page == "connections") { navigate("connections") }, weighted())
            nav.addView(button("Game Profiles", page == "profiles") { navigate("profiles") }, weighted())
            root.addView(nav)
        }
        statusText = label(connectionText, 12, muted).also {
            if (page == "play" || page == "mouse") { it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }
            root.addView(it)
        }
        if (page == "play") {
            renderPlay()
        } else if (page == "mouse") {
            renderMouse()
        } else {
            content = column()
            root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            if (page == "connections") renderConnections() else renderProfiles()
        }
        refreshDevice()
    }

    private fun renderConnections() {
        content.addView(label("Your setup", 28, ink, true))
        content.addView(label("GameSir → Pixel → Wi-Fi → Windows 11", 14, muted))
        val controller = card()
        controller.addView(label("01   PHYSICAL CONTROLLER", 12, accent, true))
        deviceText = label("", 18, ink, true).also { controller.addView(it) }
        controller.addView(label("G8 Galileo: use green Android mode with steady lights. Connect directly to the Pixel's USB-C port.", 13, muted))
        inputText = label("Press a button or move a stick to test input.", 13, muted).also { controller.addView(it) }
        controller.addView(button("Choose controller / refresh") { chooseController() })

        val pc = card()
        pc.addView(label("02   GAMING PC", 12, accent, true))
        pc.addView(label(if (link.paired) "Paired with ${link.pcName.ifEmpty { link.pairedHost }}" else "Redux Receiver", 18, ink, true))
        pc.addView(label(if (link.paired) "This phone reconnects on its own whenever the receiver is running. Pair again only for a new PC or after the PC forgot its phones."
            else "Run Redux Receiver on Windows (tray icon). Both devices must be on the same home network. Use its six-digit PIN below.", 13, muted))
        if (link.paired) {
            val resume = row()
            resume.addView(button("Connect", true) { link.connect() }, weighted())
            resume.addView(button("Disconnect") { link.disconnect() }, weighted())
            pc.addView(resume)
        }
        val address = field("PC IPv4 address or hostname", link.pairedHost)
        pc.addView(address)
        pc.addView(button("Find PCs on this network") {
            connectionText = "Looking for Redux receivers…"; statusText?.text = connectionText
            link.discover { found -> main.post {
                connectionText = if (found.isEmpty()) "No receiver found. Enter its IPv4 address manually." else "Choose a PC to pair."
                statusText?.text = connectionText
                if (found.isNotEmpty()) AlertDialog.Builder(this).setTitle("Gaming PCs").setItems(found.map { "${it.first} · ${it.second}" }.toTypedArray()) { _, index -> address.setText(found[index].second) }.show()
            } }
        })
        val pin = field("Six-digit PIN", "").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; typeface = Typeface.DEFAULT }
        pc.addView(pin)
        val actions = row()
        actions.addView(button("Pair & connect", !link.paired) {
            pin.clearFocus(); address.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(pin.windowToken, 0)
            link.pair(address.text.toString().trim(), pin.text.toString().trim())
            main.postDelayed({ if (page == "connections") render() }, 2500)
        }, weighted())
        if (!link.paired) actions.addView(button("Disconnect") { link.disconnect() }, weighted())
        pc.addView(actions)
        pc.addView(button("Reset trust for this PC") {
            AlertDialog.Builder(this).setTitle("Forget this PC?")
                .setMessage("Removes the saved certificate and the reconnect token. Do this after reinstalling the receiver or when its certificate changed on your own PC. Pair again with the PIN afterwards.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> link.disconnect(); link.forgetTrust(address.text.toString().trim()); render() }.show()
        })

        val output = card()
        output.addView(label("03   WINDOWS CONTROLLER", 12, accent, true))
        output.addView(label("One controller. All your inputs.", 18, ink, true))
        output.addView(label("Redux exposes 32 buttons, two sticks, two independent triggers and a D-pad. The Jaguar keypad uses buttons 17–28. Check it in Windows with the tray menu's Open joy.cpl. Mouse mode adds a second device, the Redux Phone Mouse, with receiver 0.4 or later.", 13, muted))
        output.addView(button("Open the ${profile.name} profile", true) { navigate("play") })
        output.addView(button("Use the phone as a mouse") { navigate("mouse") })
        content.addView(label("Built with input-mapping and certificate-pinning code adapted from Dish by TinkerNorth (LGPL-3.0-or-later).", 11, muted))
    }

    private fun renderProfiles() {
        content.addView(label("Game Profiles", 28, ink, true))
        content.addView(label("Pick the keypad overlay that fits your game. Every profile drives the same Windows buttons 17–28.", 14, muted))
        val follow = card()
        follow.addView(label("FOLLOW THE PC", 12, accent, true))
        follow.addView(label(if (followPc) "On · the Redux front end switches this phone to the game it launches" else "Off · profiles change only from this phone", 14, ink))
        follow.addView(button(if (followPc) "Turn off" else "Turn on") { prefs.edit().putBoolean("followPc", !followPc).apply(); render() })
        for (item in profiles) {
            val active = item.id == profileId
            val c = card()
            c.addView(label(if (item.id == Profiles.DEFAULT) "DEFAULT PROFILE" else if (active) "ACTIVE" else "ATARI JAGUAR", 12, if (active) accent else muted, true))
            c.addView(label(item.name, 24, ink, true))
            c.addView(label(item.subtitle, 14, muted))
            if (item.plates.size > 1) c.addView(label(item.plates.joinToString(" · ") { it.name }, 13, muted))
            c.addView(button(if (active) "Play" else "Play with ${item.name}", active) { selectProfile(item.id); navigate("play") })
        }
        val mouse = card()
        mouse.addView(label("PHONE MOUSE", 12, accent, true))
        mouse.addView(label("Trackpad for the PC", 24, ink, true))
        mouse.addView(label("Move the Windows cursor from the phone's screen with left, right and middle buttons and a scroll strip. The GameSir controls keep working; LB and RB double as left and right click while mouse mode is open.", 14, muted))
        mouse.addView(button("Open mouse mode") { navigate("mouse") })
        val bindings = card()
        bindings.addView(label("BIGPEMU BINDING GUIDE", 12, accent, true))
        bindings.addView(label("Input Device 1 → bindings", 18, ink, true))
        bindings.addView(label("D-pad / left stick → movement\nG8 A / B / X → Jaguar A / B / C\nMenu / View → Pause / Option\nPhone keys 1–9, *, 0, # → buttons 17–28", 15, ink))
        bindings.addView(label("Bind each input once in BigPEmu. Every profile sends the same Windows buttons, so one binding covers all overlays.", 13, muted))
        content.addView(label("Overlay artwork: AtariAge scans and community renders of the original Atari overlays, trademarks of their owners, included for personal use.", 11, muted))
    }

    private fun renderPlay() {
        val play = row()
        root.addView(play, LinearLayout.LayoutParams(-1, 0, 1f))
        val info = column().apply { setPadding(0, 0, dp(12), 0) }
        info.addView(label(profile.name.uppercase(), 20, ink, true).apply { maxLines = 2 })
        info.addView(label(plate.name.uppercase(), 12, accent, true))
        if (profile.plates.size > 1) info.addView(button("Next overlay (${plateIndex + 1}/${profile.plates.size})") {
            plateIndex = (plateIndex + 1) % profile.plates.size; synchronized(state) { state.keypad = 0 }; render()
        })
        info.addView(label("Keep your GameSir controls in hand.\nTap and hold keys together.", 12, muted))
        inputText = label("Waiting for input", 12, muted).also { it.maxLines = 3; info.addView(it) }
        info.addView(button("Release all inputs") { releaseInputs(); render() })
        play.addView(info, LinearLayout.LayoutParams(0, -1, 0.30f))
        val surface = OverlayKeypad(this).apply {
            onKeysChanged = { mask -> synchronized(state) { state.keypad = mask } }
            onKeyPressed = { key -> inputText?.text = "${plate.labels[key].ifEmpty { "Key ${keyName(key)}" }} · button ${17 + key}" }
            show(plate)
        }
        keypad = surface
        play.addView(surface, LinearLayout.LayoutParams(0, -1, 0.70f))
    }

    private fun keyName(key: Int) = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")[key]

    /** Mouse mode: a trackpad, a scroll strip and held left/middle/right buttons, laid out for thumbs in the G8 grips. */
    private fun renderMouse() {
        val body = column()
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val top = row()
        top.addView(label("MOUSE MODE", 12, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        val readiness = label(mouseReadiness(), 12, muted).also { it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(readiness, LinearLayout.LayoutParams(0, -2, 2f))
        val speed = button(MouseMotion.label(motion.speed)) { }
        speed.setOnClickListener {
            motion.speed = if (motion.speed >= MouseMotion.SPEEDS) 1 else motion.speed + 1
            prefs.edit().putInt("mouseSpeed", motion.speed).apply()
            speed.text = MouseMotion.label(motion.speed)
        }
        top.addView(speed, LinearLayout.LayoutParams(dp(110), -2))
        body.addView(top)
        val middle = row()
        val pad = TrackpadView(this).apply {
            onGestureStart = { motion.reset() }
            onMove = { dx, dy -> val (x, y) = motion.counts(dx, dy); synchronized(state) { state.addMouseMotion(x, y) } }
            onScroll = { dy -> val detents = motion.detents(dy); if (detents != 0) synchronized(state) { state.addMouseMotion(0, 0, detents) } }
            onTap = { clickMouse(ControllerState.MOUSE_LEFT) }
            onTwoFingerTap = { clickMouse(ControllerState.MOUSE_RIGHT) }
        }
        middle.addView(pad, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, dp(6), dp(8), dp(8)) })
        val strip = TrackpadView(this, scrollOnly = true).apply {
            onGestureStart = { motion.reset() }
            onScroll = { dy -> val detents = motion.detents(dy); if (detents != 0) synchronized(state) { state.addMouseMotion(0, 0, detents) } }
        }
        middle.addView(strip, LinearLayout.LayoutParams(dp(48), -1).apply { setMargins(0, dp(6), 0, dp(8)) })
        body.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = row()
        for ((label, bit, weight, prominent) in listOf(Triple("Left click", ControllerState.MOUSE_LEFT, 3f) to true, Triple("Middle", ControllerState.MOUSE_MIDDLE, 1f) to false, Triple("Right click", ControllerState.MOUSE_RIGHT, 3f) to true).map { (t, p) -> listOf(t.first, t.second, t.third, p) }) {
            val view = HoldButton(this, label as String, prominent as Boolean).apply {
                onHeld = { held -> heldMouse = if (held) heldMouse or (bit as Int) else heldMouse and (bit as Int).inv(); updateMouseButtons() }
            }
            mouseButtons.add(view)
            bottom.addView(view, LinearLayout.LayoutParams(0, dp(58), weight as Float).apply { setMargins(0, 0, if (label == "Right click") 0 else dp(8), 0) })
        }
        body.addView(bottom)
    }

    private fun mouseReadiness() = when {
        !link.connected -> "Not connected to the PC"
        !link.mouseProtocol -> "Update Redux Receiver to 0.4 for mouse mode"
        link.mouseReady -> "Mouse ready on ${link.pcName.ifEmpty { "the PC" }} · LB / RB click"
        else -> "Connected · the PC has no mouse device yet"
    }

    private fun devices() = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter { !it.isVirtual && (it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK)) }
    private fun refreshDevice() {
        val available = devices()
        if (available.none { it.id == selectedDeviceId }) {
            selectedDeviceId = available.firstOrNull { it.name.contains("gamesir", true) }?.id ?: available.firstOrNull()?.id
            releaseInputs()
        }
        deviceText?.text = available.firstOrNull { it.id == selectedDeviceId }?.name ?: "Connect your GameSir"
    }
    private fun chooseController() {
        refreshDevice()
        val available = devices()
        if (available.isEmpty()) { connectionText = "No gamepad detected. Reseat the Pixel and use green Android mode."; statusText?.text = connectionText; return }
        AlertDialog.Builder(this).setTitle("Physical controller").setItems(available.map { it.name }.toTypedArray()) { _, i ->
            selectedDeviceId = available[i].id; releaseInputs(); refreshDevice()
        }.show()
    }
    override fun onInputDeviceAdded(deviceId: Int) { refreshDevice() }
    override fun onInputDeviceChanged(deviceId: Int) { refreshDevice() }
    override fun onInputDeviceRemoved(deviceId: Int) { if (deviceId == selectedDeviceId) releaseInputs(); refreshDevice() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.deviceId != selectedDeviceId || event.device?.let { it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK) } != true) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        // In mouse mode the shoulder buttons click instead of reaching the gamepad.
        if (page == "mouse" && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            val mouseBit = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) ControllerState.MOUSE_LEFT else ControllerState.MOUSE_RIGHT
            physicalMouse = if (down) physicalMouse or mouseBit else physicalMouse and mouseBit.inv()
            updateMouseButtons()
            return true
        }
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0x1000; KeyEvent.KEYCODE_BUTTON_B -> 0x2000
            KeyEvent.KEYCODE_BUTTON_X -> 0x4000; KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
            KeyEvent.KEYCODE_BUTTON_L1 -> 0x100; KeyEvent.KEYCODE_BUTTON_R1 -> 0x200
            KeyEvent.KEYCODE_BUTTON_START -> 0x10; KeyEvent.KEYCODE_BUTTON_SELECT -> 0x20
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x40; KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x80
            KeyEvent.KEYCODE_BUTTON_MODE -> 0x400
            KeyEvent.KEYCODE_DPAD_UP -> 1; KeyEvent.KEYCODE_DPAD_DOWN -> 2
            KeyEvent.KEYCODE_DPAD_LEFT -> 4; KeyEvent.KEYCODE_DPAD_RIGHT -> 8
            else -> 0
        }
        synchronized(state) {
            if (bit != 0) xusb = if (down) xusb or bit else xusb and bit.inv()
            // Analog trigger events take priority on devices that supply them.
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 && event.device?.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null && event.device?.getMotionRange(MotionEvent.AXIS_BRAKE) == null) state.lt = if (down) 65535 else 0
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2 && event.device?.getMotionRange(MotionEvent.AXIS_RTRIGGER) == null && event.device?.getMotionRange(MotionEvent.AXIS_GAS) == null) state.rt = if (down) 65535 else 0
            updateButtons()
        }
        inputText?.text = "${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} · ${if (down) "pressed" else "released"}"
        return bit != 0 || event.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.deviceId != selectedDeviceId || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        fun axis(code: Int): Float {
            val value = event.getAxisValue(code)
            val flat = event.device?.getMotionRange(code)?.flat ?: 0.05f
            return if (abs(value) <= maxOf(flat, 0.05f)) 0f else value.coerceIn(-1f, 1f)
        }
        fun trigger(primary: Int, fallback: Int): Int = (maxOf(event.getAxisValue(primary), event.getAxisValue(fallback)).coerceIn(0f, 1f) * 65535).toInt()
        synchronized(state) {
            state.lx = (axis(MotionEvent.AXIS_X) * 32767).toInt(); state.ly = (axis(MotionEvent.AXIS_Y) * 32767).toInt()
            val rx = if (event.device?.getMotionRange(MotionEvent.AXIS_Z) != null) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
            val ry = if (event.device?.getMotionRange(MotionEvent.AXIS_RZ) != null) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
            state.rx = (axis(rx) * 32767).toInt(); state.ry = (axis(ry) * 32767).toInt()
            state.lt = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE); state.rt = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            hatX = axis(MotionEvent.AXIS_HAT_X).toInt(); hatY = axis(MotionEvent.AXIS_HAT_Y).toInt()
            updateButtons()
        }
        inputText?.text = "LX ${state.lx}  LY ${state.ly}\nLT ${state.lt}  RT ${state.rt}"
        return true
    }
    private fun updateButtons() {
        val dpad = (if (hatX < 0) 4 else if (hatX > 0) 8 else 0) or (if (hatY < 0) 1 else if (hatY > 0) 2 else 0)
        val packed = xusbToHid(xusb or dpad)
        state.buttons = hidButtonsOf(packed); state.hat = hidHatOf(packed)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = label(value, size.toFloat(), color, bold)
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if (primary) bg else ink); backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent else panel)
        setOnClickListener { action() }
    }
    private fun field(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(ink); setHintTextColor(muted); textSize = 15f
        setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minHeight = dp(48)
    }
    private fun card() = column().apply {
        background = shape(panel, 16); setPadding(dp(18), dp(14), dp(18), dp(14))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(14), 0, 0) })
    }
}
