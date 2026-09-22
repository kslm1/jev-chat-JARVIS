# Jev 助手 Direct

这个分支把原版的 OpenRouter 聚合调用改成两个官方 API 直连：

- TypeSafe Jev：`https://api.typesafe.ai/v1/systemone`，模型 `jev-latest`
- DeepSeek：`https://api.deepseek.com/chat/completions`，默认模型 `deepseek-flash`

## 和原版的区别

- 不需要 OpenRouter Key。
- 设置页分别填写 **TypeSafe API Key** 和 **DeepSeek API Key**。
- TypeSafe 负责意图/风险/动作判断以及 3 条候选回复排序。
- DeepSeek 负责生成 3 条中文候选回复。
- 包名改为 `com.jev.probe.direct`，App 名为 **Jev助手 Direct**，可和原版并存安装。
- Key 只保存在 App 私有 SharedPreferences；不要把真实 Key 提交到 GitHub。

## Pixel 测试顺序

1. 安装 Direct APK。
2. 在系统里允许“受限制的设置”，再开启无障碍。
3. 开启“显示在其他应用上层”。
4. 应用信息 → 电池 → 允许后台使用 / 不受限制。
5. Jev助手 Direct → 设置：
   - 填 TypeSafe API Key
   - 填 DeepSeek API Key
   - DeepSeek 模型保持 `deepseek-flash`
6. 先点“分别测试 TypeSafe + DeepSeek”。
7. 两项都成功后，再点“完整链路测试（含 Jev 排序）”。
8. 再进入微信/QQ/X 做真实聊天测试。

## 构建

GitHub Actions 工作流：`.github/workflows/build-direct-apk.yml`

成功后下载 artifact：`JevAssistant-Direct-debug`。

本分支先提供 debug APK 用于测试；确认功能稳定后再处理长期 release 签名。
