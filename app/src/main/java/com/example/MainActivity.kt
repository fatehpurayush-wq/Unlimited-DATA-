package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.model.VpnServer
import com.example.ui.VpnViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.FreePublicVpnTheme
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.serialization.Serializable

@Serializable
object RouteDashboard

@Serializable
object RouteServers

@Serializable
object RouteSettings

private data class NavTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val app = application as FreePublicVpnApp
        setContent {
            FreePublicVpnTheme {
                val vpnViewModel: VpnViewModel = viewModel(factory = VpnViewModel.Factory(app))
                FreePublicVpnRoot(viewModel = vpnViewModel)
            }
        }
    }
}

@Composable
fun FreePublicVpnRoot(viewModel: VpnViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingActionAfterVpnPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingActionAfterVpnPermission?.invoke()
        } else {
            pendingActionAfterVpnPermission = null
        }
        pendingActionAfterVpnPermission = null
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setNotificationsEnabled(granted)
    }

    fun executeWithVpnPermission(action: () -> Unit) {
        if (uiState.connectionState.isConnected || uiState.connectionState.isBusy) {
            action()
            return
        }
        val prepareIntent = viewModel.prepareVpnPermissionIntent()
        if (prepareIntent != null) {
            pendingActionAfterVpnPermission = action
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                uiState.preferences.notificationsEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            action()
        }
    }

    fun openExternalOpenVpn(server: VpnServer) {
        val intent = viewModel.launchOpenVpnProfile(server)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val tabs = remember {
        listOf(
            NavTab(RouteDashboard, "Protect", Icons.Filled.Shield, Icons.Outlined.Shield, "nav_dashboard"),
            NavTab(RouteServers, "Servers", Icons.Filled.Public, Icons.Outlined.Public, "nav_servers"),
            NavTab(RouteSettings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateToTab(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
    ) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBlack,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MidnightNavy,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyanAccent,
                                    selectedTextColor = CyanAccent,
                                    indicatorColor = ElectricBlue.copy(alpha = 0.22f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MidnightNavy,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CyanAccent,
                                    selectedTextColor = CyanAccent,
                                    indicatorColor = ElectricBlue.copy(alpha = 0.22f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = RouteDashboard
                    ) {
                        composable<RouteDashboard> {
                            DashboardScreen(
                                uiState = uiState,
                                onConnectClick = {
                                    executeWithVpnPermission { viewModel.onConnectOrDisconnectClicked() }
                                },
                                onAutoOptimalClick = {
                                    executeWithVpnPermission { viewModel.connectAutoOptimal() }
                                },
                                onSwitchServerClick = { navigateToTab(RouteServers) },
                                onDismissError = viewModel::dismissError,
                                onOpenVpnHandoff = ::openExternalOpenVpn,
                                onDismissOpenVpnHandoff = viewModel::dismissOpenVpnHandoff,
                                isOpenVpnInstalled = viewModel.isOpenVpnClientInstalled()
                            )
                        }
                        composable<RouteServers> {
                            ServersScreen(
                                uiState = uiState,
                                onRefreshDirectories = viewModel::refreshDirectories,
                                onProbeAllLatencies = viewModel::probeAllLatencies,
                                onProbeSingle = viewModel::probeSingleServer,
                                onSelectFilter = viewModel::setFilter,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onConnectToServer = { server ->
                                    executeWithVpnPermission {
                                        viewModel.selectServer(server, connectNow = true)
                                        navigateToTab(RouteDashboard)
                                    }
                                },
                                onAutoBestClick = {
                                    executeWithVpnPermission {
                                        viewModel.connectAutoOptimal()
                                        navigateToTab(RouteDashboard)
                                    }
                                },
                                onImportProfile = viewModel::importConfig,
                                onDeleteImported = viewModel::deleteImportedServer,
                                onInspectServer = viewModel::inspectServer,
                                getRedactedPreview = viewModel::getRedactedConfigPreview,
                                onOpenInOpenVpn = ::openExternalOpenVpn,
                                isOpenVpnInstalled = viewModel.isOpenVpnClientInstalled()
                            )
                        }
                        composable<RouteSettings> {
                            SettingsScreen(
                                uiState = uiState,
                                onAutoConnectChange = viewModel::setAutoConnect,
                                onAutoReconnectChange = viewModel::setAutoReconnect,
                                onProtocolChange = viewModel::setProtocolPreference,
                                onDnsModeChange = viewModel::setDnsMode,
                                onCustomDnsSave = viewModel::setCustomDnsIp,
                                onIpv6Change = viewModel::setIpv6Enabled,
                                onSplitTunnelModeChange = viewModel::setSplitTunnelMode,
                                onLoadInstalledApps = viewModel::loadInstalledApps,
                                onToggleSplitTunnelApp = viewModel::toggleSplitTunnelPackage,
                                onNotificationsChange = { enabled ->
                                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.setNotificationsEnabled(enabled)
                                    }
                                },
                                onResetKeys = viewModel::resetAndRegenerateKeys
                            )
                        }
                    }
                }
            }
        }
    }
}
