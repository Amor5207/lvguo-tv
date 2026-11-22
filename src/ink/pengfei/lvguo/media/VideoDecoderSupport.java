package ink.pengfei.lvguo.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import ink.pengfei.lvguo.model.Gear;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按本机实际解码器筛选服务端档位：H.264 优先，其次硬件 H.265，最后尝试系统软解。 */
public final class VideoDecoderSupport {

    private static final String TAG = "VideoDecoderSupport";
    private static final String MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static volatile MediaCodecInfo[] cachedCodecs;

    private VideoDecoderSupport() {}

    public static List<Gear> compatible(List<Gear> gears) {
        List<Gear> out = new ArrayList<>();
        if (gears == null || gears.isEmpty()) return out;
        List<Gear> supported = new ArrayList<>();
        List<Gear> h264 = new ArrayList<>();
        for (Gear gear : gears) {
            if (!supportedCodec(gear)) continue;
            supported.add(gear);
            if (gear.isH264()) h264.add(gear);
        }
        if (supported.isEmpty()) return out;
        // H.264 是电视兼容性最好的码流；只要上游下发就直接交给上层选档。
        if (!h264.isEmpty()) return h264;

        MediaCodecInfo[] codecs;
        try {
            codecs = cachedCodecs;
            if (codecs == null) {
                codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
                cachedCodecs = codecs;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Could not inspect decoders; trying the smallest quality", e);
            return smallestQuality(supported);
        }

        List<MediaCodecInfo> hardware = new ArrayList<>();
        boolean software = false;
        for (MediaCodecInfo codec : codecs) {
            if (codec.isEncoder()) continue;
            boolean supportsHevc = false;
            for (String type : codec.getSupportedTypes()) {
                if (MIME_HEVC.equalsIgnoreCase(type)) { supportsHevc = true; break; }
            }
            if (!supportsHevc) continue;
            if (PlaybackCompatibility.isHardware(codec)) hardware.add(codec);
            else software = true;
        }

        if (hardware.isEmpty()) {
            if (software) {
                // 部分电视/盒子带 AOSP 的 H.265 软解。软解 1080P 通常扛不住，只试最低档。
                Log.w(TAG, "No hardware H.265 decoder; trying system software decoder at the smallest quality");
            } else {
                Log.w(TAG, "No H.265 decoder advertised; trying the smallest quality");
            }
            return smallestQuality(supported);
        }

        for (Gear gear : supported) {
            if (gear == null || gear.mainUrl == null || gear.mainUrl.isEmpty()) continue;
            if (gear.isH265() || gear.codec == null || gear.codec.isEmpty()) {
                if (supportsSize(hardware, gear.width, gear.height, gear.fps)) out.add(gear);
                else Log.i(TAG, String.format(Locale.US,
                        "Skipping unsupported H.265 quality %s (%dx%d)",
                        gear.definition, gear.width, gear.height));
            } else {
                out.add(gear);
            }
        }
        if (out.isEmpty()) {
            // 设备能力表有时不完整；交给 Media3 用最低档再试一次。
            Log.w(TAG, "No advertised H.265 size matched; trying the smallest quality");
            return smallestQuality(supported);
        }
        return out;
    }

    private static List<Gear> smallestQuality(List<Gear> gears) {
        List<Gear> out = new ArrayList<>();
        Gear smallest = null;
        if (gears != null) {
            for (Gear gear : gears) {
                if (!supportedCodec(gear) || gear.mainUrl == null || gear.mainUrl.isEmpty()) continue;
                int side = gear.shortSide();
                if (smallest == null || (side > 0 && (smallest.shortSide() <= 0 || side < smallest.shortSide()))) smallest = gear;
            }
        }
        if (smallest != null) out.add(smallest);
        return out;
    }

    private static boolean supportedCodec(Gear gear) {
        return gear != null && !gear.isProprietary();
    }

    private static boolean supportsSize(List<MediaCodecInfo> decoders, int width, int height, float fps) {
        if (width <= 0 || height <= 0) return true;
        for (MediaCodecInfo codec : decoders) {
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MIME_HEVC);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null && (fps > 0 ? video.areSizeAndRateSupported(width, height, fps) : video.isSizeSupported(width, height))) return true;
            } catch (Throwable e) {
                // Incomplete records cannot establish support for a higher quality.
                Log.d(TAG, "Incomplete H.265 capabilities for " + codec.getName(), e);
            }
        }
        return false;
    }
}
