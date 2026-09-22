package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutZyboxOptimizationBinding
import io.nekohasekai.sagernet.widget.ListListener

class ZyBoxOptimizationFragment : ToolbarFragment(R.layout.layout_zybox_optimization) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutZyboxOptimizationBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.zybox_opt_title)

        // ZyBox: GitHub 开源地址 → 浏览器
        binding.zyboxOptGithubItem.setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/fuoins/ZyBoxForAndroid")
                    )
                )
            } catch (e: Exception) {
                io.nekohasekai.sagernet.ktx.Logs.w(e)
            }
        }
    }
}
