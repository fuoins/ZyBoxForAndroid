package zy.hotspot.app.client

import android.net.LinkAddress
import zy.hotspot.app.root.daemon.NeighbourState

data class ClientAddressInfo(var state: NeighbourState = NeighbourState.NEIGHBOUR_STATE_UNSET,
                             val address: LinkAddress? = null, val hostname: String? = null) {
    companion object {
        private val getDeprecationTime by lazy { LinkAddress::class.java.getDeclaredMethod("getDeprecationTime") }
        private val getExpirationTime by lazy { LinkAddress::class.java.getDeclaredMethod("getExpirationTime") }
    }
    val deprecationTime get() = getDeprecationTime(address) as Long
    val expirationTime get() = getExpirationTime(address) as Long
}
