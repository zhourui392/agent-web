package com.example.agentweb.app.mode;

/**
 * 模式用例：创建 / 编辑 / 删除 / 从模板 fork。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface ModeAppService {

    ModeView create(SaveModeCommand command);

    ModeView update(String modeId, SaveModeCommand command);

    void delete(String modeId);

    /** 从模板 revision fork（复合键定位）：拷贝冻结能力与工作流文本为当前用户的自定义模式。 */
    ModeView fork(String definitionIdentifier, long revisionId, String displayName);
}
