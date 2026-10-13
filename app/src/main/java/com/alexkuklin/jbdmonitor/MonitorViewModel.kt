package com.alexkuklin.jbdmonitor

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.exp

enum class ConnState { Idle, Scanning, Connecting, Connected }

data class Sample(val tMs: Long, val amps: Float)

data class UiState(
    val conn: ConnState = ConnState.Idle,
    val devices: List<Device> = emptyList(),
    val deviceName: String? = null,
    val info: BasicInfo? = null,
    val cells: List<Float> = emptyList(),
    /** Current smoothed over ~30 s, used for the time-to-full/empty estimate. */
    val smoothedAmps: Float = 0f,
    val history: List<Sample> = emptyList(),
    val message: String? = null,
)

class MonitorViewModel(app: Application) : AndroidViewModel(app), BleClient.Listener {

    companion object {
        const val HISTORY_WINDOW_MS = 30 * 60 * 1000L
        private const val SMOOTHING_TAU_S = 30.0
    }

    private val prefs = app.getSharedPreferences("jbd", Context.MODE_PRIVATE)
    private val client = BleClient(app, this)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val found = LinkedHashMap<String, Device>()
    private val history = ArrayList<Sample>()
    private var assembler = FrameAssembler()
    private var lastSampleMs = 0L
    private var smoothed = 0f
    private var poll: Job? = null
    private var reconnect: Job? = null
    private var scanStop: Job? = null

    /** Address we should stay connected to; null once the user disconnects. */
    private var wantAddress: String? = null

    fun autoConnect() {
        if (_ui.value.conn != ConnState.Idle) return
        val addr = prefs.getString("addr", null)
        if (addr != null) connect(Device(addr, prefs.getString("name", null), 0, true)) else startScan()
    }

    fun startScan() {
        if (!client.isEnabled) return _ui.update { it.copy(message = "Bluetooth is off") }
        found.clear()
        _ui.update { it.copy(conn = ConnState.Scanning, devices = emptyList(), message = null) }
        client.startScan()
        scanStop?.cancel()
        scanStop = viewModelScope.launch {
            delay(20_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStop?.cancel()
        client.stopScan()
        _ui.update { if (it.conn == ConnState.Scanning) it.copy(conn = ConnState.Idle) else it }
    }

    fun connect(device: Device) {
        if (!client.isEnabled) return _ui.update { it.copy(message = "Bluetooth is off") }
        stopScan()
        wantAddress = device.address
        prefs.edit().putString("addr", device.address).putString("name", device.name).apply()
        _ui.update { UiState(conn = ConnState.Connecting, deviceName = device.name ?: device.address) }
        client.connect(device.address)
    }

    fun disconnect() {
        wantAddress = null
        reconnect?.cancel()
        poll?.cancel()
        client.close()
        prefs.edit().remove("addr").remove("name").apply()
        history.clear()
        _ui.value = UiState()
    }

    // --- BleClient.Listener (called on binder threads, hop to main) ---

    override fun onScanResult(device: Device) {
        viewModelScope.launch {
            found[device.address] = device
            val sorted = found.values.sortedWith(
                compareByDescending<Device> { it.looksLikeJbd }
                    .thenByDescending { it.name != null }
                    .thenByDescending { it.rssi }
            )
            _ui.update { it.copy(devices = sorted) }
        }
    }

    override fun onScanFailed(code: Int) {
        viewModelScope.launch { _ui.update { it.copy(conn = ConnState.Idle, message = "Scan failed ($code)") } }
    }

    override fun onConnected() {
        viewModelScope.launch {
            assembler = FrameAssembler()
            history.clear()
            lastSampleMs = 0L
            _ui.update { it.copy(conn = ConnState.Connected, message = null, history = emptyList()) }
            poll?.cancel()
            poll = launch {
                while (true) {
                    client.send(JbdProtocol.REQUEST_BASIC)
                    delay(400)
                    client.send(JbdProtocol.REQUEST_CELLS)
                    delay(600)
                }
            }
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch {
            poll?.cancel()
            val addr = wantAddress
            if (addr == null) {
                _ui.update { it.copy(conn = ConnState.Idle) }
                return@launch
            }
            _ui.update { it.copy(conn = ConnState.Connecting, message = "Connection lost, retrying…") }
            reconnect?.cancel()
            reconnect = launch {
                delay(3_000)
                if (wantAddress == addr) client.connect(addr)
            }
        }
    }

    override fun onError(message: String) {
        viewModelScope.launch {
            wantAddress = null
            prefs.edit().remove("addr").remove("name").apply()
            _ui.update { it.copy(message = message) }
        }
    }

    override fun onData(bytes: ByteArray) {
        viewModelScope.launch {
            for (f in assembler.feed(bytes)) {
                if (f.status != 0) continue
                when (f.cmd) {
                    JbdProtocol.CMD_BASIC -> JbdProtocol.parseBasic(f.data)?.let(::onBasic)
                    JbdProtocol.CMD_CELLS -> _ui.update { it.copy(cells = JbdProtocol.parseCells(f.data)) }
                }
            }
        }
    }

    private fun onBasic(info: BasicInfo) {
        val now = System.currentTimeMillis()
        smoothed = if (lastSampleMs == 0L) info.current else {
            val alpha = 1.0 - exp(-((now - lastSampleMs) / 1000.0) / SMOOTHING_TAU_S)
            smoothed + (alpha * (info.current - smoothed)).toFloat()
        }
        lastSampleMs = now
        history += Sample(now, info.current)
        val cutoff = now - HISTORY_WINDOW_MS
        val stale = history.indexOfFirst { it.tMs >= cutoff }.let { if (it < 0) history.size else it }
        if (stale > 0) history.subList(0, stale).clear()
        _ui.update { it.copy(info = info, smoothedAmps = smoothed, history = history.toList()) }
    }

    override fun onCleared() {
        client.close()
        client.stopScan()
    }
}
