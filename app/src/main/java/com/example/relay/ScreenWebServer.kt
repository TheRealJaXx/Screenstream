package com.example.relay

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Universal In-Browser Web Streamer for ScreenStream.
 *
 * Runs an embedded HTTP server (default port 8080):
 * - Serves a responsive, cyberpunk-styled HTML5 web application at http://<phone_ip>:8080/
 * - Delivers ultra-low-latency MJPEG multipart video at /stream.mjpg compatible with any browser
 * - Dispatches single snapshot at /snapshot.jpg
 * - Exposes JSON telemetry API at /api/status
 */
class ScreenWebServer(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ScreenWebServer"
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    @Volatile
    private var latestJpegFrame: ByteArray? = null
    private val webClients = CopyOnWriteArrayList<BufferedOutputStream>()

    private val _connectedWebViewers = MutableStateFlow(0)
    val connectedWebViewers: StateFlow<Int> = _connectedWebViewers.asStateFlow()

    private val _webServerPort = MutableStateFlow(8080)
    val webServerPort: StateFlow<Int> = _webServerPort.asStateFlow()

    private val startTimeMs = AtomicInteger(0)

    fun start(port: Int = 8080): Boolean {
        if (isRunning.get()) {
            if (_webServerPort.value == port) return true
            stop()
        }

        return try {
            _webServerPort.value = port
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            isRunning.set(true)
            scope = CoroutineScope(Dispatchers.IO)
            startTimeMs.set((SystemClock.uptimeMillis() / 1000).toInt())

            scope?.launch {
                acceptLoop()
            }

            onLog(TAG, "Web Viewer Server active at http://${NetworkUtils.getLocalIpAddress()}:$port", false)
            true
        } catch (e: Exception) {
            onLog(TAG, "Failed to start Web Server on port $port: ${e.message}", true)
            false
        }
    }

    fun submitJpegFrame(jpeg: ByteArray) {
        latestJpegFrame = jpeg
        if (webClients.isEmpty()) return

        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()
        val footer = "\r\n".toByteArray()

        val deadClients = mutableListOf<BufferedOutputStream>()
        for (client in webClients) {
            try {
                client.write(header)
                client.write(jpeg)
                client.write(footer)
                client.flush()
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }

        if (deadClients.isNotEmpty()) {
            webClients.removeAll(deadClients)
            _connectedWebViewers.value = webClients.size
        }
    }

    private suspend fun acceptLoop() {
        val server = serverSocket ?: return
        while (isRunning.get() && scope?.isActive == true) {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                scope?.launch {
                    handleHttpRequest(socket)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleHttpRequest(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Drain headers
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
            }

            val out = BufferedOutputStream(socket.getOutputStream())

            when {
                path == "/" || path.startsWith("/index") -> {
                    val html = getViewerHtml()
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${html.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n" + html
                    out.write(response.toByteArray())
                    out.flush()
                    socket.close()
                }

                path.startsWith("/stream.mjpg") -> {
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Expires: 0\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n\r\n"
                    out.write(header.toByteArray())
                    out.flush()

                    webClients.add(out)
                    _connectedWebViewers.value = webClients.size

                    // Keep socket open; client is pushed frames via submitJpegFrame
                }

                path.startsWith("/snapshot.jpg") -> {
                    val frame = latestJpegFrame
                    if (frame != null) {
                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(frame)
                        out.flush()
                    } else {
                        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                        out.write(header.toByteArray())
                        out.flush()
                    }
                    socket.close()
                }

                path.startsWith("/api/status") -> {
                    val uptimeSec = (SystemClock.uptimeMillis() / 1000).toInt() - startTimeMs.get()
                    val json = """{"status":"active","viewers":${webClients.size},"uptimeSec":$uptimeSec}"""
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${json.toByteArray().size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n" + json
                    out.write(header.toByteArray())
                    out.flush()
                    socket.close()
                }

                else -> {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    out.write(notFound.toByteArray())
                    out.flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP client handling exception: ${e.message}")
        }
    }

    private fun getViewerHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>ScreenStream Live Viewer</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: #0A0E17;
      color: #F3F4F6;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      display: flex;
      flex-direction: column;
      height: 100vh;
      overflow: hidden;
    }
    header {
      background: #111827;
      border-bottom: 1px solid #1F293D;
      padding: 12px 20px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      z-index: 10;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(16, 185, 129, 0.15);
      border: 1px solid #10B981;
      color: #10B981;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.5px;
      padding: 3px 8px;
      border-radius: 12px;
      text-transform: uppercase;
    }
    .badge-dot {
      width: 7px;
      height: 7px;
      background: #10B981;
      border-radius: 50%;
      animation: pulse 1.5s infinite;
    }
    @keyframes pulse {
      0% { opacity: 0.4; transform: scale(0.9); }
      50% { opacity: 1; transform: scale(1.1); }
      100% { opacity: 0.4; transform: scale(0.9); }
    }
    .title {
      font-size: 17px;
      font-weight: 700;
      background: linear-gradient(90deg, #00E5FF, #8B5CF6);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .actions {
      display: flex;
      gap: 8px;
    }
    button {
      background: #1A2234;
      border: 1px solid #243048;
      color: #F3F4F6;
      padding: 6px 14px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 6px;
      transition: all 0.2s;
    }
    button:hover {
      background: #243048;
      border-color: #00E5FF;
      color: #00E5FF;
    }
    main {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #000000;
      position: relative;
      overflow: hidden;
    }
    #stream {
      max-width: 100%;
      max-height: 100%;
      object-fit: contain;
      box-shadow: 0 0 40px rgba(0, 229, 255, 0.05);
    }
    .hud {
      position: absolute;
      bottom: 16px;
      left: 16px;
      background: rgba(17, 24, 39, 0.85);
      backdrop-filter: blur(8px);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      padding: 8px 14px;
      font-size: 12px;
      font-family: monospace;
      color: #00E5FF;
      display: flex;
      gap: 16px;
      pointer-events: none;
    }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <div class="badge"><div class="badge-dot"></div>LIVE</div>
      <div class="title">ScreenStream Viewer</div>
    </div>
    <div class="actions">
      <button onclick="downloadSnapshot()">📸 Snapshot</button>
      <button onclick="toggleFullscreen()">⛶ Fullscreen</button>
      <button onclick="reloadStream()">↻ Reload</button>
    </div>
  </header>
  <main id="playerContainer">
    <img id="stream" src="/stream.mjpg" alt="ScreenStream Live Feed" onerror="handleStreamError()" />
    <div class="hud">
      <span id="hudFps">Ultra Low Latency</span>
      <span id="hudClock">--:--:--</span>
    </div>
  </main>
  <script>
    function toggleFullscreen() {
      const container = document.getElementById('playerContainer');
      if (!document.fullscreenElement) {
        container.requestFullscreen().catch(err => alert(err.message));
      } else {
        document.exitFullscreen();
      }
    }
    function reloadStream() {
      const img = document.getElementById('stream');
      img.src = '/stream.mjpg?t=' + Date.now();
    }
    function handleStreamError() {
      setTimeout(reloadStream, 1500);
    }
    function downloadSnapshot() {
      window.open('/snapshot.jpg', '_blank');
    }
    setInterval(() => {
      const d = new Date();
      document.getElementById('hudClock').innerText = d.toTimeString().split(' ')[0] + '.' + String(d.getMilliseconds()).padStart(3, '0');
    }, 50);
  </script>
</body>
</html>"""
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        for (client in webClients) {
            try { client.close() } catch (_: Exception) {}
        }
        webClients.clear()
        _connectedWebViewers.value = 0

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        latestJpegFrame = null
    }
}
