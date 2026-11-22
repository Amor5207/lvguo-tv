package ink.pengfei.lvguo.net.hgsign;

import ink.pengfei.lvguo.net.lvguosign.LvguoPlayback;

/** 已签名热更新模块（module.dex ABI v1）的最小兼容桥接，业务代码已全部迁移至 lvguosign.LvguoPlayback。 */
public final class HgPlayback {
    private HgPlayback() {}
    public static byte[] moduleSm3(byte[] data) { return LvguoPlayback.moduleSm3(data); }
}
