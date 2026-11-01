package zy.hotspot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.coroutineScope
import zy.hotspot.app.client.ClientViewModel
import zy.hotspot.app.net.wifi.WifiDoubleLock
import zy.hotspot.app.ui.VpnHotspotApp
import zy.hotspot.app.ui.theme.VpnHotspotTheme
import zy.hotspot.app.util.launchUrl
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        App.ensureInit(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model by viewModels<ClientViewModel>()
        lifecycle.addObserver(model)
        WifiDoubleLock.ActivityListener(this)
        setContent {
            val context = LocalContext.current
            val uriHandler = remember(context) {
                object : UriHandler {
                    override fun openUri(uri: String) = context.launchUrl(uri)
                }
            }
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                VpnHotspotTheme { VpnHotspotApp(model) }
            }
        }
    }
}
