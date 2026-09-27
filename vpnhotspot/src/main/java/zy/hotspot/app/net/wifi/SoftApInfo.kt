package zy.hotspot.app.net.wifi

import android.net.wifi.SoftApInfo
import android.os.Build
import androidx.annotation.RequiresApi
import zy.hotspot.app.util.ConstantLookup
import zy.hotspot.app.util.UnblockCentral
import timber.log.Timber

@RequiresApi(30)
val softApChannelWidthLookup = ConstantLookup("CHANNEL_WIDTH_") { SoftApInfo::class.java }

@get:RequiresApi(31)
val SoftApInfo.apInstanceIdentifierOrNull: String? get() = try {
    UnblockCentral.getApInstanceIdentifier(this)
} catch (e: NoSuchMethodError) {
    if (Build.VERSION.SDK_INT >= 31) Timber.w(e)
    null
}
