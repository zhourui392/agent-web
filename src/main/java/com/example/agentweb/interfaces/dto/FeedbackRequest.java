package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * PUT /api/chat/session/{id}/feedback 请求体,整体替换语义。
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class FeedbackRequest {

    /** 评分枚举名 CORRECT/PARTIALLY_CORRECT/INCORRECT,null/空白表示未评分 */
    private String rating;

    /** 文字补充,可空 */
    private String comment;
}
