package com.nox.streamapi.config;

import jakarta.annotation.PostConstruct;
import org.schabi.newpipe.extractor.NewPipe;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NewPipeConfig {

    @PostConstruct
    public void init() {
        NewPipe.init(new NewPipeDownloader());
    }
}
