package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                        SagerDatabase.subscriptionDao.all()
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

    // ZyBox: 导出顺序与主页节点列表一致（按分组排序设置：原始/名称/延时），全部节点保留
    private suspend fun sortedExportProfiles(group: ProxyGroup): List<ProxyEntity> {
        var profiles = try {
            SagerDatabase.proxyDao.getByGroup(group.id)
        } catch (e: Exception) {
            Logs.w("export getByGroup failed: ${e.message}")
            emptyList()
        }
        when (group.order) {
            GroupOrder.BY_NAME -> {
                profiles = profiles.sortedBy { it.displayName() }
            }
            GroupOrder.BY_DELAY -> {
                profiles = profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            }
        }
        return profiles
    }

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = sortedExportProfiles(selectedGroup)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    // ZyBox: export nodes together with their speed-test results ("|ping=xxx")
    private val exportProfilesWithPing =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = sortedExportProfiles(selectedGroup)
                    val links = profiles.joinToString("\n") {
                        it.toStdLink(compact = true) + if (it.ping > 0) "|ping=${it.ping}" else ""
                    }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            // ZyBox: 未分组自动迁移到"宇神神了"并删除；空未分组一律移除（不再依赖 size>1）
            val ungrouped = groups.find { it.ungrouped }
            if (ungrouped != null) {
                val zyGroup = groups.find { it.name == "宇神神了" && !it.ungrouped }
                if (zyGroup != null && ungrouped.id != zyGroup.id) {
                    val entities = SagerDatabase.proxyDao.getByGroup(ungrouped.id)
                    if (entities.isNotEmpty()) {
                        var order = SagerDatabase.proxyDao.nextOrder(zyGroup.id) ?: 1L
                        entities.forEach {
                            it.groupId = zyGroup.id
                            it.userOrder = order++
                        }
                        SagerDatabase.proxyDao.updateProxy(entities)
                    }
                    SagerDatabase.groupDao.deleteGroup(ungrouped)
                    groups.remove(ungrouped)
                } else if (SagerDatabase.proxyDao.countByGroup(ungrouped.id) == 0L) {
                    groups.removeAll { it.ungrouped }
                }
            }
            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            // ZyBox: 防御——添加新分组前清除残留的空未分组
            groupList.removeAll { it.ungrouped }
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

        override suspend fun subscriptionAdd(entity: SubscriptionEntity) {
            reload()
        }

        override suspend fun subscriptionUpdated(entity: SubscriptionEntity) {
            reload()
        }

        override suspend fun subscriptionRemoved(entity: SubscriptionEntity) {
            reload()
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = sortedExportProfiles(selectedGroup)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_export_ping_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = sortedExportProfiles(selectedGroup)
                        val links = profiles.joinToString("\n") {
                            it.toStdLink(compact = true) + if (it.ping > 0) "|ping=${it.ping}" else ""
                        }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_ping_file -> {
                    startFilesForResult(exportProfilesWithPing, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                R.id.action_add_subscription -> {
                    showAddSubscriptionDialog(proxyGroup)
                }

                R.id.action_manage_subscriptions -> {
                    showManageSubscriptionsDialog(proxyGroup)
                }

                R.id.action_merge_to_group -> {
                    showMergeGroupDialog(proxyGroup)
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener { }

            // ZyBox: 导入分组也显示"修改"按钮（设置页内分组类型固定为"导入"）
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                updateGroupSubscriptions(proxyGroup)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                runOnDefaultDispatcher {
                    val hasSubs = SagerDatabase.subscriptionDao.countByGroup(proxyGroup.id) > 0
                    onMainDispatcher {
                        if (!hasSubs) {
                            popup.menu.removeItem(R.id.action_manage_subscriptions)
                        }
                        popup.setOnMenuItemClickListener(this@GroupHolder)
                        popup.show()
                    }
                }
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                val subCount = SagerDatabase.subscriptionDao.countByGroup(group.id)
                onMainDispatcher {
                    if (proxyGroup.id !in GroupUpdater.updating) {
                        updateButton.isInvisible =
                            group.type != GroupType.SUBSCRIPTION && subCount == 0L
                    }
                    @Suppress("DEPRECATION") when {
                        subCount > 0L -> {
                            groupStatus.text = getString(
                                R.string.group_status_proxies_subscriptions, size, subCount
                            )
                        }

                        group.type == GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        group.type == GroupType.SUBSCRIPTION -> {
                            groupStatus.text = if (size == 0L) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }

        // ===== 多订阅支持：一个分组绑定多个订阅 =====

        private fun updateGroupSubscriptions(group: ProxyGroup) {
            runOnDefaultDispatcher {
                if (group.type == GroupType.SUBSCRIPTION && group.subscription != null) {
                    GroupUpdater.startUpdate(group, true)
                }
                for (entity in SagerDatabase.subscriptionDao.getByGroup(group.id)) {
                    GroupUpdater.startUpdate(entity, true)
                }
            }
        }

        private fun showAddSubscriptionDialog(group: ProxyGroup) {
            val view = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
            val nameInput = view.findViewById<EditText>(R.id.subscription_name)
            val linkInput = view.findViewById<EditText>(R.id.subscription_link)
            val autoUpdateSwitch = view.findViewById<SwitchMaterial>(R.id.subscription_auto_update)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_subscription_to_group)
                .setView(view)
                .setPositiveButton(R.string.yes) { _, _ ->
                    val link = linkInput.text?.toString()?.trim().orEmpty()
                    if (link.isBlank()) {
                        snackbar(R.string.subscription_link_empty).show()
                        return@setPositiveButton
                    }
                    val name = nameInput.text?.toString()?.trim().orEmpty()
                    val bean = SubscriptionBean().applyDefaultValues().apply {
                        this.link = link
                        autoUpdate = autoUpdateSwitch.isChecked
                    }
                    val entity = SubscriptionEntity(
                        name = name.ifBlank { null },
                        groupId = group.id,
                        bean = bean
                    )
                    runOnDefaultDispatcher {
                        val created = GroupManager.createSubscription(entity)
                        GroupUpdater.startUpdate(created, true)
                        // ZyBox: 提示显示本组订阅总数（含分组自带主订阅 + 原有订阅）
                        val mainSub = if (group.type == GroupType.SUBSCRIPTION && group.subscription != null) 1 else 0
                        val count = mainSub + SagerDatabase.subscriptionDao.getByGroup(group.id).size
                        onMainDispatcher {
                            snackbar(getString(R.string.subscription_added_count, count)).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showManageSubscriptionsDialog(group: ProxyGroup) {
            runOnDefaultDispatcher {
                val entities = SagerDatabase.subscriptionDao.getByGroup(group.id)
                onMainDispatcher {
                    val mainSub = group.subscription?.takeIf {
                        group.type == GroupType.SUBSCRIPTION
                    }
                    if (entities.isEmpty() && mainSub == null) {
                        snackbar(R.string.no_subscriptions).show()
                        return@onMainDispatcher
                    }
                    val scroll = ScrollView(requireContext())
                    val container = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
                    }
                    scroll.addView(container)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.manage_subscriptions)
                        .setView(scroll)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    // ZyBox: 分组自带主订阅（第一行，仅可更新，不提供删除/编辑）
                    if (mainSub != null) {
                        container.addView(
                            buildMainSubscriptionRow(group, mainSub) { dialog.dismiss() }
                        )
                    }
                    for (entity in entities) {
                        container.addView(buildSubscriptionRow(entity) { dialog.dismiss() })
                    }
                    dialog.show()
                }
            }
        }

        // ZyBox: 分组自带主订阅行（更新走分组更新入口，不写 SubscriptionEntity 表）
        private fun buildMainSubscriptionRow(
            group: ProxyGroup, bean: SubscriptionBean, dismiss: () -> Unit
        ): View {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp2px(8), 0, dp2px(8))
            }
            row.addView(TextView(requireContext()).apply {
                text = group.displayName()
                textSize = 15f
                setTextColor(0xFF222222.toInt())
            })
            row.addView(TextView(requireContext()).apply {
                text = bean.link.orEmpty()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            })
            val lastUpdated = bean.lastUpdated ?: 0
            row.addView(TextView(requireContext()).apply {
                text = if (lastUpdated > 0) {
                    getString(R.string.subscription_updated_at, subscriptionTimeText(lastUpdated))
                } else {
                    getString(R.string.subscription_never_updated)
                }
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            })
            row.addView(Button(requireContext()).apply {
                text = getString(R.string.update_subscription)
                isAllCaps = false
                setOnClickListener {
                    dismiss()
                    runOnDefaultDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            })
            return row
        }

        private fun buildSubscriptionRow(entity: SubscriptionEntity, dismiss: () -> Unit): View {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp2px(8), 0, dp2px(8))
            }
            row.addView(TextView(requireContext()).apply {
                text = entity.displayName()
                textSize = 15f
                setTextColor(0xFF222222.toInt())
            })
            row.addView(TextView(requireContext()).apply {
                text = entity.bean?.link.orEmpty()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            })
            val lastUpdated = entity.bean?.lastUpdated ?: 0
            row.addView(TextView(requireContext()).apply {
                text = if (lastUpdated > 0) {
                    getString(R.string.subscription_updated_at, subscriptionTimeText(lastUpdated))
                } else {
                    getString(R.string.subscription_never_updated)
                }
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            })
            val buttons = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(requireContext()).apply {
                    text = getString(R.string.update_subscription)
                    isAllCaps = false
                    setOnClickListener {
                        dismiss()
                        runOnDefaultDispatcher {
                            GroupUpdater.startUpdate(entity, true)
                        }
                    }
                })
                addView(Button(requireContext()).apply {
                    text = getString(R.string.edit_subscription)
                    isAllCaps = false
                    setOnClickListener {
                        dismiss()
                        showEditSubscriptionDialog(entity)
                    }
                })
                addView(Button(requireContext()).apply {
                    text = getString(R.string.delete_subscription)
                    isAllCaps = false
                    setOnClickListener {
                        dismiss()
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.delete_subscription)
                            .setMessage(R.string.delete_subscription_confirm)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                runOnDefaultDispatcher {
                                    GroupManager.deleteSubscription(entity)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                })
            }
            row.addView(buttons)
            return row
        }

        private fun showEditSubscriptionDialog(entity: SubscriptionEntity) {
            val view = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
            val nameInput = view.findViewById<EditText>(R.id.subscription_name)
            val linkInput = view.findViewById<EditText>(R.id.subscription_link)
            val autoUpdateSwitch = view.findViewById<SwitchMaterial>(R.id.subscription_auto_update)
            nameInput.setText(entity.name ?: "")
            linkInput.setText(entity.bean?.link ?: "")
            autoUpdateSwitch.isChecked = entity.bean?.autoUpdate == true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.edit_subscription)
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    val link = linkInput.text?.toString()?.trim().orEmpty()
                    if (link.isBlank()) {
                        snackbar(R.string.subscription_link_empty).show()
                        return@setPositiveButton
                    }
                    val bean = entity.bean ?: SubscriptionBean().applyDefaultValues()
                    bean.link = link
                    bean.autoUpdate = autoUpdateSwitch.isChecked
                    entity.name = nameInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    entity.bean = bean
                    runOnDefaultDispatcher {
                        GroupManager.updateSubscription(entity)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showMergeGroupDialog(source: ProxyGroup) {
            runOnDefaultDispatcher {
                val groups = SagerDatabase.groupDao.allGroups()
                    .filter { it.id != source.id && !it.ungrouped }
                onMainDispatcher {
                    if (groups.isEmpty()) {
                        snackbar(R.string.no_groups_to_merge).show()
                        return@onMainDispatcher
                    }
                    val names = groups.map { it.displayName() }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.merge_to_other_group)
                        .setItems(names) { _, which ->
                            val target = groups[which]
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.confirm)
                                .setMessage(
                                    getString(
                                        R.string.merge_confirm_message,
                                        source.displayName(),
                                        target.displayName()
                                    )
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        GroupManager.mergeGroup(source.id, target.id)
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }

        private fun subscriptionTimeText(lastUpdated: Int): String {
            val date = Date(lastUpdated * 1000L)
            val minute = if (date.minutes < 10) "0${date.minutes}" else "${date.minutes}"
            return "${date.year + 1900}-${date.month + 1}-${date.date} ${date.hours}:$minute"
        }
    }

}