package ink.pengfei.lvguo.update;

/**
 * 检查更新的唯一配置点。接入其他应用时只改这里（服务地址 / 渠道），
 * 应用 ID 取自运行时包名，版本号取自安装包自身，无需手填。
 */
final class UpdateConfig {
    private UpdateConfig() {}

    /** 更新登记服务（走边缘加速）。 */
    static volatile String BASE = "https://update.pengfei.ink";   // 非 final 仅为测试可替换
    /** 渠道：stable = 正式版。 */
    static final String CHANNEL = "stable";
    /** 自动检查的最短间隔。 */
    static final long CHECK_INTERVAL_MS = 6 * 3600_000L;
    /** 「稍后」之后多久内不再自动弹窗。 */
    static final long SNOOZE_MS = 24 * 3600_000L;
    static final int CONNECT_TIMEOUT_MS = 6_000, READ_TIMEOUT_MS = 10_000;
}
