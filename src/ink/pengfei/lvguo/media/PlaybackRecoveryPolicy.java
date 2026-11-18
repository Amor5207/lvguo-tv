package ink.pengfei.lvguo.media;

/** Retry budgets and quality fallback used before showing a terminal playback error. */
public final class PlaybackRecoveryPolicy {
    private PlaybackRecoveryPolicy() {}

    public static final int NETWORK_RETRY_LIMIT = 6;
    public static final int SOURCE_RETRY_LIMIT = 2;
    private static final long[] RETRY_DELAYS_MS = {750L, 1500L, 3000L, 6000L, 12000L, 20000L};

    /** attempt is one-based and counts retries already scheduled after the first failure. */
    public static long retryDelayMs(int attempt) {
        int index = Math.max(0, Math.min(RETRY_DELAYS_MS.length - 1, attempt - 1));
        return RETRY_DELAYS_MS[index];
    }

    /** Step down through available common renditions without skipping the current episode. */
    public static int nextLowerQuality(int currentShortSide) {
        if (currentShortSide > 720) return 720;
        if (currentShortSide > 540) return 540;
        if (currentShortSide > 480) return 480;
        if (currentShortSide > 360) return 360;
        return 0;
    }
}
