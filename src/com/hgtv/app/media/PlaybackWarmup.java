package com.hgtv.app.media;

import com.hgtv.app.net.*;
import com.hgtv.app.App;
import java.util.concurrent.*;

/** Debounced metadata warmup; never downloads an entire episode on each focus change. */
public final class PlaybackWarmup {
    private static final ScheduledExecutorService EXEC=Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> pending;
    private static volatile String last;
    private static volatile int generation;
    public static synchronized void cancel(){generation++;if(pending!=null)pending.cancel(false);pending=null;}
    public static synchronized void schedule(String id) {
        if(id==null || id.equals(last))return;
        if(pending!=null)pending.cancel(false);
        final int token=++generation;
        pending=EXEC.schedule(()->{
            try {
                Repo.Detail detail=Repo.detail(id);
                if(token!=generation)return;
                if(!detail.episodes.isEmpty())Repo.play(detail.episodes.get(0).vid,HgApi.DEVICE_LEVEL_H265);
                last=id;
            }catch(Exception ignored){}
        },600,TimeUnit.MILLISECONDS);
    }
}
