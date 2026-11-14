package com.nox.streamapi.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final Pattern VIDEO_ID = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GetMapping("/api/v1/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "filter", defaultValue = "all") String filter,
            @RequestParam(name = "page", required = false) String page) {

        if (q == null || q.isBlank() || q.length() > 100) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
                    "Query 'q' is required and must be 1-100 characters.");
        }

        List<String> contentFilters;
        if (filter.equals("all")) {
            contentFilters = List.of();
        } else if (filter.equals("videos")) {
            contentFilters = List.of("videos");
        } else if (filter.equals("music")) {
            contentFilters = List.of("music_songs");
        } else {
            return error(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                    "Filter must be one of: all, videos, music.");
        }

        try {
            var handler = ServiceList.YouTube.getSearchQHFactory()
                    .fromQuery(q.trim(), contentFilters, "");

            List<InfoItem> items;
            Page next;

            if (page == null || page.isBlank()) {
                SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, handler);
                items = info.getRelatedItems();
                next = info.getNextPage();
            } else {
                Page decoded = decodePage(page);
                if (decoded == null) {
                    return error(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                            "The page token is not valid.");
                }
                var more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, decoded);
                items = more.getItems();
                next = more.getNextPage();
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (InfoItem item : items) {
                if (item instanceof StreamInfoItem s) {
                    results.add(toRow(s));
                }
            }

            boolean hasMore = hasMore(next);
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("hasMore", hasMore);
            pagination.put("nextPage", hasMore ? encodePage(next) : null);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("query", q.trim());
            body.put("filter", filter);
            body.put("count", results.size());
            body.put("data", results);
            body.put("pagination", pagination);
            return ResponseEntity.ok(body);

        } catch (SignInConfirmNotBotException e) {
            log.warn("Source blocked this IP for search '{}'", q);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_BLOCKED",
                    "The source temporarily blocked this server. Try again later.");
        } catch (ExtractionException e) {
            log.error("Search extraction failed for '{}'", q, e);
            return error(HttpStatus.BAD_GATEWAY, "EXTRACTION_FAILED", "Could not complete the search.");
        } catch (IOException e) {
            log.error("Network error during search '{}'", q, e);
            return error(HttpStatus.BAD_GATEWAY, "NETWORK_ERROR", "Network error while contacting the source.");
        }
    }

    private static boolean hasMore(Page p) {
        return p != null && (
                (p.getUrl() != null && !p.getUrl().isEmpty())
                        || (p.getIds() != null && !p.getIds().isEmpty()));
    }

    private static String encodePage(Page p) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("u", p.getUrl());
            m.put("i", p.getId());
            m.put("d", p.getIds());
            m.put("c", p.getCookies());
            m.put("b", p.getBody() == null ? null : Base64.getEncoder().encodeToString(p.getBody()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(m));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Page decodePage(String token) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            JsonNode n = MAPPER.readTree(raw);

            String url = n.hasNonNull("u") ? n.get("u").asText() : null;
            if (!isYoutubeUrl(url)) {
                return null;
            }
            String id = n.hasNonNull("i") ? n.get("i").asText() : null;

            List<String> ids = null;
            if (n.hasNonNull("d")) {
                ids = new ArrayList<>();
                for (JsonNode x : n.get("d")) {
                    ids.add(x.asText());
                }
            }

            Map<String, String> cookies = new LinkedHashMap<>();
            if (n.hasNonNull("c")) {
                n.get("c").fields().forEachRemaining(
                        e -> cookies.put(e.getKey(), e.getValue().asText()));
            }

            byte[] body = n.hasNonNull("b") ? Base64.getDecoder().decode(n.get("b").asText()) : null;

            return new Page(url, id, ids, cookies.isEmpty() ? null : cookies, body);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    // The token comes from the client, so only YouTube hosts are accepted.
    private static boolean isYoutubeUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            return "https".equals(u.getScheme()) && host != null
                    && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> toRow(StreamInfoItem s) {
        String url = s.getUrl();
        Matcher m = VIDEO_ID.matcher(url == null ? "" : url);
        String videoId = m.find() ? m.group(1) : null;

        String thumbnail = s.getThumbnails().stream()
                .max(Comparator.comparingInt(Image::getHeight))
                .map(Image::getUrl)
                .orElse(null);

        StreamType type = s.getStreamType();
        boolean isLive = type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("videoId", videoId);
        row.put("title", s.getName());
        row.put("uploader", s.getUploaderName());
        row.put("thumbnail", thumbnail);
        row.put("duration", s.getDuration() >= 0 ? s.getDuration() : null);
        row.put("viewCount", s.getViewCount() >= 0 ? s.getViewCount() : null);
        row.put("uploadDate", s.getTextualUploadDate());
        row.put("isLive", isLive);
        row.put("url", url);
        return row;
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
