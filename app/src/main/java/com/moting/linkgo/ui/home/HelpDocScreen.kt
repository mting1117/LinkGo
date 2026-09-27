package com.moting.linkgo.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import com.moting.linkgo.ui.components.SettingsSection

/**
 * 链接跳转 (LinkGo) 官方全量指南页面
 */
@Composable
fun HelpDocScreen(
    onNavigateBack: () -> Unit
) {
    CollapsingTopBarScaffold(
        title = "官方指南",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        }
    ) { padding, nestedScrollConnection ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "LinkGo 是一款面向 Android 进阶用户的智能链接路由与分发编排工具。通过系统级 Intent 接管、剪贴板多链路感知与厂商深度小窗引擎，让您完全掌控每一个链接的去向与展示形态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // 1. 基础配置
            item {
                DocSection(
                    title = "基础配置：系统分发接管与核心权限",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_settings),
                    content = {
                        DocItem(
                            subtitle = "核心分发接管：设置为默认浏览器",
                            body = "这是实现自动跳转的基石。只有将 LinkGo 设为系统默认浏览器，系统在点击各类网页链接时才会优先交由 LinkGo 进行智能规则解析与路由。"
                        )
                        DocItem(
                            subtitle = "应用识别：读取应用列表 (包可见性)",
                            body = "LinkGo 需要获取已安装应用列表以匹配目标应用。在 HyperOS/MIUI/ColorOS 等系统中，请前往“应用管理 -> LinkGo -> 权限管理”，将“读取应用列表 / 应用信息”设为“始终允许”。"
                        )
                        DocItem(
                            subtitle = "任务列表可见性 (最近任务防残留)",
                            body = "共三处独立开关，各管一段：①「设置 - 交互设置」中的任务列表可见性，控制 LinkGo 自身是否出现在最近任务；② 首页「备选浏览器」卡片中的同名开关，控制备选跳转后目标浏览器是否留下卡片；③ 规则编辑页的「不留后台卡片」，控制该条规则命中的目标应用是否留下卡片。三者互不影响。"
                        )
                        DocItem(
                            subtitle = "实时跳转通知 (Android 16+ 动态活动)",
                            body = "在支持 Android 16 (Baklava)+ 的系统上，LinkGo 支持以状态栏动态胶囊与实时活动通知呈现跳转进度，并提供快捷复制与一键直达。"
                        )
                    }
                )
            }

            // 2. 剪贴板后台监听链路
            item {
                DocSection(
                    title = "剪贴板后台监听：多链路技术原理",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_paste_clipboard),
                    content = {
                        DocItem(
                            subtitle = "LSPosed 模块模式 (首选 · 系统级 Hook)",
                            body = "• 原理：通过 Xposed 框架直接注入系统剪贴板服务（ClipboardService），监听系统级数据写入回调。\n• 特性：零唤醒、零后台常驻功耗、无视任何后台限制，体验最极致。"
                        )
                        DocItem(
                            subtitle = "Shizuku / Sui 模式 (推荐 · 免 Root 特权)",
                            body = "• 原理：通过 Shizuku 授权获取系统 AIDL 跨进程 Binder 句柄，在前台服务中注册 PrimaryClipChangedListener 回调。\n• 特性：无需 Root 权限，安全纯净，稳定性极高。"
                        )
                        DocItem(
                            subtitle = "Root (Superuser) 模式",
                            body = "• 原理：通过 Root 特权在后台启动独立守护子进程，以系统特权身份常驻监听剪贴板 Binder 事件。\n• 特性：适合已 Root 但未安装 LSPosed 框架的设备。"
                        )
                        DocItem(
                            subtitle = "写入安全系统设置 (WRITE_SECURE_SETTINGS)",
                            body = "Android 10+ 对后台应用读取剪贴板施加了严格限制。若使用 Shizuku 或前台服务模式，授予此系统权限可确保 LinkGo 在处于后台时仍可合法提取剪贴板文本并弹出悬浮胶囊。"
                        )
                    }
                )
            }

            // 3. 规则编排与 URL 变量系统
            item {
                DocSection(
                    title = "规则编排：匹配引擎与 URL 变量重构",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_task_list),
                    content = {
                        DocItem(
                            subtitle = "匹配模式 (Matching Strategies)",
                            body = "• 域名/包含：只要链接中包含指定关键字（如 bilibili.com）即可触发。\n• 精确匹配：完整链接与设定文本完全一致时触发。\n• 正则表达式：支持完整 Java/Kotlin 正则语法，配合捕获组提取关键参数。"
                        )
                        DocItem(
                            subtitle = "URL 变量重构语法",
                            body = "支持将网页链接无缝转换为目标 App 的原生 Scheme/Deeplink 直达协议：\n• {url}: 原始链接完整文本\n• {host} / {path} / {query}: 提取域名、路径与查询参数\n• {1}, {2}...: 正则捕获组内容\n• 变换后缀 (编解码支持)：\n  - _url_enc / _url_dec: URL 编码 / 解码\n  - _b64_enc / _b64_dec: Base64 编码 / 解码\n  - 支持链式组合，例如：zhihu://answers/{1_url_dec}。"
                        )
                        DocItem(
                            subtitle = "短链接解析与追踪",
                            body = "针对 t.cn、b23.tv 等短链，开启后在规则匹配前自动通过 HTTP 重定向追踪最终真实 URL，确保命中精确规则。"
                        )
                        DocItem(
                            subtitle = "解析并分发 (桥接链式分发)",
                            body = "针对多层嵌套链接（如知乎中间跳转页、微信安全中转链接），LinkGo 自动提取出真实目标后再次送入规则库进行二次智能路由，实现全自动链式分发。"
                        )
                    }
                )
            }

            // 4. 自由窗口与小窗模式
            item {
                DocSection(
                    title = "自由窗口：深度适配各厂商小窗引擎",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_open_new_window),
                    content = {
                        DocItem(
                            subtitle = "厂商启动模式适配",
                            body = "• 自由窗口 1 (标准)：Mode5，深度兼容小米 HyperOS / MIUI。\n• 自由窗口 2：Mode100，适配 OPPO ColorOS 系统。\n• 自由窗口 3：Mode102，适配荣耀 MagicOS 系统。\n• 自由窗口 4 (OriginOS)：适配 vivo OriginOS 小窗分发。\n• 自由窗口 5 (FlymeOS)：Mode11，适配魅族 Flyme 系统。\n• 自由窗口 6 (MyOS)：适配中兴/努比亚 MyOS 系统。"
                        )
                        DocItem(
                            subtitle = "快捷反转窗口模式",
                            body = "在悬浮胶囊或备选选择器中长按目标图标，可临时反转窗口模式（默认小窗的应用以全屏打开，默认全屏的应用以小窗打开）。"
                        )
                    }
                )
            }

            // 5. 避坑与故障排查指南
            item {
                DocSection(
                    title = "🚧 常见问题与故障排查 (Troubleshooting)",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_flash),
                    content = {
                        TroubleShootItem(
                            title = "1. 点击链接直接全屏进入了原 App，未触发 LinkGo",
                            phenomenon = "点击 B 站或知乎等链接时，系统直接拉起对应 App 全屏，LinkGo 未被唤起。",
                            reason = "Android 系统的 App Links（默认打开）优先级高于浏览器。当目标 App 的“在支持的链接中打开”被启用时，系统会优先直通目标 App。",
                            solution = "前往：系统设置 -> 应用管理 -> 选择对应 App（如哔哩哔哩） -> 默认打开 / 打开支持的链接 -> 设置为“每次询问”或“不允许/在浏览器中打开”。"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        TroubleShootItem(
                            title = "2. 提示分发成功，但屏幕无任何界面跳转反应",
                            phenomenon = "状态栏提示分发完成，但手机屏幕无任何弹窗、小窗或应用拉起动作。",
                            reason = "国内定制系统（小米/魅族/华为/vivo等）为防止后台恶意弹窗，默认拦截了后台应用的界面弹出权限。LinkGo 作为路由中转器必须具备该权限。",
                            solution = "前往：系统设置 -> 应用管理 -> LinkGo -> 权限管理 -> 找到“后台弹出界面 / 显示在其他应用上层” -> 务必设置为“始终允许”。"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        TroubleShootItem(
                            title = "3. 微信 / QQ 等应用内置浏览器点击链接不触发 LinkGo",
                            phenomenon = "在微信或 QQ 聊天记录中点击链接直接在内置浏览器中打开，不流向 LinkGo。",
                            reason = "微信等即时通讯工具默认使用内置 X5/WebView 网页组件浏览网页，未将 Intent 发送至系统默认浏览器。",
                            solution = "在微信内置网页右上角点击“...” -> 选择“在浏览器中打开”。只要链接流向系统，设为默认浏览器的 LinkGo 即可精准接管。"
                        )
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun DocSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSection(topLabel = title) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

@Composable
fun DocItem(
    subtitle: String,
    body: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            subtitle,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TroubleShootItem(
    title: String,
    phenomenon: String,
    reason: String? = null,
    solution: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            color = MaterialTheme.colorScheme.primary
        )
        
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "【现象】",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = phenomenon,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (reason != null) {
                Text(
                    text = "【分析】",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Text(
                text = "【解决】",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = solution,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
