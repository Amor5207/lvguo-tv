package ink.pengfei.lvguo.store;

/** Resolves a local pending play against the same series in cloud history. */
final class HistoryConflict {
    private HistoryConflict() { }

    static boolean keepPending(boolean pending, long localTime, long cloudTime) {
        return pending && localTime >= cloudTime;
    }

    static boolean deletedAfterLocal(long localTime, long deletedTime) {
        return deletedTime > 0 && deletedTime >= localTime;
    }

    static boolean queryDeletion(boolean pending, boolean seenInCloud) {
        return pending && seenInCloud;
    }

    static boolean confirmsProgress(long submittedAt, int episode, int position, String vid,
                                    long cloudTime, int cloudEpisode, int cloudPosition, String cloudVid) {
        return submittedAt > 0 && cloudTime >= submittedAt - 1000L && episode == cloudEpisode
                && (cloudVid == null || cloudVid.isEmpty() || cloudVid.equals(vid))
                && Math.abs((long) cloudPosition - position) <= 5000L;
    }
}
