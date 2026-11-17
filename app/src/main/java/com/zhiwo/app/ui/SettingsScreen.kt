package com.zhiwo.app.ui

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.app.AppViewModel
import com.zhiwo.app.BuildConfig
import com.zhiwo.app.data.Integrity
import com.zhiwo.app.data.Settings
import com.zhiwo.app.prompt.Persona
import kotlinx.coroutines.launch
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onThemeChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { Settings.get(context) }

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var thinking by remember { mutableStateOf(settings.thinking) }
    var effort by remember { mutableStateOf(settings.effort) }
    var thinkingStyle by remember { mutableStateOf(settings.thinkingStyle) }
    var masterName by remember { mutableStateOf(settings.masterName) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var prompt by remember { mutableStateOf(settings.systemPrompt) }
    var savedAt by remember { mutableStateOf(0L) }

    var showProfile by remember { mutableStateOf(false) }
    var profileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val scope = rememberCoroutineScope()

    // 当前安装包的签名指纹，给「这是谁签的」一个可验证的凭据
    val signatureSha = remember { Integrity.sha256(context) }
    val genuine = remember(signatureSha) {
        signatureSha?.equals(Integrity.EXPECTED_SHA256, ignoreCase = true) == true
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) {
            scope.launch {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.second.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
                Toast.makeText(context, if (ok) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val file = vm.createBackupFile()
                if (file == null) {
                    Toast.makeText(context, "备份失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
                file.delete()
                Toast.makeText(
                    context,
                    if (ok) "备份完成" else "写入失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreUri = uri }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle("接口")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址") },
                singleLine = true,
                supportingText = { Text("DeepSeek 用 https://api.deepseek.com；接本地 Ollama 就填它的地址") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("用 Keystore 加密存在本机，不会上传到别处") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                singleLine = true,
                supportingText = { Text("DeepSeek 用 deepseek-flash；接别家就填别家的模型名") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("思考过程")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示思考过程", fontSize = 15.sp)
                    Text(
                        "关掉以后只出回答，快一些，也省 token",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = thinking,
                    onCheckedChange = {
                        thinking = it
                        settings.thinking = it
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("想多深", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low" to "浅", "high" to "标准", "max" to "很深").forEach { (value, label) ->
                    FilterChip(
                        selected = effort == value,
                        onClick = {
                            effort = value
                            // 开关类设置改了就存，不然改完直接返回会丢
                            settings.effort = value
                        },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("像人一样想", fontSize = 15.sp)
                    Text(
                        "额外约束思考的语气，别写成解题报告",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = thinkingStyle,
                    onCheckedChange = {
                        thinkingStyle = it
                        settings.thinkingStyle = it
                    }
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionTitle("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = themeMode == value,
                            onClick = {
                                themeMode = value
                                settings.themeMode = value
                                onThemeChanged()
                            },
                            label = { Text(label) }
                        )
                    }
            }

            Spacer(Modifier.height(22.dp))
            SectionTitle("数据")
            SettingsRow(
                title = "导出全部对话",
                subtitle = "所有会话导成一个 Markdown，按知识库分组"
            ) {
                val payload = vm.exportAll()
                exportPayload = payload
                exportLauncher.launch(payload.first)
            }

            SettingsRow(
                title = "备份全部数据",
                subtitle = "数据库 + 图片打包成 zip，换手机、重装都用它"
            ) {
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                    .format(java.util.Date())
                backupLauncher.launch("zhiwo-backup-$day.zip")
            }

            SettingsRow(
                title = "从备份文件恢复",
                subtitle = "选一个 zip（含图片）或老的 .db，当前数据会被替换"
            ) {
                restoreLauncher.launch(arrayOf("*/*"))
            }

            SettingsRow(
                title = "导出诊断日志",
                subtitle = "崩过之后在这里拿堆栈，直接分享出去"
            ) {
                val log = vm.diagnosticLog()
                if (log == null) {
                    Toast.makeText(context, "没有崩溃记录", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_SEND
                    ).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "德谬歌矩阵 诊断日志")
                        putExtra(android.content.Intent.EXTRA_TEXT, log)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "发送日志")
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionTitle("总 AI")
            OutlinedTextField(
                value = masterName,
                onValueChange = { masterName = it },
                label = { Text("名字") },
                singleLine = true,
                supportingText = { Text("默认 Demiurge，改成你叫得顺口的就行") },
                modifier = Modifier.fillMaxWidth()
            )

            SettingsRow(
                title = "用户档案",
                subtitle = "总 AI 眼里的「你是什么人」，可以删掉记错的条目"
            ) {
                profileLines = vm.profileLines()
                showProfile = true
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("它是谁", Modifier.weight(1f))
                TextButton(onClick = { prompt = Persona.DEFAULT }) {
                    Text("恢复默认", fontSize = 12.sp)
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp)
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    settings.baseUrl = baseUrl.trim()
                    settings.apiKey = apiKey.trim()
                    settings.model = model.trim()
                    settings.thinking = thinking
                    settings.effort = effort
                    settings.thinkingStyle = thinkingStyle
                    settings.masterName = masterName
                    settings.systemPrompt = prompt
                    savedAt = System.currentTimeMillis()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (savedAt > 0) "已保存" else "保存")
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "所有内容只存在这台手机上。Key 存在应用私有目录里，不会上传到别处。",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(30.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(14.dp))

            Text(
                text = "德谬歌矩阵 v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "开发者  Cyrecho",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            signatureSha?.let { sha ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "签名  ${sha.take(16)}…",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (genuine) {
                        "✓ 与官方签名一致"
                    } else {
                        "⚠ 签名和官方不一致，这个包被重新签过"
                    },
                    fontSize = 11.sp,
                    color = if (genuine) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("确认恢复？") },
            text = {
                Text(
                    "当前的对话、知识库、档案都会被备份里的内容替换，且不能撤销。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = uri
                    restoreUri = null
                    vm.restoreFromFile(target)
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreUri = null }) { Text("取消") }
            }
        )
    }

    if (showProfile) {
        AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("用户档案", fontSize = 16.sp) },
            text = {
                if (profileLines.isEmpty()) {
                    Text(
                        "还是空的。长按一条对话 →「加入用户档案」，它会从里面提炼关于你的事。",
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        profileLines.forEach { line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = line.removePrefix("- "),
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    vm.removeProfileLine(line)
                                    profileLines = vm.profileLines()
                                }) { Text("删", fontSize = 12.sp) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfile = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("→", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp)
    )
}
