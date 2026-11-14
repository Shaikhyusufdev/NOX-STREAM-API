package com.nox.streamapi.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @GetMapping("/api/v1/test/search")
    public Map<String, Object> testSearch(@RequestParam("q") String q) throws Exception {
        SearchInfo info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.getSearchQHFactory().fromQuery(q));

        List<Map<String, Object>> results = new ArrayList<>();
        for (InfoItem item : info.getRelatedItems()) {
            if (item instanceof StreamInfoItem s) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", s.getName());
                row.put("uploader", s.getUploaderName());
                row.put("duration", s.getDuration());
                row.put("url", s.getUrl());
                results.add(row);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("query", q);
        body.put("count", results.size());
        body.put("data", results);
        return body;
    }
}
