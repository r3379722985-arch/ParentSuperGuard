# 隐私与零联网承诺（Privacy Pledge）

**安心超级卫士（ParentSuperGuard）** 面向长辈与家庭设备设计，严格遵守以下透明隐私底线：

1. **物理级零联网**：`AndroidManifest.xml` 中**不申请 `android.permission.INTERNET` 权限**。系统层面彻底阻断任何数据上传、广告拉取、远程统计或静默更新。
2. **绝不读取聊天与隐私内容**：
   * `CacheCleanAccessibilityService` 在 XML 中显式限定 `android:packageNames` 仅监听各品牌系统「设置 / 安全中心」包名，绝不接收微信、QQ、短信、银行等任何个人应用的窗口事件。
   * 默认开启「清完自动关闭无障碍（`disableSelf()`）」，清理结束瞬间自动退出无障碍服务，彻底规避银行 App / 云闪付风控告警。
3. **纯本地存储**：个人保护名单、清理历史、防坑体检进度、可选 4 位管理锁（本地随机盐 PBKDF2-HMAC-SHA256 10 万次迭代存储）均仅保存在手机本地私有 `SharedPreferences` 中，卸载即彻底清除。
