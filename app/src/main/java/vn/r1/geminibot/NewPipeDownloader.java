package vn.r1.geminibot;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

/** Cầu nối mạng cho NewPipeExtractor (thư viện tự lo phần YouTube, app chỉ cần gửi HTTP). */
final class NewPipeDownloader extends Downloader {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        byte[] data = request.dataToSend();
        RequestBody body = data != null ? RequestBody.create(null, data) : null;
        okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT);
        for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
            b.removeHeader(h.getKey());
            for (String v : h.getValue()) b.addHeader(h.getKey(), v);
        }
        okhttp3.Response r = http.newCall(b.build()).execute();
        try {
            if (r.code() == 429) throw new ReCaptchaException("YouTube đòi xác minh (429)", request.url());
            String text = r.body() != null ? r.body().string() : null;
            return new Response(r.code(), r.message(), r.headers().toMultimap(), text,
                    r.request().url().toString());
        } finally {
            r.close();
        }
    }
}
