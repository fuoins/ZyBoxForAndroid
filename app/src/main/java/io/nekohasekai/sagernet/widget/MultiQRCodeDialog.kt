package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import java.nio.charset.StandardCharsets

// ZyBox: 多选批量二维码对话框（纵向滚动，每个选中节点一个二维码+名称）
class MultiQRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_LINKS = "io.nekohasekai.sagernet.MultiQRCodeDialog.KEY_LINKS"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(links: List<Pair<String, String>>) : this() {
        arguments = bundleOf(Pair(KEY_LINKS, links.toTypedArray()))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = try {
        val links = (arguments?.getSerializable(KEY_LINKS) as? Array<Pair<String, String>>)
            ?: emptyArray()

        val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)

        ScrollView(requireContext()).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL

                for ((name, url) in links) {
                    val hints = mutableMapOf<EncodeHintType, Any>()
                    if (!iso88591.canEncode(url)) {
                        hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
                    }
                    val qrBits = MultiFormatWriter().encode(
                        url, BarcodeFormat.QR_CODE, size, size, hints
                    )
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                        for (x in 0 until size) for (y in 0 until size) {
                            setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
                        }
                    }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp2px(12)
                        lp.bottomMargin = dp2px(4)
                        layoutParams = lp
                        addView(ImageView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setImageBitmap(bmp)
                        })
                        addView(TextView(context).apply {
                            gravity = Gravity.CENTER
                            text = name
                            setTextColor(Color.BLACK)
                        })
                    })
                }
            })
        }
    } catch (e: Exception) {
        Logs.w(e)
        (activity as MainActivity).snackbar(e.readableMessage).show()
        dismiss()
        null
    }

    private fun dp2px(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
