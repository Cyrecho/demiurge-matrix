package com.zhiwo.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwo.app.AppViewModel
import com.zhiwo.app.BranchInfo
import com.zhiwo.app.R
import com.zhiwo.app.data.Conversation
import com.zhiwo.app.data.Kb
import com.zhiwo.app.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    onNewChat: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.chat.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var input by rememberSaveable { mutableStateOf("") }
    // 正在修改的那条消息：不再弹对话框，直接回填到输入框
    var editingMsg by remember { mutableStateOf<Message?>(null) }
    // 长按弹出的操作面板
    var sheetMsg by remember { mutableStateOf<Message?>(null) }
    var showTree by remember { mutableStateOf(false) }
    // 对话记录抽屉
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val masters by vm.masters.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 选图片：系统照片选择器（不需要存储权限）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { copyImageToCache(context, uri) }
                if (path != null) {
                    vm.addPendingImage(path)
                } else {
                    Toast.makeText(context, "这张图读不出来", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 文件：纯文本本地读入，PDF/Word/PPT 交给服务端解析
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            // 只按扩展名判断是不是纯文本。文件管理器给的 MIME 不可靠，
            // .pptx 有时会报成 text/plain，那样会把二进制内容读进输入框变成乱码。
            val textExt = setOf(
                "txt", "md", "markdown", "csv", "tsv", "json", "jsonl",
                "xml", "yaml", "yml", "log", "ini", "conf", "cfg", "toml",
                "properties", "sql", "kt", "java", "py", "js", "ts", "tsx",
                "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
                "php", "sh", "bat", "ps1", "html", "htm", "css", "scss"
            )
            val isPlainText = name.substringAfterLast('.', "").lowercase() in textExt

            if (isPlainText) {
                scope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()
                                ?.use { it.readText() }
                        }
                        if (text.isNullOrBlank()) {
                            Toast.makeText(context, "这个文件读不出文字", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipped = if (text.length > 20000) text.take(20000) else text
                            input = if (input.isBlank()) clipped else input + "\n\n" + clipped
                            Toast.makeText(context, "已读入 ${clipped.length} 字", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "读取失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                vm.uploadDocument(uri, name)
            }
        }
    }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var moving by remember { mutableStateOf<Conversation?>(null) }
    var creatingKb by remember { mutableStateOf(false) }
    var newKbName by remember { mutableStateOf("") }
    var selectedKb by remember { mutableStateOf<String?>(null) }
    var kbMenu by remember { mutableStateOf<Kb?>(null) }
    var kbRename by remember { mutableStateOf<Kb?>(null) }
    var kbDelete by remember { mutableStateOf<Kb?>(null) }
    var kbNameText by remember { mutableStateOf("") }
    var contextInfo by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var exportPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var actionConv by remember { mutableStateOf<Conversation?>(null) }
    var kbPersona by remember { mutableStateOf<Kb?>(null) }
    var kbPersonaText by remember { mutableStateOf("") }
    var scopeConv by remember { mutableStateOf<Conversation?>(null) }
    var scopeSelection by remember { mutableStateOf<Set<String>>(emptySet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.second.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (ok) "已导出" else "导出失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val org by vm.organize.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearNotice()
        }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) vm.refreshList()
    }

    val messages = state.messages

    // 用户是不是还贴着底部看。手指往上翻之后就不该再把他拽回来。
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 160
        }
    }

    // 生成时跟随滚动：只补「最后一条底部露出视口」那点差值，
    // 不是每帧硬跳到末尾。这样文字是往下流的，不会一顿一顿。
    LaunchedEffect(messages.size, state.liveText.length, state.liveThinking.length) {
        if (messages.isEmpty() || !nearBottom) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull { it.index == messages.lastIndex }
        if (last == null) {
            listState.scrollToItem(messages.lastIndex)
        } else {
            val overshoot = last.offset + last.size - info.viewportEndOffset
            if (overshoot > 0) listState.scrollBy(overshoot.toFloat())
        }
    }
    LaunchedEffect(editingMsg?.id) {
        if (editingMsg != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    if (showTree) {
        // 树界面是盖在聊天页上的，不拦返回键会直接退出 app
        androidx.activity.compose.BackHandler { showTree = false }
        TreeScreen(
            vm = vm,
            convId = state.conv?.id,
            activeLeafId = state.conv?.activeLeafId,
            onClose = { showTree = false }
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.width(304.dp)
            ) {
                HistoryDrawer(
                    conversations = conversations,
                    currentId = state.conv?.id,
                    kbs = kbs,
                    selectedKbId = selectedKb,
                    onSelectKb = { selectedKb = it },
                    onNewKb = { creatingKb = true },
                    onOpenMaster = { id ->
                        scope.launch { drawerState.close() }
                        vm.openMaster(id)
                    },
                    masterInstances = masters,
                    onNewMaster = {
                        scope.launch { drawerState.close() }
                        vm.newMasterVariant()
                    },
                    onDeleteMaster = { vm.deleteMasterVariant(it) },
                    onMasterScope = { conv ->
                        scopeConv = conv
                        scopeSelection = conv.kbScope.toSet()
                    },
                    onOpenKbMaster = { kbId ->
                        scope.launch { drawerState.close() }
                        vm.openKbMaster(kbId)
                    },
                    masterName = vm.masterName,
                    onKbMenu = { kb ->
                        scope.launch { drawerState.close() }
                        kbMenu = kb
                    },
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onOpen = { id ->
                        scope.launch { drawerState.close() }
                        vm.openConversation(id)
                    },
                    onLongPress = { conv ->
                        scope.launch { drawerState.close() }
                        actionConv = conv
                    },
                    onOpenImport = {
                        scope.launch { drawerState.close() }
                        onOpenImport()
                    },
                    onOrganize = {
                        scope.launch { drawerState.close() }
                        vm.organizeAll()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }
                )
            }
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.conv?.title ?: "新对话",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ds_menu),
                            contentDescription = "对话记录",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTree = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ds_branch),
                            contentDescription = "分支结构",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ds_new_chat),
                            contentDescription = "新对话",
                            modifier = Modifier.size(22.dp)
                        )
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
                .imePadding()
        ) {
            if (messages.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "说点什么就开始。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageItem(
                            msg = msg,
                            branch = state.branches[msg.id],
                            liveText = if (msg.id == state.liveId) state.liveText else null,
                            liveThinking = if (msg.id == state.liveId) state.liveThinking else null,
                            streaming = state.streaming && msg.id == state.liveId,
                            onLongPress = { sheetMsg = msg },
                            onCopy = {
                                clipboard.setText(AnnotatedString(msg.content))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onRegenerate = { vm.regenerate(msg) },
                            onSwitchBranch = { vm.switchBranch(it) }
                        )
                    }
                }
            }

            state.searchHint?.let { hint ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(LocalZhiwoExtra.current.thinkingBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = hint,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.5.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { vm.dismissError() }) { Text("知道了") }
                    }
                }
            }

            editingMsg?.let {
                EditingBanner(
                    onCancel = {
                        editingMsg = null
                        input = ""
                        // 退出编辑时把附件清掉，不然会带进下一条消息
                        vm.clearPendingAttachments()
                    }
                )
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                streaming = state.streaming,
                thinkingOn = state.thinkingOn,
                searchOn = state.searchOn,
                editing = editingMsg != null,
                focusRequester = focusRequester,
                onToggleThinking = { vm.setThinking(!state.thinkingOn) },
                onSearchClick = {
                    vm.setSearch(!state.searchOn)
                    if (!state.searchOn) {
                        Toast.makeText(context, "已开启联网，由模型自己判断要不要搜", Toast.LENGTH_SHORT).show()
                    }
                },
                onPickFile = {
                    // 放开成 */*：有些机型对 pptx/docx 的 MIME 过滤会把文件藏起来，
                    // 类型判断改在回调里按扩展名做
                    filePicker.launch(arrayOf("*/*"))
                },
                onPickImage = {
                    imagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                pendingImages = state.pendingImages,
                onRemoveImage = { vm.removePendingImage(it) },
                pendingDocs = state.pendingDocs,
                onRemoveDoc = { vm.removePendingDoc(it) },
                onSend = {
                    val t = input.trim()
                    val hasAttach = state.pendingImages.isNotEmpty() || state.pendingDocs.isNotEmpty()
                    if (t.isNotEmpty() || hasAttach) {
                        val editing = editingMsg
                        if (editing != null) {
                            vm.editUser(editing, t)
                            editingMsg = null
                        } else {
                            vm.send(t)
                        }
                        input = ""
                    }
                },
                onStop = { vm.stop() }
            )
        }
    }

    }

    val sheet = sheetMsg
    if (sheet != null) {
        MessageActionSheet(
            msg = sheet,
            onDismiss = { sheetMsg = null },
            onCopy = {
                clipboard.setText(AnnotatedString(sheet.content))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                sheetMsg = null
            },
            onShare = {
                shareText(context, sheet.content)
                sheetMsg = null
            },
            onRegenerate = {
                vm.regenerate(sheet)
                sheetMsg = null
            },
            onEdit = {
                // 把这条消息原来的附件装回待发送区，否则一改字图就没了
                vm.clearPendingAttachments()
                sheet.attachments.forEach { att ->
                    if (att.startsWith("/")) {
                        vm.addPendingImage(att)
                    } else {
                        val ref = parseDocRef(att)
                        vm.addPendingDoc("ok|${ref.fileId}|${ref.name}")
                    }
                }
                editingMsg = sheet
                input = sheet.content
                sheetMsg = null
            }
        )
    }

    renaming?.let { conv ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(conv.id, renameText)
                    renaming = null
                }) { Text("好了") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }

    deleting?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉这个对话？", fontSize = 16.sp) },
            text = { Text("删了就找不回来了。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(conv.id)
                    // 删掉的如果是当前会话，vm 会把状态清成空白对话；
                    // 这里绝对不能再调 newChat()，否则立刻又建出一条空记录
                    deleting = null
                }) { Text("删吧") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("不了") }
            }
        )
    }

    moving?.let { conv ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("移动到知识库", fontSize = 16.sp) },
            text = {
                Column {
                    MoveRow("未分类") {
                        vm.moveConversation(conv.id, null)
                        moving = null
                    }
                    kbs.forEach { kb ->
                        MoveRow(kb.name) {
                            vm.moveConversation(conv.id, kb.id)
                            moving = null
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moving = null }) { Text("取消") }
            }
        )
    }

    if (creatingKb) {
        AlertDialog(
            onDismissRequest = { creatingKb = false },
            title = { Text("新建知识库", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = newKbName,
                    onValueChange = { newKbName = it },
                    singleLine = true,
                    placeholder = { Text("比如：线性代数", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createKb(newKbName)
                    newKbName = ""
                    creatingKb = false
                }) { Text("建好了") }
            },
            dismissButton = {
                TextButton(onClick = {
                    creatingKb = false
                    newKbName = ""
                }) { Text("取消") }
            }
        )
    }

    kbMenu?.let { kb ->
        AlertDialog(
            onDismissRequest = { kbMenu = null },
            title = { Text(kb.name, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "记忆权重 ${kb.weight}/100",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                    MoveRow("提高权重 +10") {
                        vm.setKbWeight(kb.id, kb.weight + 10)
                        kbMenu = null
                    }
                    MoveRow("降低权重 -10") {
                        vm.setKbWeight(kb.id, kb.weight - 10)
                        kbMenu = null
                    }
                    MoveRow("重命名") {
                        kbNameText = kb.name
                        kbRename = kb
                        kbMenu = null
                    }
                    MoveRow("删除这个知识库") {
                        kbDelete = kb
                        kbMenu = null
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { kbMenu = null }) { Text("取消") }
            }
        )
    }

    kbRename?.let { kb ->
        AlertDialog(
            onDismissRequest = { kbRename = null },
            title = { Text("重命名知识库", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = kbNameText,
                    onValueChange = { kbNameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameKb(kb.id, kbNameText)
                    kbRename = null
                }) { Text("好了") }
            },
            dismissButton = {
                TextButton(onClick = { kbRename = null }) { Text("取消") }
            }
        )
    }

    kbDelete?.let { kb ->
        AlertDialog(
            onDismissRequest = { kbDelete = null },
            title = { Text("删掉「${kb.name}」？", fontSize = 16.sp) },
            text = {
                Text(
                    "库里的对话不会被删，只会变成未分类。",
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteKb(kb.id)
                    if (selectedKb == kb.id) selectedKb = null
                    kbDelete = null
                }) { Text("删吧") }
            },
            dismissButton = {
                TextButton(onClick = { kbDelete = null }) { Text("不了") }
            }
        )
    }

    actionConv?.let { conv ->
        ConversationActionSheet(
            conv = conv,
            onDismiss = { actionConv = null },
            onPin = {
                vm.setPinned(conv.id, !conv.pinned)
                actionConv = null
            },
            onRename = {
                renaming = conv
                renameText = conv.title
                actionConv = null
            },
            onMove = {
                moving = conv
                actionConv = null
            },
            onUnlock = {
                vm.unlockConversation(conv.id)
                actionConv = null
            },
            onAddProfile = {
                vm.addToProfile(conv.id)
                actionConv = null
            },
            onContext = {
                contextInfo = Triple(
                    conv.id,
                    vm.contextTokens(conv.id),
                    vm.contextMessageCount(conv.id)
                )
                actionConv = null
            },
            onExport = {
                val payload = vm.exportConversation(conv.id)
                actionConv = null
                if (payload == null) {
                    Toast.makeText(context, "这条对话导不出来", Toast.LENGTH_SHORT).show()
                } else {
                    exportPayload = payload
                    exportLauncher.launch(payload.first)
                }
            },
            onDelete = {
                deleting = conv
                actionConv = null
            }
        )
    }

    scopeConv?.let { conv ->
        AlertDialog(
            onDismissRequest = { scopeConv = null },
            title = { Text("知识范围", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "只让这个 ${conv.title} 关心选中的库：记忆、检索都收在里面。一个都不选就是全部。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (kbs.isEmpty()) {
                        Text("还没有知识库", fontSize = 13.sp)
                    } else {
                        kbs.forEach { kb ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scopeSelection = if (kb.id in scopeSelection) {
                                            scopeSelection - kb.id
                                        } else {
                                            scopeSelection + kb.id
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = kb.id in scopeSelection,
                                    onCheckedChange = { checked ->
                                        scopeSelection = if (checked) {
                                            scopeSelection + kb.id
                                        } else {
                                            scopeSelection - kb.id
                                        }
                                    }
                                )
                                Text(kb.name, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setMasterScope(conv.id, scopeSelection.toList())
                    scopeConv = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { scopeConv = null }) { Text("取消") }
            }
        )
    }

    kbPersona?.let { kb ->
        AlertDialog(
            onDismissRequest = { kbPersona = null },
            title = { Text("${kb.name} · 助手人格", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "留空就用默认人格。这里写的内容会放在提示词最前面，记忆和档案照常接在后面。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = kbPersonaText,
                        onValueChange = { kbPersonaText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        placeholder = {
                            Text(
                                "比如：你是一位只谈线性代数的助教，说话简洁克制……",
                                fontSize = 12.sp
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setKbAssistantPrompt(kb.id, kbPersonaText)
                    kbPersona = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { kbPersona = null }) { Text("取消") }
            }
        )
    }

    contextInfo?.let { (convId, tokens, count) ->
        val limit = 1_000_000
        val percent = (tokens * 100.0 / limit)
        AlertDialog(
            onDismissRequest = { contextInfo = null },
            title = { Text("上下文占用", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "约 ${formatTokens(tokens)} token · $count 条消息",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "模型能装 100 万，现在用了 ${"%.1f".format(percent)}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "压缩会把前半段的原文换成一段摘要，之后照常聊，但更省 token。旧消息会被删掉，摘要留着。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    contextInfo = null
                    vm.compressConversation(convId)
                }) { Text("压缩前半段") }
            },
            dismissButton = {
                TextButton(onClick = { contextInfo = null }) { Text("关闭") }
            }
        )
    }

    if (org.running || org.message != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissOrganize() },
            title = {
                Text(if (org.running) "正在整理" else "整理", fontSize = 16.sp)
            },
            text = {
                if (org.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${org.done}/${org.total}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = org.message ?: "",
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                if (!org.running) {
                    TextButton(onClick = { vm.dismissOrganize() }) { Text("知道了") }
                }
            }
        )
    }
}

@Composable
private fun MoveRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 长按消息后从底部升起的操作面板，替代系统文本菜单。 */
@Composable
private fun MessageActionSheet(
    msg: Message,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // 必须 remember，否则每次重组都新建一个，点击涟漪状态会乱
    val dismissInteraction = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(
                    indication = null,
                    interactionSource = dismissInteraction
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = scheme.surface,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (msg.isUser) {
                        SheetAction(R.drawable.ic_ds_edit, "修改", onEdit)
                    } else {
                        SheetAction(R.drawable.ic_ds_refresh, "重新生成", onRegenerate)
                    }
                    SheetAction(R.drawable.ic_ds_copy, "复制", onCopy)
                    SheetAction(R.drawable.ic_ds_share, "分享", onShare)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Icon(
            painter = painterResource(res),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    msg: Message,
    branch: BranchInfo?,
    liveText: String?,
    liveThinking: String?,
    streaming: Boolean,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onSwitchBranch: (String) -> Unit
) {
    var liked by remember { mutableStateOf(false) }
    var disliked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val text = liveText ?: msg.content
    val thinking = liveThinking ?: msg.thinking

    Box(Modifier.fillMaxWidth()) {
        if (msg.isUser) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                horizontalAlignment = Alignment.End
            ) {
                Surface(
                    color = LocalZhiwoExtra.current.userBubble,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        msg.attachments.forEachIndexed { index, path ->
                            if (index > 0) Spacer(Modifier.height(6.dp))
                            if (path.startsWith("/")) {
                                AttachmentImage(
                                    path = path,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                // 上传过的文档：用文件卡片，别再当图片解码
                                val ref = parseDocRef(path)
                                FileChip(fileName = ref.name, status = ref.status)
                            }
                        }
                        if (msg.attachments.isNotEmpty() && msg.content.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                        }
                        if (msg.content.isNotBlank()) {
                            Text(
                                text = msg.content,
                                color = LocalZhiwoExtra.current.onUserBubble,
                                fontSize = 15.5.sp,
                                lineHeight = 23.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                if (branch != null && branch.count > 1) {
                    Spacer(Modifier.height(2.dp))
                    BranchSwitcher(branch, onSwitchBranch)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
            ) {
                if (thinking.isNotBlank()) {
                    ThinkingPanel(
                        thinking = thinking,
                        streaming = streaming && text.isBlank()
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (text.isBlank() && streaming) {
                    Text(
                        text = if (thinking.isBlank()) "……" else "正在想",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                } else {
                    MarkdownView(text = text)
                }

                if (msg.citations.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "参考来源",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    msg.citations.take(5).forEach { c ->
                        Text(
                            text = "· ${c.title}",
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clickable { openUrl(context, c.url) }
                        )
                    }
                }

                if (msg.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "出错了：${msg.error}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.5.sp
                    )
                }

                if (!streaming && text.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (branch != null && branch.count > 1) {
                            BranchSwitcher(branch, onSwitchBranch)
                            Spacer(Modifier.width(2.dp))
                        }
                        ActionIcon(R.drawable.ic_ds_copy, "复制") {
                            onCopy()
                        }
                        ActionIcon(
                            res = if (liked) R.drawable.ic_ds_like_fill else R.drawable.ic_ds_like,
                            desc = "赞",
                            tint = if (liked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            liked = !liked
                            if (liked) disliked = false
                        }
                        ActionIcon(
                            res = if (disliked) R.drawable.ic_ds_dislike_fill else R.drawable.ic_ds_dislike,
                            desc = "踩",
                            tint = if (disliked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            disliked = !disliked
                            if (disliked) liked = false
                        }
                        Spacer(Modifier.weight(1f))
                        ActionIcon(R.drawable.ic_ds_refresh, "重新生成") {
                            onRegenerate()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchSwitcher(branch: BranchInfo, onSwitch: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { branch.prevId?.let(onSwitch) },
            enabled = branch.prevId != null,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ds_chevron_left),
                contentDescription = "上一个分支",
                tint = if (branch.prevId != null) scheme.onSurfaceVariant
                else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = "${branch.index}/${branch.count}",
            fontSize = 11.5.sp,
            color = scheme.onSurfaceVariant
        )
        IconButton(
            onClick = { branch.nextId?.let(onSwitch) },
            enabled = branch.nextId != null,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ds_chevron_right),
                contentDescription = "下一个分支",
                tint = if (branch.nextId != null) scheme.onSurfaceVariant
                else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun ActionIcon(
    @DrawableRes res: Int,
    desc: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            painter = painterResource(res),
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

/** 修改消息时显示在输入框上方的一条提示，替代弹窗。 */
@Composable
private fun EditingBanner(onCancel: () -> Unit) {
    Surface(color = LocalZhiwoExtra.current.thinkingBg) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ds_edit),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "修改输入",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "发送后从这条岔开，原来的保留",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "取消",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onCancel() }
                    .padding(4.dp)
            )
        }
    }
}

/**
 * 从相册选来的图片复制到 app 私有目录，顺便压到最长边 1280、JPEG 84。
 * 相册原图动辄几 MB，base64 之后更大，不压会把请求体撑爆。
 */
private fun copyImageToCache(context: Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxEdge = 1280
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(raw.width, raw.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else raw
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 84, it) }
        file.absolutePath
    } catch (e: Throwable) {
        null
    }
}

/** 把 token 数说成人话：12.3K / 1.2M。 */
private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1000.0)
    else -> tokens.toString()
}

/** 从 content:// 里取真实文件名，取不到就退回路径。 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && index >= 0) c.getString(index) else null
        } ?: uri.lastPathSegment ?: "文件"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "文件"
    }
}

private fun openUrl(context: Context, url: String) {    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "打不开这个链接", Toast.LENGTH_SHORT).show()
    }
}

private fun shareText(context: Context, text: String) {    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享到"))
    } catch (e: Exception) {
        Toast.makeText(context, "没有可以分享的应用", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ThinkingPanel(thinking: String, streaming: Boolean) {
    // 生成时展开（让你看见它真的在想），结束后自动收起，只留一行预览
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (!streaming) expanded = false
    }
    val scheme = MaterialTheme.colorScheme

    Surface(
        color = LocalZhiwoExtra.current.thinkingBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ds_chevron_right),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(if (expanded) 90f else 0f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "正在想…" else "思考过程",
                    fontSize = 12.5.sp,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${thinking.length} 字",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "展开",
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                // 展开就是整段铺开，跟着页面滚，不再限制在固定高度里自己滑
                MarkdownView(
                    text = thinking,
                    textSize = 13.sp,
                    lineHeight = 20.sp,
                    bodyColor = scheme.onSurfaceVariant.copy(alpha = 0.92f)
                )
            } else if (thinking.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = thinking.replace('\n', ' ').trim().take(70),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    thinkingOn: Boolean,
    searchOn: Boolean,
    editing: Boolean,
    focusRequester: FocusRequester,
    onToggleThinking: () -> Unit,
    onSearchClick: () -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    pendingImages: List<String>,
    onRemoveImage: (String) -> Unit,
    pendingDocs: List<String>,
    onRemoveDoc: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(
                        res = R.drawable.ic_ds_think,
                        label = "深度思考",
                        active = thinkingOn,
                        onClick = onToggleThinking
                    )
                    ToggleChip(
                        res = R.drawable.ic_ds_search,
                        label = "智能搜索",
                        active = searchOn,
                        onClick = onSearchClick
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // 待发送的文档卡片
            if (pendingDocs.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingDocs.forEach { entry ->
                        val ref = parseDocRef(entry)
                        FileChip(
                            fileName = ref.name,
                            status = ref.status,
                            onRemove = { onRemoveDoc(entry) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (pendingImages.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEach { path ->
                        Box {
                            AttachmentImage(
                                path = path,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { onRemoveImage(path) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("×", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text("发消息或按住说话", fontSize = 15.sp) },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalZhiwoExtra.current.inputBar,
                        unfocusedContainerColor = LocalZhiwoExtra.current.inputBar,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(Modifier.width(8.dp))
                // 原版的 ⊕：点开是「图片 / 文本文件」
                // 不用 IconButton：它有 48dp 的强制最小触摸尺寸，会盖掉我们设的尺寸
                val canSend = streaming || value.isNotBlank() ||
                    pendingImages.isNotEmpty() || pendingDocs.isNotEmpty()
                Box {
                    var attachMenu by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { attachMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ds_plus),
                            contentDescription = "添加附件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenu,
                        onDismissRequest = { attachMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("图片") },
                            onClick = {
                                attachMenu = false
                                onPickImage()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("文本文件") },
                            onClick = {
                                attachMenu = false
                                onPickFile()
                            }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        )
                        .clickable(enabled = canSend) {
                            if (streaming) onStop() else onSend()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (streaming) R.drawable.ic_ds_stop else R.drawable.ic_ds_send
                        ),
                        contentDescription = if (streaming) "停止" else "发送",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    @DrawableRes res: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (active) scheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent
    val fg = if (active) scheme.primary else scheme.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (active) Color.Transparent else scheme.outline, shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(res),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, fontSize = 12.5.sp, color = fg)
    }
}
