package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutZyboxOptimizationBinding
import io.nekohasekai.sagernet.widget.ListListener

// ZyBox: 优化内容页（数据驱动动态构建，UI 全面升级样式）
class ZyBoxOptimizationFragment : ToolbarFragment(R.layout.layout_zybox_optimization) {

    // 大类：标题 → 小项列表（小标题, 描述）
    private data class OptGroup(val title: String, val items: List<Pair<String, String>>)

    private val groups = listOf(
        OptGroup(
            "多订阅与默认配置", listOf(
                "多订阅合并" to "一个分组可挂多个实体订阅，节点合并显示在同一分组；有订阅的分组点击 ⋮ → 添加订阅到本分组，添加多个订阅后自动解锁「管理订阅」功能。",
                "多订阅增删修改订阅" to "管理订阅里可单独添加、编辑、删除每个订阅，分组自带主订阅也一并显示（仅可更新）；节点可逐条删除，互不干扰。",
                "多订阅共同更新" to "一键更新分组下全部订阅，更新时只替换属于该订阅自己的节点，其他订阅节点不受影响。",
                "多订阅整组测速" to "对分组内全部订阅的节点统一测速（TCPing / URL Test），实时显示进度与结果。",
                "支持默认分组修改" to "导入分组（宇神神了）点「修改」不再闪退：分组类型锁定为「导入」、隐藏订阅/更新设置，只留分组改名。",
            )
        ),
        OptGroup(
            "节点功能优化", listOf(
                "去重 zy 版" to "除名字外，其他配置完全相同的节点才视为重复（TLS/SNI/路径等任一不同都会保留），避免误删可用节点；无重复时明确提示。",
                "多选" to "⋮ → 多选，点击节点即选择（样式跟随当前卡片：描边=粉色整卡描边 / 经典=左侧主题色竖条）；顶部一键全选/反选/退出；批量清空流量、删除节点、删除重复/去重zy版、TCPing、URL Test、清理测试结果、清理不可用配置、批量二维码与剪切板导出（标准/标准+ping/SN Link）。",
                "全局模式" to "绕过所有路由规则，全部流量走当前节点（默认关闭，可保留内网直连）。",
            )
        ),
        OptGroup(
            "测速全面升级", listOf(
                "连接自动测速" to "连接成功后自动对当前连接节点测速，状态栏显示「测速中… → 延迟 xx ms」，点击可再次测速。",
                "测速延迟持久化同步" to "测速同步的延迟实时写入数据库，重启软件、切换节点后仍保留，按延迟排序不回退；刷新按钮按当前排序重排立即生效。",
            )
        ),
        OptGroup(
            "导出升级", listOf(
                "分组剪切板导出+ping值" to "分组 → 导出到剪切板一键复制全部节点链接（含 ping 值）。",
                "分组文件导出+ping值" to "分组 → 导出到文件（含 ping 值），另一台 ZyBox 导入后延迟直接恢复，无需重新测速。",
                "单个及多选节点+ping 剪切板及二维码" to "单节点分享菜单与多选批量菜单均提供标准 / 标准+ping / SN Link 的二维码与剪切板导出。",
            )
        ),
        OptGroup(
            "导入升级", listOf(
                "大文件导入优化" to "数万节点一次事务导入，不卡不丢。",
                "导入自动刷新" to "从文件/剪切板导入节点创建新分组后，主页分组栏与节点列表自动刷新，无需重进页面。",
                "导入带 ping" to "导入含 |ping= 标记的链接或文件时，自动恢复对应节点的延迟与状态，无需重新测速。",
            )
        ),
        OptGroup(
            "搜索持久化", listOf(
                "搜索保持" to "搜索后进入节点编辑、分组设置等页面再返回，搜索关键词与筛选结果自动保持，无需重新输入搜索。",
            )
        ),
        OptGroup(
            "首次初始化向导", listOf(
                "一键初始化" to "首次启动弹出初始化向导：自动初始化（自动申请权限并进入一次设置页完成速度显示初始化后返回主页）、单独授权通知/VPN 权限，进度 0/3 实时显示；菜单「权限与初始化查询」页可随时查询并补做未完成项。",
            )
        ),
        OptGroup(
            "UI 全面升级", listOf(
                "排序与外观" to "右上角 ⋮：排序（原始/名称/延时，新分组默认延时）、布局（单列/双列，双列时操作按钮收进 ⋮）、卡片样式（经典=左侧主题色竖条 / 描边=整卡描边）。",
                "分组名胶囊" to "分组栏采用胶囊样式，当前分组高亮跟随主题色。",
                "工具栏增强" to "回到顶部（直达无动画）、刷新（按当前排序重排）；主页左上角 ZyBox 标题不截断。",
                "ZyBox 关于与更新" to "菜单新增关于页：检查更新渐变按钮、GitHub 地址链接按钮、捐赠支持、授权协议；自动获取 GitHub 最新发布版本，支持一键更新。",
            )
        ),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutZyboxOptimizationBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.zybox_opt_title)

        // ZyBox: 捐赠支持 → 浏览器
        binding.zyboxOptDonateButton.setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://zy520.de5.net/juanzeng/")
                    )
                )
            } catch (e: Exception) {
                io.nekohasekai.sagernet.ktx.Logs.w(e)
            }
        }

        buildContent(binding.zyboxOptContainer)
    }

    private fun buildContent(container: LinearLayout) {
        val dp = resources.displayMetrics.density

        fun dp2px(v: Int): Int = (v * dp).toInt()

        for (group in groups) {
            // 大标题：粉色渐变胶囊
            container.addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp2px(2), dp2px(14), dp2px(2), dp2px(6))
                }
                background = requireContext().getDrawable(R.drawable.zybox_group_bg)
                text = group.title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp2px(4), dp2px(12), dp2px(4), dp2px(12))
            })

            // 小项卡片
            for ((subTitle, desc) in group.items) {
                container.addView(MaterialCardView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp2px(2), dp2px(4), dp2px(2), dp2px(4))
                    }
                    radius = dp2px(12).toFloat()
                    cardElevation = 1f
                    setContentPadding(dp2px(4), dp2px(10), dp2px(4), dp2px(10))
                    addView(LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                        addView(View(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(dp2px(8), dp2px(8)).apply {
                                topMargin = dp2px(5)
                                setMargins(dp2px(12), dp2px(5), dp2px(10), 0)
                            }
                            background = requireContext().getDrawable(R.drawable.zybox_dot_pink)
                        })
                        addView(LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            ).apply {
                                setMargins(0, 0, dp2px(12), 0)
                            }
                            addView(TextView(requireContext()).apply {
                                text = subTitle
                                textSize = 14f
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setTextColor(0xFF333333.toInt())
                            })
                            addView(TextView(requireContext()).apply {
                                text = desc
                                textSize = 12f
                                setTextColor(0xFF777777.toInt())
                                setLineSpacing(0f, 1.15f)
                            })
                        })
                    })
                })
            }
        }

        // GitHub 开源地址（链接按钮）
        container.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp2px(2), dp2px(16), dp2px(2), dp2px(4))
            }
            background = requireContext().getDrawable(R.drawable.zybox_github_bg)
            text = "GitHub 开源地址\nhttps://github.com/fuoins/ZyBoxForAndroid"
            setTextColor(requireContext().getColor(R.color.material_pink_500))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp2px(4), dp2px(12), dp2px(4), dp2px(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
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
        })
    }
}
