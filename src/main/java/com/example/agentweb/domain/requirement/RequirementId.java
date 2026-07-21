package com.example.agentweb.domain.requirement;

import lombok.Value;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 需求 ID：'R' + yyMMdd + 4 位随机数字（对齐 TicketId 风格，短 ID 便于口头引用与分支命名）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class RequirementId {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    String value;

    public static RequirementId newId() {
        String datePart = LocalDate.now().format(DATE_PART);
        String randomPart = String.format("%04d", RANDOM.nextInt(10000));
        return new RequirementId("R" + datePart + randomPart);
    }
}
