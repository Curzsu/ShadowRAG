package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real provider decisions with the actual shipped rules and tool schema; synthetic documents only. */
@EnabledIfSystemProperty(named="chat.gemini.live",matches="true")
@EnabledIfEnvironmentVariable(named="SHADOWRAG_LIVE_GEMINI_KEY",matches=".+")
@Timeout(120)
class LiveGeminiKnowledgeFirstTest {
    @ParameterizedTest
    @CsvSource({"person-hit,苏哲是谁,true", "person-miss,青岚联调人物九号是谁,true",
            "technical-fact,什么是ReAct,true", "greeting,你好,false", "calculation,计算7乘8,false"})
    void actualRulesPreferKnowledgeForFacts(String scenario,String question,boolean expectedSearch) throws Exception {
        var mapper=new ObjectMapper(); var p=new AiProperties();
        p.getGeneration().setMaxTokens(1024); p.getGeneration().setTemperature(0.0);
        var yaml=new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        String rules=Objects.requireNonNull(yaml.getObject()).getProperty("ai.prompt.rules");
        assertNotNull(rules);
        String model=Objects.requireNonNullElse(System.getenv("SHADOWRAG_LIVE_GEMINI_MODEL"),"gemini-3.1-flash-lite");
        var client=new DeepSeekClient("https://generativelanguage.googleapis.com/v1beta/openai",
                System.getenv("SHADOWRAG_LIVE_GEMINI_KEY"),model,p,mapper,
                Objects.requireNonNullElse(System.getenv("SHADOWRAG_LIVE_GEMINI_PROXY"),""));
        var retrieval=mock(HybridSearchService.class); var queries=new ArrayList<String>();
        when(retrieval.searchWithPermission(anyString(),eq("knowledge-first-test"),eq(10))).thenAnswer(invocation -> {
            String query=invocation.getArgument(0); queries.add(query);
            if(scenario.equals("person-hit") && query.contains("苏哲"))
                return List.of(new SearchResult("synthetic-person",1,"苏哲是虚构的联调人物，在联调组负责 ShadowRAG 的文档检索模块。此信息仅供合成测试。",1.0,"联调人物.pdf"));
            if(scenario.equals("technical-fact"))
                return List.of(new SearchResult("synthetic-tech",1,"ReAct 在本项目中是模型选择工具、执行检索、把工具结果回填模型并继续回答的循环。",1.0,"ReAct说明.pdf"));
            return List.of();
        });
        var tokens=new TokenEstimator(mapper);
        var loop=new AgentLoopService(client,new KnowledgeBaseSearchTool(retrieval,mapper,p),mapper,p,
                new ContextBudgetService(p,tokens),tokens);
        var command=new ChatCommand("knowledge-first-test","synthetic-policy",UUID.randomUUID(),question);
        var context=new ChatRequestContext(command,System.nanoTime()+TimeUnit.SECONDS.toNanos(90));
        var events=new ArrayList<ChatOutput>(); long started=System.nanoTime();
        try { loop.generate(command,context,List.of(Map.of("role","system","content",rules),Map.of("role","user","content",question)),events::add); }
        finally { context.generationResources().stop(); }
        var ends=events.stream().filter(e -> e.type().equals("round_end")).toList();
        assertFalse(ends.isEmpty()); assertEquals("final",ends.get(ends.size()-1).data().get("kind"));
        Object finalId=ends.get(ends.size()-1).data().get("roundId");
        String answer=events.stream().filter(e -> e.type().equals("chunk") && finalId.equals(e.data().get("roundId")))
                .map(e -> (String)e.data().get("chunk")).reduce("",String::concat);
        var report=new LinkedHashMap<String,Object>(); report.put("scenario",scenario); report.put("model",model);
        report.put("question",question); report.put("syntheticRetrieval",true); report.put("queries",queries);
        report.put("roundKinds",ends.stream().map(e -> e.data().get("kind")).toList()); report.put("answer",answer);
        report.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        Path directory=Path.of(System.getProperty("chat.acceptance.metrics-dir",".superpowers/sdd/2026-10-06-knowledge-base-react"));
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("knowledge-first-"+scenario+".json").toFile(),report);
        assertEquals(expectedSearch,!queries.isEmpty(),"Actual provider should use the expected search route");
        if(scenario.equals("person-hit")) {
            assertTrue(answer.contains("ShadowRAG") && answer.contains("来源#")); assertFalse(answer.contains("琅琊榜"));
        }
        if(scenario.equals("person-miss")) {
            assertTrue(answer.contains("未找到") || answer.contains("未检索到") || answer.contains("暂无") || answer.contains("没有找到"));
            assertFalse(answer.contains("琅琊榜")); assertFalse(answer.contains("来源#"));
        }
        if(scenario.equals("technical-fact")) assertTrue(answer.contains("来源#"));
        if(scenario.equals("calculation")) assertTrue(answer.contains("56"));
    }
}
