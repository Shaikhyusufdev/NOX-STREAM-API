package com.nox.streamapi.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

public class NewPipeDownloader extends Downloader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpRequest.BodyPublisher body = request.dataToSend() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.dataToSend());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(Duration.ofSeconds(20))
                .method(request.httpMethod(), body);

        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            String name = e.getKey();
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Host")) {
                continue;
            }
            for (String value : e.getValue()) {
                builder.header(name, value);
            }
        }

        boolean hasUserAgent = request.headers().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase("User-Agent"));
        if (!hasUserAgent) {
            builder.header("User-Agent", USER_AGENT);
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", ex);
        }

        if (response.statusCode() == 429) {
            throw new ReCaptchaException("Rate limited / captcha required", request.url());
        }

        return new Response(
                response.statusCode(),
                "",
                response.headers().map(),
                response.body(),
                response.uri().toString());
    }
}
