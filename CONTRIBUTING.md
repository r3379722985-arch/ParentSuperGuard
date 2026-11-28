# 参与贡献（Contributing Guide）

欢迎为更多国产安卓机型与长辈场景完善 **安心超级卫士**！

## 常见贡献方式

1. **新增或修正手机品牌/系统适配**：
   * 编辑 `app/src/main/java/com/custom/parentguard/BrandProfile.kt` 补充对应系统的「存储入口」「清除缓存」「强行停止」按钮文案；
   * 如该品牌系统设置页属于独立包名，请在 `app/src/main/res/xml/cache_clean_accessibility_config.xml` 的 `android:packageNames` 中补充。
2. **补充地方银行 / 医保 / 政务白名单**：
   * 编辑 `app/src/main/java/com/custom/parentguard/IroncladWhitelist.kt`，将包名加入 `STRICT_NEVER_TOUCH_PACKAGES`，并在 `IroncladWhitelistTest.kt` 添加回归测试。
3. **运行单元测试**：
   ```bash
   gradle testDebugUnitTest --no-daemon
   ```
   提交 PR 前请确保全部单元测试通过，且**绝对不要**向 `AndroidManifest.xml` 添加 `INTERNET` 权限。
