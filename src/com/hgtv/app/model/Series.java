package com.hgtv.app.model;

/** 剧（系列） */
public final class Series {
    public String seriesId;
    public String title;
    public int episodeCount;
    public long playCount;
    public String cover;
    public String intro;
    public int rank;
    /** 是否展示榜单名次角标 */
    public boolean showRank;
    /** 题材/标签（详情页或榜单里带出，可为空） */
    public String category;
    public String actors;
    /** 第 1 集的 vid（榜单直接带出）——详情接口不可用时的播放兜底 */
    public String firstVid;
    /** 全剧总时长（秒，榜单带出） */
    public int durationSec;

    public String playText() {
        if (playCount <= 0) return "";
        if (playCount >= 100000000L) {
            return String.format(java.util.Locale.US, "%.1f亿次播放", playCount / 100000000.0);
        }
        if (playCount >= 10000L) {
            return String.format(java.util.Locale.US, "%.1f万次播放", playCount / 10000.0);
        }
        return playCount + "次播放";
    }

    public String epText() {
        return episodeCount > 0 ? ("全" + episodeCount + "集") : "";
    }
}
