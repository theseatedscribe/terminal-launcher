package com.terminallauncher.android11

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class OutputType { NORMAL, SYSTEM, APP, ERROR }

data class OutputLine(val text: String, val type: OutputType = OutputType.NORMAL)
data class CommandResult(
    val lines: List<OutputLine>,
    val asyncGeminiPrompt: String? = null
)
data class SuggestionItem(val label: String, val insertText: String, val executeOnTap: Boolean)

class CommandEngine(
    private val context: Context,
    private val appIndex: AppIndexManager
) {
    private val geminiEndpoint = "https://ais-dev-3rk7aibjuy7aq5gqdw6p5w-798489759487.europe-west2.run.app/api/gemini/query"
    private val aliases = mutableMapOf<String, String>()
    private val rootDir: File = if (true) {
        context.getExternalFilesDir(null) ?: context.filesDir
    } else {
        android.os.Environment.getExternalStorageDirectory()
    }
    private var currentDir: File = rootDir
    private var torchOn = false

    private val builtInCommands = listOf(
        "help", "apps", "ls", "cd", "pwd", "cat", "alias",
        "wifi", "bt", "flash", "search", "gemini", "call", "clear"
    )

    init {
        loadBundledAliases()
    }

    private fun loadBundledAliases() {
        runCatching {
            context.assets.open("alias.txt").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                        val idx = line.indexOf("=")
                        aliases[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    }
                }
            }
        }
    }

    fun currentPathDisplay(): String {
        val rel = currentDir.absolutePath.removePrefix(rootDir.absolutePath)
        return if (rel.isEmpty()) "~" else "~$rel"
    }

    fun suggest(rawQuery: String): List<SuggestionItem> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) {
            return aliases.keys.take(4).map { SuggestionItem(it, it, true) } +
                listOf("apps", "wifi", "ls", "help").map { SuggestionItem(it, it, true) }
        }
        val cmdMatches = builtInCommands
            .filter { it.startsWith(q) }
            .map { SuggestionItem(it, "$it ", false) }
        val aliasMatches = aliases.keys
            .filter { it.lowercase().startsWith(q) }
            .map { SuggestionItem("$it→", it, true) }
        val appMatches = appIndex.allApps()
            .filter { it.label.lowercase().contains(q) }
            .take(6)
            .map { SuggestionItem(it.label, it.label, true) }
        return (aliasMatches + cmdMatches + appMatches).take(10)
    }

    fun execute(rawInput: String): CommandResult {
        val expanded = expandAlias(rawInput.trim())
        val parts = expanded.split(Regex("\\s+"))
        val cmd = parts.firstOrNull()?.lowercase().orEmpty()
        val args = parts.drop(1)

        return when (cmd) {
            "help" -> CommandResult(listOf(
                OutputLine("Available commands:", OutputType.SYSTEM),
                OutputLine("  apps          List installed Android 11 packages"),
                OutputLine("  ls / cd / pwd Navigate scoped storage directory"),
                OutputLine("  cat <file>    Read text file"),
                OutputLine("  alias         Show active alias bindings"),
                OutputLine("  wifi / bt     Open Android 11 Settings Panel"),
                OutputLine("  flash         Toggle camera LED flashlight"),
                OutputLine("  call <num>    Open dialer intent"),
                OutputLine("  search <q>    Search the web in default browser")
            ))
            "apps" -> {
                val list = appIndex.allApps()
                CommandResult(
                    listOf(OutputLine("Installed launcher apps (${list.size}):", OutputType.SYSTEM)) +
                        list.map { OutputLine("  ${it.label}  [${it.packageName}]", OutputType.APP) }
                )
            }
            "pwd" -> CommandResult(listOf(OutputLine(currentDir.absolutePath)))
            "ls" -> {
                val files = currentDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                if (files.isEmpty()) {
                    CommandResult(listOf(OutputLine("(empty directory)")))
                } else {
                    CommandResult(files.map {
                        val suffix = if (it.isDirectory) "/" else ""
                        OutputLine("${it.name}$suffix", if (it.isDirectory) OutputType.SYSTEM else OutputType.NORMAL)
                    })
                }
            }
            "cd" -> changeDirectory(args.firstOrNull() ?: "~")
            "cat" -> readFile(args.firstOrNull())
            "alias" -> CommandResult(
                aliases.entries.map { OutputLine("${it.key} = ${it.value}") }
            )
            "wifi" -> openWifiPanel()
            "bt" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CommandResult(listOf(OutputLine("Opened Bluetooth settings.", OutputType.SYSTEM)))
            }
            "flash" -> toggleFlashlight()
            "call" -> {
                val number = args.joinToString(" ")
                if (number.isBlank()) {
                    CommandResult(listOf(OutputLine("Usage: call <phone-number>", OutputType.ERROR)))
                } else {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    CommandResult(listOf(OutputLine("Dialing $number…", OutputType.SYSTEM)))
                }
            }
            "search" -> {
                val q = Uri.encode(args.joinToString(" "))
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=$q"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                CommandResult(listOf(OutputLine("Searching: ${args.joinToString(" ")}", OutputType.SYSTEM)))
            }
            "gemini" -> {
                val prompt = expanded.removePrefix("gemini").trim()
                if (prompt.isEmpty()) {
                    CommandResult(listOf(OutputLine("Usage: gemini <question>", OutputType.ERROR)))
                } else {
                    CommandResult(
                        lines = listOf(OutputLine("Querying Gemini…", OutputType.SYSTEM)),
                        asyncGeminiPrompt = prompt
                    )
                }
            }
            else -> {
                val app = appIndex.findBestMatch(expanded)
                if (app != null && appIndex.launch(app)) {
                    CommandResult(listOf(OutputLine("Launching ${app.label} (${app.packageName})", OutputType.APP)))
                } else {
                    CommandResult(listOf(OutputLine("Command or app not found: $expanded", OutputType.ERROR)))
                }
            }
        }
    }

    fun queryGeminiBackend(prompt: String): CommandResult {
        return runCatching {
            val conn = (URL(geminiEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            val payload = JSONObject().put("prompt", prompt).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()

            if (conn.responseCode in 200..299) {
                val text = json?.optString("text").orEmpty().ifBlank { body }
                CommandResult(text.lines().map { OutputLine(it) })
            } else {
                val err = json?.optString("error").orEmpty().ifBlank { "HTTP ${conn.responseCode}" }
                CommandResult(listOf(OutputLine("Gemini error: $err", OutputType.ERROR)))
            }
        }.getOrElse {
            CommandResult(listOf(OutputLine("Network error: ${it.message}", OutputType.ERROR)))
        }
    }

    private fun expandAlias(input: String): String {
        val first = input.substringBefore(" ")
        val replacement = aliases[first] ?: return input
        val rest = input.removePrefix(first)
        return replacement + rest
    }

    private fun changeDirectory(target: String): CommandResult {
        val next = when (target) {
            "~" -> rootDir
            ".." -> currentDir.parentFile ?: rootDir
            else -> File(currentDir, target)
        }
        return if (next.exists() && next.isDirectory) {
            currentDir = next
            CommandResult(listOf(OutputLine(currentPathDisplay(), OutputType.SYSTEM)))
        } else {
            CommandResult(listOf(OutputLine("Directory not found: $target", OutputType.ERROR)))
        }
    }

    private fun readFile(name: String?): CommandResult {
        if (name.isNullOrBlank()) {
            return CommandResult(listOf(OutputLine("Usage: cat <filename>", OutputType.ERROR)))
        }
        val file = File(currentDir, name)
        return if (file.exists() && file.isFile) {
            CommandResult(file.readLines().take(60).map { OutputLine(it) })
        } else {
            CommandResult(listOf(OutputLine("File not found: $name", OutputType.ERROR)))
        }
    }

    private fun openWifiPanel(): CommandResult {
        // Android 10/11 blocks silent WifiManager.setWifiEnabled(); use Settings Panel
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return CommandResult(listOf(OutputLine("Opened Android 11 Wi-Fi panel.", OutputType.SYSTEM)))
    }

    private fun toggleFlashlight(): CommandResult {
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return CommandResult(listOf(OutputLine("No torch hardware found.", OutputType.ERROR)))
            torchOn = !torchOn
            cm.setTorchMode(cameraId, torchOn)
            CommandResult(listOf(OutputLine("Flashlight ${if (torchOn) "ON" else "OFF"}", OutputType.SYSTEM)))
        }.getOrElse {
            CommandResult(listOf(OutputLine("Torch error: ${it.message}", OutputType.ERROR)))
        }
    }
}
