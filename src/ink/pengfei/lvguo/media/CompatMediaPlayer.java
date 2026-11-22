package ink.pengfei.lvguo.media;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaDataSource;
import android.media.PlaybackParams;
import android.net.Uri;
import android.view.SurfaceHolder;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.MediaSource;

/** Alternate extraction/decoder backend using the existing decrypted sources and controls. */
public final class CompatMediaPlayer extends MediaPlayer {
    private final ExoPlayer backend;
    private boolean ready, released, pendingSeek, frameSeen, frameNotified;
    private SurfaceHolder holder;
    private OnPreparedListener preparedListener;
    private OnCompletionListener completionListener;
    private OnErrorListener errorListener;
    private OnInfoListener infoListener;
    private OnSeekCompleteListener seekListener;
    private OnVideoSizeChangedListener sizeListener;
    private OnBufferingUpdateListener bufferListener;
    private EpisodeTransitionListener episodeTransitionListener;
    private PerformanceListener performanceListener;
    private long lagWindowStartMs, lastLagNotifyMs, lastDropLogAt, lastOffsetLogAt;
    private int lagWindowDropped, lateReports;
    private static final long LAG_WINDOW_MS=10_000L, LAG_NOTIFY_COOLDOWN_MS=30_000L, LATE_FRAME_OFFSET_US=80_000L;
    private static final int LAG_DROP_LIMIT=20;

    public interface EpisodeTransitionListener {
        void onTransition(String mediaId, int oldPositionMs, boolean completed);
    }

    /** 持续掉帧/迟到帧信号：用于把「能播但解码跟不上」与硬解码失败区分开。 */
    public interface PerformanceListener {
        void onVideoLag(int droppedFrames, long windowMs, long averageOffsetUs);
    }

    public CompatMediaPlayer(Context context) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector((mime, secure, tunneling) -> {
                    List<MediaCodecInfo> codecs = new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling));
                    if (!MimeTypes.VIDEO_H265.equals(mime)) return codecs;
                    // Prefer hardware HEVC. Only when the device has no hardware H.265
                    // decoder do we let Media3 try a system software decoder.
                    List<MediaCodecInfo> hardware = new ArrayList<>();
                    for (MediaCodecInfo codec : codecs)
                        if (codec.hardwareAccelerated && !codec.softwareOnly && !PlaybackCompatibility.isSoftware(codec.name)) hardware.add(codec);
                    return hardware.isEmpty() ? codecs : hardware;
                });
        backend = new ExoPlayer.Builder(context, renderers)
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,500,1000).build()).build();
        backend.addAnalyticsListener(new AnalyticsListener(){
            @Override public void onVideoDecoderInitialized(EventTime time,String name,long initialized,long duration){
                PlaybackDiagnostics.event("compat_decoder",name);
            }
            @Override public void onAudioPositionAdvancing(EventTime time,long playoutStartSystemTimeMs){
                PlaybackDiagnostics.event("compat_audio_clock","playoutStartSystemMs="+playoutStartSystemTimeMs+" eventRealtimeMs="+time.realtimeMs+" positionMs="+backend.getCurrentPosition());
            }
            @Override public void onAudioUnderrun(EventTime time,int bufferSize,long bufferSizeMs,long elapsedSinceLastFeedMs){
                PlaybackDiagnostics.event("compat_audio_underrun","bufferBytes="+bufferSize+" bufferMs="+bufferSizeMs+" feedGapMs="+elapsedSinceLastFeedMs+" positionMs="+backend.getCurrentPosition());
            }
            @Override public void onVideoFrameProcessingOffset(EventTime time,long totalProcessingOffsetUs,int frameCount){
                if(frameCount<=0)return;
                long averageOffsetUs=totalProcessingOffsetUs/frameCount;
                long now=android.os.SystemClock.elapsedRealtime();
                if(averageOffsetUs>=50_000L && now-lastOffsetLogAt>=10_000L){
                    lastOffsetLogAt=now;
                    PlaybackDiagnostics.event("compat_video_late_frames","averageOffsetUs="+averageOffsetUs+" frameCount="+frameCount+" positionMs="+backend.getCurrentPosition());
                }
                if(!readyForLagMetrics()){lateReports=0;return;}
                if(averageOffsetUs>=LATE_FRAME_OFFSET_US){
                    if(++lateReports>=3)lagSignal(0,0,averageOffsetUs);
                }else lateReports=0;
            }
            @Override public void onDroppedVideoFrames(EventTime time,int droppedFrames,long elapsedMs){
                if(droppedFrames<=0)return;
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastDropLogAt>=5_000L){
                    lastDropLogAt=now;
                    PlaybackDiagnostics.event("compat_dropped_frames","count="+droppedFrames+" elapsedMs="+elapsedMs+" positionMs="+backend.getCurrentPosition());
                }
                if(droppedFrames>=12 && elapsedMs<=1500)info(MEDIA_INFO_VIDEO_TRACK_LAGGING);
                if(!readyForLagMetrics()){resetLagWindow();return;}
                if(lagWindowStartMs==0 || now-lagWindowStartMs>LAG_WINDOW_MS){lagWindowStartMs=now;lagWindowDropped=0;}
                lagWindowDropped+=droppedFrames;
                if(lagWindowDropped>=LAG_DROP_LIMIT)lagSignal(lagWindowDropped,now-lagWindowStartMs,0);
            }
        });
        backend.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(released)return;
                if(state!=Player.STATE_READY)resetLagWindow();
                android.util.Log.i("TVPlayback","Compatibility state="+state+" buffered="+backend.getBufferedPosition());
                if(state==Player.STATE_READY){
                    if(!ready){ready=true;if(preparedListener!=null)preparedListener.onPrepared(CompatMediaPlayer.this);}
                    else info(MEDIA_INFO_BUFFERING_END);
                    notifyFrame();
                    if(pendingSeek)seekFinished();
                    if(bufferListener!=null)bufferListener.onBufferingUpdate(CompatMediaPlayer.this,backend.getBufferedPercentage());
                }else if(state==Player.STATE_BUFFERING && ready)info(MEDIA_INFO_BUFFERING_START);
                else if(state==Player.STATE_ENDED && completionListener!=null)completionListener.onCompletion(CompatMediaPlayer.this);
            }
            @Override public void onRenderedFirstFrame(){
                if(!frameSeen){
                    frameSeen=true;
                    PlaybackDiagnostics.event("compat_video_first_frame","elapsedRealtimeMs="+android.os.SystemClock.elapsedRealtime()+" positionMs="+backend.getCurrentPosition());
                }
                android.util.Log.i("TVPlayback","Compatibility rendered frame ready="+ready);notifyFrame();
            }
            @Override public void onVideoSizeChanged(VideoSize size){if(sizeListener!=null)sizeListener.onVideoSizeChanged(CompatMediaPlayer.this,size.width,size.height);}
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){
                if(reason==Player.DISCONTINUITY_REASON_SEEK)resetLagWindow();
                if(reason==Player.DISCONTINUITY_REASON_SEEK && backend.getPlaybackState()==Player.STATE_READY)seekFinished();
                if (backend.getCurrentMediaItemIndex() > 0
                        && oldPosition.mediaItemIndex != newPosition.mediaItemIndex
                        && (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        || reason == Player.DISCONTINUITY_REASON_SEEK)) {
                    frameSeen=false;frameNotified=false;
                    EpisodeTransitionListener listener=episodeTransitionListener;
                    if(listener!=null && newPosition.mediaItem!=null)
                        listener.onTransition(newPosition.mediaItem.mediaId, (int)Math.max(0,oldPosition.positionMs),
                                reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
                }
            }
            @Override public void onMediaItemTransition(MediaItem item,int reason){
                resetLagWindow();
                if(item!=null && backend.getCurrentMediaItemIndex()>0){frameSeen=false;frameNotified=false;}
            }
            @Override public void onPlayerError(PlaybackException error){
                if(released)return;
                PlaybackDiagnostics.event("compat_error",error.getErrorCodeName()+": "+error.getMessage());
                if(errorListener!=null)errorListener.onError(CompatMediaPlayer.this,error.errorCode>=4000&&error.errorCode<5000?MEDIA_ERROR_UNSUPPORTED:MEDIA_ERROR_IO,error.errorCode);
            }
        });
    }
    private boolean readyForLagMetrics(){
        return !released && frameSeen && backend.getPlaybackState()==Player.STATE_READY && backend.isPlaying();
    }
    private void resetLagWindow(){lagWindowStartMs=0;lagWindowDropped=0;lateReports=0;}
    private void lagSignal(int droppedFrames,long windowMs,long averageOffsetUs){
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastLagNotifyMs<LAG_NOTIFY_COOLDOWN_MS)return;
        lastLagNotifyMs=now;resetLagWindow();
        PerformanceListener listener=performanceListener;
        if(listener!=null)listener.onVideoLag(droppedFrames,windowMs,averageOffsetUs);
    }
    private void info(int what){if(!released && infoListener!=null)infoListener.onInfo(this,what,0);}
    private void notifyFrame(){if(ready && frameSeen && !frameNotified){frameNotified=true;info(MEDIA_INFO_VIDEO_RENDERING_START);}}
    private void seekFinished(){if(!pendingSeek)return;pendingSeek=false;if(seekListener!=null)seekListener.onSeekComplete(this);}
    @Override public void setOnPreparedListener(OnPreparedListener listener){preparedListener=listener;}
    @Override public void setOnCompletionListener(OnCompletionListener listener){completionListener=listener;}
    @Override public void setOnErrorListener(OnErrorListener listener){errorListener=listener;}
    @Override public void setOnInfoListener(OnInfoListener listener){infoListener=listener;}
    @Override public void setOnSeekCompleteListener(OnSeekCompleteListener listener){seekListener=listener;}
    @Override public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener){sizeListener=listener;}
    @Override public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener){bufferListener=listener;}
    @Override public void setDataSource(String path){setDataSource(path,path);}
    public void setDataSource(String path,String mediaId){
        MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(new File(path))).setMediaId(mediaId).build();
        backend.setMediaItem(item);
    }
    @Override public void setDataSource(MediaDataSource source){setDataSource(source,"current");}
    public void setDataSource(MediaDataSource source,String mediaId){backend.setMediaSource(sourceFor(source,mediaId));}
    private MediaSource sourceFor(MediaDataSource source,String mediaId){
        DataSource.Factory factory=()->new DataSource(){
            private long position;private Uri uri;
            @Override public void addTransferListener(TransferListener listener){}
            @Override public long open(DataSpec spec)throws IOException{position=spec.position;uri=spec.uri;return source.getSize()-position;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException{
                if(length==0)return 0;int read=source.readAt(position,bytes,offset,length);if(read>0)position+=read;return read;
            }
            @Override public Uri getUri(){return uri;}
            @Override public void close(){}
        };
        MediaItem item=new MediaItem.Builder().setMediaId(mediaId)
                .setUri(Uri.parse("lvguo://decrypted/"+Uri.encode(mediaId)))
                .setMimeType(MimeTypes.VIDEO_MP4).build();
        return new ProgressiveMediaSource.Factory(factory).createMediaSource(item);
    }
    /** 保活换集：用已暖好的流替换当前媒体源，不释放播放器、不动 surface。
     *  旧画面的最后一帧会一直留在屏幕上，直到新一集首帧渲染出来 → 无缝、不黑屏；
     *  起播位置仍由调用方在 onPrepared 里 seek（下一集传 0 即从头开始，不会重放上一集）。 */
    public void replaceSource(MediaDataSource source,String mediaId){
        if(released)return;
        ready=false;pendingSeek=false;frameSeen=false;frameNotified=false;resetLagWindow();
        backend.setVolume(0f);
        backend.setMediaSource(sourceFor(source,mediaId));
        backend.prepare();
    }
    public void replaceSource(String path,String mediaId){
        if(released)return;
        ready=false;pendingSeek=false;frameSeen=false;frameNotified=false;resetLagWindow();
        backend.setVolume(0f);
        MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(new File(path))).setMediaId(mediaId).build();
        backend.setMediaItem(item);
        backend.prepare();
    }
    public String currentMediaId(){MediaItem it=backend.getCurrentMediaItem();return it==null?null:it.mediaId;}
    public boolean isReleased(){return released;}

    public void setEpisodeTransitionListener(EpisodeTransitionListener listener){episodeTransitionListener=listener;}
    public void setPerformanceListener(PerformanceListener listener){performanceListener=listener;}
    public boolean appendNextDataSource(MediaDataSource source,String mediaId){
        if(released || backend.getMediaItemCount()!=1)return false;
        backend.addMediaSource(sourceFor(source,mediaId));return true;
    }
    public boolean appendNextFile(File file,String mediaId){
        if(released || backend.getMediaItemCount()!=1 || file==null || !file.isFile())return false;
        MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(mediaId).build();
        backend.addMediaItem(item);return true;
    }
    public boolean hasQueuedEpisode(){return !released && backend.getMediaItemCount()>backend.getCurrentMediaItemIndex()+1;}
    public void advanceToNextEpisode(){if(hasQueuedEpisode())backend.seekToNextMediaItem();}
    public void removeQueuedEpisodes(){
        if(released)return;int first=backend.getCurrentMediaItemIndex()+1;
        if(first<backend.getMediaItemCount())backend.removeMediaItems(first,backend.getMediaItemCount());
    }
    public void removePlayedEpisodes(){
        if(released)return;int current=backend.getCurrentMediaItemIndex();
        if(current>0)backend.removeMediaItems(0,current);
    }
    @Override public void setDisplay(SurfaceHolder surface){holder=surface;backend.setVideoSurfaceHolder(surface);}
    @Override public void setScreenOnWhilePlaying(boolean enabled){if(holder!=null)holder.setKeepScreenOn(enabled);}
    @Override public void prepareAsync(){resetLagWindow();backend.setVolume(0f);backend.prepare();backend.play();}
    @Override public void start(){backend.play();}
    @Override public void pause(){backend.pause();}
    @Override public void stop(){backend.stop();}
    @Override public boolean isPlaying(){return backend!=null && !released && backend.getPlayWhenReady() && backend.getPlaybackState()!=Player.STATE_ENDED;}
    @Override public int getDuration(){if(backend==null)return 0;long duration=backend.getDuration();return duration==C.TIME_UNSET?0:(int)Math.min(Integer.MAX_VALUE,duration);}
    // MediaPlayer's constructor calls getCurrentPosition before this subclass is initialized.
    @Override public int getCurrentPosition(){return backend==null?0:(int)Math.min(Integer.MAX_VALUE,backend.getCurrentPosition());}
    @Override public int getVideoWidth(){return backend==null?0:backend.getVideoSize().width;}
    @Override public int getVideoHeight(){return backend==null?0:backend.getVideoSize().height;}
    @Override public void seekTo(int milliseconds){seekTo(milliseconds,SEEK_CLOSEST);}
    @Override public void seekTo(long milliseconds,int mode){
        pendingSeek=true;
        backend.setSeekParameters(mode==SEEK_PREVIOUS_SYNC?SeekParameters.PREVIOUS_SYNC:mode==SEEK_NEXT_SYNC?SeekParameters.NEXT_SYNC:mode==SEEK_CLOSEST_SYNC?SeekParameters.CLOSEST_SYNC:SeekParameters.EXACT);
        backend.seekTo(Math.max(0,milliseconds));
    }
    @Override public void setVolume(float left,float right){backend.setVolume((left+right)/2f);}
    @Override public void setPlaybackParams(PlaybackParams params){backend.setPlaybackParameters(new PlaybackParameters(params.getSpeed(),params.getPitch()));}
    @Override public PlaybackParams getPlaybackParams(){PlaybackParameters p=backend.getPlaybackParameters();return new PlaybackParams().setSpeed(p.speed).setPitch(p.pitch);}
    @Override public void reset(){if(!released){backend.stop();backend.clearMediaItems();ready=false;pendingSeek=false;frameSeen=false;frameNotified=false;}}
    @Override public void release(){if(released)return;released=true;if(holder!=null)holder.setKeepScreenOn(false);backend.release();super.release();}
}
