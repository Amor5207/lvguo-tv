package ink.pengfei.lvguo.model;

/** 单集 */
public final class Episode {
    public int index;
    public String title;
    public String vid;
    public int durationSec;
    public String cover;
    /** RedFruit SaaS playback context. -1 means the album response omitted the field. */
    public int videoPlatform = -1;
    public int videoSeriesIdType = -1;
    public int contentType = -1;
}
