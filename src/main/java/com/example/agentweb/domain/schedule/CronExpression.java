package com.example.agentweb.domain.schedule;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

/**
 * Spring 5.3+ 六段式 Cron 表达式值对象。
 *
 * @author alex
 * @since 2026-07-23
 */
public record CronExpression(String value) {

    private static final CronParser PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53));

    public CronExpression {
        value = validate(value);
    }

    public static CronExpression parse(String value) {
        return new CronExpression(value);
    }

    private static String validate(String value) {
        try {
            PARSER.parse(value).validate();
            return value.trim();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无效的 Cron 表达式: " + value, e);
        }
    }
}
