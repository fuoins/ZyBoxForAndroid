# ZyBox for Android

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/fuoins/ZyBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/fuoins/ZyBoxForAndroid/releases)
[![Release](https://img.shields.io/github/v/release/fuoins/ZyBoxForAndroid)](https://github.com/fuoins/ZyBoxForAndroid/releases)

基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（官方 v1.4.2 基线）的个人定制版代理客户端，内置 sing-box 核心，专为多订阅、多节点场景优化。

## 下载 / Download

最新版本见右侧 Releases（或直接访问 [Releases 页面](https://github.com/fuoins/ZyBoxForAndroid/releases)）。

> 当前最新：**v3.0.0**
>
> 内置 geoip/geosite 数据库，开箱即用；仅构建 `arm64-v8a`（64 位 ARM 设备）。

## 定制功能 / Features

### 多订阅与默认配置
- **多订阅合并**：一个分组可挂多个实体订阅，节点合并显示在同一分组；分组 ⋮ → 管理订阅 直接可见，无需先添加订阅才解锁，可随时添加 / 编辑 / 删除每个订阅
- **多订阅增删修改订阅**：管理订阅里可单独添加、编辑、删除每个订阅，分组自带主订阅也一并显示（仅可更新）；节点可逐条删除，互不干扰
- **多订阅共同更新**：一键更新分组下全部订阅，更新时只替换属于该订阅自己的节点，其他订阅节点不受影响
- **多订阅整组测速**：对分组内全部订阅的节点统一测速（TCPing / URL Test），实时显示进度与结果
- **支持默认分组修改**：首个默认分组（首次导入自动创建）属于基本分组，可正常改名与修改

### 节点功能优化
- **去重 zy 版**：除名字外，其他配置完全相同的节点才视为重复（TLS/SNI/路径等任一不同都会保留），避免误删可用节点；无重复时明确提示
- **多选**：⋮ → 多选，点击节点即选择（样式跟随当前卡片：描边=粉色整卡描边 / 经典=左侧主题色竖条；区间选择有独立样式）；顶部一键全选/反选/退出，区间按钮点击后连点两个节点选中区间及本身；批量清空流量、删除节点、删除重复/去重zy版、TCPing、URL Test、清理测试结果、清理不可用配置、批量二维码与剪切板/文件导出（标准/标准+ping/SN Link）
- **全局模式**：绕过所有路由规则，全部流量走当前节点（默认关闭，可保留内网直连）

### 测速全面升级
- **连接自动测速**：连接成功后自动对当前连接节点测速，状态栏显示「测速中… → 延迟 xx ms」，点击可再次测速
- **自动测速等待时间**：可设置测速前等待时间（默认 1ms）：隧道未稳定时增加等待可减少误判超时；「首次失败重试」开启后首次超时自动再测一次，二次成功才显示延迟
- **测速延迟持久化同步**：测速同步的延迟实时写入数据库，重启软件、切换节点后仍保留，按延迟排序不回退；「同↑+红色保留」可同时保留超时/不可用/连接重置结果（红色显示），与同步二选一；刷新按钮按当前排序重排立即生效
- **未测试节点一键测速**：右上角 ⋮：TCPing 未测试节点 / URL Test 未测试节点，只对还没有延迟数据的节点批量测速
- **连接状态显示**：连接后左下角显示代理速度与延迟，右下角实时显示当前 IP 与 GeoIP 国家（中文名称），点击延迟可再次测速

### 导出升级
- **分组剪切板导出+ping值**：分组 → 导出到剪切板一键复制全部节点链接（含 ping 值）
- **分组文件导出+ping值**：分组 → 导出到文件（含 ping 值），另一台 ZyBox 导入后延迟直接恢复，无需重新测速
- **单个及多选节点+ping 剪切板及二维码**：单节点分享菜单与多选批量菜单均提供标准 / 标准+ping / SN Link 的二维码与剪切板导出

### 导入升级
- **新增分组与当前分组**：从剪切板/文件/二维码/手动输入导入时，均提供「到当前分组」与「到新建分组」两种入口（⋮ 菜单共 8 种导入方式）；扫描二维码与手动输入默认进入当前分组
- **大文件导入优化**：数万节点一次事务导入，不卡不丢
- **导入自动刷新**：从文件/剪切板导入节点创建新分组后，主页分组栏与节点列表自动刷新，无需重进页面
- **导入带 ping**：导入含 |ping= 标记的链接或文件时，自动恢复对应节点的延迟与状态，无需重新测速

### 搜索持久化
- **搜索保持**：搜索后进入节点编辑、分组设置等页面再返回，搜索关键词与筛选结果自动保持，无需重新输入搜索

### 首次初始化向导
- **4 项必须权限**：首次启动向导含 4 项必须：自动初始化 / 通知权限 / VPN 权限 / 应用列表权限（用于分应用代理）；顶部进入按钮显示「进入 X/4」，全部完成且初始化跑完才可进入；VPN 热点分栏含 Root 权限检测
- **一键必须 / 一键必须+可选**：一键必须：自动完成 4 项必须权限并进入一次设置页后返回主页；一键必须+可选：额外开启全部路由规则并导入分应用代理绕过配置；处理中按钮提示「正在一键处理中 请勿乱动」，必须权限 5 秒内未给全自动停止并提示手动授权
- **可选功能**：路由规则一键开启（切到路由页全开后回主页）、分应用代理绕过配置一键设置（内置作者自用配置，可在设置里修改）
- **权限与初始化查询**：菜单 → 权限与初始化查询：随时查询 4 项权限 + Root + 应用列表状态，一键补齐未完成项

### VPN 热点
- **VPN 热点**：菜单 → VPN 热点：开启后热点即带 VPN 共享，开关与 [VPNHotspot](https://github.com/Mygod/VPNHotspot) 同路径：优先 root 会话反射系统服务直连，无 root 自动降级普通进程
- **热点开关与状态实时同步**：开启/关闭热点实时生效；热点状态、已连接设备数实时刷新，系统设置中手动开关热点页面自动跟随
- **已连接设备数实时显示**：热点状态卡实时显示已连接设备数/最大支持数
- **管理系统共享**：热点 / USB / 蓝牙 / 以太网网络共享统一入口，一键跳转系统对应设置页
- **网络共享硬件加速**：显示系统当前开关状态；若 VPN 共享无法使用，请尝试在开发者选项关闭「网络共享硬件加速」
- **临时热点**：保留系统临时 Wi-Fi 热点入口
- **权限状态**：页面内实时查询 Root、附近的设备、修改系统设置权限状态，一键跳转授权

### 路由开箱即用
- **内置 geoip.db / geosite.db**：首次启动自动拷贝到数据目录，"屏蔽广告 / 中国域名 / 中国IP"等规则集无需手动下载资产

### UI 全面升级
- **UI 全面重构**：潮汐设计（浅色底 + 白卡 + 大圆角）、节点/分组卡片重新布局、延迟胶囊、协议类型徽章、Tab 胶囊
- **排序与外观**（右上角 ⋮）：排序（原始/名称/延时，新分组默认延时）、布局（单列/双列，双列时操作按钮收进 ⋮）、卡片样式（经典=左侧主题色竖条 / 描边=整卡描边）
- **分组名胶囊**：分组栏胶囊样式，当前分组高亮跟随主题色
- **工具栏增强**：回到顶部（直达无动画）、刷新（按当前排序重排）；主页左上角 ZyBox 标题不截断
- **捐赠入口**：抽屉菜单「推广」改为「捐赠」，一键跳转作者捐赠页（https://zy520.de5.net/juanzeng/）
- **ZyBox 关于与更新**：菜单新增关于页：检查更新渐变按钮、GitHub 地址链接按钮、捐赠支持、授权协议；自动获取 GitHub 最新发布版本，支持一键更新
- **默认粉色主题**，20 余种配色可在设置中切换

## 构建 / Build

环境：JDK 17 + Android SDK（compileSdk 35）。

```bash
git clone https://github.com/fuoins/ZyBoxForAndroid.git
cd ZyBoxForAndroid
# 仅构建 arm64-v8a 的正式版 APK
./gradlew :app:assembleOssRelease
# 产物: app/build/outputs/apk/oss/release/
```

版本号在 `nb4a.properties` 中修改（`VERSION_NAME` / `VERSION_CODE`）。

## 致谢 / Credits

- [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（[MatsuriDayo](https://github.com/MatsuriDayo)）—— 基础项目
- [starifly/NekoBoxForAndroid](https://github.com/starifly/NekoBoxForAndroid) —— **排序与外观**（排序/单列双列/卡片经典描边）、**全局模式** 功能参考移植
- [VPNHotspot](https://github.com/Mygod/VPNHotspot)（[Mygod](https://github.com/Mygod)）—— **VPN 热点**功能参考移植（热点开关、ap0 VPN 共享、客户端状态回调、网络共享入口）

## 许可 / License

- 本定制版基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（[MatsuriDayo](https://github.com/MatsuriDayo)），遵循 **GPL-3.0** 开源协议
- 移植的 **VPN 热点** 模块源自 [VPNHotspot](https://github.com/Mygod/VPNHotspot)（Mygod），遵循 **GPL-3.0** 开源协议
- sing-box core 遵循其各自的开源许可
- 应用图标为定制素材，仅用于本项目分发
