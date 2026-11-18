package ink.pengfei.lvguo.net;

/** Validates history cursors before a partial cloud list can replace local data. */
final class HistoryPaging {
    private HistoryPaging() { }

    static int next(boolean hasMore, int offset, int nextOffset) {
        if (!hasMore) return -1;
        if (nextOffset <= offset) throw new IllegalArgumentException("History cursor did not advance");
        return nextOffset;
    }
}
