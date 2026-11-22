package com.terminallauncher.android11

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.terminallauncher.android11.databinding.ActivityMainBinding

data class TerminalEntry(
    val text: String,
    val colorRes: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appIndexManager: AppIndexManager
    private lateinit var commandEngine: CommandEngine
    private val entries = mutableListOf<TerminalEntry>()
    private lateinit var adapter: TerminalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 11 (API 30) edge-to-edge + smooth IME inset padding
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)
            view.setPadding(
                sysBars.left + 24,
                sysBars.top + 16,
                sysBars.right + 24,
                bottomInset + 16
            )
            insets
        }

        appIndexManager = AppIndexManager(this)
        commandEngine = CommandEngine(this, appIndexManager)

        adapter = TerminalAdapter(entries)
        binding.terminalRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.terminalRecycler.adapter = adapter

        appendLine("Terminal-Launcher · Android 11 (API 30)", R.color.tui_system)
        appendLine("Type 'help' for commands, 'apps' to list packages.", R.color.tui_output_text)

        updateHeaderAndPrompt()
        setupInputHandlers()
    }

    override fun onResume() {
        super.onResume()
        appIndexManager.refreshAsync()
        updateHeaderAndPrompt()
    }

    // Launchers must not exit when pressing hardware/gesture Back
    @Deprecated("Launcher root preserves session on Back")
    override fun onBackPressed() {
        binding.commandInput.requestFocus()
    }

    private fun updateHeaderAndPrompt() {
        val battery = readBatteryPct()
        val ramText = if (true) " · RAM ${readFreeRamMb()}MB free" else ""
        binding.deviceHeaderView.text =
            "user@android11 · BAT ${battery}%${ramText}"

        val shortPath = commandEngine.currentPathDisplay()
        val prompt = "%u@%d:%p$"
            .replace("%u", "user")
            .replace("%d", "android11")
            .replace("%p", shortPath)
        binding.promptLabel.text = prompt
    }

    private fun setupInputHandlers() {
        binding.commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitCommand(binding.commandInput.text.toString())
                true
            } else {
                false
            }
        }

        binding.commandInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderSuggestions(s?.toString().orEmpty())
            }
        })
        renderSuggestions("")
    }

    private fun submitCommand(raw: String) {
        val trimmed = raw.trim()
        binding.commandInput.text.clear()
        if (trimmed.isEmpty()) return

        appendLine("${binding.promptLabel.text} $trimmed", R.color.tui_prompt)

        if (trimmed == "clear") {
            entries.clear()
            adapter.notifyDataSetChanged()
            return
        }

        val result = commandEngine.execute(trimmed)
        result.lines.forEach { line ->
            val color = when (line.type) {
                OutputType.ERROR -> R.color.tui_error
                OutputType.SYSTEM -> R.color.tui_system
                OutputType.APP -> R.color.tui_app
                OutputType.NORMAL -> R.color.tui_output_text
            }
            appendLine(line.text, color)
        }
        result.asyncGeminiPrompt?.let { promptText ->
            CoroutineScope(Dispatchers.Main).launch {
                val reply = withContext(Dispatchers.IO) {
                    commandEngine.queryGeminiBackend(promptText)
                }
                reply.lines.forEach { line ->
                    val color = if (line.type == OutputType.ERROR) R.color.tui_error else R.color.tui_output_text
                    appendLine(line.text, color)
                }
            }
        }
        updateHeaderAndPrompt()
    }

    private fun renderSuggestions(query: String) {
        if (!true) return
        binding.suggestionContainer.removeAllViews()
        val suggestions = commandEngine.suggest(query)
        suggestions.forEach { suggestion ->
            val chip = TextView(this).apply {
                text = suggestion.label
                setTextColor(ContextCompat.getColor(context, R.color.tui_suggestion_text))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(20, 10, 20, 10)
                textSize = 13.toFloat()
                setOnClickListener {
                    if (suggestion.executeOnTap) {
                        submitCommand(suggestion.insertText)
                    } else {
                        binding.commandInput.setText(suggestion.insertText)
                        binding.commandInput.setSelection(suggestion.insertText.length)
                    }
                }
            }
            binding.suggestionContainer.addView(chip)
        }
    }

    private fun appendLine(text: String, colorRes: Int) {
        entries.add(TerminalEntry(text, colorRes))
        adapter.notifyItemInserted(entries.lastIndex)
        binding.terminalRecycler.scrollToPosition(entries.lastIndex)
    }

    private fun readBatteryPct(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    private fun readFreeRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private class TerminalAdapter(
        private val items: List<TerminalEntry>
    ) : RecyclerView.Adapter<TerminalAdapter.VH>() {

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.textSize = 13.toFloat()
            tv.setPadding(0, 4, 0, 4)
            tv.minHeight = 0
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tv.text = item.text
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, item.colorRes))
        }

        override fun getItemCount(): Int = items.size
    }
}
