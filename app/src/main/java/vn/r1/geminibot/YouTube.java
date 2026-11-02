package vn.r1.geminibot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * Tìm / lấy nhạc YouTube bằng NewPipeExtractor (thay cho yt-dlp của Voicebot trên Pi).
 * Bài trả về cùng dạng JSON với Pi: {id, title, duration, channel, channel_id, view_count, verified}.
 */
final class YouTube {
    private static final StreamingService YT = ServiceList.YouTube;
    private static final Localization VI = new Localization("vi", "VN");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern EXPIRE = Pattern.compile("[?&]expire=(\\d+)");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build();
    private static boolean ready;

    /** Link phát đã lấy (YouTube cho dùng vài giờ): id -> Audio. */
    private static final Map<String, Audio> CACHE = new HashMap<>();

    /** Luồng tiếng của 1 bài. */
    static final class Audio {
        String url, userAgent, title, channel, channelId, codec;
        long duration, expiresAt;
        int kbps;
    }

    private YouTube() {}

    static synchronized void init() {
        if (ready) return;
        NewPipe.init(new NewPipeDownloader(), VI, new ContentCountry("VN"));
        ready = true;
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static JSONArray search(String q, int max) throws Exception {
        init();
        SearchInfo info = SearchInfo.getInfo(YT, YT.getSearchQHFactory()
                .fromQuery(q, Collections.singletonList("videos"), ""));
        return toJson(info.getRelatedItems(), max, Collections.<String>emptySet());
    }

    /** Từ khoá gợi ý khi đang gõ (API gợi ý công khai của Google, như Pi). */
    static JSONArray suggest(String q) {
        JSONArray out = new JSONArray();
        try {
            String url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=vi&q="
                    + URLEncoder.encode(q, "UTF-8");
            okhttp3.Response r = HTTP.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
            try {
                JSONArray a = new JSONArray(r.body().string());
                JSONArray s = a.optJSONArray(1);
                for (int i = 0; s != null && i < s.length() && i < 8; i++) out.put(s.optString(i));
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Bài liên quan (YouTube Mix của bài, như Pi), bỏ các id trong skip. */
    static JSONArray related(String id, Set<String> skip, int max) throws Exception {
        init();
        Set<String> bo = new HashSet<>(skip);
        bo.add(id);
        try {
            PlaylistInfo mix = PlaylistInfo.getInfo(YT, "https://www.youtube.com/watch?v=" + id + "&list=RD" + id);
            JSONArray out = toJson(mix.getRelatedItems(), max, bo);
            if (out.length() > 0) return out;
        } catch (Exception e) {
            BotLog.w("YouTube Mix " + id + ": " + e.getMessage());
        }
        StreamInfo info = StreamInfo.getInfo(YT, "https://www.youtube.com/watch?v=" + id);
        return toJson(info.getRelatedItems(), max, bo);
    }

    /** Video mới nhất của 1 kênh: {channel, results}. */
    static JSONObject channel(String channelId, int max) throws Exception {
        init();
        ChannelInfo ch = ChannelInfo.getInfo(YT, "https://www.youtube.com/channel/" + channelId);
        JSONArray results = new JSONArray();
        for (ListLinkHandler tab : ch.getTabs()) {
            if (!tab.getContentFilters().contains(ChannelTabs.VIDEOS)) continue;
            results = toJson(ChannelTabInfo.getInfo(YT, tab).getRelatedItems(), max,
                    Collections.<String>emptySet());
            break;
        }
        return new JSONObject().put("channel", ch.getName()).put("results", results);
    }

    /** Link luồng tiếng để phát (ưu tiên m4a/AAC, rồi webm/Opus), có đệm vài giờ. */
    static Audio audio(String id, boolean fresh) throws Exception {
        synchronized (CACHE) {
            Audio a = CACHE.get(id);
            if (!fresh && a != null && a.expiresAt > System.currentTimeMillis() + 60000) return a;
        }
        init();
        StreamInfo info = StreamInfo.getInfo(YT, "https://www.youtube.com/watch?v=" + id);
        AudioStream best = null;
        for (AudioStream s : info.getAudioStreams()) {
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !s.isUrl()) continue;
            if (best == null || score(s) > score(best)) best = s;
        }
        if (best == null) throw new Exception("YouTube không trả luồng tiếng cho " + id);
        Audio a = new Audio();
        a.url = best.getContent();
        a.userAgent = YoutubeParsingHelper.isAndroidStreamingUrl(a.url) ? YoutubeParsingHelper.getAndroidUserAgent(VI)
                : YoutubeParsingHelper.isIosStreamingUrl(a.url) ? YoutubeParsingHelper.getIosUserAgent(VI)
                : NewPipeDownloader.USER_AGENT;
        a.title = info.getName();
        a.duration = info.getDuration();
        a.channel = info.getUploaderName();
        a.channelId = channelId(info.getUploaderUrl());
        a.codec = best.getFormat() == MediaFormat.M4A ? "AAC" : "OPUS";
        a.kbps = best.getAverageBitrate() > 0 ? best.getAverageBitrate() : best.getBitrate() / 1000;
        Matcher m = EXPIRE.matcher(a.url);
        a.expiresAt = m.find() ? Math.min(Long.parseLong(m.group(1)) * 1000 - 600000,
                System.currentTimeMillis() + 3 * 3600000L) : System.currentTimeMillis() + 1800000L;
        synchronized (CACHE) {
            CACHE.put(id, a);
        }
        return a;
    }

    /** m4a (AAC, Android 5.1 giải mã chắc chắn) trước, trong cùng loại thì bitrate cao hơn. */
    private static int score(AudioStream s) {
        int br = s.getAverageBitrate() > 0 ? s.getAverageBitrate() : s.getBitrate() / 1000;
        return (s.getFormat() == MediaFormat.M4A ? 10000 : 0) + br;
    }

    private static JSONArray toJson(List<? extends InfoItem> items, int max, Set<String> skip) throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>(skip);
        for (InfoItem it : items) {
            if (!(it instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) it;
            String id;
            try {
                id = YT.getStreamLHFactory().getId(s.getUrl());
            } catch (Exception e) {
                continue;
            }
            if (!validId(id) || !seen.add(id)) continue;
            JSONObject o = new JSONObject()
                    .put("id", id)
                    .put("title", s.getName())
                    .put("channel", s.getUploaderName() != null ? s.getUploaderName() : "")
                    .put("channel_id", channelId(s.getUploaderUrl()))
                    .put("verified", s.isUploaderVerified());
            if (s.getDuration() > 0) o.put("duration", s.getDuration());
            if (s.getViewCount() >= 0) o.put("view_count", s.getViewCount());
            out.put(o);
            if (out.length() >= max) break;
        }
        return out;
    }

    private static String channelId(String url) {
        if (url == null) return "";
        int i = url.indexOf("/channel/");
        if (i < 0) return "";
        String id = url.substring(i + 9);
        int q = id.indexOf('?');
        return q >= 0 ? id.substring(0, q) : id;
    }
}
