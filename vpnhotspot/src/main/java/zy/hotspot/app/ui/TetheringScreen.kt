package zy.hotspot.app.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.MacAddress
import android.net.TetheringManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import zy.hotspot.app.App.Companion.app
import zy.hotspot.app.LocalOnlyHotspotService
import zy.hotspot.app.R
import zy.hotspot.app.StaticIpSetter
import zy.hotspot.app.TetheringService
import zy.hotspot.app.manage.BluetoothTethering
import zy.hotspot.app.manage.ManageBar
import zy.hotspot.app.net.MacAddressCompat
import zy.hotspot.app.net.TetherOffloadManager
import zy.hotspot.app.net.TetherStates
import zy.hotspot.app.net.TetherType
import zy.hotspot.app.net.TetheringManagerCompat
import zy.hotspot.app.net.wifi.WifiApManager
import zy.hotspot.app.root.WifiApCommands
import zy.hotspot.app.net.wifi.VendorData
import zy.hotspot.app.ui.theme.VpnHotspotPreviewSurface
import zy.hotspot.app.util.Services
import zy.hotspot.app.util.readableMessage
import zy.hotspot.app.widget.SmartSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.reflect.InvocationTargetException
import java.net.NetworkInterface
import java.net.SocketException
import java.text.NumberFormat
import java.util.Locale

data class TetheringServiceState(
    val managedIfaces: Set<String> = emptySet(),
    val inactiveIfaces: Set<String> = emptySet(),
    val monitoredIfaces: Set<String> = emptySet(),
)

@Composable
fun TetheringScreen(
    snackbarHostState: SnackbarHostState,
    localOnlyIface: String?,
    tetherStates: TetherStates,
    tetheringServiceState: TetheringServiceState,
    interfaceRefreshVersion: Int = 0,
    onRefresh: () -> Unit = {},
    onConfigureTemporaryHotspot: (() -> Unit)?,
    onConfigureAp: () -> Unit,
    onStopTemporaryHotspot: () -> Unit,
) {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val linkStyles = rememberNetworkAddressLinkStyles()
    val staticIpActive by StaticIpSetter.active.collectAsStateWithLifecycle()
    val staticIpAddresses by StaticIpSetter.addresses.collectAsStateWithLifecycle()
    val staticIpApplying by StaticIpSetter.applying.collectAsStateWithLifecycle()
    var staticIpDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var staticIpDraftText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(staticIpDraft.orEmpty()))
    }
    val tetherTypeVersion by if (inspectionMode) remember { mutableIntStateOf(0) } else rememberTetherTypeVersion()
    var manageBarVersion by remember { mutableIntStateOf(0) }
    var offloadEnabled by remember { mutableStateOf(!inspectionMode && TetherOffloadManager.enabled) }
    var offloadChanging by remember { mutableStateOf(false) }
    val ifaceLookup = remember(
        tetherStates,
        tetheringServiceState,
        localOnlyIface,
        interfaceRefreshVersion,
    ) {
        networkInterfaceLookup()
    }
    val monitored = tetheringServiceState.monitoredIfaces
    val managed = tetheringServiceState.managedIfaces
    val inactive = tetheringServiceState.inactiveIfaces
    val tetheredTypes = remember(tetherStates, tetherTypeVersion) {
        tetherStates.tethered.map { TetherType.ofInterface(it) }.toSet()
    }
    val interfaceIfaces = remember(tetherStates, monitored) {
        (tetherStates.tethered + monitored).toSortedSet().toList()
    }
    val wifiBaseError = tetherError(context, tetherStates, TetherType.WIFI)
    val wifiSummary by if (inspectionMode) {
        remember(wifiBaseError) { mutableStateOf(wifiBaseError) }
    } else rememberWifiSummary(wifiBaseError, linkStyles)
    val localOnlyBaseSummary = networkInterfaceAddressesText(ifaceLookup[localOnlyIface], linkStyles)
    val localOnlySummary by if (inspectionMode || Build.VERSION.SDK_INT < 33 || localOnlyIface.isNullOrEmpty()) {
        remember(localOnlyBaseSummary) { mutableStateOf(localOnlyBaseSummary) }
    } else rememberWifiSummaryApi30(localOnlyBaseSummary, linkStyles, SoftApCallbackTarget.LocalOnlyHotspot)
    var bluetoothVersion by remember { mutableIntStateOf(0) }
    val bluetoothAdapter = if (inspectionMode) null else remember {
        context.getSystemService<BluetoothManager>()?.adapter
    }
    val bluetoothTethering = remember(bluetoothAdapter) {
        bluetoothAdapter?.let { BluetoothTethering(it) { bluetoothVersion++ } }
    }
    val requestBluetooth = if (inspectionMode) null else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bluetoothTethering?.ensureInit()
                bluetoothVersion++
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bluetoothTethering) {
        var resumed = false
        fun refreshBluetooth() {
            if (resumed) return
            resumed = true
            manageBarVersion++
            if (bluetoothTethering == null || Build.VERSION.SDK_INT < 31) return
            // ZyBox: 蓝牙功能未迁移（不显示蓝牙行），不再自动请求 BLUETOOTH_CONNECT 权限，
            // 避免权限弹窗循环导致系统移除页面任务；已授权则照常初始化。
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED) {
                bluetoothTethering.ensureInit()
                bluetoothVersion++
            }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = refreshBluetooth()
            override fun onPause(owner: LifecycleOwner) {
                resumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshBluetooth()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bluetoothTethering?.close()
        }
    }
    val startLocalOnly: (String) -> Unit = if (inspectionMode) {
        { _: String -> }
    } else {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
            onResult = { app.startServiceWithLocation<LocalOnlyHotspotService>(context) },
        )
        launcher::launch
    }
    val showBluetooth = inspectionMode || (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            bluetoothTethering != null)
    val bluetoothActive = remember(showBluetooth, bluetoothTethering, bluetoothVersion, tetherStates.tethered) {
        if (showBluetooth) bluetoothTethering?.active else null
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = {
            onRefresh()
            manageBarVersion++
            bluetoothVersion++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        SettingsList {
            // ZyBox: wlan热点置顶
            preferenceGroup(key = "wifi_hotspot") {
                row(R.string.tethering_manage_wifi) {
                    TetheringTypeRow(
                        icon = R.drawable.ic_network_wifi,
                        title = R.string.tethering_manage_wifi,
                        checked = tetheredTypes.contains(TetherType.WIFI),
                        summary = wifiSummary,
                        tetheringType = TetheringManager.TETHERING_WIFI,
                        snackbarHostState = snackbarHostState,
                        onConfigure = onConfigureAp,
                    )
                }
            }
            // ZyBox: 临时热点（恢复 fix11 误删的行）
            preferenceGroup(key = "active_tethering") {
                row(R.string.tethering_temp_hotspot) {
                    val toggleLocalOnly: () -> Unit = {
                        if (localOnlyIface == null) {
                            startLocalOnly(if (Build.VERSION.SDK_INT >= 33) {
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            } else Manifest.permission.ACCESS_FINE_LOCATION)
                        } else onStopTemporaryHotspot()
                    }
                    TetheringRow(
                        icon = R.drawable.ic_android_wifi_3_bar_plus,
                        title = stringResource(R.string.tethering_temp_hotspot),
                        summary = localOnlySummary,
                        checked = localOnlyIface != null,
                        onClick = onConfigureTemporaryHotspot ?: toggleLocalOnly,
                        onCheckedChange = if (onConfigureTemporaryHotspot == null) null else toggleLocalOnly,
                    )
                }
            }
            for (iface in interfaceIfaces) {
                item(key = "interface_$iface") {
                    val active = managed.contains(iface)
                    val watch = monitored.contains(iface)
                    val ifaceInactive = inactive.contains(iface)
                    val vpnTethering = active && !ifaceInactive
                    PreferenceGroup {
                        row("vpn_tethering") {
                            TetheringRow(
                                icon = TetherType.ofInterface(iface).icon,
                                // ZyBox: 接口名后标注 (vpn热点)
                                title = "$iface (vpn热点)",
                                summary = networkInterfaceAddressesText(
                                    ifaceLookup[iface],
                                    linkStyles,
                                    macOnly = ifaceInactive,
                                ),
                                checked = vpnTethering,
                                enabled = !ifaceInactive,
                                onClick = {
                                    if (active) context.startService(Intent(context, TetheringService::class.java)
                                        .putExtra(TetheringService.EXTRA_REMOVE_INTERFACE, iface))
                                    else context.startForegroundService(Intent(context, TetheringService::class.java)
                                        .putExtra(TetheringService.EXTRA_ADD_INTERFACES, arrayOf(iface)))
                                },
                            )
                        }
                        if (vpnTethering || watch) row("watch_reconnect") {
                            PreferenceSwitchRow(
                                title = stringResource(R.string.tethering_watch_reconnect),
                                checked = watch,
                                onCheckedChange = {
                                    if (watch) context.startService(Intent(context, TetheringService::class.java)
                                        .putExtra(TetheringService.EXTRA_REMOVE_INTERFACE_MONITOR, iface))
                                    else context.startForegroundService(Intent(context, TetheringService::class.java)
                                        .putExtra(TetheringService.EXTRA_ADD_INTERFACE_MONITOR, iface))
                                },
                            )
                        }
                    }
                }
            }
            preferenceGroup(key = "manage_tethering") {
                row(R.string.tethering_manage) {
                    PreferenceRow(
                        icon = R.drawable.ic_add,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.tethering_manage),
                        // ZyBox: 描述字体减小
                        summaryContent = {
                            Text(
                                buildAnnotatedString {
                                    append(stringResource(R.string.tethering_manage_shares_summary))
                                    if (offloadEnabled) {
                                        append('\n')
                                        append(stringResource(R.string.tethering_manage_offload_enabled))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = { ManageBar.start(context::startActivity) },
                    )
                }
                row(R.string.settings_system_tether_offload) {
                    PreferenceSwitchRow(
                        icon = R.drawable.ic_speed,
                        title = stringResource(R.string.settings_system_tether_offload),
                        // ZyBox: 描述字体减小
                        summaryContent = {
                            Text(
                                stringResource(R.string.settings_system_tether_offload_summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        checked = offloadEnabled,
                        onCheckedChange = { enabled ->
                            if (inspectionMode) return@PreferenceSwitchRow
                            scope.launch {
                                offloadChanging = true
                                try {
                                    TetherOffloadManager.setEnabled(enabled)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.w(e)
                                    snackbarHostState.showLongSnackbar(e.readableMessage)
                                } finally {
                                    offloadEnabled = TetherOffloadManager.enabled
                                    offloadChanging = false
                                }
                            }
                        },
                    )
                }
                // ZyBox: 静态ip移到管理组末尾
                row(R.string.tethering_static_ip) {
                    TetheringRow(
                        icon = R.drawable.ic_push_pin,
                        title = stringResource(R.string.tethering_static_ip),
                        summary = buildAnnotatedString {
                            for ((address, prefixLength) in staticIpAddresses) {
                                if (length > 0) append('\n')
                                appendIpAddress(address, linkStyles)
                                if (prefixLength.toInt() != address.address.size * 8) append("/$prefixLength")
                            }
                        },
                        checked = staticIpActive,
                        switchEnabled = !staticIpApplying,
                        onClick = {
                            staticIpDraft = StaticIpSetter.ips
                        },
                        onCheckedChange = { StaticIpSetter.enable(!staticIpActive) },
                    )
                }
            }
            // ZyBox: 底部提示——开启wlan热点才会显示ap0(vpn热点)
            item(key = "zybox_hint") {
                Text(
                    text = "开启wlan热点才会显示ap0(vpn热点)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (staticIpDraft != null) {
        val focusRequester = rememberDialogFocusRequester()
        val staticIpDraftValue = staticIpDraftText.text.toString()
        val staticIpDraftError = try {
            StaticIpSetter.parseAddresses(staticIpDraftValue).count()
            null
        } catch (e: InvocationTargetException) {
            e.readableMessage
        }
        val staticIpTitle = stringResource(R.string.tethering_static_ip)
        AlertDialog(
            onDismissRequest = {
                staticIpDraft = null
            },
            title = { Text(staticIpTitle) },
            text = {
                val scrollState = rememberScrollState()
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.tethering_static_ip_help),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = staticIpDraftText,
                        onValueChange = { staticIpDraftText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = staticIpTitle },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        minLines = 2,
                        maxLines = 2,
                        isError = staticIpDraftError != null,
                        shape = OutlinedTextFieldDefaults.shape,
                        colors = OutlinedTextFieldDefaults.colors(),
                        supportingText = if (staticIpDraftError != null) {
                            {
                                Text(
                                    text = staticIpDraftError,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        error(staticIpDraftError)
                                    },
                                )
                            }
                        } else null,
                    )
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    enabled = staticIpDraftError == null,
                    onClick = {
                        StaticIpSetter.ips = staticIpDraftValue.trim()
                        staticIpDraft = null
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                DialogDismissButton(onClick = {
                    staticIpDraft = null
                }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TetheringTypeRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    checked: Boolean,
    summary: AnnotatedString?,
    tetheringType: Int,
    snackbarHostState: SnackbarHostState,
    onConfigure: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toggle: () -> Unit = toggle@{
        if (!Settings.System.canWrite(context)) try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))
            return@toggle
        } catch (e: RuntimeException) {
            app.logEvent("manage_write_settings")
        }
        scope.launch {
            runTethering(context, snackbarHostState, TetherType.fromTetheringType(tetheringType)) {
                if (checked) TetheringManagerCompat.stopTethering(tetheringType)
                else TetheringManagerCompat.startTethering(tetheringType, true)
            }
        }
    }
    TetheringRow(
        icon = icon,
        title = stringResource(title),
        summary = summary,
        checked = checked,
        onClick = onConfigure ?: toggle,
        onCheckedChange = if (onConfigure == null) null else toggle,
    )
}

@Composable
private fun BluetoothTetheringRow(
    active: Boolean?,
    summary: AnnotatedString,
    bluetoothTethering: BluetoothTethering?,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TetheringRow(
        icon = R.drawable.ic_bluetooth,
        title = stringResource(R.string.tethering_manage_bluetooth),
        summary = summary,
        checked = active == true,
        enabled = bluetoothTethering != null,
        onClick = {
            if (!Settings.System.canWrite(context)) try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))
                return@TetheringRow
            } catch (e: RuntimeException) {
                app.logEvent("manage_write_settings")
            }
            when (active) {
                true -> scope.launch {
                    runTethering(context, snackbarHostState, TetherType.BLUETOOTH, onRefresh) {
                        bluetoothTethering?.stop()
                    }
                }
                false -> scope.launch {
                    runTethering(context, snackbarHostState, TetherType.BLUETOOTH, onRefresh) {
                        bluetoothTethering?.start(context)
                    }
                }
                null -> ManageBar.start(context::startActivity)
            }
        },
    )
}

@Composable
private fun TetheringRow(
    @DrawableRes icon: Int,
    title: String,
    summary: AnnotatedString? = null,
    checked: Boolean,
    enabled: Boolean = true,
    switchEnabled: Boolean = enabled,
    onClick: () -> Unit,
    onCheckedChange: (() -> Unit)? = null,
) {
    val summaryContent: (@Composable () -> Unit)? = summary?.takeIf { it.text.isNotEmpty() }?.let {
        {
            RowSelectionContainer {
                Text(it)
            }
        }
    }
    if (onCheckedChange == null) {
        PreferenceSwitchRow(
            icon = icon,
            title = title,
            summaryContent = summaryContent,
            checked = checked,
            enabled = enabled && switchEnabled,
            onCheckedChange = { onClick() },
        )
    } else {
        val (rowFocusModifier, switchFocusModifier) = rememberPreferenceSplitFocusModifiers()
        PreferenceRow(
            icon = icon,
            title = title,
            modifier = rowFocusModifier,
            summaryContent = summaryContent,
            enabled = enabled,
            trailing = {
                PreferenceSplitSwitch(
                    label = title,
                    checked = checked,
                    modifier = switchFocusModifier,
                    enabled = switchEnabled,
                    onCheckedChange = { onCheckedChange() },
                )
            },
            onClick = onClick,
        )
    }
}

@Preview(name = "Tethering", showBackground = true, widthDp = 420, heightDp = 720)
@Preview(
    name = "Tethering - dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TetheringPreview() {
    VpnHotspotPreviewSurface {
        TetheringScreen(
            snackbarHostState = remember { SnackbarHostState() },
            localOnlyIface = null,
            tetherStates = TetherStates(),
            tetheringServiceState = TetheringServiceState(),
            onConfigureTemporaryHotspot = null,
            onConfigureAp = {},
            onStopTemporaryHotspot = {},
        )
    }
}

/**
 * Run a tether start/stop ([block]) and surface the outcome: [onChanged] on success, a permission
 * prompt or snackbar for a [TetheringManagerCompat.Failure] error code, and a toast + Manage screen
 * for any other exception. Mirrors the old tetheringCallback object.
 */
private suspend fun runTethering(
    context: Context,
    snackbarHostState: SnackbarHostState,
    tetherType: TetherType,
    onChanged: () -> Unit = {},
    block: suspend () -> Unit,
) {
    try {
        block()
        onChanged()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TetheringManagerCompat.Failure) {
        onChanged()
        e.errorCode?.let { showTetherError(context, snackbarHostState, tetherType, it) }
    } catch (e: Exception) {
        TetheringManagerCompat.reportException(e)
        Toast.makeText(context, e.readableMessage, Toast.LENGTH_LONG).show()
        ManageBar.start(context::startActivity)
    }
}

private suspend fun showTetherError(context: Context, snackbarHostState: SnackbarHostState, tetherType: TetherType,
                                    error: Int) {
    if (Build.VERSION.SDK_INT >= 30 && error == TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION) {
        Toast.makeText(context, R.string.permission_missing, Toast.LENGTH_LONG).show()
        ManageBar.start(context::startActivity)
    } else snackbarHostState.showLongSnackbar(tetherErrorMessage(context, tetherType, error))
}

private fun tetherErrorMessage(context: Context, tetherType: TetherType, error: Int) = context.getString(
    R.string.tether_error_message,
    context.getString(tetherType.label),
    tetherErrorLabel(context, error),
)

@Composable
private fun rememberTetherTypeVersion(): State<Int> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return if (Build.VERSION.SDK_INT < 30) remember { mutableIntStateOf(0) } else produceState(0, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            TetherType.changes.collect { value++ }
        }
    }
}

@Composable
private fun rememberWifiSummary(
    baseError: AnnotatedString?,
    linkStyles: TextLinkStyles,
): State<AnnotatedString?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val locale = LocalConfiguration.current.locales[0]
    if (Build.VERSION.SDK_INT >= 30) return rememberWifiSummaryApi30(baseError, linkStyles)
    return produceState(baseError, context, lifecycleOwner, locale, baseError, linkStyles) {
        var wifiFailureReason: Int? = null
        var wifiNumClients: Int? = null
        fun update() {
            value = wifiSummary(
                context,
                locale,
                wifiFailureReason,
                wifiNumClients,
                null,
                null,
                baseError,
            )
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WifiApCommands.softApCallbackFlow(expensive = true).catch { e ->
                if (e is CancellationException) throw e
                Timber.w(e)
            }.collect { event ->
                when (event) {
                    is WifiApManager.Event.OnStateChanged -> {
                        if (!WifiApManager.checkWifiApState(event.state)) return@collect
                        wifiFailureReason = if (event.state == WifiApManager.WIFI_AP_STATE_FAILED) {
                            event.failureReason
                        } else null
                        update()
                    }
                    is WifiApManager.Event.OnNumClientsChanged -> {
                        wifiNumClients = event.numClients
                        update()
                    }
                    else -> { }
                }
            }
        }
    }
}

internal fun wifiSummary(
    context: Context,
    locale: Locale,
    failureReason: Int?,
    numClients: Int?,
    info: AnnotatedString?,
    maxSupportedClients: Int?,
    baseError: AnnotatedString?,
): AnnotatedString? {
    val integerFormat = NumberFormat.getIntegerInstance(locale)
    val summary = buildAnnotatedString {
        fun line(content: AnnotatedString.Builder.() -> Unit) {
            if (length > 0) append('\n')
            content()
        }
        failureReason?.let { line { append(softApStartFailureLabel(context, it)) } }
        baseError?.takeIf { it.text.isNotEmpty() }?.let { line { append(it) } }
        info?.takeIf { it.text.isNotEmpty() }?.let { line { append(it) } }
        maxSupportedClients?.let {
            line { append(context.resources.getQuantityString(
                R.plurals.tethering_manage_wifi_client_limit,
                numClients ?: 0,
                numClients?.let { integerFormat.format(it.toLong()) } ?: "?",
                integerFormat.format(it.toLong()),
            )) }
        } ?: numClients?.let {
            line { append(context.resources.getQuantityString(
                R.plurals.tethering_manage_wifi_clients,
                it,
                integerFormat.format(it.toLong()),
            )) }
        }
    }
    return if (summary.text.isEmpty()) null else summary
}

private fun networkInterfaceLookup(): Map<String, NetworkInterface> {
    return try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.associateBy { it.name } ?: emptyMap()
    } catch (e: Exception) {
        if (e is SocketException) Timber.d(e) else Timber.w(e)
        emptyMap()
    }
}

private fun networkInterfaceAddressesText(
    iface: NetworkInterface?,
    linkStyles: TextLinkStyles,
    macOnly: Boolean = false,
    macOverride: MacAddress? = null,
): AnnotatedString = buildAnnotatedString {
    var macAddress = macOverride
    if (macAddress == null && iface != null) try {
        val hardwareAddress = iface.hardwareAddress
        macAddress = try {
            hardwareAddress?.let(MacAddress::fromBytes)
        } catch (e: IllegalArgumentException) {
            try {
                hardwareAddress?.let { MacAddress.fromString(String(it)) }.also { Timber.d(e) }
            } catch (e2: IllegalArgumentException) {
                e.addSuppressed(e2)
                Timber.w(e)
                null
            }
        }
    } catch (_: SocketException) { }
    if (macAddress != null && macAddress != MacAddressCompat.ANY_ADDRESS) appendMacAddress(macAddress.toString(), linkStyles)
    if (!macOnly && iface != null) for (address in iface.interfaceAddresses) {
        if (length > 0) append('\n')
        appendIpAddress(address.address, linkStyles)
        address.networkPrefixLength.also {
            if (it.toInt() != address.address.address.size * 8) append("/$it")
        }
    }
}

private fun tetherError(context: Context, states: TetherStates, tetherType: TetherType): AnnotatedString? {
    val interested = states.errored.keys.filter { TetherType.ofInterface(it).isA(tetherType) }
    return if (interested.isEmpty()) null else AnnotatedString(interested.joinToString("\n") { iface ->
        "$iface: " + try {
            tetherErrorLabel(context, if (Build.VERSION.SDK_INT < 30) @Suppress("DEPRECATION") {
                TetheringManagerCompat.getLastTetherError(iface)
            } else states.errored[iface] ?: 0)
        } catch (e: InvocationTargetException) {
            if (e.cause !is SecurityException) Timber.w(e) else Timber.d(e)
            e.readableMessage
        }
    })
}

