<div align="center">

<img src="docs/banner.png" width="100%" alt="ZyBox for Android">

<br/>

**基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（官方 v1.4.2 基线）的定制代理客户端，内置 sing-box 内核**<br/>
*把「多订阅、多节点」的订阅管理、测速、导入导出整个重做了一遍*

<br/>

<a href="https://github.com/fuoins/ZyBoxForAndroid/releases/latest"><img src="https://img.shields.io/github/v/release/fuoins/ZyBoxForAndroid?style=for-the-badge&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&color=FF3D82&logo=github&logoColor=white" alt="最新版本"></a> <a href="https://github.com/fuoins/ZyBoxForAndroid/releases"><img src="https://img.shields.io/github/downloads/fuoins/ZyBoxForAndroid/total?style=for-the-badge&label=%E7%B4%AF%E8%AE%A1%E4%B8%8B%E8%BD%BD&color=A855F7&logo=github&logoColor=white" alt="累计下载"></a> <img src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 5.0+"> <img src="https://img.shields.io/badge/ABI-arm64--v8a-5B8DEF?style=for-the-badge" alt="arm64-v8a"> <img src="https://img.shields.io/badge/License-GPL--3.0-FF8A3D?style=for-the-badge" alt="GPL-3.0">

<br/>

[下载](#-下载) ｜ [亮点](#-亮点) ｜ [功能详解](#-功能详解) ｜ [和原版有什么不同](#-和原版有什么不同) ｜ [构建](#-构建) ｜ [常见问题](#-常见问题)

</div>

## 📥 下载

| 版本 | 架构 | 体积 | 适用设备 | 安装包 |
| :--- | :--- | :--- | :--- | :--- |
| **v3.0.0** | `arm64-v8a` | 21.1 MB | Android 5.0+ 的 64 位 ARM 手机 / 平板 | [**下载 APK**](https://github.com/fuoins/ZyBoxForAndroid/releases/download/v3.0.0/ZyBox-3.0.0-arm64-v8a.apk) ／ [全部版本](https://github.com/fuoins/ZyBoxForAndroid/releases) |

安装包内置 geoip / geosite 数据库，**装完即用**，不需要再手动下载路由资产。目前只发布 `arm64-v8a` 单包，32 位设备与模拟器需要自行构建。

<details>
<summary><b>安装前请先看这 3 条</b></summary>

<br/>

**1. 先备份旧配置**
如果是从原版或其他分支换过来，先在旧版里导出一次配置备份，出问题可以随时恢复。

**2. 可能与原版冲突**
本项目与原版 NekoBox 使用同一个包名 `moe.nb4a`，但签名不同。覆盖安装报「应用未安装」时，先卸载原版再安装，再用备份恢复配置。

**3. 首启向导要走完**
第一次启动会要求 4 项必须权限（自动初始化 / 通知 / VPN / 应用列表）。顶部按钮显示「进入 X/4」，全部给齐且初始化跑完才能进主页；5 秒内没给全会自动停止，改为手动授权。

</details>

## ✨ 亮点

| | | |
| :---: | :---: | :---: |
| **🔀 多订阅合并**<br/><br/>一个分组挂多个订阅，节点合并显示，更新与测速互不干扰 | **⚡ 延迟不再丢**<br/><br/>连接自动测速，结果写进数据库，重启、切节点都不回退 | **📦 导入导出带 ping**<br/><br/>换机时导出带延迟，导入直接恢复，不用重测 |
| **✅ 批量操作**<br/><br/>多选、区间选择，批量删除 / 测速 / 导出 / 清流量 | **📶 VPN 热点**<br/><br/>热点直接带 VPN 共享，移植自 VPNHotspot | **🎨 潮汐 UI**<br/><br/>浅色底 + 白卡 + 大圆角，默认粉色，20 余种配色 |

## 🧩 功能详解

> 点标题展开。想看某一项具体怎么用，直接找对应分组。

<details>
<summary><b>订阅与分组</b> —— 一个分组挂多个订阅，更新、测速、导出互不影响</summary>

<br/>

- **多订阅合并**：一个分组可挂多个实体订阅，节点合并显示在同一分组内。
- **管理订阅常驻可见**：分组 ⋮ → 管理订阅，不需要先添加订阅才解锁。
- **订阅级增删改**：可单独添加 / 编辑 / 删除每个订阅；分组自带的主订阅一并显示（仅可更新）。
- **共同更新**：一键更新分组下全部订阅，只替换属于该订阅自己的节点，其他订阅的节点原样保留。
- **整组测速**：对分组内全部订阅的节点统一测速（TCPing / URL Test），实时显示进度与结果。
- **默认分组可改**：首次导入自动创建的分组属于基本分组，可正常改名与修改。

</details>

<details>
<summary><b>导入</b> —— 8 种入口，都能选「当前分组 / 新建分组」</summary>

<br/>

- **两个落点**：从剪切板、文件、二维码、手动输入导入时，均可选择「到当前分组」或「到新建分组」。
- **大文件不卡**：数万节点一次事务导入，不卡顿、不丢节点。
- **导入后自动刷新**：创建新分组后，主页分组栏与节点列表自动刷新，不用退出重进。
- **导入带 ping**：链接或文件含 `|ping=` 标记时，自动恢复对应节点的延迟与状态，无需重新测速。
- 扫描二维码与手动输入默认进入当前分组。

</details>

<details>
<summary><b>导出</b> —— 分组、单节点、多选批量，全部支持带 ping</summary>

<br/>

- **分组 → 剪切板**：一键复制分组内全部节点链接（含 ping 值）。
- **分组 → 文件**：导出文件同样带 ping，另一台 ZyBox 导入后延迟直接恢复。
- **单节点 / 多选批量**：标准、标准 + ping、SN Link 三种格式，二维码与剪切板两种出口。

</details>

<details>
<summary><b>节点与多选</b> —— 去重更保守，批量操作更省事</summary>

<br/>

- **去重 zy 版**：除名字外其他配置完全相同才判为重复（TLS / SNI / 路径任一不同都会保留），避免误删可用节点；没有重复时明确提示。
- **多选模式**（分组 ⋮ → 多选）：点节点即选中，样式跟随当前卡片（描边 = 整卡粉色描边，经典 = 左侧主题色竖条）；顶部一键全选 / 反选 / 退出。
- **区间选择**：点「区间」后再点两个节点，一次选中区间（含两端），选完自动退出区间模式。
- **批量操作**：清空流量、删除节点、删除重复 / 去重 zy 版、TCPing、URL Test、清理测试结果、清理不可用配置，以及批量二维码与剪切板 / 文件导出。
- **全局模式**：绕过所有路由规则，全部流量走当前节点（默认关闭，可保留内网直连）。
- **搜索持久化**：进节点编辑、分组设置再返回，搜索关键词与筛选结果保持不变。

</details>

<details>
<summary><b>测速</b> —— 连接即测，结果持久化</summary>

<br/>

- **连接自动测速**：连接成功后自动测当前节点，状态栏依次显示「测速中… → 延迟 xx ms」，点击可再测。
- **等待时间可调**（默认 1 ms）：隧道没稳定就测容易误判超时，加一点等待更准。
- **首次失败重试**：首次超时自动再测一次，二次成功才显示延迟。
- **延迟持久化同步**：延迟实时写入数据库，重启软件、切换节点后仍保留，按延迟排序不会回退。
- **同↑ + 红色保留**：与上一条二选一，额外保留超时 / 不可用 / 连接重置的结果（红色显示）。
- **只测没测过的**：右上角 ⋮ → TCPing 未测试节点 / URL Test 未测试节点。
- **连接状态显示**：左下角显示代理速度与延迟，右下角显示当前 IP 与 GeoIP 国家（中文名），点延迟可再测。

</details>

<details>
<summary><b>首次初始化向导</b> —— 4 项必须权限，一次点完</summary>

<br/>

- **4 项必须**：自动初始化 / 通知权限 / VPN 权限 / 应用列表权限（分应用代理用）；顶部按钮显示「进入 X/4」，全部完成且初始化跑完才可进入。VPN 热点分栏另有 Root 权限检测。
- **一键必须**：自动完成 4 项必须权限，进一次设置页后返回主页。
- **一键必须 + 可选**：在上一档基础上，再开启全部路由规则并导入分应用代理绕过配置。
- **防误操作**：处理中提示「正在一键处理中，请勿乱动」；必须权限 5 秒内未给全会自动停止并提示手动授权。
- **权限查询**：菜单 → 权限与初始化查询，随时查看 4 项权限 + Root + 应用列表状态，并一键补齐未完成项。

</details>

<details>
<summary><b>VPN 热点</b> —— 移植 VPNHotspot，热点直接共享 VPN</summary>

<br/>

- **同路径开关**：优先走 root 会话反射系统服务，无 root 自动降级为普通进程，与 VPNHotspot 行为一致。
- **状态实时同步**：开关实时生效，热点状态与已连接设备数实时刷新，在系统设置里手动开关也会跟随。
- **已连接数显示**：状态卡显示「已连接 / 最大支持」。
- **系统共享入口**：热点 / USB / 蓝牙 / 以太网统一入口，一键跳转对应设置页；保留系统临时热点入口。
- **硬件加速提示**：显示「网络共享硬件加速」当前状态；VPN 共享不可用时，可在开发者选项关闭它。
- **权限状态**：页面内实时查询 Root、附近的设备、修改系统设置权限，一键跳转授权。

</details>

<details>
<summary><b>界面与外观</b> —— 潮汐设计，默认粉色</summary>

<br/>

- **潮汐设计**：浅色底 + 白卡 + 大圆角；节点与分组卡片重新布局，延迟胶囊、协议类型徽章、Tab 胶囊。
- **排序与外观**（右上角 ⋮）：排序（原始 / 名称 / 延时，新分组默认延时）、布局（单列 / 双列，双列时操作收进 ⋮）、卡片样式（经典 = 左侧主题色竖条，描边 = 整卡描边）。
- **工具栏**：回到顶部（无动画直达）、刷新（按当前排序重排）；主页左上角标题不截断。
- **分组名胶囊**：分组栏胶囊样式，当前分组高亮跟随主题色。
- **关于页**：检查更新渐变按钮、GitHub 地址、捐赠支持、授权协议；自动获取 GitHub 最新发布版本，支持一键更新。
- **主题**：默认粉色，20 余种配色可在设置里切换。
- **捐赠入口**：抽屉菜单「推广」改为「捐赠」，一键跳转作者捐赠页。

</details>

<details>
<summary><b>路由</b> —— geoip / geosite 内置，开箱即用</summary>

<br/>

- 首次启动自动把内置 `geoip.db` / `geosite.db` 拷贝到数据目录。
- 「屏蔽广告 / 中国域名 / 中国 IP」等规则集不需要手动下载资产。

</details>

## 🆚 和原版有什么不同

| 常见痛点 | ZyBox 的做法 |
| :--- | :--- |
| 一个分组只能挂一个订阅，多机场要来回切 | 一个分组挂多个订阅，合并显示、独立更新、整组测速 |
| 测速结果重启就丢，换机要重新测一遍 | 延迟写库持久化，导出带 ping，导入即恢复 |
| 批量删节点、批量导出只能一条条点 | 多选 + 区间选择 + 一整排批量操作 |
| 想让热点也走代理，得另装一个 App | 内置 VPN 热点，开关就在菜单里 |
| 首次使用要自己找路由规则、逐个开权限 | 首启向导 + 一键必须权限 + 内置规则库 |

## 🛠 构建

| 项目 | 要求 |
| :--- | :--- |
| JDK | 17 |
| Android SDK | `compileSdk 37`、`targetSdk 35`、`minSdk 21` |
| 产物架构 | 仅 `arm64-v8a`（`splits.abi` 中配置） |

```bash
git clone https://github.com/fuoins/ZyBoxForAndroid.git
cd ZyBoxForAndroid

# 构建（产物：app/build/outputs/apk/<flavor>/release/）
./gradlew :app:assembleFdroidRelease

# 也可换成 :app:assembleOssRelease / :app:assemblePlayRelease
```

版本号在 `nb4a.properties` 里改：`VERSION_NAME`（如 `3.0.0`）与 `VERSION_CODE`。

推送到 `main` 会自动触发 [`.github/workflows/build.yml`](.github/workflows/build.yml)。也可以在 Actions 页面手动运行，指定 `variant`（`fdroidRelease` / `playRelease` / `debug`）与是否开启 `minify`，APK 在 workflow 的 Artifacts 里下载。

## ❓ 常见问题

<details>
<summary><b>安装时提示「应用未安装」？</b></summary>

<br/>

与原版同包名 `moe.nb4a` 但签名不同，系统会拒绝覆盖。先卸载原版再安装（提前导出配置备份）。

</details>

<details>
<summary><b>32 位设备 / 模拟器能装吗？</b></summary>

<br/>

目前只发布 `arm64-v8a` 单包。32 位设备与模拟器需要自行修改构建脚本里的 ABI 配置后重新编译。

</details>

<details>
<summary><b>延迟一直显示超时 / 不可用？</b></summary>

<br/>

先把「自动测速等待时间」调大（隧道没稳定就测容易误判超时），并确认「首次失败重试」已开启。仍然全部超时，多半是节点本身或网络问题。

</details>

<details>
<summary><b>VPN 热点开了，连接的设备上不了网？</b></summary>

<br/>

先到开发者选项关闭「网络共享硬件加速」再试。无 root 时走普通进程模式，部分机型还需要授予「修改系统设置」与「附近的设备」权限。

</details>

<details>
<summary><b>需要手动下载 geoip / geosite 吗？</b></summary>

<br/>

不需要。安装包内置了 `geoip.db` / `geosite.db`，首次启动自动拷贝到数据目录。

</details>

<details>
<summary><b>和上游 NekoBox 的更新关系？</b></summary>

<br/>

本项目基于 NekoBoxForAndroid 官方 v1.4.2 基线开发，改动集中在订阅管理、测速、导入导出、VPN 热点与界面，属于定制分支而非上游的替代品。

</details>

## 🙏 致谢

| 项目 | 作者 | 用到了什么 |
| :--- | :--- | :--- |
| [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) | [MatsuriDayo](https://github.com/MatsuriDayo) | 基础项目（v1.4.2 基线） |
| [starifly/NekoBoxForAndroid](https://github.com/starifly/NekoBoxForAndroid) | starifly | 排序与外观（排序 / 单列双列 / 卡片样式）、全局模式 |
| [VPNHotspot](https://github.com/Mygod/VPNHotspot) | [Mygod](https://github.com/Mygod) | VPN 热点模块（开关、ap0 共享、客户端回调、共享入口） |

## 📄 许可

- 本项目基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（MatsuriDayo），遵循 **GPL-3.0**。
- 移植的 VPN 热点模块源自 [VPNHotspot](https://github.com/Mygod/VPNHotspot)（Mygod），遵循 **GPL-3.0**。
- sing-box 内核遵循其自身的开源许可。
- 应用图标为定制素材，仅用于本项目分发。

<div align="center">
<br/>

如果 ZyBox 帮你省了点事，点个 ⭐ 就是最好的支持

<a href="https://github.com/fuoins/ZyBoxForAndroid/stargazers"><b>Star</b></a> ｜ <a href="https://zy520.de5.net/juanzeng/"><b>捐赠</b></a>

</div>
