package com.bbkcrypto.cipherlab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CipherLabApp() }
    }
}

@Composable
private fun CipherLabApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF10131A)) {
            CipherScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CipherScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cipherlab", Context.MODE_PRIVATE) }
    val algorithms = CipherTool.specs.map { it.name }
    var input by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(algorithms.first()) }
    var search by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var algorithmExpanded by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(ThemePalette.byName(prefs.getString("theme", null))) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var output by remember { mutableStateOf("") }
    var pendingFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingFileName by remember { mutableStateOf("cipherlab.bin") }
    val spec = CipherTool.specFor(algorithm)
    val filteredSpecs = CipherTool.specs.filter {
        (!onlyFavorites || it.name in favorites) && (search.isBlank() || it.name.contains(search, true) || it.category.contains(search, true))
    }

    val createFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingFileBytes
        if (uri != null && bytes != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(context, "Dosya kaydedildi", Toast.LENGTH_SHORT).show()
        }
    }
    val encryptFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            require(key.length >= 6) { "Dosya için anahtar alanına en az 6 karakter parola yaz." }
            pendingFileBytes = FileCrypto.encrypt(context.contentResolver.openInputStream(uri)!!.readBytes(), key)
            pendingFileName = "cipherlab-encrypted.clf"
            createFileLauncher.launch(pendingFileName)
        }.onFailure { Toast.makeText(context, it.message ?: "Hata", Toast.LENGTH_LONG).show() }
    }
    val decryptFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            require(key.length >= 6) { "Dosya için anahtar alanına en az 6 karakter parola yaz." }
            pendingFileBytes = FileCrypto.decrypt(context.contentResolver.openInputStream(uri)!!.readBytes(), key)
            pendingFileName = "cipherlab-decrypted.bin"
            createFileLauncher.launch(pendingFileName)
        }.onFailure { Toast.makeText(context, it.message ?: "Hata", Toast.LENGTH_LONG).show() }
    }
    val createQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(QrPng.create(input.ifBlank { output })) }
            Toast.makeText(context, "QR PNG kaydedildi", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, it.message ?: "Hata", Toast.LENGTH_LONG).show() }
    }

    fun runTool(decode: Boolean) {
        val result = runCatching {
            if (algorithm.startsWith("Vault ")) VaultTool.run(context, algorithm, input, key)
            else if (decode) CipherTool.decode(algorithm, input, key) else CipherTool.encode(algorithm, input, key)
        }.getOrElse { it.message ?: "Hata" }
        output = result
        history = (listOf(HistoryEntry(System.currentTimeMillis(), if (decode) "DECODE" else "ENCODE", algorithm, input.take(140), result.take(140))) + history)
            .filter { System.currentTimeMillis() - it.time < HISTORY_TTL_MS }
            .take(30)
        saveHistory(prefs, history)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.background, palette.background2)))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CipherLab v2", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = palette.accent)
        Text("Renkli temalar, Türkçe Morse, 24 saatlik log ve akıllı anahtar alanı.", color = palette.text)

        ExposedDropdownMenuBox(expanded = themeExpanded, onExpandedChange = { themeExpanded = !themeExpanded }) {
            OutlinedTextField(
                value = palette.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Tema") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(themeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                ThemePalette.all.forEach {
                    DropdownMenuItem(text = { Text(it.name) }, onClick = {
                        palette = it
                        prefs.edit().putString("theme", it.name).apply()
                        themeExpanded = false
                    })
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Metin") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Algoritma Ara") },
            supportingText = { Text("Örn: morse, aes, auto, json, playfair") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                favorites = if (algorithm in favorites) favorites - algorithm else favorites + algorithm
                prefs.edit().putStringSet("favorites", favorites).apply()
            }, modifier = Modifier.weight(1f)) { Text(if (algorithm in favorites) "Favoriden Çıkar" else "Favoriye Ekle") }
            OutlinedButton(onClick = { onlyFavorites = !onlyFavorites }, modifier = Modifier.weight(1f)) { Text(if (onlyFavorites) "Tümünü Göster" else "Favoriler") }
        }

        ExposedDropdownMenuBox(expanded = algorithmExpanded, onExpandedChange = { algorithmExpanded = !algorithmExpanded }) {
            OutlinedTextField(
                value = algorithm,
                onValueChange = {},
                readOnly = true,
                label = { Text("Algoritma") },
                supportingText = { Text(spec.category) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(algorithmExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = algorithmExpanded, onDismissRequest = { algorithmExpanded = false }) {
                filteredSpecs.forEach {
                    DropdownMenuItem(text = { Text("${it.name}  ·  ${it.category}") }, onClick = {
                        algorithm = it.name
                        key = it.defaultKey
                        algorithmExpanded = false
                    })
                }
                if (filteredSpecs.isEmpty()) {
                    DropdownMenuItem(text = { Text("Sonuç yok") }, onClick = { })
                }
            }
        }

        if (spec.keyLabel != null) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(spec.keyLabel) },
                supportingText = { Text(spec.hint) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("Bu algoritma anahtar istemez.", color = palette.accent)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { runTool(false) }, modifier = Modifier.weight(1f)) {
                Text("Encode / Şifrele")
            }
            Button(onClick = { runTool(true) }, modifier = Modifier.weight(1f)) {
                Text("Decode / Çöz")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = palette.card), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Sonuç", fontWeight = FontWeight.Bold, color = palette.accent)
                Spacer(Modifier.height(8.dp))
                Text(output.ifBlank { "Çıktı burada görünecek." }, color = palette.text)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { copy(context, output) }, modifier = Modifier.weight(1f)) { Text("Kopyala") }
            OutlinedButton(onClick = { share(context, output) }, modifier = Modifier.weight(1f)) { Text("Paylaş") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { input = output }, modifier = Modifier.weight(1f)) { Text("Sonucu Input Yap") }
            OutlinedButton(onClick = { input = ""; output = "" }, modifier = Modifier.weight(1f)) { Text("Temizle") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = palette.card), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Araçlar", fontWeight = FontWeight.Bold, color = palette.accent)
                Text("Dosya işlemleri anahtar alanındaki parolayı kullanır.", color = palette.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { encryptFileLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("Dosya Şifrele") }
                    OutlinedButton(onClick = { decryptFileLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("Dosya Çöz") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { createQrLauncher.launch("cipherlab-qr.png") }, modifier = Modifier.weight(1f)) { Text("QR PNG Kaydet") }
                    OutlinedButton(onClick = { BiometricGate.authenticate(context) { output = it } }, modifier = Modifier.weight(1f)) { Text("Parmak İzi Test") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = palette.card), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("24 Saatlik Log", fontWeight = FontWeight.Bold, color = palette.accent)
                if (history.isEmpty()) Text("Henüz kayıt yok.", color = palette.text)
                history.take(8).forEach {
                    Text("${it.mode} · ${it.algorithm}\n${it.input} -> ${it.output}", color = palette.text)
                }
                OutlinedButton(onClick = { share(context, history.joinToString("\n\n") { "${it.mode} · ${it.algorithm}\nIN: ${it.input}\nOUT: ${it.output}" }) }, modifier = Modifier.fillMaxWidth()) { Text("Logu Dışa Aktar") }
                OutlinedButton(onClick = { history = emptyList(); saveHistory(prefs, history) }, modifier = Modifier.fillMaxWidth()) { Text("Logu Temizle") }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CipherLab", text))
    Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "Paylaş"))
}

private const val HISTORY_TTL_MS = 24L * 60L * 60L * 1000L

private data class HistoryEntry(val time: Long, val mode: String, val algorithm: String, val input: String, val output: String)

private data class AlgorithmSpec(val name: String, val category: String, val keyLabel: String? = null, val hint: String = "", val defaultKey: String = "")

private data class ThemePalette(val name: String, val background: Color, val background2: Color, val card: Color, val accent: Color, val text: Color) {
    companion object {
        val all = listOf(
            ThemePalette("Cyber Neon", Color(0xFF060814), Color(0xFF17122E), Color(0xFF171A2B), Color(0xFF00E5FF), Color(0xFFEAFBFF)),
            ThemePalette("Matrix Green", Color(0xFF020804), Color(0xFF062011), Color(0xFF0B1F14), Color(0xFF00FF66), Color(0xFFE8FFE8)),
            ThemePalette("Ocean Blue", Color(0xFF06131F), Color(0xFF063B57), Color(0xFF102B3C), Color(0xFF39C6FF), Color(0xFFE9F8FF)),
            ThemePalette("Sunset", Color(0xFF241025), Color(0xFF512018), Color(0xFF382038), Color(0xFFFF9A3D), Color(0xFFFFF1E7)),
            ThemePalette("Blood Red", Color(0xFF120306), Color(0xFF30070E), Color(0xFF230D12), Color(0xFFFF3355), Color(0xFFFFECEF)),
            ThemePalette("Purple Hacker", Color(0xFF12051F), Color(0xFF35135B), Color(0xFF231138), Color(0xFFE060FF), Color(0xFFFBEEFF)),
            ThemePalette("Gold Luxury", Color(0xFF0D0A04), Color(0xFF2D2108), Color(0xFF211A0D), Color(0xFFFFC857), Color(0xFFFFF7DD)),
            ThemePalette("Ice Cyan", Color(0xFF071820), Color(0xFF103D4A), Color(0xFF102B32), Color(0xFF8DF6FF), Color(0xFFEFFFFF)),
            ThemePalette("Terminal Classic", Color(0xFF000000), Color(0xFF091109), Color(0xFF071407), Color(0xFF9DFF7A), Color(0xFFE8FFE0)),
            ThemePalette("Turkish Flag", Color(0xFF220005), Color(0xFF8A0014), Color(0xFF35050C), Color(0xFFFFFFFF), Color(0xFFFFF1F1)),
            ThemePalette("Royal Indigo", Color(0xFF090A25), Color(0xFF25206A), Color(0xFF171846), Color(0xFF8EA2FF), Color(0xFFF0F2FF)),
            ThemePalette("Toxic Lime", Color(0xFF090F02), Color(0xFF263B05), Color(0xFF172407), Color(0xFFC6FF00), Color(0xFFF6FFD8))
        )
        fun byName(name: String?) = all.firstOrNull { it.name == name } ?: all.first()
    }
}

private fun loadHistory(prefs: SharedPreferences): List<HistoryEntry> = prefs.getString("history", "").orEmpty()
    .lineSequence()
    .mapNotNull { line ->
        val p = line.split('\u001F')
        if (p.size == 5) HistoryEntry(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3], p[4]) else null
    }
    .filter { System.currentTimeMillis() - it.time < HISTORY_TTL_MS }
    .toList()

private fun saveHistory(prefs: SharedPreferences, history: List<HistoryEntry>) {
    fun clean(value: String) = value.replace('\n', ' ').replace('\u001F', ' ')
    prefs.edit().putString("history", history.joinToString("\n") { listOf(it.time.toString(), it.mode, it.algorithm, clean(it.input), clean(it.output)).joinToString("\u001F") }).apply()
}

private object VaultTool {
    fun run(context: Context, name: String, input: String, key: String): String {
        val prefs = context.getSharedPreferences("cipherlab_vault", Context.MODE_PRIVATE)
        fun titlePassword(): Pair<String, String> {
            val parts = key.split('|', limit = 2)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].length >= 6) { "Anahtar formatı: başlık|enAz6KarakterParola" }
            return parts[0].trim() to parts[1]
        }
        return when (name) {
            "Vault Save" -> {
                val (title, password) = titlePassword()
                val titles = prefs.getStringSet("titles", emptySet()).orEmpty() + title
                prefs.edit().putString("note_$title", CipherTool.encode("AES-256-GCM", input, password)).putStringSet("titles", titles).apply()
                "Not şifreli kasaya kaydedildi: $title"
            }
            "Vault List" -> prefs.getStringSet("titles", emptySet()).orEmpty().sorted().joinToString("\n").ifBlank { "Kasa boş." }
            "Vault Read" -> {
                val (title, password) = titlePassword()
                val encrypted = prefs.getString("note_$title", null) ?: error("Not bulunamadı: $title")
                CipherTool.decode("AES-256-GCM", encrypted, password)
            }
            "Vault Delete" -> {
                val title = key.trim()
                require(title.isNotEmpty()) { "Silmek için başlık gir." }
                val titles = prefs.getStringSet("titles", emptySet()).orEmpty() - title
                prefs.edit().remove("note_$title").putStringSet("titles", titles).apply()
                "Silindi: $title"
            }
            "Vault Wipe" -> {
                require(key == "WIPE") { "Tüm kasayı silmek için anahtar alanına WIPE yaz." }
                prefs.edit().clear().apply()
                "Kasa tamamen silindi."
            }
            else -> "Bilinmeyen kasa işlemi"
        }
    }
}

private object CipherTool {
    val specs = listOf(
        AlgorithmSpec("Base64", "Encode"), AlgorithmSpec("Base32", "Encode"), AlgorithmSpec("Base58", "Encode"), AlgorithmSpec("Hex", "Encode"), AlgorithmSpec("Binary", "Encode"), AlgorithmSpec("ASCII Decimal", "Encode"),
        AlgorithmSpec("URL", "Encode"), AlgorithmSpec("HTML Entity", "Encode"), AlgorithmSpec("Unicode Escape", "Encode"), AlgorithmSpec("Auto Decode Lab", "Analiz"), AlgorithmSpec("Deep Decode Scan", "Analiz"), AlgorithmSpec("Text Analyzer", "Analiz"), AlgorithmSpec("ROT47", "Klasik"),
        AlgorithmSpec("Caesar", "Klasik", "Sayı", "Örn: 3", "3"), AlgorithmSpec("Turkish Caesar", "Klasik", "Sayı", "Türk alfabesi için öteleme. Örn: 3", "3"), AlgorithmSpec("Caesar Brute Force", "Klasik"),
        AlgorithmSpec("ROT13", "Klasik"), AlgorithmSpec("Atbash", "Klasik"), AlgorithmSpec("Vigenere", "Klasik", "Anahtar", "Harfli anahtar. Örn: gizli"), AlgorithmSpec("XOR", "Klasik", "Anahtar", "Metin anahtarı"),
        AlgorithmSpec("Affine", "Klasik", "A,B", "Örn: 5,8. A değeri 26 ile aralarında asal olmalı.", "5,8"), AlgorithmSpec("Rail Fence", "Klasik", "Ray sayısı", "Örn: 3", "3"),
        AlgorithmSpec("Playfair", "Klasik", "Anahtar", "Örn: secret"), AlgorithmSpec("Beaufort", "Klasik", "Anahtar", "Örn: secret"), AlgorithmSpec("Gronsfeld", "Klasik", "Sayı anahtarı", "Örn: 31415"),
        AlgorithmSpec("Bacon", "Klasik"), AlgorithmSpec("Polybius", "Klasik"), AlgorithmSpec("Tap Code", "Klasik"), AlgorithmSpec("Columnar", "Klasik", "Anahtar", "Örn: zebra"), AlgorithmSpec("Scytale", "Klasik", "Sütun", "Örn: 5", "5"),
        AlgorithmSpec("Autokey Vigenere", "Klasik", "Anahtar", "Örn: secret"), AlgorithmSpec("One-Time Pad", "Klasik", "Anahtar", "Metin kadar uzun anahtar"), AlgorithmSpec("Porta", "Klasik", "Anahtar", "Örn: secret"), AlgorithmSpec("Nihilist", "Klasik", "Anahtar", "polybiusKey|numericKey", "cipher|31415"), AlgorithmSpec("ADFGX", "Klasik", "Anahtar", "Polybius|Columnar", "cipher|zebra"), AlgorithmSpec("Hill 2x2", "Klasik", "a,b,c,d", "Örn: 3,3,2,5", "3,3,2,5"), AlgorithmSpec("Bifid", "Klasik"), AlgorithmSpec("Trifid", "Klasik"), AlgorithmSpec("Route Cipher", "Klasik", "Sütun", "Örn: 5", "5"), AlgorithmSpec("Book Cipher", "Klasik", "Kitap metni", "Anahtar alanına kitap metni, input kelimeler"), AlgorithmSpec("Pigpen", "Alfabe"),
        AlgorithmSpec("A1Z26", "Alfabe"), AlgorithmSpec("Reverse Text", "Alfabe"), AlgorithmSpec("Mirror Text", "Alfabe"), AlgorithmSpec("Upside Down", "Alfabe"), AlgorithmSpec("Leet", "Alfabe"), AlgorithmSpec("Morse", "Alfabe"), AlgorithmSpec("NATO", "Alfabe"),
        AlgorithmSpec("Invisible Text", "Gizli Mesaj"), AlgorithmSpec("Zero Width", "Gizli Mesaj"), AlgorithmSpec("Zalgo", "Gizli Mesaj"), AlgorithmSpec("Fullwidth", "Font"), AlgorithmSpec("Small Caps", "Font"), AlgorithmSpec("Bubble Text", "Font"), AlgorithmSpec("Random Case", "Font"),
        AlgorithmSpec("QR Generate", "Paylaşım"), AlgorithmSpec("JWT Decode", "CyberChef"), AlgorithmSpec("JSON Pretty", "CyberChef"), AlgorithmSpec("JSON Minify", "CyberChef"), AlgorithmSpec("XML Pretty", "CyberChef"), AlgorithmSpec("URL Parser", "CyberChef"), AlgorithmSpec("Regex Tester", "CyberChef", "Regex", "Örn: [a-z]+"), AlgorithmSpec("Timestamp", "CyberChef"), AlgorithmSpec("UUID Generator", "CyberChef"), AlgorithmSpec("Hexdump", "CyberChef"), AlgorithmSpec("IP Subnet", "CyberChef", "CIDR", "Örn: 192.168.1.0/24"), AlgorithmSpec("CRC32", "Checksum"),
        AlgorithmSpec("Vault Save", "Şifreli Kasa", "Başlık|Parola", "Input not içeriği. Anahtar: baslik|parola"), AlgorithmSpec("Vault List", "Şifreli Kasa"), AlgorithmSpec("Vault Read", "Şifreli Kasa", "Başlık|Parola", "Anahtar: baslik|parola"), AlgorithmSpec("Vault Delete", "Şifreli Kasa", "Başlık", "Silinecek not başlığı"), AlgorithmSpec("Vault Wipe", "Şifreli Kasa", "WIPE", "Tüm kasayı silmek için WIPE yaz"),
        AlgorithmSpec("Password Generator", "Güvenlik", "Uzunluk", "Örn: 24", "24"), AlgorithmSpec("Password Strength", "Güvenlik"), AlgorithmSpec("HMAC-SHA256", "Hash", "Anahtar", "HMAC anahtarı"),
        AlgorithmSpec("MD5", "Hash"), AlgorithmSpec("SHA-1", "Hash"), AlgorithmSpec("SHA-256", "Hash"), AlgorithmSpec("SHA-512", "Hash"),
        AlgorithmSpec("AES-256-GCM", "Güvenli", "Parola", "En az 6 karakter parola"), AlgorithmSpec("Enigma I", "Tarihi", "Enigma Ayarı", "I II III|B|01 01 01|AAA|AB CD EF", "I II III|B|01 01 01|AAA|")
    )
    fun specFor(name: String) = specs.firstOrNull { it.name == name } ?: specs.first()

    fun encode(name: String, input: String, key: String) = when (name) {
        "Base64" -> Base64.getEncoder().encodeToString(input.toByteArray())
        "Base32" -> base32Encode(input.toByteArray())
        "Base58" -> base58Encode(input.toByteArray())
        "Hex" -> input.toByteArray().joinToString("") { "%02x".format(it) }
        "Binary" -> input.toByteArray().joinToString(" ") { it.toInt().and(255).toString(2).padStart(8, '0') }
        "ASCII Decimal" -> input.toByteArray().joinToString(" ") { it.toInt().and(255).toString() }
        "URL" -> URLEncoder.encode(input, StandardCharsets.UTF_8.name())
        "HTML Entity" -> input.map { "&#${it.code};" }.joinToString("")
        "Unicode Escape" -> input.map { "\\u%04x".format(it.code) }.joinToString("")
        "Auto Decode Lab" -> autoDecode(input)
        "Deep Decode Scan" -> deepDecode(input)
        "Text Analyzer" -> analyzeText(input)
        "ROT47" -> rot47(input)
        "Caesar" -> caesar(input, key.toIntOrNull() ?: 3)
        "Turkish Caesar" -> alphabetShift(input, key.toIntOrNull() ?: 3, "abcçdefgğhıijklmnoöprsştuüvyz", "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ")
        "Caesar Brute Force" -> (0..25).joinToString("\n") { "$it: ${caesar(input, -it)}" }
        "ROT13" -> caesar(input, 13)
        "Atbash" -> atbash(input)
        "Vigenere" -> vigenere(input, key, false)
        "XOR" -> Base64.getEncoder().encodeToString(xorBytes(input.toByteArray(), key))
        "Affine" -> affine(input, key, false)
        "Rail Fence" -> railFenceEncode(input, key.toIntOrNull() ?: 3)
        "Playfair" -> playfair(input, key, false)
        "Beaufort" -> beaufort(input, key)
        "Gronsfeld" -> gronsfeld(input, key, false)
        "Bacon" -> baconEncode(input)
        "Polybius" -> polybiusEncode(input)
        "Tap Code" -> tapCodeEncode(input)
        "Columnar" -> columnarEncode(input, key)
        "Scytale" -> scytaleEncode(input, key.toIntOrNull() ?: 5)
        "Autokey Vigenere" -> autokey(input, key, false)
        "One-Time Pad" -> oneTimePad(input, key, false)
        "Porta" -> porta(input, key)
        "Nihilist" -> nihilist(input, key, false)
        "ADFGX" -> adfgx(input, key, false)
        "Hill 2x2" -> hill2x2(input, key, false)
        "Bifid" -> bifid(input, false)
        "Trifid" -> trifid(input, false)
        "Route Cipher" -> routeEncode(input, key.toIntOrNull() ?: 5)
        "Book Cipher" -> bookEncode(input, key)
        "Pigpen" -> pigpenEncode(input)
        "A1Z26" -> a1z26Encode(input)
        "Reverse Text" -> input.reversed()
        "Mirror Text" -> mirrorText(input)
        "Upside Down" -> upsideDown(input)
        "Leet" -> leet(input)
        "Morse" -> morseEncode(input)
        "NATO" -> natoEncode(input)
        "Invisible Text" -> invisibleEncode(input)
        "Zero Width" -> zeroWidthEncode(input)
        "Zalgo" -> zalgo(input)
        "Fullwidth" -> fullwidth(input)
        "Small Caps" -> smallCaps(input)
        "Bubble Text" -> bubbleText(input)
        "Random Case" -> randomCase(input)
        "QR Generate" -> qrAscii(input)
        "JWT Decode" -> jwtDecode(input)
        "JSON Pretty" -> jsonPretty(input)
        "JSON Minify" -> jsonMinify(input)
        "XML Pretty" -> xmlPretty(input)
        "URL Parser" -> urlParser(input)
        "Regex Tester" -> regexTester(input, key)
        "Timestamp" -> timestampTool(input)
        "UUID Generator" -> List((input.toIntOrNull() ?: 5).coerceIn(1, 50)) { UUID.randomUUID().toString() }.joinToString("\n")
        "Hexdump" -> hexdump(input)
        "IP Subnet" -> ipSubnet(input.ifBlank { key })
        "CRC32" -> crc32(input)
        "Password Generator" -> passwordGenerator(key.toIntOrNull() ?: 24)
        "Password Strength" -> passwordStrength(input)
        "HMAC-SHA256" -> hmacSha256(input, key)
        "MD5" -> digest(input, "MD5")
        "SHA-1" -> digest(input, "SHA-1")
        "SHA-256" -> digest(input, "SHA-256")
        "SHA-512" -> digest(input, "SHA-512")
        "AES-256-GCM" -> aesEncrypt(input, key)
        "Enigma I" -> Enigma.from(key).process(input)
        else -> input
    }

    fun decode(name: String, input: String, key: String) = when (name) {
        "Base64" -> String(Base64.getDecoder().decode(input.trim()))
        "Base32" -> String(base32Decode(input))
        "Base58" -> String(base58Decode(input))
        "Hex" -> String(input.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        "Binary" -> String(input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(2).toByte() }.toByteArray())
        "ASCII Decimal" -> String(input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt().toByte() }.toByteArray())
        "URL" -> URLDecoder.decode(input, StandardCharsets.UTF_8.name())
        "HTML Entity" -> htmlDecode(input)
        "Unicode Escape" -> unicodeDecode(input)
        "Auto Decode Lab" -> autoDecode(input)
        "Deep Decode Scan" -> deepDecode(input)
        "Text Analyzer" -> analyzeText(input)
        "ROT47" -> rot47(input)
        "Caesar" -> caesar(input, -(key.toIntOrNull() ?: 3))
        "Turkish Caesar" -> alphabetShift(input, -(key.toIntOrNull() ?: 3), "abcçdefgğhıijklmnoöprsştuüvyz", "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ")
        "Caesar Brute Force" -> (0..25).joinToString("\n") { "$it: ${caesar(input, -it)}" }
        "ROT13" -> caesar(input, 13)
        "Atbash" -> atbash(input)
        "Vigenere" -> vigenere(input, key, true)
        "XOR" -> String(xorBytes(Base64.getDecoder().decode(input.trim()), key))
        "Affine" -> affine(input, key, true)
        "Rail Fence" -> railFenceDecode(input, key.toIntOrNull() ?: 3)
        "Playfair" -> playfair(input, key, true)
        "Beaufort" -> beaufort(input, key)
        "Gronsfeld" -> gronsfeld(input, key, true)
        "Bacon" -> baconDecode(input)
        "Polybius" -> polybiusDecode(input)
        "Tap Code" -> tapCodeDecode(input)
        "Columnar" -> columnarDecode(input, key)
        "Scytale" -> scytaleDecode(input, key.toIntOrNull() ?: 5)
        "Autokey Vigenere" -> autokey(input, key, true)
        "One-Time Pad" -> oneTimePad(input, key, true)
        "Porta" -> porta(input, key)
        "Nihilist" -> nihilist(input, key, true)
        "ADFGX" -> adfgx(input, key, true)
        "Hill 2x2" -> hill2x2(input, key, true)
        "Bifid" -> bifid(input, true)
        "Trifid" -> trifid(input, true)
        "Route Cipher" -> routeDecode(input, key.toIntOrNull() ?: 5)
        "Book Cipher" -> bookDecode(input, key)
        "Pigpen" -> pigpenDecode(input)
        "A1Z26" -> a1z26Decode(input)
        "Reverse Text" -> input.reversed()
        "Mirror Text" -> mirrorText(input)
        "Upside Down" -> upsideDown(input)
        "Leet" -> input
        "Morse" -> morseDecode(input)
        "NATO" -> natoDecode(input)
        "Invisible Text" -> invisibleDecode(input)
        "Zero Width" -> zeroWidthDecode(input)
        "Zalgo" -> stripZalgo(input)
        "Fullwidth" -> unFullwidth(input)
        "Small Caps" -> input
        "Bubble Text" -> unBubbleText(input)
        "Random Case" -> input.lowercase(Locale.ROOT)
        "QR Generate" -> qrAscii(input)
        "JWT Decode" -> jwtDecode(input)
        "JSON Pretty" -> jsonPretty(input)
        "JSON Minify" -> jsonMinify(input)
        "XML Pretty" -> xmlPretty(input)
        "URL Parser" -> urlParser(input)
        "Regex Tester" -> regexTester(input, key)
        "Timestamp" -> timestampTool(input)
        "UUID Generator" -> UUID.randomUUID().toString()
        "Hexdump" -> hexdump(input)
        "IP Subnet" -> ipSubnet(input.ifBlank { key })
        "CRC32" -> crc32(input)
        "Password Generator" -> passwordGenerator(key.toIntOrNull() ?: 24)
        "Password Strength" -> passwordStrength(input)
        "HMAC-SHA256" -> hmacSha256(input, key)
        "MD5", "SHA-1", "SHA-256", "SHA-512" -> "Hash tek yönlüdür, decode edilemez."
        "AES-256-GCM" -> aesDecrypt(input, key)
        "Enigma I" -> Enigma.from(key).process(input)
        else -> input
    }

    private fun caesar(text: String, shift: Int): String = text.map { c ->
        val base = when (c) { in 'a'..'z' -> 'a'; in 'A'..'Z' -> 'A'; else -> return@map c }
        (base.code + positiveMod(c.code - base.code + shift, 26)).toChar()
    }.joinToString("")

    private fun alphabetShift(text: String, shift: Int, lower: String, upper: String) = text.map { c ->
        val alphabet = when (c) { in lower -> lower; in upper -> upper; else -> return@map c }
        alphabet[positiveMod(alphabet.indexOf(c) + shift, alphabet.length)]
    }.joinToString("")

    private fun atbash(text: String) = text.map { c ->
        when (c) { in 'a'..'z' -> ('z'.code - (c.code - 'a'.code)).toChar(); in 'A'..'Z' -> ('Z'.code - (c.code - 'A'.code)).toChar(); else -> c }
    }.joinToString("")

    private fun vigenere(text: String, key: String, decrypt: Boolean): String {
        require(key.any { it.isLetter() }) { "Vigenere için harfli anahtar gir." }
        val shifts = key.filter { it.isLetter() }.map { it.uppercaseChar().code - 'A'.code }
        var i = 0
        return text.map { c ->
            if (!c.isLetter()) c else caesar(c.toString(), shifts[i++ % shifts.size] * if (decrypt) -1 else 1).first()
        }.joinToString("")
    }

    private fun xorBytes(bytes: ByteArray, key: String): ByteArray {
        require(key.isNotEmpty()) { "XOR için anahtar gir." }
        val keyBytes = key.toByteArray()
        return bytes.mapIndexed { i, b -> (b.toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }.toByteArray()
    }

    private fun leet(text: String) = text.map { c ->
        when (c.lowercaseChar()) { 'a' -> '4'; 'e' -> '3'; 'i' -> '1'; 'o' -> '0'; 's' -> '5'; 't' -> '7'; else -> c }
    }.joinToString("")

    private fun htmlDecode(text: String) = Regex("&#(\\d+);").replace(text) { it.groupValues[1].toInt().toChar().toString() }
    private fun unicodeDecode(text: String) = Regex("\\\\u([0-9a-fA-F]{4})").replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
    private fun rot47(text: String) = text.map { if (it.code in 33..126) (33 + positiveMod(it.code - 33 + 47, 94)).toChar() else it }.joinToString("")
    private fun digest(text: String, algorithm: String) = MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun autoDecode(input: String): String {
        val candidates = decodeCandidates(input)
            .distinctBy { it.second }
            .sortedByDescending { scoreText(it.second) }
            .take(35)
        if (candidates.isEmpty()) return "Net bir decode adayı bulunamadı. Text Analyzer ile karakter yapısına bak."
        return buildString {
            appendLine("Auto Decode Lab")
            appendLine("Olası sonuçlar en okunabilirden aşağı sıralandı.")
            appendLine()
            candidates.forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.first}  | skor ${scoreText(item.second)}")
                appendLine(item.second.preview())
                appendLine()
            }
        }
    }

    private fun deepDecode(input: String): String {
        data class Node(val path: String, val text: String, val depth: Int)
        val seen = mutableSetOf(input)
        var layer = listOf(Node("Input", input, 0))
        val hits = mutableListOf<Node>()
        repeat(3) {
            val next = mutableListOf<Node>()
            layer.forEach { node ->
                decodeCandidates(node.text).forEach { (name, decoded) ->
                    val clean = decoded.trim()
                    if (clean.length >= 2 && clean !in seen && clean.length <= 12_000) {
                        seen += clean
                        val child = Node("${node.path} -> $name", clean, node.depth + 1)
                        next += child
                        if (scoreText(clean) >= 12) hits += child
                    }
                }
            }
            layer = next.sortedByDescending { scoreText(it.text) }.take(40)
        }
        val best = hits.distinctBy { it.text }.sortedByDescending { scoreText(it.text) }.take(25)
        if (best.isEmpty()) return "Deep Decode Scan sonuç bulamadı.\n\n${analyzeText(input)}"
        return buildString {
            appendLine("Deep Decode Scan")
            appendLine("3 kata kadar zincir decode denendi.")
            appendLine()
            best.forEachIndexed { index, hit ->
                appendLine("${index + 1}. ${hit.path}  | skor ${scoreText(hit.text)}")
                appendLine(hit.text.preview())
                appendLine()
            }
        }
    }

    private fun analyzeText(input: String): String {
        val trimmed = input.trim()
        val chars = trimmed.length
        val bytes = trimmed.toByteArray().size
        val unique = trimmed.toSet().size
        val printable = trimmed.count { it.code in 32..126 || it == '\n' || it == '\t' }
        val entropy = shannonEntropy(trimmed)
        val hints = mutableListOf<String>()
        if (trimmed.matches(Regex("[A-Za-z0-9+/=\\s]+")) && trimmed.length % 4 == 0) hints += "Base64 olabilir"
        if (trimmed.matches(Regex("[A-Z2-7=\\s]+"))) hints += "Base32 olabilir"
        if (trimmed.matches(Regex("[1-9A-HJ-NP-Za-km-z\\s]+"))) hints += "Base58 olabilir"
        if (trimmed.filterNot { it.isWhitespace() }.matches(Regex("[0-9a-fA-F]+")) && trimmed.filterNot { it.isWhitespace() }.length % 2 == 0) hints += "Hex olabilir"
        if (trimmed.matches(Regex("[01\\s]+"))) hints += "Binary olabilir"
        if (trimmed.contains('%')) hints += "URL encoded olabilir"
        if (trimmed.contains("&#")) hints += "HTML entity olabilir"
        if (trimmed.contains("\\u")) hints += "Unicode escape olabilir"
        if (trimmed.matches(Regex("[.\\- /]+"))) hints += "Morse olabilir"
        if (trimmed.matches(Regex("[0-9 /-]+"))) hints += "A1Z26 / ASCII decimal olabilir"
        if (entropy > 4.5) hints += "Yüksek entropy: sıkıştırılmış, şifrelenmiş veya güçlü encode olabilir"
        return buildString {
            appendLine("Text Analyzer")
            appendLine("Karakter: $chars")
            appendLine("Byte: $bytes")
            appendLine("Benzersiz karakter: $unique")
            appendLine("Printable oranı: ${if (chars == 0) 0 else printable * 100 / chars}%")
            appendLine("Entropy: ${"%.2f".format(Locale.US, entropy)}")
            appendLine()
            appendLine("Tahminler:")
            if (hints.isEmpty()) appendLine("- Belirgin format yakalanmadı") else hints.forEach { appendLine("- $it") }
        }
    }

    private fun decodeCandidates(input: String): List<Pair<String, String>> {
        val trimmed = input.trim()
        val noSpace = trimmed.filterNot { it.isWhitespace() }
        val out = mutableListOf<Pair<String, String>>()
        fun add(name: String, block: () -> String) { runCatching { block() }.getOrNull()?.takeIf { it.isNotBlank() && it != input }?.let { out += name to it } }
        add("Base64") { String(Base64.getDecoder().decode(trimmed)) }
        add("Base32") { String(base32Decode(trimmed)) }
        add("Base58") { String(base58Decode(trimmed)) }
        add("Hex") { String(noSpace.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }
        add("Binary") { String(trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(2).toByte() }.toByteArray()) }
        add("ASCII Decimal") { String(trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt().toByte() }.toByteArray()) }
        add("URL") { URLDecoder.decode(trimmed, StandardCharsets.UTF_8.name()) }
        add("HTML Entity") { htmlDecode(trimmed) }
        add("Unicode Escape") { unicodeDecode(trimmed) }
        add("Morse") { morseDecode(trimmed) }
        add("A1Z26") { a1z26Decode(trimmed) }
        add("ROT13") { caesar(trimmed, 13) }
        add("ROT47") { rot47(trimmed) }
        add("Atbash") { atbash(trimmed) }
        (1..25).forEach { shift -> add("Caesar -$shift") { caesar(trimmed, -shift) } }
        return out.filter { (_, text) -> text.any { it.isLetterOrDigit() } }
    }

    private fun scoreText(text: String): Int {
        if (text.isBlank()) return 0
        val lower = text.lowercase(Locale.ROOT)
        val common = listOf(" the ", " and ", " you ", " that ", "merhaba", "selam", "ben", "bir", "ve", "bu", "şifre", "gizli", "mesaj", "http", "flag", "password")
        val printable = text.count { it.code in 32..126 || it in "çğıöşüÇĞİÖŞÜ\n\t" }
        val letters = text.count { it.isLetter() }
        val spaces = text.count { it.isWhitespace() }
        val bad = text.count { it.code < 9 || it.code in 14..31 }
        return (printable * 4 / text.length) + (letters * 3 / text.length) + (spaces * 2 / text.length) + common.count { it in lower } * 8 - bad * 4
    }

    private fun shannonEntropy(text: String): Double {
        if (text.isEmpty()) return 0.0
        return text.groupingBy { it }.eachCount().values.sumOf { count ->
            val p = count.toDouble() / text.length
            -p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
        }
    }

    private fun String.preview(max: Int = 700): String = if (length <= max) this else take(max) + "..."

    private fun affine(text: String, key: String, decrypt: Boolean): String {
        val parts = key.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
        val a = parts.getOrNull(0) ?: 5
        val b = parts.getOrNull(1) ?: 8
        val inverse = (0 until 26).firstOrNull { (a * it) % 26 == 1 } ?: error("Affine A değeri 26 ile aralarında asal olmalı.")
        return text.map { c ->
            val base = when (c) { in 'a'..'z' -> 'a'; in 'A'..'Z' -> 'A'; else -> return@map c }
            val x = c.code - base.code
            val y = if (decrypt) positiveMod(inverse * (x - b), 26) else positiveMod(a * x + b, 26)
            (base.code + y).toChar()
        }.joinToString("")
    }

    private fun railFenceEncode(text: String, rails: Int): String {
        if (rails <= 1) return text
        val rows = List(rails) { StringBuilder() }
        var row = 0
        var dir = 1
        text.forEach {
            rows[row].append(it)
            if (row == 0) dir = 1 else if (row == rails - 1) dir = -1
            row += dir
        }
        return rows.joinToString("")
    }

    private fun railFenceDecode(text: String, rails: Int): String {
        if (rails <= 1) return text
        val pattern = text.indices.map {
            val cycle = 2 * rails - 2
            val mod = it % cycle
            if (mod < rails) mod else cycle - mod
        }
        val counts = (0 until rails).map { rail -> pattern.count { it == rail } }
        val railChars = mutableMapOf<Int, ArrayDeque<Char>>()
        var index = 0
        counts.forEachIndexed { rail, count ->
            railChars[rail] = ArrayDeque(text.substring(index, index + count).toList())
            index += count
        }
        return pattern.map { railChars[it]!!.removeFirst() }.joinToString("")
    }

    private fun a1z26Encode(text: String) = text.uppercase(Locale.ROOT).map { if (it in 'A'..'Z') (it - 'A' + 1).toString() else if (it == ' ') "/" else it.toString() }.joinToString(" ")
    private fun a1z26Decode(text: String) = text.trim().split(Regex("\\s+")).joinToString("") { token -> if (token == "/") " " else token.toIntOrNull()?.takeIf { it in 1..26 }?.let { ('A' + it - 1).toString() } ?: token }

    private fun beaufort(text: String, key: String): String {
        require(key.any { it.isLetter() }) { "Beaufort için anahtar gir." }
        val shifts = key.filter { it.isLetter() }.map { it.uppercaseChar() - 'A' }
        var i = 0
        return text.map { c ->
            val base = when (c) { in 'a'..'z' -> 'a'; in 'A'..'Z' -> 'A'; else -> return@map c }
            val x = c.uppercaseChar() - 'A'
            val y = positiveMod(shifts[i++ % shifts.size] - x, 26)
            (base.code + y).toChar()
        }.joinToString("")
    }

    private fun gronsfeld(text: String, key: String, decrypt: Boolean): String {
        val digits = key.filter { it.isDigit() }.map { it.digitToInt() }
        require(digits.isNotEmpty()) { "Gronsfeld için sayı anahtarı gir." }
        var i = 0
        return text.map { c ->
            if (!c.isLetter()) c else caesar(c.toString(), digits[i++ % digits.size] * if (decrypt) -1 else 1).first()
        }.joinToString("")
    }

    private fun baconEncode(text: String) = text.uppercase(Locale.ROOT).map { c ->
        if (c in 'A'..'Z') (c - 'A').toString(2).padStart(5, '0').map { if (it == '0') 'A' else 'B' }.joinToString("") else if (c == ' ') "/" else c.toString()
    }.joinToString(" ")

    private fun baconDecode(text: String) = text.uppercase(Locale.ROOT).split(Regex("\\s+")).joinToString("") { token ->
        if (token == "/") " " else token.filter { it == 'A' || it == 'B' }.takeIf { it.length == 5 }?.map { if (it == 'A') '0' else '1' }?.joinToString("")?.toInt(2)?.let { ('A' + it).toString() } ?: token
    }

    private fun polybiusEncode(text: String): String {
        val square = "ABCDEFGHIKLMNOPQRSTUVWXYZ"
        return text.uppercase(Locale.ROOT).map { c ->
            val normalized = if (c == 'J') 'I' else c
            val idx = square.indexOf(normalized)
            if (idx >= 0) "${idx / 5 + 1}${idx % 5 + 1}" else if (c == ' ') "/" else c.toString()
        }.joinToString(" ")
    }

    private fun polybiusDecode(text: String): String {
        val square = "ABCDEFGHIKLMNOPQRSTUVWXYZ"
        return text.trim().split(Regex("\\s+")).joinToString("") { token ->
            if (token == "/") " " else if (token.length == 2 && token.all { it in '1'..'5' }) square[(token[0] - '1') * 5 + (token[1] - '1')].toString() else token
        }
    }

    private fun tapCodeEncode(text: String) = polybiusEncode(text).replace('1', '.').replace('2', ':').replace('3', ';').replace('4', '!').replace('5', '?')
    private fun tapCodeDecode(text: String) = polybiusDecode(text.replace('.', '1').replace(':', '2').replace(';', '3').replace('!', '4').replace('?', '5'))

    private fun columnOrder(key: String): List<Int> = key.ifBlank { "KEY" }.mapIndexed { i, c -> i to c }.sortedWith(compareBy<Pair<Int, Char>> { it.second }.thenBy { it.first }).map { it.first }

    private fun columnarEncode(text: String, key: String): String {
        val cols = key.ifBlank { "KEY" }.length
        val rows = text.chunked(cols)
        return columnOrder(key).joinToString("") { col -> rows.mapNotNull { row -> row.getOrNull(col) }.joinToString("") }
    }

    private fun columnarDecode(text: String, key: String): String {
        val cols = key.ifBlank { "KEY" }.length
        val rows = (text.length + cols - 1) / cols
        val shortCols = cols * rows - text.length
        val order = columnOrder(key)
        val grid = Array(rows) { CharArray(cols) { '\u0000' } }
        var index = 0
        order.forEach { col ->
            val len = rows - if (col >= cols - shortCols && shortCols > 0) 1 else 0
            repeat(len) { row -> grid[row][col] = text[index++] }
        }
        return grid.joinToString("") { row -> row.filter { it != '\u0000' }.joinToString("") }
    }

    private fun scytaleEncode(text: String, cols: Int): String = text.chunked(cols.coerceAtLeast(1)).let { rows -> (0 until cols.coerceAtLeast(1)).joinToString("") { col -> rows.mapNotNull { it.getOrNull(col) }.joinToString("") } }
    private fun scytaleDecode(text: String, cols: Int): String = columnarDecode(text, (0 until cols.coerceAtLeast(1)).joinToString("") { ('A' + it).toString() })

    private fun autokey(text: String, key: String, decrypt: Boolean): String {
        require(key.any { it.isLetter() }) { "Autokey için anahtar gir." }
        val cleanKey = key.filter { it.isLetter() }.uppercase(Locale.ROOT)
        val result = StringBuilder()
        var keyStream = cleanKey
        text.forEach { c ->
            if (!c.isLetter()) result.append(c) else {
                val shift = keyStream[result.count { it.isLetter() }] - 'A'
                val out = caesar(c.toString(), if (decrypt) -shift else shift).first()
                result.append(out)
                keyStream += if (decrypt) out.uppercaseChar() else c.uppercaseChar()
            }
        }
        return result.toString()
    }

    private fun oneTimePad(text: String, key: String, decrypt: Boolean): String {
        val letters = key.filter { it.isLetter() }
        require(letters.isNotEmpty()) { "OTP için anahtar gir." }
        var i = 0
        return text.map { c ->
            if (!c.isLetter()) c else {
                val shift = letters[i++ % letters.length].uppercaseChar() - 'A'
                caesar(c.toString(), if (decrypt) -shift else shift).first()
            }
        }.joinToString("")
    }

    private fun playfair(text: String, key: String, decrypt: Boolean): String {
        val square = (key.uppercase(Locale.ROOT) + "ABCDEFGHIKLMNOPQRSTUVWXYZ").filter { it in 'A'..'Z' }.map { if (it == 'J') 'I' else it }.distinct().joinToString("")
        val pairs = text.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.map { if (it == 'J') 'I' else it }.joinToString("").let { raw ->
            val out = mutableListOf<Pair<Char, Char>>(); var i = 0
            while (i < raw.length) { val a = raw[i]; val b = raw.getOrNull(i + 1) ?: 'X'; if (a == b) { out += a to 'X'; i++ } else { out += a to b; i += 2 } }
            out
        }
        fun pos(c: Char) = square.indexOf(c).let { it / 5 to it % 5 }
        val dir = if (decrypt) -1 else 1
        return pairs.joinToString("") { (a, b) ->
            val (r1, c1) = pos(a); val (r2, c2) = pos(b)
            when {
                r1 == r2 -> "${square[r1 * 5 + positiveMod(c1 + dir, 5)]}${square[r2 * 5 + positiveMod(c2 + dir, 5)]}"
                c1 == c2 -> "${square[positiveMod(r1 + dir, 5) * 5 + c1]}${square[positiveMod(r2 + dir, 5) * 5 + c2]}"
                else -> "${square[r1 * 5 + c2]}${square[r2 * 5 + c1]}"
            }
        }
    }

    private fun porta(text: String, key: String): String {
        require(key.any { it.isLetter() }) { "Porta için anahtar gir." }
        val pairs = listOf("AB", "CD", "EF", "GH", "IJ", "KL", "MN", "OP", "QR", "ST", "UV", "WX", "YZ")
        val alphabets = listOf(
            "NOPQRSTUVWXYZABCDEFGHIJKLM", "OPQRSTUVWXYZNMABCDEFGHIJKL", "PQRSTUVWXYZNOLMABCDEFGHIJK", "QRSTUVWXYZNOPKLMABCDEFGHIJ", "RSTUVWXYZNOPQJKLMABCDEFGHI",
            "STUVWXYZNOPQRIJKLMABCDEFGH", "TUVWXYZNOPQRSHIJKLMABCDEFG", "UVWXYZNOPQRSTGHIJKLMABCDEF", "VWXYZNOPQRSTUFGHIJKLMABCDE", "WXYZNOPQRSTUVEFGHIJKLMABCD",
            "XYZNOPQRSTUVWDEFGHIJKLMABC", "YZNOPQRSTUVWXCDEFGHIJKLMAB", "ZNOPQRSTUVWXYBCDEFGHIJKLMA"
        )
        val cleanKey = key.filter { it.isLetter() }.uppercase(Locale.ROOT)
        var i = 0
        return text.uppercase(Locale.ROOT).map { c ->
            if (c !in 'A'..'Z') c else {
                val k = cleanKey[i++ % cleanKey.length]
                val row = pairs.indexOfFirst { k in it }.coerceAtLeast(0)
                val alphabet = alphabets[row]
                if (c <= 'M') alphabet[c - 'A'] else ('A' + alphabet.indexOf(c))
            }
        }.joinToString("")
    }

    private fun nihilist(text: String, key: String, decrypt: Boolean): String {
        val parts = key.split('|', limit = 2)
        val numericKey = parts.getOrElse(1) { "31415" }.filter { it.isDigit() }.map { it.digitToInt() }.ifEmpty { listOf(3, 1, 4, 1, 5) }
        return if (!decrypt) {
            val nums = polybiusEncode(text).split(Regex("\\s+")).filter { it.matches(Regex("\\d{2}")) }.map { it.toInt() }
            nums.mapIndexed { i, n -> n + numericKey[i % numericKey.size] }.joinToString(" ")
        } else {
            inputNumbers(text).mapIndexed { i, n -> (n - numericKey[i % numericKey.size]).toString().padStart(2, '0') }.joinToString(" ").let { polybiusDecode(it) }
        }
    }

    private fun adfgx(text: String, key: String, decrypt: Boolean): String {
        val parts = key.split('|', limit = 2)
        val columnKey = parts.getOrElse(1) { "zebra" }
        val labels = "ADFGX"
        return if (!decrypt) {
            val fractionated = polybiusEncode(text).split(Regex("\\s+")).joinToString("") { token ->
                if (token.length == 2 && token.all { it in '1'..'5' }) "${labels[token[0] - '1']}${labels[token[1] - '1']}" else ""
            }
            columnarEncode(fractionated, columnKey)
        } else {
            val fractionated = columnarDecode(text, columnKey)
            fractionated.chunked(2).joinToString(" ") { pair ->
                if (pair.length == 2) "${labels.indexOf(pair[0]) + 1}${labels.indexOf(pair[1]) + 1}" else ""
            }.let { polybiusDecode(it) }
        }
    }

    private fun inputNumbers(text: String) = Regex("-?\\d+").findAll(text).map { it.value.toInt() }.toList()

    private val pigpenSymbols = listOf("⌜", "⌝", "⌞", "⌟", "□", "◇", "△", "▽", "○", "⌜•", "⌝•", "⌞•", "⌟•", "□•", "◇•", "△•", "▽•", "○•", "⊢", "⊣", "⊥", "⊤", "⊢•", "⊣•", "⊥•", "⊤•")
    private fun pigpenEncode(text: String) = text.uppercase(Locale.ROOT).map { if (it in 'A'..'Z') pigpenSymbols[it - 'A'] else it.toString() }.joinToString(" ")
    private fun pigpenDecode(text: String): String {
        val rev = pigpenSymbols.mapIndexed { i, s -> s to ('A' + i) }.toMap()
        return text.split(Regex("\\s+")).joinToString("") { rev[it]?.toString() ?: if (it == "/") " " else it }
    }

    private fun hill2x2(text: String, key: String, decrypt: Boolean): String {
        val k = key.split(',', ' ').mapNotNull { it.toIntOrNull() }.let { if (it.size >= 4) it else listOf(3, 3, 2, 5) }
        val det = positiveMod(k[0] * k[3] - k[1] * k[2], 26)
        val invDet = (0 until 26).firstOrNull { det * it % 26 == 1 } ?: error("Hill matrisi terslenemez.")
        val m = if (!decrypt) k else listOf(k[3] * invDet, -k[1] * invDet, -k[2] * invDet, k[0] * invDet).map { positiveMod(it, 26) }
        val clean = text.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.padEnd((text.count { it.isLetter() } + 1) / 2 * 2, 'X')
        return clean.chunked(2).joinToString("") { pair ->
            val a = pair[0] - 'A'; val b = pair[1] - 'A'
            "${('A' + positiveMod(m[0] * a + m[1] * b, 26))}${('A' + positiveMod(m[2] * a + m[3] * b, 26))}"
        }
    }

    private fun bifid(text: String, decrypt: Boolean): String {
        val square = "ABCDEFGHIKLMNOPQRSTUVWXYZ"
        val clean = text.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.map { if (it == 'J') 'I' else it }
        fun pos(c: Char) = square.indexOf(c).let { (it / 5 + 1) to (it % 5 + 1) }
        return if (!decrypt) {
            val coords = clean.map { pos(it) }
            (coords.map { it.first } + coords.map { it.second }).chunked(2).joinToString("") { square[(it[0] - 1) * 5 + (it[1] - 1)].toString() }
        } else {
            val nums = clean.flatMap { val p = pos(it); listOf(p.first, p.second) }
            val half = nums.size / 2
            nums.take(half).zip(nums.drop(half)).joinToString("") { (r, c) -> square[(r - 1) * 5 + (c - 1)].toString() }
        }
    }

    private fun trifid(text: String, decrypt: Boolean): String {
        val cube = "ABCDEFGHIJKLMNOPQRSTUVWXYZ."
        val clean = text.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it == '.' }
        fun pos(c: Char): List<Int> { val i = cube.indexOf(c).coerceAtLeast(0); return listOf(i / 9 + 1, i % 9 / 3 + 1, i % 3 + 1) }
        fun char(a: Int, b: Int, c: Int) = cube[(a - 1) * 9 + (b - 1) * 3 + (c - 1)]
        return if (!decrypt) {
            val coords = clean.map { pos(it) }
            (coords.map { it[0] } + coords.map { it[1] } + coords.map { it[2] }).chunked(3).joinToString("") { char(it[0], it[1], it[2]).toString() }
        } else {
            val nums = clean.flatMap { pos(it) }
            val third = nums.size / 3
            (0 until third).joinToString("") { char(nums[it], nums[it + third], nums[it + third * 2]).toString() }
        }
    }

    private fun routeEncode(text: String, cols: Int): String {
        val c = cols.coerceAtLeast(2)
        val rows = text.chunked(c).map { it.padEnd(c, 'X') }
        return rows.indices.flatMap { r -> if (r % 2 == 0) rows[r].toList() else rows[r].reversed().toList() }.joinToString("")
    }

    private fun routeDecode(text: String, cols: Int): String {
        val c = cols.coerceAtLeast(2)
        return text.chunked(c).mapIndexed { i, row -> if (i % 2 == 0) row else row.reversed() }.joinToString("").trimEnd('X')
    }

    private fun bookEncode(text: String, book: String): String {
        require(book.isNotBlank()) { "Book Cipher için anahtar alanına kitap/metin gir." }
        val words = book.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.isNotBlank() }
        return text.lowercase(Locale.ROOT).split(Regex("\\s+")).joinToString(" ") { word -> (words.indexOf(word) + 1).takeIf { it > 0 }?.toString() ?: "?${word}" }
    }

    private fun bookDecode(text: String, book: String): String {
        val words = book.split(Regex("\\W+")).filter { it.isNotBlank() }
        return text.split(Regex("\\s+")).joinToString(" ") { token -> token.toIntOrNull()?.let { words.getOrNull(it - 1) } ?: token }
    }

    private fun qrAscii(text: String): String {
        require(text.isNotBlank()) { "QR için metin gir." }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 33, 33)
        return buildString {
            appendLine("QR ASCII")
            appendLine("Koyu bloklar QR modülüdür. Kopyala/paylaş ile metin olarak gönderebilirsin.")
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) append(if (matrix[x, y]) "██" else "  ")
                appendLine()
            }
        }
    }

    private fun mirrorText(text: String) = text.reversed().map { mirrorMap[it] ?: it }.joinToString("")
    private val mirrorMap = mapOf('a' to 'ɒ', 'b' to 'd', 'c' to 'ɔ', 'd' to 'b', 'e' to 'ɘ', 'f' to 'ɟ', 'g' to 'ǫ', 'h' to 'ʜ', 'i' to 'i', 'j' to 'ꞁ', 'k' to 'ʞ', 'l' to 'l', 'm' to 'm', 'n' to 'ᴎ', 'o' to 'o', 'p' to 'q', 'q' to 'p', 'r' to 'ɿ', 's' to 'ƨ', 't' to 'ƚ', 'u' to 'υ', 'v' to 'v', 'w' to 'w', 'x' to 'x', 'y' to 'ʏ', 'z' to 'z')

    private fun upsideDown(text: String) = text.reversed().map { upsideMap[it] ?: it }.joinToString("")
    private val upsideMap = mapOf('a' to 'ɐ', 'b' to 'q', 'c' to 'ɔ', 'd' to 'p', 'e' to 'ǝ', 'f' to 'ɟ', 'g' to 'ƃ', 'h' to 'ɥ', 'i' to 'ᴉ', 'j' to 'ɾ', 'k' to 'ʞ', 'l' to 'ʃ', 'm' to 'ɯ', 'n' to 'u', 'o' to 'o', 'p' to 'd', 'q' to 'b', 'r' to 'ɹ', 's' to 's', 't' to 'ʇ', 'u' to 'n', 'v' to 'ʌ', 'w' to 'ʍ', 'x' to 'x', 'y' to 'ʎ', 'z' to 'z', '!' to '¡', '?' to '¿', '.' to '˙')

    private fun invisibleEncode(text: String) = text.toByteArray().joinToString("") { byte -> byte.toInt().and(255).toString(2).padStart(8, '0').map { if (it == '0') '\u200B' else '\u200C' }.joinToString("") }
    private fun invisibleDecode(text: String): String {
        val bits = text.filter { it == '\u200B' || it == '\u200C' }.map { if (it == '\u200B') '0' else '1' }.joinToString("")
        return String(bits.chunked(8).filter { it.length == 8 }.map { it.toInt(2).toByte() }.toByteArray())
    }

    private fun zeroWidthEncode(text: String) = "CipherLab" + invisibleEncode(text) + "\u2063"
    private fun zeroWidthDecode(text: String) = invisibleDecode(text)

    private val zalgoMarks = listOf('\u0300', '\u0301', '\u0302', '\u0303', '\u0304', '\u0305', '\u0306', '\u0307', '\u0308', '\u0309', '\u030A', '\u030B', '\u030C', '\u0323', '\u0324', '\u0325', '\u0326', '\u0327', '\u0328')
    private fun zalgo(text: String) = text.flatMap { c -> listOf(c) + List(3) { zalgoMarks.random() } }.joinToString("")
    private fun stripZalgo(text: String) = text.filterNot { it in zalgoMarks }

    private fun fullwidth(text: String) = text.map { if (it.code in 33..126) (it.code + 0xFEE0).toChar() else if (it == ' ') '　' else it }.joinToString("")
    private fun unFullwidth(text: String) = text.map { if (it.code in 0xFF01..0xFF5E) (it.code - 0xFEE0).toChar() else if (it == '　') ' ' else it }.joinToString("")

    private val smallCapsMap = mapOf('a' to 'ᴀ', 'b' to 'ʙ', 'c' to 'ᴄ', 'd' to 'ᴅ', 'e' to 'ᴇ', 'f' to 'ꜰ', 'g' to 'ɢ', 'h' to 'ʜ', 'i' to 'ɪ', 'j' to 'ᴊ', 'k' to 'ᴋ', 'l' to 'ʟ', 'm' to 'ᴍ', 'n' to 'ɴ', 'o' to 'ᴏ', 'p' to 'ᴘ', 'q' to 'ǫ', 'r' to 'ʀ', 's' to 'ꜱ', 't' to 'ᴛ', 'u' to 'ᴜ', 'v' to 'ᴠ', 'w' to 'ᴡ', 'x' to 'x', 'y' to 'ʏ', 'z' to 'ᴢ')
    private fun smallCaps(text: String) = text.lowercase(Locale.ROOT).map { smallCapsMap[it] ?: it }.joinToString("")

    private fun bubbleText(text: String) = text.map { c -> when (c) { in 'a'..'z' -> (0x24D0 + c.code - 'a'.code).toChar(); in 'A'..'Z' -> (0x24B6 + c.code - 'A'.code).toChar(); in '1'..'9' -> (0x2460 + c.code - '1'.code).toChar(); '0' -> '⓪'; else -> c } }.joinToString("")
    private fun unBubbleText(text: String) = text.map { c -> when (c.code) { in 0x24D0..0x24E9 -> ('a'.code + c.code - 0x24D0).toChar(); in 0x24B6..0x24CF -> ('A'.code + c.code - 0x24B6).toChar(); in 0x2460..0x2468 -> ('1'.code + c.code - 0x2460).toChar(); 0x24EA -> '0'; else -> c } }.joinToString("")

    private fun randomCase(text: String) = text.map { if (it.isLetter() && SecureRandom().nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("")

    private fun jwtDecode(input: String): String {
        val parts = input.trim().split('.')
        require(parts.size >= 2) { "JWT formatı header.payload.signature şeklinde olmalı." }
        fun decodePart(part: String) = String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
        return buildString {
            appendLine("Header:")
            appendLine(jsonPretty(decodePart(parts[0])))
            appendLine("Payload:")
            appendLine(jsonPretty(decodePart(parts[1])))
            if (parts.size > 2) appendLine("Signature: ${parts[2]}")
        }
    }

    private fun jsonPretty(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
    }

    private fun jsonMinify(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("[")) JSONArray(trimmed).toString() else JSONObject(trimmed).toString()
    }

    private fun xmlPretty(input: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input.byteInputStream())
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val writer = java.io.StringWriter()
        transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer))
        return writer.toString()
    }

    private fun urlParser(input: String): String {
        val uri = URI(input.trim())
        val query = uri.rawQuery?.split('&')?.joinToString("\n") { part ->
            val p = part.split('=', limit = 2)
            val k = URLDecoder.decode(p[0], StandardCharsets.UTF_8.name())
            val v = URLDecoder.decode(p.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            "$k = $v"
        } ?: "Yok"
        return buildString {
            appendLine("Scheme: ${uri.scheme ?: ""}")
            appendLine("Host: ${uri.host ?: ""}")
            appendLine("Port: ${if (uri.port == -1) "" else uri.port}")
            appendLine("Path: ${uri.path ?: ""}")
            appendLine("Fragment: ${uri.fragment ?: ""}")
            appendLine("Query:")
            appendLine(query)
        }
    }

    private fun regexTester(input: String, pattern: String): String {
        require(pattern.isNotBlank()) { "Regex alanına pattern gir." }
        val regex = Regex(pattern)
        val matches = regex.findAll(input).toList()
        return buildString {
            appendLine("Regex: $pattern")
            appendLine("Eşleşme sayısı: ${matches.size}")
            appendLine()
            matches.take(50).forEachIndexed { index, match ->
                appendLine("${index + 1}. [${match.range.first}-${match.range.last}] ${match.value}")
                if (match.groupValues.size > 1) appendLine("Gruplar: ${match.groupValues.drop(1).joinToString(" | ")}")
            }
        }
    }

    private fun crc32(input: String): String {
        val crc = CRC32()
        crc.update(input.toByteArray())
        return crc.value.toString(16).padStart(8, '0')
    }

    private fun hexdump(input: String): String = input.toByteArray().toList().chunked(16).mapIndexed { row, bytes ->
        val hex = bytes.joinToString(" ") { it.toInt().and(255).toString(16).padStart(2, '0') }.padEnd(47)
        val ascii = bytes.map { b -> b.toInt().and(255).toChar().takeIf { it.code in 32..126 } ?: '.' }.joinToString("")
        "%08x  %s  %s".format(row * 16, hex, ascii)
    }.joinToString("\n")

    private fun ipSubnet(input: String): String {
        val parts = input.trim().split('/')
        require(parts.size == 2) { "CIDR formatı gir: 192.168.1.0/24" }
        val ip = parts[0].split('.').map { it.toInt() }
        val prefix = parts[1].toInt().coerceIn(0, 32)
        require(ip.size == 4 && ip.all { it in 0..255 }) { "Geçersiz IP" }
        val value = ip.fold(0L) { acc, n -> (acc shl 8) or n.toLong() }
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val network = value and mask
        val broadcast = network or (mask xor 0xffffffffL)
        fun fmt(v: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255).toString() }
        val hosts = if (prefix >= 31) 0 else (1L shl (32 - prefix)) - 2
        return "IP: ${fmt(value)}\nMask: ${fmt(mask)}\nNetwork: ${fmt(network)}\nBroadcast: ${fmt(broadcast)}\nHosts: $hosts"
    }

    private fun timestampTool(input: String): String {
        val now = System.currentTimeMillis()
        val number = input.trim().toLongOrNull()
        return if (number == null) "Now ms: $now\nNow sec: ${now / 1000}" else {
            val ms = if (number < 10_000_000_000L) number * 1000 else number
            "Input ms: $ms\nInput sec: ${ms / 1000}\nDate: ${java.util.Date(ms)}"
        }
    }

    private fun passwordGenerator(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*_-+=?"
        val random = SecureRandom()
        return (1..length.coerceIn(6, 128)).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun passwordStrength(text: String): String {
        var score = 0
        if (text.length >= 8) score += 20
        if (text.length >= 14) score += 20
        if (text.any { it.isLowerCase() }) score += 15
        if (text.any { it.isUpperCase() }) score += 15
        if (text.any { it.isDigit() }) score += 15
        if (text.any { !it.isLetterOrDigit() }) score += 15
        val label = when { score >= 85 -> "Çok güçlü"; score >= 65 -> "Güçlü"; score >= 40 -> "Orta"; else -> "Zayıf" }
        return "Skor: $score/100\nSeviye: $label\nUzunluk: ${text.length}"
    }

    private fun hmacSha256(text: String, key: String): String {
        require(key.isNotEmpty()) { "HMAC için anahtar gir." }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun base32Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = StringBuilder()
        for (byte in bytes) {
            buffer = (buffer shl 8) or byte.toInt().and(255)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun base32Decode(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        text.uppercase(Locale.ROOT).filter { it in alphabet }.forEach {
            buffer = (buffer shl 5) or alphabet.indexOf(it)
            bits += 5
            if (bits >= 8) {
                out += ((buffer shr (bits - 8)) and 255).toByte()
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private val base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private fun base58Encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var digits = mutableListOf(0)
        for (byte in bytes) {
            var carry = byte.toInt().and(255)
            for (i in digits.indices) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        repeat(bytes.takeWhile { it.toInt() == 0 }.count()) { digits.add(0) }
        return digits.asReversed().joinToString("") { base58Alphabet[it].toString() }
    }

    private fun base58Decode(text: String): ByteArray {
        if (text.isBlank()) return ByteArray(0)
        var bytes = mutableListOf(0)
        text.trim().forEach { c ->
            var carry = base58Alphabet.indexOf(c)
            require(carry >= 0) { "Base58 karakteri değil: $c" }
            for (i in bytes.indices) {
                carry += bytes[i] * 58
                bytes[i] = carry and 255
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(carry and 255)
                carry = carry shr 8
            }
        }
        repeat(text.takeWhile { it == '1' }.count()) { bytes.add(0) }
        return bytes.asReversed().map { it.toByte() }.toByteArray()
    }

    private val morse = mapOf('A' to ".-", 'B' to "-...", 'C' to "-.-.", 'Ç' to "-.-..", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'Ğ' to "--.-.", 'H' to "....", 'I' to "..", 'İ' to ".-..-", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'Ö' to "---.", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'Ş' to ".--..", 'T' to "-", 'U' to "..-", 'Ü' to "..--", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.")
    private fun morseEncode(text: String) = text.uppercase(Locale.ROOT).map { if (it == ' ') "/" else morse[it] ?: it }.joinToString(" ")
    private fun morseDecode(text: String): String { val rev = morse.entries.associate { it.value to it.key }; return text.trim().split(Regex("\\s+")).joinToString("") { if (it == "/") " " else rev[it]?.toString() ?: it } }

    private val nato = listOf("Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray", "Yankee", "Zulu")
    private fun natoEncode(text: String) = text.uppercase(Locale.ROOT).map { if (it in 'A'..'Z') nato[it - 'A'] else it.toString() }.joinToString(" ")
    private fun natoDecode(text: String): String { val rev = nato.associateBy { it.uppercase(Locale.ROOT) }; return text.split(Regex("\\s+")).joinToString("") { token -> rev[token.uppercase(Locale.ROOT)]?.let { ('A' + nato.indexOf(it)).toString() } ?: token } }

    private fun aesEncrypt(text: String, password: String): String {
        require(password.length >= 6) { "AES için en az 6 karakter parola gir." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(text.toByteArray())
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(4 + salt.size + iv.size + encrypted.size).put("CL1:".toByteArray()).put(salt).put(iv).put(encrypted).array())
    }

    private fun aesDecrypt(text: String, password: String): String {
        val data = ByteBuffer.wrap(Base64.getDecoder().decode(text.trim()))
        val magic = ByteArray(4).also { data.get(it) }
        require(String(magic) == "CL1:") { "CipherLab AES çıktısı değil." }
        val salt = ByteArray(16).also { data.get(it) }
        val iv = ByteArray(12).also { data.get(it) }
        val encrypted = ByteArray(data.remaining()).also { data.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(password, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted))
    }

    private fun aesKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }
}

private fun positiveMod(value: Int, modulo: Int): Int = ((value % modulo) + modulo) % modulo

private object FileCrypto {
    fun encrypt(bytes: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(bytes)
        return ByteBuffer.allocate(4 + salt.size + iv.size + encrypted.size)
            .put("CLF1".toByteArray())
            .put(salt)
            .put(iv)
            .put(encrypted)
            .array()
    }

    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        val data = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4).also { data.get(it) }
        require(String(magic) == "CLF1") { "CipherLab dosyası değil veya bozuk." }
        val salt = ByteArray(16).also { data.get(it) }
        val iv = ByteArray(12).also { data.get(it) }
        val encrypted = ByteArray(data.remaining()).also { data.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }
}

private object QrPng {
    fun create(text: String): ByteArray {
        require(text.isNotBlank()) { "QR için input veya sonuç metni gerekli." }
        val size = 768
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
}

private object BiometricGate {
    fun authenticate(context: Context, result: (String) -> Unit) {
        val activity = context as? FragmentActivity ?: run {
            result("Biometric için Activity bulunamadı.")
            return
        }
        val manager = BiometricManager.from(context)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            result("Bu cihazda biyometrik/ekran kilidi hazır değil.")
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(authResult: BiometricPrompt.AuthenticationResult) {
                result("Biyometrik doğrulama başarılı.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                result("Biyometrik hata: $errString")
            }

            override fun onAuthenticationFailed() {
                result("Biyometrik doğrulama başarısız.")
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("CipherLab Kilit")
            .setSubtitle("Biyometrik veya ekran kilidi ile doğrula")
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }
}

private class Enigma(private val rotors: List<Rotor>, private val reflector: String, plugPairs: String) {
    private val plug = ('A'..'Z').associateWith { it }.toMutableMap()

    init {
        plugPairs.uppercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length == 2 }.forEach {
            plug[it[0]] = it[1]
            plug[it[1]] = it[0]
        }
    }

    fun process(text: String): String = text.uppercase(Locale.ROOT).map { c ->
        if (c !in 'A'..'Z') c else {
            step()
            var x = plug[c]!! - 'A'
            rotors.asReversed().forEach { x = it.forward(x) }
            x = reflector[x] - 'A'
            rotors.forEach { x = it.backward(x) }
            plug[('A' + x)]!!
        }
    }.joinToString("")

    private fun step() {
        val left = rotors[0]
        val middle = rotors[1]
        val right = rotors[2]
        if (middle.atNotch()) left.step()
        if (middle.atNotch() || right.atNotch()) middle.step()
        right.step()
    }

    companion object {
        private val rotorData = mapOf("I" to ("EKMFLGDQVZNTOWYHXUSPAIBRCJ" to 'Q'), "II" to ("AJDKSIRUXBLHWTMCQGZNPYFVOE" to 'E'), "III" to ("BDFHJLCPRTXVZNYEIWGAKMUSQO" to 'V'), "IV" to ("ESOVPZJAYQUIRHXLNFTGKDCMWB" to 'J'), "V" to ("VZBRGITYUPSDNHLXAWMJQOFECK" to 'Z'))
        private val reflectors = mapOf("B" to "YRUHQSLDPXNGOKMIEBFZCWVJAT", "C" to "FVPJIAOYEDRZXWGCTKUQSBNMHL")

        fun from(settings: String): Enigma {
            val parts = settings.split('|').map { it.trim() }
            val names = parts.getOrNull(0)?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: listOf("I", "II", "III")
            val reflector = reflectors[parts.getOrNull(1)?.uppercase(Locale.ROOT)] ?: reflectors.getValue("B")
            val rings = parts.getOrNull(2)?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull()?.minus(1) } ?: listOf(0, 0, 0)
            val pos = (parts.getOrNull(3)?.uppercase(Locale.ROOT) ?: "AAA").padEnd(3, 'A')
            val rotors = names.take(3).mapIndexed { index, name ->
                val data = rotorData[name.uppercase(Locale.ROOT)] ?: rotorData.getValue(listOf("I", "II", "III")[index])
                Rotor(data.first, data.second, rings.getOrElse(index) { 0 }, pos[index] - 'A')
            }
            return Enigma(rotors, reflector, parts.getOrNull(4).orEmpty())
        }
    }
}

private class Rotor(private val wiring: String, private val notch: Char, private val ring: Int, private var position: Int) {
    private val inverse = IntArray(26).also { arr -> wiring.forEachIndexed { i, c -> arr[c - 'A'] = i } }
    fun atNotch() = position == notch - 'A'
    fun step() { position = (position + 1) % 26 }
    fun forward(x: Int): Int { val shifted = positiveMod(x + position - ring, 26); return positiveMod(wiring[shifted] - 'A' - position + ring, 26) }
    fun backward(x: Int): Int { val shifted = positiveMod(x + position - ring, 26); return positiveMod(inverse[shifted] - position + ring, 26) }
}
