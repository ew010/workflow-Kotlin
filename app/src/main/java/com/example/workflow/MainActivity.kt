package com.example.workflow

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val ACTION_RUN_WORKFLOW_SHORTCUT = "com.example.workflow.action.RUN_WORKFLOW_SHORTCUT"
private const val EXTRA_WORKFLOW_ID = "workflow_id"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: WorkflowViewModel = viewModel()
                WorkflowApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val operations: List<AtomicOperation>
)

@Serializable
sealed class AtomicOperation {
    abstract fun displayText(): String
}

@Serializable
@SerialName("delete_folder")
data class DeleteFolderOperation(
    val path: String
) : AtomicOperation() {
    override fun displayText(): String {
        return "删除目录: $path"
    }
}

@Serializable
@SerialName("copy_folder")
data class CopyFolderOperation(
    val sourcePath: String,
    val destinationPath: String
) : AtomicOperation() {
    override fun displayText(): String = "复制目录: $sourcePath -> $destinationPath"
}

@Serializable
@SerialName("set_system_time")
data class SetSystemTimeOperation(
    val auto: Boolean,
    val epochMillis: Long? = null
) : AtomicOperation() {
    override fun displayText(): String {
        return if (auto) {
            "系统时间: 自动设置"
        } else {
            "系统时间: 手动设置为 ${epochMillis ?: "(空)"}"
        }
    }
}

@Serializable
@SerialName("open_app")
data class OpenAppOperation(val packageName: String) : AtomicOperation() {
    override fun displayText(): String = "打开 APP: $packageName"
}

data class ExecutionResult(
    val success: Boolean,
    val message: String
)

data class InstalledApp(
    val appName: String,
    val packageName: String
)

class WorkflowRepository(private val app: Application) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val storeFile: File
        get() = File(app.filesDir, "workflows.json")

    suspend fun loadAll(): List<Workflow> = withContext(Dispatchers.IO) {
        if (!storeFile.exists()) {
            storeFile.writeText("[]")
            return@withContext emptyList()
        }

        val raw = storeFile.readText()
        if (raw.isBlank()) return@withContext emptyList()

        runCatching {
            json.decodeFromString(ListSerializer(Workflow.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    suspend fun save(workflow: Workflow) = withContext(Dispatchers.IO) {
        val old = loadAll().toMutableList()
        val idx = old.indexOfFirst { it.id == workflow.id }
        if (idx >= 0) {
            old[idx] = workflow
        } else {
            old.add(workflow)
        }

        storeFile.writeText(
            json.encodeToString(ListSerializer(Workflow.serializer()), old.toList())
        )
    }
}

class WorkflowExecutor(private val app: Application) {

    suspend fun execute(workflow: Workflow): List<ExecutionResult> {
        val results = mutableListOf<ExecutionResult>()

        for ((index, op) in workflow.operations.withIndex()) {
            val result = withContext(Dispatchers.IO) {
                runCatching { runOperation(op) }
                    .getOrElse { ExecutionResult(false, "步骤${index + 1}异常: ${it.message}") }
            }
            results.add(result)
            if (!result.success) {
                break
            }
        }

        return results
    }

    private fun runOperation(operation: AtomicOperation): ExecutionResult {
        return when (operation) {
            is DeleteFolderOperation -> deleteFolder(operation)
            is CopyFolderOperation -> copyFolder(operation.sourcePath, operation.destinationPath)
            is SetSystemTimeOperation -> setSystemTime(operation)
            is OpenAppOperation -> openApp(operation.packageName)
        }
    }

    private fun deleteFolder(operation: DeleteFolderOperation): ExecutionResult {
        if (!hasAllFilesAccess()) {
            openAllFilesAccessSettings(app)
            return ExecutionResult(false, "删除目录失败: 请开启所有文件访问权限")
        }

        val path = operation.path
        val dir = File(path)
        if (!dir.exists()) return ExecutionResult(true, "删除目录: 跳过，目录不存在")
        if (!dir.isDirectory) return ExecutionResult(false, "删除目录失败: 目标不是目录")

        return if (dir.deleteRecursively()) {
            ExecutionResult(true, "删除目录成功: $path")
        } else {
            ExecutionResult(false, "删除目录失败: $path")
        }
    }

    private fun copyFolder(sourcePath: String, destinationPath: String): ExecutionResult {
        val src = File(sourcePath)
        if (!src.exists() || !src.isDirectory) {
            return ExecutionResult(false, "复制失败: 源目录不存在或不是目录")
        }

        val dest = File(destinationPath)
        val ok = copyDirectoryRecursive(src, dest)
        return if (ok) {
            ExecutionResult(true, "复制目录成功: $sourcePath -> $destinationPath")
        } else {
            ExecutionResult(false, "复制目录失败: $sourcePath -> $destinationPath")
        }
    }

    private fun copyDirectoryRecursive(src: File, dest: File): Boolean {
        if (!dest.exists() && !dest.mkdirs()) {
            return false
        }

        src.listFiles()?.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                if (!copyDirectoryRecursive(file, target)) return false
            } else {
                if (!runCatching {
                        file.inputStream().use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }.isSuccess
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun setSystemTime(op: SetSystemTimeOperation): ExecutionResult {
        return if (op.auto) {
            val autoCmd = runShellAsRoot("settings put global auto_time 1")
            if (autoCmd) {
                ExecutionResult(true, "系统时间已设置为自动")
            } else {
                // Android 普通应用无法直接改系统时间，此处回退到系统设置页。
                val intent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                ExecutionResult(false, "无系统权限，已打开日期设置页面，请手动开启自动时间")
            }
        } else {
            val ms = op.epochMillis
                ?: return ExecutionResult(false, "设置时间失败: epochMillis 不能为空")
            val seconds = ms / 1000
            val disableAuto = runShellAsRoot("settings put global auto_time 0")
            val setTime = runShellAsRoot("date -s @$seconds")
            if (disableAuto && setTime) {
                ExecutionResult(true, "系统时间已设置为 ${Instant.ofEpochMilli(ms)}")
            } else {
                val intent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                ExecutionResult(false, "无系统权限，已打开日期设置页面，请手动设置时间")
            }
        }
    }

    private fun openApp(packageName: String): ExecutionResult {
        val launchIntent = app.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ExecutionResult(false, "打开 APP 失败: 找不到包名 $packageName")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launchIntent)
        return ExecutionResult(true, "打开 APP 成功: $packageName")
    }

    private fun runShellAsRoot(command: String): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val code = process.waitFor()
            code == 0
        }.getOrDefault(false)
    }
}

class WorkflowViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WorkflowRepository(app)
    private val executor = WorkflowExecutor(app)

    private val _workflows = MutableStateFlow<List<Workflow>>(emptyList())
    val workflows: StateFlow<List<Workflow>> = _workflows.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    init {
        refresh()
        loadInstalledApps()
    }

    fun refresh() {
        viewModelScope.launch {
            _workflows.value = repository.loadAll()
        }
    }

    fun saveWorkflow(name: String, operations: List<AtomicOperation>, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (name.isBlank()) {
                onDone(false, "工作流名称不能为空")
                return@launch
            }
            if (operations.isEmpty()) {
                onDone(false, "至少添加一个原子操作")
                return@launch
            }

            val workflow = Workflow(
                id = UUID.randomUUID().toString(),
                name = name,
                operations = operations
            )
            repository.save(workflow)
            refresh()
            onDone(true, "工作流已保存")
        }
    }

    fun runWorkflow(workflow: Workflow) {
        viewModelScope.launch {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            appendLog("[$time] 开始执行: ${workflow.name}")
            val results = executor.execute(workflow)
            results.forEach { result ->
                appendLog("  - ${if (result.success) "OK" else "FAIL"}: ${result.message}")
            }
            appendLog("[$time] 执行结束: ${workflow.name}")
        }
    }

    fun runWorkflowById(workflowId: String): Boolean {
        val target = _workflows.value.firstOrNull { it.id == workflowId } ?: return false
        runWorkflow(target)
        return true
    }

    fun pinWorkflowShortcut(workflow: Workflow, onDone: (Boolean, String) -> Unit) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onDone(false, "当前系统版本不支持固定桌面快捷方式")
            return
        }

        val shortcutManager = app.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            onDone(false, "当前桌面启动器不支持添加快捷方式")
            return
        }

        val launchIntent = Intent(app, MainActivity::class.java).apply {
            action = ACTION_RUN_WORKFLOW_SHORTCUT
            putExtra(EXTRA_WORKFLOW_ID, workflow.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val shortcut = ShortcutInfo.Builder(app, "workflow_${workflow.id}")
            .setShortLabel(workflow.name.take(20))
            .setLongLabel("执行工作流: ${workflow.name}")
            .setIcon(Icon.createWithResource(app, android.R.drawable.ic_media_play))
            .setIntent(launchIntent)
            .build()

        val accepted = shortcutManager.requestPinShortcut(shortcut, null)
        if (accepted) {
            onDone(true, "已请求添加到桌面，请在系统提示中确认")
        } else {
            onDone(false, "添加到桌面失败")
        }
    }

    private fun appendLog(text: String) {
        _logs.value = (_logs.value + text).takeLast(100)
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val pm = app.packageManager
                val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(queryIntent, 0)
                    .mapNotNull { resolveInfo ->
                        val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                        val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
                        if (label.isBlank()) return@mapNotNull null
                        InstalledApp(appName = label, packageName = pkg)
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.appName.lowercase() }
            }
        }
    }
}

enum class OperationType(val title: String) {
    DELETE("删除文件夹"),
    COPY("复制文件夹"),
    SET_TIME("设置系统时间"),
    OPEN_APP("打开 App")
}

@Composable
private fun WorkflowApp(vm: WorkflowViewModel) {
    val workflows by vm.workflows.collectAsState()
    val logs by vm.logs.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    var creatorMode by remember { mutableStateOf(false) }
    var handledShortcutSignature by remember { mutableStateOf<String?>(null) }
    var listStatus by remember { mutableStateOf("") }
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(workflows.size, activity?.intent?.action, activity?.intent?.getStringExtra(EXTRA_WORKFLOW_ID)) {
        val intent = activity?.intent ?: return@LaunchedEffect
        if (intent.action != ACTION_RUN_WORKFLOW_SHORTCUT) return@LaunchedEffect
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return@LaunchedEffect
        if (workflowId.isBlank()) return@LaunchedEffect
        if (workflows.isEmpty()) return@LaunchedEffect
        val signature = "${intent.action}:$workflowId"
        if (handledShortcutSignature == signature) return@LaunchedEffect

        val ok = vm.runWorkflowById(workflowId)
        listStatus = if (ok) "已通过桌面快捷方式执行工作流" else "快捷方式对应的工作流不存在"
        handledShortcutSignature = signature
    }

    if (creatorMode) {
        CreateWorkflowScreen(
            installedApps = installedApps,
            allFilesAccess = allFilesAccess,
            onRequestAllFilesAccess = { openAllFilesAccessSettings(context) },
            onBack = { creatorMode = false },
            onSave = { name, operations, onResult ->
                vm.saveWorkflow(name, operations) { ok, msg ->
                    onResult(ok, msg)
                    if (ok) creatorMode = false
                }
            }
        )
    } else {
        WorkflowListScreen(
            workflows = workflows,
            logs = logs,
            status = listStatus,
            allFilesAccess = allFilesAccess,
            onRequestAllFilesAccess = { openAllFilesAccessSettings(context) },
            onRefresh = vm::refresh,
            onRun = vm::runWorkflow,
            onPin = { workflow ->
                vm.pinWorkflowShortcut(workflow) { _, message ->
                    listStatus = message
                }
            },
            onCreate = { creatorMode = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowListScreen(
    workflows: List<Workflow>,
    logs: List<String>,
    status: String,
    allFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onRefresh: () -> Unit,
    onRun: (Workflow) -> Unit,
    onPin: (Workflow) -> Unit,
    onCreate: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("工作流") },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = onCreate) { Text("新建") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!allFilesAccess) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("未开启所有文件访问权限", fontWeight = FontWeight.Bold)
                            Text(
                                "若需要直接按路径删除外部目录，请先开启权限。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = onRequestAllFilesAccess) {
                                Text("开启权限")
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("已保存工作流")
            }

            if (workflows.isEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("暂无工作流", fontWeight = FontWeight.Bold)
                            Text("点击右上角“新建”创建", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(workflows, key = { it.id }) { wf ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(wf.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            wf.operations.forEachIndexed { index, op ->
                                Text("${index + 1}. ${op.displayText()}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onRun(wf) }) { Text("执行") }
                                OutlinedButton(onClick = { onPin(wf) }) { Text("加到桌面") }
                            }
                        }
                    }
                }
            }

            if (status.isNotBlank()) {
                item {
                    StatusBanner(status)
                }
            }

            item {
                SectionHeader("执行日志")
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(min = 120.dp, max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                        } else {
                            logs.forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateWorkflowScreen(
    installedApps: List<InstalledApp>,
    allFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onBack: () -> Unit,
    onSave: (String, List<AtomicOperation>, (Boolean, String) -> Unit) -> Unit
) {
    var workflowName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(OperationType.DELETE) }
    var status by remember { mutableStateOf("") }

    val operations = remember { mutableStateListOf<AtomicOperation>() }

    var deletePath by remember { mutableStateOf("") }
    var copySource by remember { mutableStateOf("") }
    var copyTarget by remember { mutableStateOf("") }
    var autoTime by remember { mutableStateOf(true) }
    var manualEpoch by remember { mutableStateOf("") }
    var appNameQuery by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var showDeletePicker by remember { mutableStateOf(false) }
    var showCopySourcePicker by remember { mutableStateOf(false) }
    var showCopyTargetPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scroll = rememberScrollState()
    val basePickerDir = remember(allFilesAccess) {
        if (allFilesAccess) {
            Environment.getExternalStorageDirectory()
        } else {
            context.filesDir
        }
    }

    fun resolveStartDir(path: String): File {
        val candidate = File(path)
        return if (candidate.exists() && candidate.isDirectory) candidate else basePickerDir
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("创建工作流") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("基本信息")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = workflowName,
                        onValueChange = { workflowName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("工作流名称") }
                    )
                }
            }

            SectionHeader("原子操作")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择操作类型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OperationType.entries.forEach { type ->
                            if (selectedType == type) {
                                Button(onClick = { selectedType = type }) { Text(type.title) }
                            } else {
                                OutlinedButton(onClick = { selectedType = type }) { Text(type.title) }
                            }
                        }
                    }

                    Divider()

                    when (selectedType) {
                        OperationType.DELETE -> {
                            OutlinedTextField(
                                value = deletePath,
                                onValueChange = { deletePath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("要删除的目录绝对路径") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDeletePicker = true }) {
                                    Text("选择目录")
                                }
                                if (!allFilesAccess) {
                                    OutlinedButton(onClick = onRequestAllFilesAccess) {
                                        Text("开启权限")
                                    }
                                }
                            }
                            if (!allFilesAccess) {
                                Text(
                                    "未开启所有文件访问权限时，路径删除仅限应用私有目录。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        OperationType.COPY -> {
                            OutlinedTextField(
                                value = copySource,
                                onValueChange = { copySource = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("源目录绝对路径") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showCopySourcePicker = true }) {
                                    Text("选择源目录")
                                }
                            }
                            OutlinedTextField(
                                value = copyTarget,
                                onValueChange = { copyTarget = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("目标目录绝对路径") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showCopyTargetPicker = true }) {
                                    Text("选择目标目录")
                                }
                            }
                        }

                        OperationType.SET_TIME -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = autoTime,
                                    onCheckedChange = { autoTime = it },
                                    modifier = Modifier.size(24.dp)
                                )
                                Text("自动设置系统时间")
                            }
                            if (!autoTime) {
                                OutlinedTextField(
                                    value = manualEpoch,
                                    onValueChange = { manualEpoch = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("目标时间 epochMillis (毫秒)") }
                                )
                            }
                        }

                        OperationType.OPEN_APP -> {
                            OutlinedTextField(
                                value = appNameQuery,
                                onValueChange = { appNameQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("按应用名搜索（如 微信）") }
                            )
                            val filteredApps = remember(appNameQuery, installedApps) {
                                val q = appNameQuery.trim()
                                if (q.isBlank()) {
                                    installedApps.take(6)
                                } else {
                                    installedApps.filter { it.appName.contains(q, ignoreCase = true) }.take(6)
                                }
                            }
                            if (filteredApps.isNotEmpty()) {
                                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        filteredApps.forEach { app ->
                                            TextButton(
                                                onClick = {
                                                    packageName = app.packageName
                                                    appNameQuery = app.appName
                                                    status = "已选择应用: ${app.appName}"
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("${app.appName} (${app.packageName})")
                                            }
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = packageName,
                                onValueChange = { packageName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("App 包名（如 com.android.settings）") }
                            )
                        }
                    }

                    Button(onClick = {
                        val op = when (selectedType) {
                        OperationType.DELETE -> {
                            if (deletePath.isBlank()) {
                                status = "请输入删除目录路径"
                                null
                            } else {
                                DeleteFolderOperation(path = deletePath)
                            }
                        }

                            OperationType.COPY -> {
                                if (copySource.isBlank() || copyTarget.isBlank()) {
                                    status = "请输入复制源路径和目标路径"
                                    null
                                } else {
                                    CopyFolderOperation(copySource, copyTarget)
                                }
                            }

                            OperationType.SET_TIME -> {
                                if (autoTime) {
                                    SetSystemTimeOperation(auto = true)
                                } else {
                                    val ms = manualEpoch.toLongOrNull()
                                    if (ms == null) {
                                        status = "epochMillis 必须是数字"
                                        null
                                    } else {
                                        SetSystemTimeOperation(auto = false, epochMillis = ms)
                                    }
                                }
                            }

                            OperationType.OPEN_APP -> {
                                if (packageName.isBlank()) {
                                    status = "请输入包名"
                                    null
                                } else {
                                    OpenAppOperation(packageName)
                                }
                            }
                        }

                        if (op != null) {
                            operations.add(op)
                            status = "已添加操作: ${op.displayText()}"
                        }
                    }) {
                        Text("添加操作")
                    }
                }
            }

            if (operations.isNotEmpty()) {
                SectionHeader("当前操作列表")
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        operations.forEachIndexed { index, operation ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${index + 1}. ${operation.displayText()}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { operations.removeAt(index) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSave(workflowName, operations.toList()) { _, msg ->
                        status = msg
                    }
                }) {
                    Text("保存工作流")
                }
                OutlinedButton(onClick = onBack) {
                    Text("取消")
                }
            }

            if (status.isNotBlank()) {
                StatusBanner(status)
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "说明: 修改系统时间属于高权限行为。普通 App 通常只能跳转到系统日期设置页，Root/设备所有者模式下可自动执行。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    DirectoryPickerDialog(
        visible = showDeletePicker,
        title = "选择要删除的目录",
        startDir = resolveStartDir(deletePath),
        onSelect = { path ->
            deletePath = path
            status = "已选择目录: $path"
        },
        onDismiss = { showDeletePicker = false }
    )

    DirectoryPickerDialog(
        visible = showCopySourcePicker,
        title = "选择源目录",
        startDir = resolveStartDir(copySource),
        onSelect = { path ->
            copySource = path
            status = "已选择源目录: $path"
        },
        onDismiss = { showCopySourcePicker = false }
    )

    DirectoryPickerDialog(
        visible = showCopyTargetPicker,
        title = "选择目标目录",
        startDir = resolveStartDir(copyTarget),
        onSelect = { path ->
            copyTarget = path
            status = "已选择目标目录: $path"
        },
        onDismiss = { showCopyTargetPicker = false }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusBanner(text: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DirectoryPickerDialog(
    visible: Boolean,
    title: String,
    startDir: File,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var currentDir by remember(startDir) { mutableStateOf(startDir) }
    val dirs = remember(currentDir) {
        currentDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(currentDir.path, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val parent = currentDir.parentFile
                    OutlinedButton(
                        onClick = { if (parent != null) currentDir = parent },
                        enabled = parent != null
                    ) {
                        Text("上一级")
                    }
                }
                if (dirs.isEmpty()) {
                    Text("当前目录无子目录", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(dirs) { dir ->
                            TextButton(
                                onClick = { currentDir = dir },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(dir.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(currentDir.path)
                onDismiss()
            }) {
                Text("选择此目录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
