package com.hgtv.app.media;

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

    public CompatMediaPlayer(Context context) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector((mime, secure, tunneling) -> {
                    List<MediaCodecInfo> codecs = new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling));
                    // Keep hardware decoding first for sustained TV playback. If hardware
                    // initialization fails, Media3 can still fall back to a software decoder.
                    if (MimeTypes.VIDEO_H265.equals(mime)) codecs.sort((a,b) -> Boolean.compare(a.softwareOnly, b.softwareOnly));
                    return codecs;
                });
        backend = new ExoPlayer.Builder(context, renderers)
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,500,1000).build()).build();
        backend.addAnalyticsListener(new AnalyticsListener(){
            @Override public void onVideoDecoderInitialized(EventTime time,String name,long initialized,long duration){
                PlaybackDiagnostics.event("compat_decoder",name);
            }
            @Override public void onDroppedVideoFrames(EventTime time,int droppedFrames,long elapsedMs){
                if(droppedFrames<=0)return;
                PlaybackDiagnostics.event("compat_dropped_frames","count="+droppedFrames+" elapsedMs="+elapsedMs+" positionMs="+backend.getCurrentPosition());
                if(droppedFrames>=12 && elapsedMs<=1500)info(MEDIA_INFO_VIDEO_TRACK_LAGGING);
            }
        });
        backend.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(released)return;
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
            @Override public void onRenderedFirstFrame(){frameSeen=true;android.util.Log.i("TVPlayback","Compatibility rendered frame ready="+ready);notifyFrame();}
            @Override public void onVideoSizeChanged(VideoSize size){if(sizeListener!=null)sizeListener.onVideoSizeChanged(CompatMediaPlayer.this,size.width,size.height);}
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){
                if(reason==Player.DISCONTINUITY_REASON_SEEK && backend.getPlaybackState()==Player.STATE_READY)seekFinished();
            }
            @Override public void onPlayerError(PlaybackException error){
                if(released)return;
                PlaybackDiagnostics.event("compat_error",error.getErrorCodeName()+": "+error.getMessage());
                if(errorListener!=null)errorListener.onError(CompatMediaPlayer.this,error.errorCode>=4000&&error.errorCode<5000?MEDIA_ERROR_UNSUPPORTED:MEDIA_ERROR_IO,error.errorCode);
            }
        });
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
    @Override public void setDataSource(String path){backend.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));}
    @Override public void setDataSource(MediaDataSource source){
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
        backend.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("hgtv://decrypted/clip")));
    }
    @Override public void setDisplay(SurfaceHolder surface){holder=surface;backend.setVideoSurfaceHolder(surface);}
    @Override public void setScreenOnWhilePlaying(boolean enabled){if(holder!=null)holder.setKeepScreenOn(enabled);}
    @Override public void prepareAsync(){backend.setVolume(0f);backend.prepare();backend.play();}
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
