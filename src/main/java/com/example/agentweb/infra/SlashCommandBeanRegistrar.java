package com.example.agentweb.infra;

import com.example.agentweb.domain.SlashCommandExpander;
import com.example.agentweb.domain.SlashCommandScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Infrastructure 层：将领域对象组装并注册为 Spring Bean。
 */
@Configuration
public class SlashCommandBeanRegistrar {

    @Bean
    public SlashCommandExpander slashCommandExpander(SlashCommandScanner scanner) {
        return new SlashCommandExpander(scanner);
    }
}
