package com.example.smssender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smssender.data.ForwardStatus
import com.example.smssender.data.PendingMessage
import com.example.smssender.model.AppConfig
import com.example.smssender.model.SmtpSecurity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var permissionsVersion by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsSenderTheme {
                SmsSenderScreen(
                    viewModel = viewModel,
                    hasSmsPermission = hasPermission(Manifest.permission.RECEIVE_SMS),
                    hasNotificationPermission = Build.VERSION.SDK_INT < 33 ||
                        hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                    permissionVersion = permissionsVersion,
                    openSettings = ::openAppSettings,
                    openBatterySettings = {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsVersion++
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}

@Composable
private fun SmsSenderScreen(
    viewModel: AppViewModel,
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    permissionVersion: Int,
    openSettings: () -> Unit,
    openBatterySettings: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showGuide by rememberSaveable { mutableStateOf(!hasSmsPermission || !hasNotificationPermission) }
    var localSmsPermission by remember(permissionVersion, hasSmsPermission) { mutableStateOf(hasSmsPermission) }
    var localNotificationPermission by remember(permissionVersion, hasNotificationPermission) {
        mutableStateOf(hasNotificationPermission)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        localSmsPermission = results[Manifest.permission.RECEIVE_SMS] ?: localSmsPermission
        if (Build.VERSION.SDK_INT >= 33) {
            localNotificationPermission =
                results[Manifest.permission.POST_NOTIFICATIONS] ?: localNotificationPermission
        }
    }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    if (showGuide && (!localSmsPermission || !localNotificationPermission)) {
        PermissionGuide(
            hasSmsPermission = localSmsPermission,
            hasNotificationPermission = localNotificationPermission,
            requestPermissions = {
                val missing = buildList {
                    if (!localSmsPermission) add(Manifest.permission.RECEIVE_SMS)
                    if (Build.VERSION.SDK_INT >= 33 && !localNotificationPermission) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                launcher.launch(missing.toTypedArray())
            },
            openSettings = openSettings,
            dismiss = { showGuide = false },
        )
    }

    Scaffold(
        containerColor = Color(0xFFF5F5F7),
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("●") },
                    label = { Text("状态") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("⚙") },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        if (selectedTab == 0) {
            HomePage(
                modifier = Modifier.padding(padding),
                configured = viewModel.isConfigured(),
                hasSmsPermission = localSmsPermission,
                hasNotificationPermission = localNotificationPermission,
                pending = pending,
                status = status,
                retry = viewModel::retry,
                showPermissions = { showGuide = true },
                openBatterySettings = openBatterySettings,
            )
        } else {
            SettingsPage(
                modifier = Modifier.padding(padding),
                initialConfig = viewModel.loadConfig(),
                save = viewModel::save,
                test = viewModel::test,
            )
        }
    }
}

@Composable
private fun PermissionGuide(
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    requestPermissions: () -> Unit,
    openSettings: () -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("开始接收并转发短信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 短信接收：仅处理今后收到的短信，不读取历史短信。")
                Text("2. 通知：用于告知后台转发结果（Android 13+）。")
                Text("拒绝后应用仍可打开，但缺少短信权限时无法自动转发。")
                Text(
                    if (hasSmsPermission && hasNotificationPermission) "权限已就绪"
                    else "请授予尚未启用的权限",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = requestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            ) { Text("继续") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = openSettings) { Text("系统设置") }
                TextButton(onClick = dismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun HomePage(
    modifier: Modifier,
    configured: Boolean,
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    pending: List<PendingMessage>,
    status: ForwardStatus,
    retry: () -> Unit,
    showPermissions: () -> Unit,
    openBatterySettings: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("短信转发", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("本机运行 · 无需登录", color = Color.Gray)
        }
        item {
            StatusCard(
                title = if (configured && hasSmsPermission) "已启用" else "需要处理",
                detail = when {
                    !hasSmsPermission -> "短信接收权限尚未授予"
                    !configured -> "请前往设置填写并保存配置"
                    else -> "新短信将自动加入可靠转发队列"
                },
                positive = configured && hasSmsPermission,
            )
        }
        item {
            IosCard {
                Text("运行检查", fontWeight = FontWeight.Bold)
                CheckRow("短信接收", hasSmsPermission)
                CheckRow("通知", hasNotificationPermission)
                CheckRow("转发配置", configured)
                if (!hasSmsPermission || !hasNotificationPermission) {
                    TextButton(onClick = showPermissions) { Text("权限引导") }
                }
            }
        }
        item {
            IosCard {
                Text("待发队列", fontWeight = FontWeight.Bold)
                Text("${pending.size} 条", style = MaterialTheme.typography.headlineMedium)
                pending.firstOrNull()?.lastError?.let {
                    Text("最近错误：$it", color = Color(0xFF8A1C1C))
                }
                Button(
                    enabled = pending.isNotEmpty(),
                    onClick = retry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                ) { Text("手动重试") }
            }
        }
        item {
            IosCard {
                Text("最近结果", fontWeight = FontWeight.Bold)
                Text(status.message)
                if (status.timestamp > 0) {
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(status.timestamp)),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            TextButton(onClick = openBatterySettings) {
                Text("后台不稳定？检查电池优化设置")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    modifier: Modifier,
    initialConfig: AppConfig,
    save: (AppConfig) -> Map<String, String>,
    test: (AppConfig) -> Map<String, String>,
) {
    var config by remember { mutableStateOf(initialConfig) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("配置会由 Android Keystore 加密后保存在本机", color = Color.Gray)
        }
        item {
            IosCard {
                Text("转发 API", fontWeight = FontWeight.Bold)
                ConfigField("API URL", config.apiUrl, errors["apiUrl"]) {
                    config = config.copy(apiUrl = it)
                }
                ConfigField("API Key", config.apiKey, errors["apiKey"], secret = true) {
                    config = config.copy(apiKey = it)
                }
            }
        }
        item {
            IosCard {
                Text("SMTP", fontWeight = FontWeight.Bold)
                ConfigField("主机", config.smtpHost, errors["smtpHost"]) {
                    config = config.copy(smtpHost = it)
                }
                ConfigField(
                    "端口",
                    config.smtpPort,
                    errors["smtpPort"],
                    keyboardType = KeyboardType.Number,
                ) { config = config.copy(smtpPort = it) }
                Text("连接安全", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SmtpSecurity.entries.forEachIndexed { index, security ->
                        SegmentedButton(
                            selected = config.security == security,
                            onClick = { config = config.copy(security = security) },
                            shape = SegmentedButtonDefaults.itemShape(index, SmtpSecurity.entries.size),
                        ) { Text(security.label) }
                    }
                }
                ConfigField("用户名", config.username, errors["username"]) {
                    config = config.copy(username = it)
                }
                ConfigField("密码 / 授权码", config.password, errors["password"], secret = true) {
                    config = config.copy(password = it)
                }
                ConfigField("发件邮箱", config.from, errors["from"], KeyboardType.Email) {
                    config = config.copy(from = it)
                }
                ConfigField("收件邮箱", config.to, errors["to"], KeyboardType.Email) {
                    config = config.copy(to = it)
                }
            }
        }
        item {
            message?.let { Text(it, color = Color.DarkGray) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        errors = save(config)
                        message = if (errors.isEmpty()) "已安全保存" else "请修正标红项目"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                ) { Text("保存") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        errors = test(config)
                        message = if (errors.isEmpty()) "测试已加入后台队列" else "请先修正配置"
                        if (errors.isEmpty()) scope.launch {
                            delay(1_500)
                            message = "请在首页查看测试结果"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("测试配置") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun StatusCard(title: String, detail: String, positive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (positive) Color.Black else Color.White),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = if (positive) Color.White else Color.Black, fontWeight = FontWeight.Bold)
            Text(detail, color = if (positive) Color.LightGray else Color.DarkGray)
        }
    }
}

@Composable
private fun IosCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun CheckRow(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (ready) "已就绪" else "未完成", color = if (ready) Color.Black else Color.Gray)
    }
}

@Composable
private fun SmsSenderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color.Black,
            onPrimary = Color.White,
            surface = Color.White,
            background = Color(0xFFF5F5F7),
        ),
        content = content,
    )
}
