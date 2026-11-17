package com.zhiwo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.app.AppViewModel
import com.zhiwo.app.net.ShareImporter
import com.zhiwo.app.net.SharePreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onImported: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SharePreview?>(null) }

    val zipState by vm.zipImport.collectAsStateWithLifecycle()
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importExportZip(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("导入对话", fontSize = 17.sp) },
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
            // ---- 官方数据包 ----
            Text("从官方数据包导入", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "DeepSeek 网页端 → 设置 → 导出所有历史对话，会给你一个 zip。" +
                    "把它选进来，所有会话连同分支一起导回本地。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    zipPicker.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                },
                enabled = !zipState.running
            ) {
                Text(if (zipState.running) "导入中…" else "选择 zip")
            }
            zipState.message?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (zipState.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (zipState.running && zipState.done > 0) {
                            "$msg 已处理 ${zipState.done} 个会话"
                        } else {
                            msg
                        },
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "或者：粘贴分享链接",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "把 DeepSeek 的分享链接贴进来，就能接着那条对话继续聊。",
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "分享只在对方服务器上保留了最终那条主链，历史分叉不会带回来。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    error = null
                    preview = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://chat.deepseek.com/share/...", fontSize = 13.sp) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    loading = true
                    error = null
                    preview = null
                    scope.launch {
                        try {
                            preview = ShareImporter.fetch(link)
                        } catch (e: Exception) {
                            error = e.message ?: "读取失败"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && link.isNotBlank()
            ) {
                Text("读取")
            }

            if (loading) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("  正在抓…", fontSize = 13.sp)
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            preview?.let { p ->
                Spacer(Modifier.height(20.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = p.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${p.turns.size} 条消息 · ${p.totalChars} 字",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val id = vm.saveImported(p)
                                onImported(id)
                            }) { Text("导入并继续") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "这个接口是人家前端用的，没有公开承诺。哪天他们改了，这里会提示读取失败，" +
                    "到时候再换方案。",
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
