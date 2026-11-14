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
