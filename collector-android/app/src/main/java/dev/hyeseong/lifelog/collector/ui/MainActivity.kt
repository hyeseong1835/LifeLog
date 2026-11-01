package dev.hyeseong.lifelog.collector.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import dev.hyeseong.lifelog.collector.CollectorService
import dev.hyeseong.lifelog.collector.core.Config
import dev.hyeseong.lifelog.collector.core.Db
import dev.hyeseong.lifelog.collector.core.Manifest
import dev.hyeseong.lifelog.collector.core.Queue
import dev.hyeseong.lifelog.collector.sources.HealthSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** 설정·권한·플러그인·실행을 한 화면에. 비밀번호는 노트북 대시보드/CLI에서 reset_device_password로 받아 붙여 넣는다(QR은 나중). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installBundledPlugins()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    /** assets의 저장소 plugins/를 filesDir/plugins로 푼다(첫 실행·갱신). phone-·health만. GitHub URL 설치는 나중. */
    private fun installBundledPlugins() {
        val dest = Config.pluginsDir(this)
        for (id in assets.list("")!!.filter { it.startsWith("phone-") || it == "health" }) {
            val d = File(dest, id); d.mkdirs()
            for (f in assets.list(id)!!) assets.open("$id/$f").use { i -> File(d, f).outputStream().use { i.copyTo(it) } }
        }
    }

    @Composable
    fun Screen() {
        val cfg = remember { Config(this) }
        val scope = rememberCoroutineScope()
        // tools/db/phone-setup.ps1이 adb `am start --es ...`로 넘기는 값으로 칸을 미리 채운다(저장은 버튼을 눌러야)
        fun extra(k: String, def: String) = intent?.getStringExtra(k)?.takeIf { it.isNotEmpty() } ?: def
        var device by remember { mutableStateOf(extra("device", cfg.device.ifEmpty { "phone" })) }
        var host by remember { mutableStateOf(extra("host", cfg.host)) }
        var port by remember { mutableStateOf(extra("port", cfg.port.toString())) }
        var database by remember { mutableStateOf(extra("database", cfg.database)) }
        var tenant by remember { mutableStateOf(extra("tenant", cfg.tenant)) }
        var password by remember { mutableStateOf(extra("password", "")) }
        var msg by remember { mutableStateOf("") }
        var pluginRows by remember { mutableStateOf(listOf<String>()) }
        var running by remember { mutableStateOf(CollectorService.running) }

        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { msg = "권한: $it" }
        val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { msg = "Health Connect 허용 ${it.size}개" }

        fun refresh() = scope.launch {
            running = CollectorService.running
            pluginRows = withContext(Dispatchers.IO) {
                try {
                    val q = Queue(Config.queuePath(this@MainActivity)).use { it.count() }
                    val lines = mutableListOf("큐 대기 ${q}건 · ${CollectorService.status}")
                    if (cfg.isComplete) Db(cfg).use { db ->
                        for (d in Config.pluginsDir(this@MainActivity).listFiles().orEmpty().filter { File(it, "manifest.json").exists() }) {
                            val m = Manifest.load(d)
                            val inst = db.getInstall(m.id, cfg.device)
                            lines += "${m.id} ${m.version}  ${if (inst == null) "미등록" else if (inst.enabled) "켜짐" else "꺼짐"}"
                        }
                    }
                    lines
                } catch (e: Throwable) { listOf("상태 조회 실패: ${e.message}") }
            }
        }
        LaunchedEffect(Unit) { refresh() }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LifeLog 수집기", style = MaterialTheme.typography.titleLarge)
            Text("DB 접속 (기기 롤)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(device, { device = it }, label = { Text("기기 id") }, singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("호스트") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it }, label = { Text("포트") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(database, { database = it }, label = { Text("DB") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(tenant, { tenant = it }, label = { Text("테넌트(Supabase 프로젝트 ref, 맨 Postgres면 비움)") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("기기 비밀번호 (reset_device_password 결과)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(onClick = {
                cfg.device = device.trim(); cfg.host = host.trim(); cfg.port = port.trim().toIntOrNull() ?: 5432; cfg.database = database.trim(); cfg.tenant = tenant.trim()
                if (password.isNotEmpty()) cfg.password = password
                password = ""
                scope.launch {
                    msg = withContext(Dispatchers.IO) { try { Db(cfg).use { "연결 확인: 스키마 버전 ${it.schemaVersion()}" } } catch (e: Throwable) { "연결 실패: ${e.message}" } }
                    refresh()
                }
            }) { Text("저장하고 연결 확인") }

            Text("권한", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("사용 정보 접근") }
                Button(onClick = { perms.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.ACTIVITY_RECOGNITION)) }) { Text("위치·알림·활동") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { perms.launch(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }) { Text("위치 항상 허용") }
                Button(onClick = { if (HealthSource.available(this@MainActivity)) health.launch(HealthSource.PERMISSIONS) else msg = "Health Connect 없음" }) { Text("Health Connect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("알림 접근(미디어)") }
                Button(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("배터리 최적화 제외") }
            }

            Text("플러그인", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                scope.launch {
                    msg = withContext(Dispatchers.IO) {
                        try {
                            Db(cfg).use { db ->
                                var n = 0
                                for (d in Config.pluginsDir(this@MainActivity).listFiles().orEmpty().filter { File(it, "manifest.json").exists() }) {
                                    val m = Manifest.load(d)
                                    db.registerPlugin(m, cfg.device)
                                    val granted = JSONArray(m.capabilities.map { it.first }).toString()
                                    db.setInstall(m.id, cfg.device, m.version, true, null, granted); n++
                                }
                                "플러그인 ${n}개 등록·켬(권한 전부 허용)"
                            }
                        } catch (e: Throwable) { "등록 실패: ${e.message}" }
                    }
                    refresh()
                }
            }) { Text("내장 플러그인 전부 등록·켜기") }
            pluginRows.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("실행", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { cfg.autostart = true; startForegroundService(Intent(this@MainActivity, CollectorService::class.java)); scope.launch { kotlinx.coroutines.delay(2000); refresh() } }, enabled = !running) { Text("수집 시작") }
                Button(onClick = { cfg.autostart = false; stopService(Intent(this@MainActivity, CollectorService::class.java)); scope.launch { kotlinx.coroutines.delay(2500); refresh() } }, enabled = running) { Text("중지") }
                Button(onClick = { refresh() }) { Text("새로고침") }
            }
            if (msg.isNotEmpty()) Text(msg)
            Text("로그", style = MaterialTheme.typography.titleMedium)
            Text(remember(pluginRows) { Config.logFile(this@MainActivity).takeIf { it.exists() }?.readLines()?.takeLast(15)?.joinToString("\n") ?: "" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
