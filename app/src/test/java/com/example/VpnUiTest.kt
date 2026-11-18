package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.model.ConnectionStep
import com.example.model.DirectorySource
import com.example.model.ServerHealth
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.ui.VpnUiState
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.theme.FreePublicVpnTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VpnUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleServer = VpnServer(
        id = "cf-warp-anycast",
        name = "Cloudflare WARP Anycast",
        countryName = "Anycast Global",
        countryCode = "UN",
        host = "engage.cloudflareclient.com",
        ipAddress = "162.159.192.1",
        port = 2408,
        protocol = VpnProtocol.WIREGUARD,
        transport = "UDP",
        directorySource = DirectorySource.CLOUDFLARE_WARP,
        latencyMs = 22,
        health = ServerHealth.ONLINE,
        inAppTunnelSupported = true
    )

    @Test
    fun `dashboard displays connect button, active server, and live telemetry`() {
        var connectClicked = false
        val uiState = VpnUiState(
            servers = listOf(sampleServer),
            selectedServer = sampleServer,
            connectionState = VpnConnectionState(
                step = ConnectionStep.CONNECTED,
                activeServer = sampleServer,
                downloadBytesPerSec = 2_097_152L,
                uploadBytesPerSec = 524_288L,
                totalRxBytes = 10_485_760L,
                totalTxBytes = 2_097_152L,
                elapsedSeconds = 125L,
                verifiedExitIp = "104.28.1.99",
                dnsServerDisplay = "1.1.1.1"
            )
        )

        composeRule.setContent {
            FreePublicVpnTheme {
                DashboardScreen(
                    uiState = uiState,
                    onConnectClick = { connectClicked = true },
                    onAutoOptimalClick = {},
                    onSwitchServerClick = {},
                    onDismissError = {},
                    onOpenVpnHandoff = {},
                    onDismissOpenVpnHandoff = {},
                    isOpenVpnInstalled = false
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("active_server_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_download").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_upload").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_transferred").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_duration").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("00:02:05").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("connect_button").performScrollTo().performClick()
        assertTrue(connectClicked)
    }

    @Test
    fun `servers screen renders discovered server cards and action buttons`() {
        var refreshClicked = false
        val uiState = VpnUiState(
            servers = listOf(sampleServer),
            selectedServer = sampleServer
        )

        composeRule.setContent {
            FreePublicVpnTheme {
                ServersScreen(
                    uiState = uiState,
                    onRefreshDirectories = { refreshClicked = true },
                    onProbeAllLatencies = {},
                    onProbeSingle = {},
                    onSelectFilter = {},
                    onSearchQueryChange = {},
                    onToggleFavorite = {},
                    onConnectToServer = {},
                    onAutoBestClick = {},
                    onImportProfile = { _, _ -> },
                    onDeleteImported = {},
                    onInspectServer = {},
                    getRedactedPreview = { null },
                    onOpenInOpenVpn = {},
                    isOpenVpnInstalled = false
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("server_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("refresh_servers_button").assertIsDisplayed()
        composeRule.onNodeWithTag("test_latency_button").assertIsDisplayed()
        composeRule.onNodeWithTag("import_config_button").assertIsDisplayed()
        composeRule.onNodeWithTag("server_card_cf-warp-anycast").assertIsDisplayed()

        composeRule.onNodeWithTag("refresh_servers_button").performClick()
        assertTrue(refreshClicked)
    }
}
