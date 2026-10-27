package com.hgtv.app.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import com.hgtv.app.model.Gear;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Checks which server-provided H.265 sizes this Android device can decode. */
public final class VideoDecoderSupport {

    private static final String TAG = "VideoDecoderSupport";
    private static final String MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static volatile MediaCodecInfo[] cachedCodecs;

    private VideoDecoderSupport() {}

    public static List<Gear> compatibleHevc(List<Gear> gears) {
        List<Gear> out = new ArrayList<>();
        if (gears == null || gears.isEmpty()) return out;

        MediaCodecInfo[] codecs;
        try {
            codecs=cachedCodecs;
            if(codecs==null){codecs=new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();cachedCodecs=codecs;}
        } catch (Throwable e) {
            // If a vendor codec registry is broken, try the smallest stream first.
            Log.w(TAG, "Could not inspect decoders; trying the smallest quality", e);
            return smallestQuality(gears);
        }

        List<MediaCodecInfo> hevcDecoders = new ArrayList<>();
        for (MediaCodecInfo codec : codecs) {
            if (codec.isEncoder()) continue;
            for (String type : codec.getSupportedTypes()) {
                if (MIME_HEVC.equalsIgnoreCase(type)) {
                    hevcDecoders.add(codec);
                    break;
                }
            }
        }

        if (hevcDecoders.isEmpty()) {
            Log.w(TAG, "No H.265 decoder advertised; trying the smallest quality");
            return smallestQuality(gears);
        }
        for (Gear gear : gears) {
            if (gear == null || gear.mainUrl == null || gear.mainUrl.isEmpty()) continue;
            if (supportsSize(hevcDecoders, gear.width, gear.height,gear.fps)) out.add(gear);
            else Log.i(TAG, String.format(Locale.US,
                    "Skipping unsupported H.265 quality %s (%dx%d)",
                    gear.definition, gear.width, gear.height));
        }
        if (out.isEmpty()) {
            // Device capability tables are sometimes incomplete. Still try the
            // smallest stream; MediaPlayer may have a vendor decoder not reported here.
            Log.w(TAG, "No advertised H.265 size matched; trying the smallest quality");
            return smallestQuality(gears);
        }
        return out;
    }

    private static List<Gear> smallestQuality(List<Gear> gears) {
        List<Gear> out = new ArrayList<>();
        Gear smallest = null;
        if (gears != null) {
            for (Gear gear : gears) {
                if (gear == null || gear.mainUrl == null || gear.mainUrl.isEmpty()) continue;
                if (smallest == null || gear.shortSide() < smallest.shortSide()) smallest = gear;
            }
        }
        if (smallest != null) out.add(smallest);
        return out;
    }

    private static boolean supportsSize(List<MediaCodecInfo> decoders, int width, int height,float fps) {
        if (width <= 0 || height <= 0) return true;
        for (MediaCodecInfo codec : decoders) {
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MIME_HEVC);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null && (fps>0?video.areSizeAndRateSupported(width,height,fps):video.isSizeSupported(width, height))) return true;
            } catch (Throwable e) {
                // Incomplete records cannot establish support for a higher quality.
                Log.d(TAG, "Incomplete H.265 capabilities for " + codec.getName(), e);
            }
        }
        return false;
    }
}
