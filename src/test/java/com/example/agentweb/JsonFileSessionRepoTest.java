package com.example.agentweb;

import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.infra.JsonFileSessionRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 JsonFileSessionRepo 的文件持久化功能
 */
public class JsonFileSessionRepoTest {

    private static final String TEST_SESSIONS_DIR = "sessions";
    private JsonFileSessionRepo repo;

    @BeforeEach
    public void setUp() {
        repo = new JsonFileSessionRepo();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // 清理测试生成的文件
        Path sessionsPath = Paths.get(TEST_SESSIONS_DIR);
        if (Files.exists(sessionsPath)) {
            Files.walk(sessionsPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testSaveAndFind() {
        // 创建会话
        ChatSession session = new ChatSession(AgentType.CODEX, "/tmp/test");
        session.addMessage("user", "Hello");
        session.addMessage("assistant", "Hi there");

        // 保存会话
        repo.save(session);

        // 验证文件已创建
        File sessionFile = new File(TEST_SESSIONS_DIR, session.getId() + ".json");
        assertTrue(sessionFile.exists(), "Session file should exist");

        // 加载会话
        ChatSession loaded = repo.find(session.getId());

        // 验证数据完整性
        assertNotNull(loaded, "Loaded session should not be null");
        assertEquals(session.getId(), loaded.getId());
        assertEquals(session.getAgentType(), loaded.getAgentType());
        assertEquals(session.getWorkingDir(), loaded.getWorkingDir());
        assertEquals(session.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(2, loaded.getMessages().size());
        assertEquals("Hello", loaded.getMessages().get(0).getContent());
        assertEquals("Hi there", loaded.getMessages().get(1).getContent());
    }

    @Test
    public void testFindNonExistentSession() {
        // 查找不存在的会话应返回 null
        ChatSession result = repo.find("non-existent-id");
        assertNull(result, "Non-existent session should return null");
    }

    @Test
    public void testUpdateSession() {
        // 创建并保存会话
        ChatSession session = new ChatSession(AgentType.CLAUDE, "/home/test");
        session.addMessage("user", "First message");
        repo.save(session);

        // 更新会话
        session.addMessage("assistant", "Response");
        session.addMessage("user", "Second message");
        repo.save(session);

        // 重新加载验证
        ChatSession loaded = repo.find(session.getId());
        assertNotNull(loaded);
        assertEquals(3, loaded.getMessages().size());
        assertEquals("Second message", loaded.getMessages().get(2).getContent());
    }

    @Test
    public void testMultipleSessions() {
        // 创建多个会话
        ChatSession session1 = new ChatSession(AgentType.CODEX, "/path1");
        ChatSession session2 = new ChatSession(AgentType.CLAUDE, "/path2");
        ChatSession session3 = new ChatSession(AgentType.CODEX, "/path3");

        session1.addMessage("user", "Message 1");
        session2.addMessage("user", "Message 2");
        session3.addMessage("user", "Message 3");

        // 保存所有会话
        repo.save(session1);
        repo.save(session2);
        repo.save(session3);

        // 验证每个会话都有独立文件
        File dir = new File(TEST_SESSIONS_DIR);
        assertTrue(dir.exists() && dir.isDirectory());
        assertEquals(3, dir.listFiles().length, "Should have 3 session files");

        // 验证每个会话可以独立加载
        ChatSession loaded1 = repo.find(session1.getId());
        ChatSession loaded2 = repo.find(session2.getId());
        ChatSession loaded3 = repo.find(session3.getId());

        assertNotNull(loaded1);
        assertNotNull(loaded2);
        assertNotNull(loaded3);

        assertEquals("/path1", loaded1.getWorkingDir());
        assertEquals("/path2", loaded2.getWorkingDir());
        assertEquals("/path3", loaded3.getWorkingDir());
    }

    @Test
    public void testJsonFormatReadability() throws Exception {
        // 创建会话
        ChatSession session = new ChatSession(AgentType.CODEX, "/tmp/readable");
        session.addMessage("user", "Test message");
        repo.save(session);

        // 读取文件内容验证格式化
        File sessionFile = new File(TEST_SESSIONS_DIR, session.getId() + ".json");
        String content = new String(Files.readAllBytes(sessionFile.toPath()));

        // 验证 JSON 是格式化的（包含换行和缩进）
        assertTrue(content.contains("\n"), "JSON should be formatted with newlines");
        assertTrue(content.contains("  "), "JSON should be formatted with indentation");
        assertTrue(content.contains("\"id\""), "JSON should contain id field");
        assertTrue(content.contains("\"agentType\""), "JSON should contain agentType field");
        assertTrue(content.contains("\"messages\""), "JSON should contain messages field");
    }
}
