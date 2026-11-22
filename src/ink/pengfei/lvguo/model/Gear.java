package ink.pengfei.lvguo.model;

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
    public final java.util.List<String> backupUrls = new java.util.ArrayList<>();
    /** Absolute Unix expiry from SaaSVideoModelData.expire_time, in milliseconds; 0 means omitted. */
    public long expiresAtMillis;
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

    public boolean isH264() {
        String c = codec == null ? "" : codec.toLowerCase(java.util.Locale.US).trim();
        return c.equals("h264") || c.equals("avc") || c.equals("avc1") || c.contains("h.264");
    }

    public boolean isH265() {
        String c = codec == null ? "" : codec.toLowerCase(java.util.Locale.US).trim();
        return c.equals("h265") || c.equals("hevc") || c.contains("h.265");
    }

    /** ByteVC1/ByteVC2/VVC 需要厂商专有解码器，普通电视硬件 HEVC 解码器放不了。 */
    public boolean isProprietary() {
        String c = codec == null ? "" : codec.toLowerCase(java.util.Locale.US);
        return c.contains("bytevc") || c.contains("bvc") || c.contains("vvc");
    }

    /** 数字越小越优先：H.264 最稳，H.265 次之，未知再次，ByteVC 最后。 */
    public int codecRank() {
        if (isH264()) return 0;
        if (isH265()) return 1;
        if (isProprietary()) return 3;
        return 2;
    }
}
