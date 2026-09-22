# ZyBox for Android

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/fuoins/ZyBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/fuoins/ZyBoxForAndroid/releases)
[![Release](https://img.shields.io/github/v/release/fuoins/ZyBoxForAndroid)](https://github.com/fuoins/ZyBoxForAndroid/releases)

基于 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（v1.4.2）的个人定制版代理工具，内置 sing-box 核心，专为多订阅、多节点场景优化。

## 下载 / Download

最新版本见右侧 Releases（或直接访问 [Releases 页面](https://github.com/fuoins/ZyBoxForAndroid/releases)）。

> 当前最新：**v1.6.5**（内置 geoip/geosite 数据库，开箱即用）
>
> 仅构建 `arm64-v8a`（64 位 ARM 设备）。

## 定制功能 / Features

与官方版的差异，全部优化功能：

### 订阅管理
- **多订阅合并**：一个分组可挂多个实体订阅，节点合并显示在一个分组里
- **订阅独立更新**：更新时只替换属于该订阅自己的节点，互不干扰
- **管理订阅**：分组内订阅可单独添加 / 更新 / 删除，节点可逐条删除

### 测速相关
- **连接后自动测速**：连接成功立即对当前连接节点测速，状态栏显示"测速中… → 延迟 xx ms"
- **测速结果持久化**：测速同步的延迟写入数据库，重启软件、切换节点后仍保留，按延迟排序不回退
- **测速延迟同步节点**：测速结果实时同步到节点右侧延迟显示，刷新按钮按当前排序重排
- **导出带测速**：导出节点时带上测速结果（`|ping=` 标记），另一台 ZyBox 导入后延迟直接恢复
- 菜单开关：连接自动测速 / 测速延迟持久化同步 可在主页 ⋮ 菜单一键切换

### 导入与去重
- **大文件批量导入**：数万节点一次事务导入，不卡不丢
- **去重 zy 版**：除名字外其他配置完全相同的节点才视为重复（TLS/SNI/路径等任一不同都会保留）
- **默认分组**：文件 / 剪切板导入的节点默认进入"宇神神了"分组

### 路由开箱即用
- **内置 geoip.db / geosite.db**：首次启动自动拷贝到数据目录，"屏蔽广告 / 中国域名 / 中国IP"等规则集无需手动下载资产

### UI 与体验
- **UI 全面重构**：潮汐设计（浅色底 + 白卡 + 大圆角）、节点/分组卡片重新布局、延迟胶囊、协议类型徽章、Tab 胶囊、沉浸细节（隐藏滚动条 / 去分隔线）
- **卡片选中描边**：点击选中或连接的节点整卡描边（跟随主题色）
- **默认粉色主题**，20 余种配色可在设置中切换
- 工具栏增强：回到顶部（直达无动画）、刷新（按当前排序重排）
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
