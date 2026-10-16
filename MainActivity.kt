package com.example.excelautodialer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.database.Cursor
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * Minimal XLSX auto-dialer sample.
 * Imports the first worksheet and looks for a column whose header contains "phone",
 * "mobile", "number", or "telephone". If no matching header exists, column 1 is used.
 */
class MainActivity : Activity() {
    private val requestPermissionsCode = 41
    private val pickXlsxCode = 42
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var contacts: List<Contact> = emptyList()
    private var nextIndex = 0
    private var running = false
    private var callWasActive = false
    private var sawRinging = false
    private var listenerRegistered = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null

    data class Contact(val name: String, val number: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        buildUi()
        updateStatus("Import an .xlsx workbook to begin.")
        requestRequiredPermissions()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        val title = TextView(this).apply {
            text = "Excel Auto Dialer"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 22, 0, 22)
        }
        importButton = Button(this).apply {
            text = "Import Excel (.xlsx)"
            setOnClickListener { chooseWorkbook() }
        }
        startButton = Button(this).apply {
            text = "Start automatic calls"
            isEnabled = false
            setOnClickListener { startQueue() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopQueue("Stopped by user.") }
        }
        layout.addView(title, matchWrap())
        layout.addView(status, matchWrap())
        layout.addView(importButton, matchWrap())
        layout.addView(startButton, matchWrap())
        layout.addView(stopButton, matchWrap())
        val note = TextView(this).apply {
            text = "Important: this app places real phone calls. Use only for contacts you are authorized to call. Keep the phone available and stop the queue whenever needed."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(note, matchWrap())
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CALL_PHONE)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_PHONE_STATE)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), requestPermissionsCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionsCode) {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                updateStatus("Call permission is required. You can still import and review the sheet.")
            } else {
                updateStatus("Permissions checked. Import an .xlsx workbook.")
            }
        }
    }

    private fun chooseWorkbook() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, pickXlsxCode)
    }

    @Deprecated("Uses legacy activity result API for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickXlsxCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IllegalStateException("Could not open workbook.")
                contacts = XlsxReader.read(input)
                    .filter { it.number.isNotBlank() }
            }
            nextIndex = 0
            startButton.isEnabled = contacts.isNotEmpty()
            updateStatus("Loaded ${contacts.size} phone number(s). First worksheet only. Check the numbers before starting.")
            if (contacts.isEmpty()) toast("No phone numbers found. Check the header or first column.")
        } catch (e: Exception) {
            contacts = emptyList()
            startButton.isEnabled = false
            updateStatus("Could not read workbook: ${e.message ?: "unsupported XLSX layout"}")
        }
    }

    private fun startQueue() {
        if (contacts.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
            toast("Grant call and phone-state permissions first.")
            return
        }
        if (running) return
        running = true
        startButton.isEnabled = false
        importButton.isEnabled = false
        stopButton.isEnabled = true
        registerCallListener()
        callNext()
    }

    private fun callNext() {
        if (!running) return
        if (nextIndex >= contacts.size) {
            stopQueue("Queue finished. ${contacts.size} row(s) processed.")
            return
        }
        val contact = contacts[nextIndex]
        val normalized = normalizePhone(contact.number)
        if (normalized.isBlank() || normalized == "+") {
            nextIndex++
            updateStatus("Skipped invalid number at row ${nextIndex + 1}.")
            mainHandler.postDelayed({ callNext() }, 700)
            return
        }
        updateStatus("Calling ${nextIndex + 1}/${contacts.size}: ${contact.name.ifBlank { normalized }}\n$normalized\nStop to cancel the remaining queue.")
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", normalized, null))
            startActivity(intent)
        } catch (e: Exception) {
            stopQueue("Could not place call: ${e.message ?: "check permissions and SIM service"}.")
        }
    }

    private fun registerCallListener() {
        if (listenerRegistered) return
        phoneListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        callWasActive = true
                        sawRinging = false
                    }
                    TelephonyManager.CALL_STATE_RINGING -> sawRinging = true
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (running && (callWasActive || sawRinging)) {
                            callWasActive = false
                            sawRinging = false
                            nextIndex++
                            // Small delay lets the telephony UI settle before launching the next call.
                            mainHandler.postDelayed({ if (running) callNext() }, 2500)
                        }
                    }
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            listenerRegistered = true
        } catch (e: SecurityException) {
            stopQueue("Phone-state permission is required to detect call completion.")
        }
    }

    private fun unregisterCallListener() {
        if (listenerRegistered) {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        }
        listenerRegistered = false
        phoneListener = null
    }

    private fun stopQueue(message: String) {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterCallListener()
        startButton.isEnabled = contacts.isNotEmpty()
        importButton.isEnabled = true
        stopButton.isEnabled = false
        updateStatus(message)
    }

    override fun onDestroy() {
        running = false
        unregisterCallListener()
        super.onDestroy()
    }

    private fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // Preserve an initial + and digits only; remove spaces, brackets, hyphens, etc.
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isEmpty()) "" else (if (plus) "+" else "") + digits
    }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/**
 * Lightweight XLSX reader implemented with ZIP + Android XmlPullParser, so no third-party
 * spreadsheet library is needed. Supports ordinary .xlsx shared strings and inline strings.
 * It reads the first worksheet and uses the first row as column headings.
 */
private object XlsxReader {
    fun read(input: java.io.InputStream): List<MainActivity.Contact> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetName = findFirstSheet(entries) ?: "xl/worksheets/sheet1.xml"
        val sheet = entries[sheetName] ?: throw IllegalArgumentException("No worksheet found.")
        val rows = parseSheet(sheet, sharedStrings)
        if (rows.isEmpty()) return emptyList()

        val headers = rows.first().map { it.trim().lowercase() }
        val phoneColumn = headers.indexOfFirst {
            it.contains("phone") || it.contains("mobile") || it.contains("telephone") ||
                it == "number" || it.contains("tel")
        }.let { if (it >= 0) it else 0 }
        val nameColumn = headers.indexOfFirst {
            it == "name" || it.contains("contact") || it.contains("customer")
        }

        return rows.drop(1).mapNotNull { row ->
            val number = row.getOrNull(phoneColumn)?.trim().orEmpty()
            if (number.isBlank() || number.equals(headers.getOrNull(phoneColumn), true)) null
            else MainActivity.Contact(
                name = if (nameColumn >= 0) row.getOrNull(nameColumn)?.trim().orEmpty() else "",
                number = number
            )
        }
    }

    private fun findFirstSheet(entries: Map<String, ByteArray>): String? {
        // Most workbooks use sheet1.xml. Resolve relationship IDs for renamed/reordered sheets.
        val workbook = entries["xl/workbook.xml"] ?: return entries.keys.firstOrNull { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseRelationships(it) } ?: return "xl/worksheets/sheet1.xml"
        val parser = parser(workbook)
        var relId: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                relId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: (0 until parser.attributeCount).mapNotNull { parser.getAttributeValue(it) }.firstOrNull { it.startsWith("rId") }
                break
            }
            parser.next()
        }
        val target = relId?.let { rels[it] } ?: return "xl/worksheets/sheet1.xml"
        return if (target.startsWith("/")) target.removePrefix("/")
        else if (target.startsWith("xl/")) target else "xl/$target"
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val p = parser(bytes)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                if (id != null && target != null) result[id] = target
            }
            p.next()
        }
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val p = parser(bytes)
        var inSi = false
        var inText = false
        val current = StringBuilder()
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "t" -> if (inSi) inText = true
                }
                XmlPullParser.TEXT -> if (inSi && inText) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "si" -> { result.add(current.toString()); inSi = false }
                }
            }
            p.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val p = parser(bytes)
        var currentRow: MutableMap<Int, String>? = null
        var cellIndex = 0
        var cellType = ""
        var value = StringBuilder()
        var inValue = false
        var inInlineText = false
        var inline = StringBuilder()

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> currentRow = mutableMapOf()
                    "c" -> {
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        val ref = p.getAttributeValue(null, "r") ?: ""
                        cellIndex = columnIndex(ref)
                        value = StringBuilder()
                        inline = StringBuilder()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> {
                    if (inValue) value.append(p.text)
                    if (inInlineText) inline.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = if (cellType == "inlineStr") inline.toString() else value.toString()
                        val decoded = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                        currentRow?.set(cellIndex, decoded)
                    }
                    "row" -> {
                        val map = currentRow ?: mutableMapOf()
                        val max = map.keys.maxOrNull() ?: -1
                        result.add((0..max).map { map[it].orEmpty() })
                        currentRow = null
                    }
                }
            }
            p.next()
        }
        return result
    }

    private fun columnIndex(cellRef: String): Int {
        val letters = cellRef.takeWhile { it.isLetter() }.uppercase()
        var result = 0
        letters.forEach { result = result * 26 + (it - 'A' + 1) }
        return (result - 1).coerceAtLeast(0)
    }

    private fun parser(bytes: ByteArray): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(bytes.inputStream(), "UTF-8")
        return p
    }
}
