package com.hgtv.app.model;

/** 清晰度档位 */
public final class Gear {
    public String definition;
    public String codec;
    public float fps;
    public int width;
    public int height;
    public long size;
    public String mainUrl;
    public String backupUrl;
    public boolean encrypted;
    public String spadeA;
    public String kid;

    /** 短剧是竖屏，1280x720 的 720p 短边才是 720，取短边做清晰度度量 */
    public int shortSide() {
        int side = Math.min(width, height);
        if (side > 0) return side;
        if (definition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(definition);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
