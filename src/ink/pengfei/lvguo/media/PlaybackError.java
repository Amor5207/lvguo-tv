package ink.pengfei.lvguo.media;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * 把各种底层异常归成几类用户能理解的问题，并给出对应的文案。
 *
 * 归类决定后台恢复策略：网络问题重试/等网络恢复，不降解码；
 * 解码问题沿解码阶梯往下走；片源问题刷新模型和匿名身份后重取。
 */
public final class PlaybackError {
    public enum Kind { OFFLINE, NETWORK, DECODE, UNAVAILABLE, LICENSE, SERIES }

    /** A player/codec callback identified a decoder failure explicitly. */
    public static final class DecoderFailure extends java.io.IOException {
        public DecoderFailure(String message) { super(message); }
    }

    public final Kind kind;
    public final String code;
    public final String detail;

    private PlaybackError(Kind kind, String code, String detail) { this.kind = kind; this.code = code; this.detail = detail; }

    public static PlaybackError of(Kind kind, Throwable cause) {
        return new PlaybackError(kind, codeFor(kind), cause == null ? "" : String.valueOf(cause.getMessage()));
    }

    /**
     * @param networkSuspect 播放器侧的旁证：最近数据流读失败过，或几乎没收到数据。
     */
    public static PlaybackError classify(Context context, Throwable cause, boolean networkSuspect) {
        if (!online(context)) return of(Kind.OFFLINE, cause);
        Kind kind = null;
        for (Throwable t = cause; t != null && kind == null; t = t.getCause()) {
            if (t instanceof DecoderFailure) { kind = Kind.DECODE; break; }
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketException || t instanceof javax.net.ssl.SSLException
                    || t instanceof EOFException
                    || (t instanceof InterruptedIOException && !(t.getMessage() != null && t.getMessage().contains("取消")))) {
                kind = Kind.NETWORK; break;
            }
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("HTTP 404") || m.contains("HTTP 410")
                    || m.contains("没有可用播放源") || m.contains("播放节点为空") || m.contains("没有可播放的剧集")) kind = Kind.UNAVAILABLE;
            else if (m.contains("密钥") || m.contains("解密")) kind = Kind.LICENSE;
            else if (m.contains("解码器") || m.contains("解码失败") || m.contains("解码停滞")
                    || m.toLowerCase(java.util.Locale.US).contains("decoder")) kind = Kind.DECODE;
            else if (m.contains("HTTP") || m.contains("接口无响应") || m.contains("取播放信息失败") || m.contains("读取失败")
                    || m.contains("分段") || m.contains("下载") || m.contains("加载播放源失败")) kind = Kind.NETWORK;
        }
        if (kind == null) kind = networkSuspect ? Kind.NETWORK : Kind.DECODE;
        return of(kind, cause);
    }

    public static boolean online(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            return true;   // 拿不到状态就当在线，让真实请求说话
        }
    }

    public boolean retryWithSameDecoder() { return kind == Kind.NETWORK || kind == Kind.OFFLINE || kind == Kind.LICENSE; }

    public String title() {
        switch (kind) {
            case OFFLINE: return "网络已断开";
            case NETWORK: return "网络不太稳定";
            case DECODE: return "这台设备暂时播放不了本集";
            case UNAVAILABLE: return "本集暂时无法播放";
            case LICENSE: return "播放授权没有通过";
            default: return "剧集加载失败";
        }
    }

    public String message() {
        switch (kind) {
            case OFFLINE: return "网络已断开，恢复后自动继续播放。";
            case NETWORK: return "视频无法连接，请稍后重试。";
            case DECODE: return "多种解码方式与清晰度均无法播放本集。";
            case UNAVAILABLE: return "暂无可用片源。";
            case LICENSE: return "播放授权未通过。";
            default: return "剧集信息加载失败，请检查网络。";
        }
    }

    private static String codeFor(Kind kind) {
        switch (kind) {
            case OFFLINE: return "NET-00";
            case NETWORK: return "NET-01";
            case DECODE: return "DEC-01";
            case UNAVAILABLE: return "SRC-01";
            case LICENSE: return "DRM-01";
            default: return "API-01";
        }
    }
}
