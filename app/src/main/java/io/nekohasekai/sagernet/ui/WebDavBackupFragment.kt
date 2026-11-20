package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ZyBox: WebDAV 分组节点备份。
 * 备份 = 导出某个分组的节点链接（普通 / 标准+ping 两种），PUT 到 WebDAV；
 * 恢复 = GET 回文本，parseProxies 解析后导入到指定分组或新建分组。
 * 导出/导入逻辑全部复用现有实现，这里只加 WebDAV 传输通道与分组选择 UI。
 */
class WebDavBackupFragment : ToolbarFragment(R.layout.layout_webdav_backup) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.webdav_backup)

        val etUrl = view.findViewById<TextInputEditText>(R.id.webdav_url)
        val etUser = view.findViewById<TextInputEditText>(R.id.webdav_user)
        val etPass = view.findViewById<TextInputEditText>(R.id.webdav_password)
        val etFile = view.findViewById<TextInputEditText>(R.id.webdav_filename)
        val spGroup = view.findViewById<Spinner>(R.id.webdav_export_group)
        val spMode = view.findViewById<Spinner>(R.id.webdav_export_mode)
        val spTarget = view.findViewById<Spinner>(R.id.webdav_import_target)
        val newGroupLayout = view.findViewById<TextInputLayout>(R.id.webdav_newgroup_layout)
        val etNewGroup = view.findViewById<TextInputEditText>(R.id.webdav_newgroup_name)
        val btnUpload = view.findViewById<View>(R.id.webdav_upload)
        val btnDownload = view.findViewById<View>(R.id.webdav_download)
        val tvStatus = view.findViewById<TextView>(R.id.webdav_status)

        // 读入已保存的连接参数
        etUrl.setText(DataStore.webdavUrl)
        etUser.setText(DataStore.webdavUser)
        etPass.setText(DataStore.webdavPassword)
        etFile.setText(DataStore.webdavFilename.ifBlank { "zybox_profiles.txt" })

        // 分组列表（导出源 + 导入目标共用）
        val groups = SagerDatabase.groupDao.allGroups().filter { !it.ungrouped }
        val groupNames = groups.map { it.displayName() }
        val exportNames = if (groupNames.isEmpty()) listOf("(no group)") else groupNames

        val exportAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, exportNames)
        exportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spGroup.adapter = exportAdapter

        // 导入目标：第一项固定为"新建分组"，其后为现有分组
        val targetNames = mutableListOf(getString(R.string.webdav_target_new))
        targetNames.addAll(groupNames)
        val targetAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, targetNames)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTarget.adapter = targetAdapter
        spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                newGroupLayout.visibility = if (pos == 0) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        fun status(msg: String) {
            tvStatus.text = msg
        }

        fun saveConfig() {
            DataStore.webdavUrl = etUrl.text?.toString()?.trim().orEmpty()
            DataStore.webdavUser = etUser.text?.toString()?.trim().orEmpty()
            DataStore.webdavPassword = etPass.text?.toString().orEmpty()
            DataStore.webdavFilename = etFile.text?.toString()?.trim().orEmpty()
        }

        fun buildUrl(): String {
            var url = etUrl.text?.toString()?.trim().orEmpty()
            require(url.startsWith("http://") || url.startsWith("https://")) {
                getString(R.string.webdav_err_url)
            }
            val name = etFile.text?.toString()?.trim().orEmpty().ifBlank { "zybox_profiles.txt" }
            if (!url.endsWith("/")) url += "/"
            return url + name
        }

        fun authHeader(): String {
            val user = etUser.text?.toString()?.trim().orEmpty()
            val pass = etPass.text?.toString().orEmpty()
            return Credentials.basic(user, pass)
        }

        // ---------- 备份：导出分组节点 -> PUT ----------
        btnUpload.setOnClickListener {
            if (groups.isEmpty()) {
                status(getString(R.string.webdav_err_no_group))
                return@setOnClickListener
            }
            saveConfig()
            status(getString(R.string.webdav_uploading))
            runOnDefaultDispatcher {
                try {
                    val url = withContext(Dispatchers.Main) { buildUrl() }
                    val auth = withContext(Dispatchers.Main) { authHeader() }
                    val pos = withContext(Dispatchers.Main) { spGroup.selectedItemPosition }
                    val withPing = withContext(Dispatchers.Main) { spMode.selectedItemPosition == 1 }
                    val group = groups[pos]
                    val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                    val content = profiles.joinToString("\n") {
                        if (withPing) it.toStdLink(compact = true) + it.pingExportSuffix()
                        else it.toStdLink(compact = true)
                    }
                    val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", auth)
                        .put(body)
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException(
                                getString(R.string.webdav_err_http, resp.code)
                            )
                        }
                    }
                    onMainDispatcher {
                        status(getString(R.string.webdav_upload_done, profiles.size))
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { status(e.readableMessage) }
                }
            }
        }

        // ---------- 恢复：GET -> 解析 -> 导入到分组/新建分组 ----------
        btnDownload.setOnClickListener {
            saveConfig()
            status(getString(R.string.webdav_downloading))
            runOnDefaultDispatcher {
                try {
                    val url = withContext(Dispatchers.Main) { buildUrl() }
                    val auth = withContext(Dispatchers.Main) { authHeader() }
                    val targetPos = withContext(Dispatchers.Main) { spTarget.selectedItemPosition }
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", auth)
                        .get()
                        .build()
                    val text = client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException(
                                getString(R.string.webdav_err_http, resp.code)
                            )
                        }
                        resp.body?.string().orEmpty()
                    }
                    if (text.isBlank()) throw RuntimeException(getString(R.string.webdav_err_empty))

                    val proxies = parseProxies(text)
                    if (proxies.isEmpty()) throw RuntimeException(getString(R.string.webdav_err_parse))

                    // 确定目标分组
                    val targetId: Long
                    if (targetPos == 0) {
                        val name = withContext(Dispatchers.Main) {
                            etNewGroup.text?.toString()?.trim().orEmpty()
                        }.ifBlank { getString(R.string.webdav_default_newgroup) }
                        val g = ProxyGroup(type = io.nekohasekai.sagernet.GroupType.BASIC).apply {
                            this.name = name
                        }
                        targetId = GroupManager.createGroup(g).id
                    } else {
                        targetId = groups[targetPos - 1].id
                    }
                    DataStore.selectedGroup = targetId

                    // 复用现有批量导入的数据层逻辑（单事务写入，避免逐条事务锁竞争）
                    var order = SagerDatabase.proxyDao.nextOrder(targetId) ?: 1L
                    val entities = ArrayList<ProxyEntity>(proxies.size)
                    for (proxy in proxies) {
                        proxy.applyDefaultValues()
                        entities.add(ProxyEntity(groupId = targetId).apply {
                            putBean(proxy)
                            userOrder = order++
                        })
                    }
                    SagerDatabase.proxyDao.insert(entities)
                    GroupManager.postReload(targetId)

                    onMainDispatcher {
                        status(getString(R.string.webdav_download_done, proxies.size))
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { status(e.readableMessage) }
                }
            }
        }
    }
}
