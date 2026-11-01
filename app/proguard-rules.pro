-repackageclasses ''
-allowaccessmodification

-keep class io.nekohasekai.sagernet.** { *;}
-keep class moe.matsuri.nb4a.** { *;}

# Clean Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

# ini4j
-keep public class org.ini4j.spi.** { <init>(); }

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException

# --- ZyBox: vpnhotspot 模块 ---
-keep class zy.hotspot.app.** { *; }
-dontwarn android.bluetooth.BluetoothPan
-dontwarn android.net.ConnectivityManager$OnStartTetheringCallback
-dontwarn android.net.TetheredClient$AddressInfo
-dontwarn android.net.TetheredClient
-dontwarn android.net.TetheringInterface
-dontwarn android.net.TetheringManager$StartTetheringCallback
-dontwarn android.net.TetheringManager$TetheringEventCallback
-dontwarn android.net.TetheringManager$TetheringInterfaceRegexps
-dontwarn android.net.TetheringManager$TetheringRequest$Builder
-dontwarn android.net.TetheringManager$TetheringRequest
-dontwarn android.net.TetheringManager
-dontwarn android.net.wifi.DeauthenticationReasonCode
-dontwarn android.net.wifi.ISoftApCallback$Stub
-dontwarn android.net.wifi.ISoftApCallback
-dontwarn android.net.wifi.IWifiManager
-dontwarn android.net.wifi.OuiKeyedData$Builder
-dontwarn android.net.wifi.OuiKeyedData
-dontwarn android.net.wifi.SoftApCapability
-dontwarn android.net.wifi.SoftApConfiguration$Builder
-dontwarn android.net.wifi.SoftApInfo
-dontwarn android.net.wifi.WifiClient
-dontwarn android.net.wifi.WifiManager$SoftApCallback
-dontwarn android.net.wifi.p2p.WifiP2pConnectionInfo
# ZyBox: vpnhotspot 模块反射层（R8 保留，防止反射目标被混淆/裁剪）
-keep class zy.hotspot.app.** { *; }

# ZyBox: VPN 热点用反射调用的隐藏系统 API（SDK android.jar 中不存在，R8 报 Missing class 需忽略）
-dontwarn android.net.IIntResultListener
-dontwarn android.net.IIntResultListener$Stub
-dontwarn android.net.ITetheringConnector
-dontwarn android.net.wifi.p2p.WifiP2pGroupList
-dontwarn android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener
