package com.nox.streamapi.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreamController {

    
    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern EXPIRE = Pattern.compile("[?&]expire=(\\d+)");

    @GetMapping("/api/v1/stream/audio/{videoId}")
    public ResponseEntity<Map<String, Object>> audio(@PathVariable String videoId) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_VIDEO_ID", "The video ID is not valid.");
        }

        try {
            StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + videoId);

            List<AudioStream> sorted = new ArrayList<>(info.getAudioStreams());
            sorted.sort(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed());

            List<Map<String, Object>> streams = new ArrayList<>();
            for (AudioStream a : sorted) {
                String url = a.getContent();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("url", url);
                row.put("mimeType", a.getFormat() != null ? a.getFormat().getMimeType() : null);
                row.put("averageBitrate", a.getAverageBitrate() > 0 ? a.getAverageBitrate() : null);
                row.put("codec", a.getCodec());
                row.put("headers", new LinkedHashMap<String, String>());
                row.put("expiresAt", expireOf(url));
                streams.add(row);
            }

            String thumbnail = info.getThumbnails().isEmpty()
                    ? null
                    : info.getThumbnails().get(0).getUrl();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("videoId", videoId);
            body.put("title", info.getName());
            body.put("uploader", info.getUploaderName());
            body.put("thumbnail", thumbnail);
            body.put("duration", info.getDuration());
            body.put("audioStreams", streams);
            return ResponseEntity.ok(body);

                } catch (ContentNotAvailableException e) {
            log.warn("Video unavailable: {}", videoId, e);
            return error(HttpStatus.NOT_FOUND, "VIDEO_UNAVAILABLE", "The requested video is unavailable.");
        } catch (ExtractionException e) {
            log.error("Audio extraction failed for {}", videoId, e);
            return error(HttpStatus.BAD_GATEWAY, "EXTRACTION_FAILED", "Could not extract this video.");
        } catch (IOException e) {
            log.error("Network error for {}", videoId, e);
            return error(HttpStatus.BAD_GATEWAY, "NETWORK_ERROR", "Network error while contacting the source.");
        }
    }

    private static Long expireOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = EXPIRE.matcher(url);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", err);
        return ResponseEntity.status(status).body(body);
    }
}
