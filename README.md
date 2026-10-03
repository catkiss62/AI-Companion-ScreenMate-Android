# ScreenMate Android

小米 Pad 6 Pro / HyperOS 平板上的独立陪看实验。0.1.2 新增连续视频实验；保留截图与《无期迷途》本地OCR作为对照，实际模型理解和小米兼容性待真机验证。

- Android 原生 Kotlin；最低 Android 8，编译/目标 API 35。
- 系统授权屏幕共享 + 前台服务；每次开始重新授权；支持单应用共享和尺寸变化。
- 本地中文 OCR 连续记录对白 + 官方 Gemini 低频补充画面 → 独立中转 Gemini 回复；DeepSeek 分段整理与回复失败兜底。
- 自由拖动、横竖屏分别记住位置、自动朝内的伙伴浮窗；点击聊天，默认收起；新回复只弹跳一次。
- 浮窗暂停 / 继续，App 内结束；取消旧请求；用户发言优先；画面无变化不反复评论。
- 会话文字立即写入 SQLite；分段摘要、后台归档重试、进程中断恢复、单会话删除与文字导出。
- 不接 TTS、不使用麦克风、不接手机记忆同步。可选应用播放音频采集，来源禁止时可能静音。视频临时写入私有缓存用于处理/回放；截图不落盘。API Key 使用 Android Keystore 加密，不随备份导出。

## 配置和使用

安装 CI 的未发布测试 APK，进入“接口与陪伴设置”。官方 Gemini 需独立 Key，先读取模型列表，再选择账户实际可用且支持图片输入的 Flash 模型。列表表示 API 可用，不保证免费；额度以 AI Studio 项目页面为准。第二通道使用完整 OpenAI 兼容 `/chat/completions` 地址、Key 和中转站模型名；DeepSeek 同理。

连续视频模式：单次 MediaProjection 接到持续 H.264 编码，约10fps、最长边1280、1.8Mbps；在约5秒后的关键帧封装独立MP4，不每5秒重启录屏。短片以 inlineData video/mp4 发送官方Gemini；原文证据、DeepSeek连续剧情、第二通道回复分别保存。三条有序处理通道可以重叠，回复慢不阻止下一段识别/整理；总缓冲约6段，跟不上时暂停新增采集并记录空缺。单片失败保留阶段，在诊断页手动重试；429遵守退避。没有承诺免费额度或5秒内完成云端回复。

首页/设置可开启“每段回复一句”，忽略普通主动冷却，按片段专属前情回答，不把已经整理的后面片段混入前面评论。用户消息优先，暂未说出的片段评论等用户回复后继续。关闭开关恢复普通主动频率。诊断可回放最近采集/实际发送的片段，查看音轨峰值、用量、积压、空缺和耗时。音频默认关闭，可在设置启用并授权；只采集其他App的MEDIA/GAME播放音频，不使用麦克风或自身声音。进入App会停止新增录制；暂停/旋转/结束丢弃不完整片段并记录空缺；最新两个诊断片段留到手动清除、下次会话或进程重启。暂停会取消尚未完成的任务，不把迟到结果记为新观察。

剧情模式随包内置中文 OCR，无需另配 Key 或下载模型。约每 750 ms 尝试处理最新帧，等待文字稳定后记录；单任务处理无积压，实际速度受设备影响。默认识别高度 55%–98% 的对白区域，设置可调整至全屏（用于居中文字）。打字延长标记同页补充，错字和快速跳页仍可能遗漏。云端识图默认最短间隔 15 秒（从请求起点算），不阻塞对白记录。主动评论最短间隔 90 秒，有新证据时调用第二通道决定是否说话；允许评论刚才几句，推进超过 3 段或证据超过 60 秒才丢弃主动评论。每日本机识图上限默认 200 次，可调整，按太平洋日期计数。这是本机预算控制，不是 Google 免费额度声明。429 和网络失败会退避，没有自动密集重试。

开始时允许悬浮窗并选择分享目标应用，随后切回视频或游戏。建议单应用共享；分享整个屏幕会包含其他应用，务必自行避开私密画面。浮窗与配置页面设置了 FLAG_SECURE，在全屏共享中可能出现小块黑色遮罩；伙伴以外的区域不拦截点击。进入设置自动暂停，手动继续后等待新画面。回 App 点“结束本次陪看”释放共享资源并排队归档；浮窗没有结束键。无网络时记录保留，恢复网络后自动重试。

HyperOS 可在应用设置允许后台运行，按需将省电策略改为无限制。系统回收后不自动恢复屏幕共享，需要再次开始。受 DRM / FLAG_SECURE 保护的视频可能黑屏，本项目不绕过系统保护。截图/OCR模式没有音频输入，无法理解无字幕对白；视频音轨需来源允许；快速跳过的剧情可能遗漏。黑屏首先到“视频 / 识屏诊断与实际回放”查看实际采集图和实际发送图、时间、原始 OCR 与耗时；暗色比例不代表 DRM 判定。截图只在内存保留，进入 App 时不采集新的画面。

## 伙伴图片

当前是原创代码绘制的小鲸鱼占位。支持本机导入 12 MB 以内 PNG/JPG 等系统支持图片，自动限制尺寸。参考 [MeteorNOX/DeepSeek-Balance-Whale-Widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget) 的朝向与弹跳思路，但没有复制人物图片；该项目人物资产的许可不同于代码 MIT。用户可以在本机自行选择获准使用的图片，不上传本仓库。

## 构建与验证

JDK 17、Android SDK 35、Gradle 8.11.1：

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

CI 在 `agent/**` 分支推送后执行单元测试、lint、Android 35 平板模拟器测试，全部通过才上传 APK artifact 并创建 Draft。测试签名保存在独立 Draft 资产中，后续 APK 可覆盖安装。不会合并 main，也不会发布正式版本。不要发布签名资产 Draft。

本地未配置 Android SDK 时以 CI 为构建证据。当前状态及待验证项见 [项目总账](SCREENMATE_当前总账.md)。

## 实现参考

- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [Gemini 项目限额](https://aistudio.google.com/rate-limit)
- [参考项目素材来源声明](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget/blob/main/PROVENANCE.md)

- [overlay-translator](https://github.com/ciddwd/overlay-translator)：本地 OCR、稳定采样与采集线程
- [EverTranslator](https://github.com/firemaples/EverTranslator)：采集诊断与超时处理
- [EasyFloat](https://github.com/princekin-f/EasyFloat)：自由浮窗与拖动行为
- [ML Kit 中文识字](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)：随包模型与输入图像生命周期
