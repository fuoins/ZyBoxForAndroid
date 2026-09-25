# ZyBox for Android

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/fuoins/ZyBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/fuoins/ZyBoxForAndroid/releases)
[![Release](https://img.shields.io/github/v/release/fuoins/ZyBoxForAndroid)](https://github.com/fuoins/ZyBoxForAndroid/releases)

基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（官方 v1.4.2 基线）的个人定制版代理工具，内置 sing-box 核心，专为多订阅、多节点场景优化。

## 下载 / Download

最新版本见右侧 Releases（或直接访问 [Releases 页面](https://github.com/fuoins/ZyBoxForAndroid/releases)）。

> 当前最新：**v2.0.2**
>
> 内置 geoip/geosite 数据库，开箱即用；仅构建 `arm64-v8a`（64 位 ARM 设备）。

## 定制功能 / Features

与官方版的差异，全部优化功能：

### 订阅管理
- **多订阅合并**：一个分组可挂多个实体订阅，节点合并显示在一个分组里
- **订阅独立更新**：更新时只替换属于该订阅自己的节点，互不干扰
- **管理订阅**：分组内订阅可单独添加 / 更新 / 删除，节点可逐条删除
- **分组导出按主页顺序**：导出节点顺序与主页看到的排序一致（原始 / 名称 / 延时），订阅节点与手动导入节点全部保留

### 导入到分组（重做）
- **导入到当前分组**：从剪切板导入 / 从文件导入 / 从剪切板导入+ping / 从文件导入+ping / 扫描二维码 / 手动输入，全部导入到**当前首页所处分组**
- **导入到新建分组**：每种导入方式都有对应的"到新建分组"入口，自动创建"宇神神了"分组并切换过去
- **默认分组**：首次使用（没有任何分组）时自动创建"宇神神了"；新建分组默认名"宇神神了"
- **导入与 ping**：普通导入不带 `|ping=` 标记；只有"+ping"导入才恢复延迟（含超时 / 不可用 / 连接重置的失败状态）

### 测速相关
- **连接后自动测速**：连接成功立即对当前连接节点测速，状态栏显示"测速中… → 延迟 xx ms"，点击可再次测速
- **测速结果持久化**：测速同步的延迟写入数据库，重启软件、切换节点后仍保留，按延迟排序不回退
- **同↑+红色保留**（与"测速延迟持久化同步"单选互斥）：同步延迟的同时，把超时 / 不可用 / 连接重置的失败结果也持久化，节点延迟位置显示红色失败状态
- **测速延迟同步节点**：测速结果实时同步到节点右侧延迟显示，刷新按钮按当前排序重排
- **只测未测试节点**：⋮ 菜单新增"TCPing未测试节点 / URL Test未测试节点"，只测还没有延迟结果的节点
- **导出带测速**：导出节点时带上测速结果（`|ping=` 标记，失败节点带 `|ping=0`），另一台 ZyBox 用"+ping"导入后延迟与失败状态直接恢复

### 多选模式
- 工具栏常驻**多选入口**（列表图标），点击进入多选；顶部一键**全选 / 反选 / 退出**
- **区间选择**：多选工具栏"区间"按钮 → 提示"点击两节点会选择区间节点及本身" → 点击两个节点，两者之间的全部节点（含两端）被选中，选中期间使用独立高亮样式（主题色浅底+描边），选完自动恢复经典 / 描边样式；区间进行中按钮变"取消"，点取消恢复普通多选点击
- 批量操作（多选 ⋮ 内）：
  - 清空多选流量统计数据 / 删除多选重复的服务器 / 去重多选zy版
  - 多选 TCPing / 多选 URL Test / 清理多选测试结果 / 清理多选不可用配置
  - 批量二维码：标准 / 标准+ping值 / SN Link
  - 批量到剪切板：标准 / 标准+ping / SN Link
  - 批量到文件：标准 / +ping

### 导入与去重
- **大文件批量导入**：数万节点一次事务导入，不卡不丢
- **去重 zy 版**：除名字外其他配置完全相同的节点才视为重复（TLS/SNI/路径等任一不同都会保留）

### 首次初始化
- **初始化向导**：首次启动弹出列表式弹窗，进度 0/3 实时显示
  - 自动初始化：一键申请权限并进入一次设置页完成速度显示初始化后返回主页
  - 授权通知权限：通知栏显示实时直连和代理的上传 / 下载速度
  - 授权 VPN 权限：必须授予，否则无法使用
- 菜单新增**权限与初始化查询**页，可随时查询 / 补做未完成项

### 路由开箱即用
- **内置 geoip.db / geosite.db**：首次启动自动拷贝到数据目录，"屏蔽广告 / 中国域名 / 中国IP"等规则集无需手动下载资产

### UI 与体验
- **UI 全面重构**：潮汐设计（浅色底 + 白卡 + 大圆角 16dp）、节点/分组卡片重新布局、延迟胶囊、协议类型徽章、Tab 胶囊、沉浸细节（隐藏滚动条 / 去分隔线）
- **卡片选中样式**：描边样式选中整卡粉色描边；经典样式选中左侧主题色粗竖条
- **排序与外观**（右上角 ⋮）：排序（原始/名称/延时，新分组默认延时）、布局（单列/双列）、卡片样式（经典/描边）
- **全局模式**：绕过所有路由规则，全部流量走当前节点（默认关闭）
- **默认粉色主题**，20 余种配色可在设置中切换
- 工具栏增强：回到顶部（直达无动画）、刷新（按当前排序重排）
- 主页左上角不再显示应用标题，工具栏更简洁
- 宝箱"神"字应用图标

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

## 许可 / License

- 本定制版基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（[MatsuriDayo](https://github.com/MatsuriDayo)），遵循 **GPL-3.0** 开源协议
- sing-box core 遵循其各自的开源许可
- 应用图标为定制素材，仅用于本项目分发
