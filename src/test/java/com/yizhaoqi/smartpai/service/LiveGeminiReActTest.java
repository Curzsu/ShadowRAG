package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in only: real Gemini HTTP, synthetic retrieval, no application secrets or private documents. */
@EnabledIfSystemProperty(named="chat.gemini.live",matches="true")
@EnabledIfEnvironmentVariable(named="SHADOWRAG_LIVE_GEMINI_KEY",matches=".+")
@Timeout(120)
class LiveGeminiReActTest {
    static final String URL="https://generativelanguage.googleapis.com/v1beta/openai";
    final ObjectMapper mapper=new ObjectMapper();
    final String model=Objects.requireNonNullElse(System.getenv("SHADOWRAG_LIVE_GEMINI_MODEL"),"gemini-2.5-flash-lite");
    final String proxy=Objects.requireNonNullElse(System.getenv("SHADOWRAG_LIVE_GEMINI_PROXY"),"");

    final class ObservedClient extends DeepSeekClient {
        final List<List<Map<String,Object>>> requests=new ArrayList<>();
        ObservedClient(AiProperties properties) { super(URL,System.getenv("SHADOWRAG_LIVE_GEMINI_KEY"),LiveGeminiReActTest.this.model,properties,LiveGeminiReActTest.this.mapper,LiveGeminiReActTest.this.proxy); }
        @Override public ModelRoundResult streamWithTools(List<Map<String,Object>> messages,List<Map<String,Object>> tools,
                                                        ChatRequestContext context,Consumer<ModelDelta> output) {
            requests.add(List.copyOf(messages));
            return super.streamWithTools(messages,tools,context,output);
        }
    }
    void run(String scenario,String prompt,String rules,boolean expectSearch) throws Exception {
        var properties=new AiProperties(); properties.getGeneration().setMaxTokens(1024); properties.getGeneration().setTemperature(0.0);
        var client=new ObservedClient(properties); var retrieval=mock(HybridSearchService.class);
        var queries=new ArrayList<String>();
        when(retrieval.searchWithPermission(anyString(),eq("gemini-live-test"),eq(10))).thenAnswer(invocation -> {
            String query=invocation.getArgument(0); queries.add(query);
            return query.contains("B")
                    ? List.of(new SearchResult("synthetic-b",2,"内部报告B：2026年营业收入120万元。",1.0,"报告B.pdf"))
                    : List.of(new SearchResult("synthetic-a",1,"内部报告A：2025年营业收入100万元。2026年的数据仅在报告B中，需继续检索报告B。",1.0,"报告A.pdf"));
        });
        var tokens=new TokenEstimator(mapper);
        var loop=new AgentLoopService(client,new KnowledgeBaseSearchTool(retrieval,mapper,properties),mapper,properties,
                new ContextBudgetService(properties,tokens),tokens);
        var command=new ChatCommand("gemini-live-test","live-synthetic",UUID.randomUUID(),prompt);
        var context=new ChatRequestContext(command,System.nanoTime()+TimeUnit.SECONDS.toNanos(90));
        var events=new ArrayList<ChatOutput>(); long started=System.nanoTime();
        try {
            loop.generate(command,context,List.of(Map.of("role","system","content",rules),Map.of("role","user","content",prompt)),events::add);
        } finally { context.generationResources().stop(); }
        var ends=events.stream().filter(event -> "round_end".equals(event.type())).toList();
        assertFalse(ends.isEmpty()); assertEquals("final",ends.get(ends.size()-1).data().get("kind"));
        Object finalRound=ends.get(ends.size()-1).data().get("roundId");
        String answer=events.stream().filter(event -> "chunk".equals(event.type()) && finalRound.equals(event.data().get("roundId")))
                .map(event -> (String)event.data().get("chunk")).reduce("",String::concat);
        var report=new LinkedHashMap<String,Object>();
        report.put("scenario",scenario); report.put("model",model); report.put("realSupplier",true);
        report.put("syntheticRetrieval",true); report.put("modelRequests",client.requests.size()); report.put("queries",queries);
        report.put("roundKinds",ends.stream().map(event -> event.data().get("kind")).toList()); report.put("answer",answer);
        report.put("elapsedMillis",Duration.ofNanos(System.nanoTime()-started).toMillis()); report.put("recordedAt",java.time.Instant.now().toString());
        Path directory=Path.of(System.getProperty("chat.acceptance.metrics-dir",".superpowers/sdd/2026-10-06-knowledge-base-react"));
        Files.createDirectories(directory); mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("live-gemini-"+scenario+".json").toFile(),report);
        if(expectSearch) {
            assertEquals(List.of("报告A","报告B"),queries); assertEquals(3,client.requests.size());
            assertEquals(List.of("intermediate","intermediate","final"),ends.stream().map(event -> event.data().get("kind")).toList());
            assertTrue(answer.contains("100") && answer.contains("120") && answer.contains("20"),"Expected comparison facts in final answer");
            for(int i=1;i<client.requests.size();i++) {
                var messages=client.requests.get(i);
                for(int j=2;j<messages.size();j+=2) {
                    var calls=(List<?>)messages.get(j).get("tool_calls"); var call=(Map<?,?>)calls.get(0);
                    assertEquals(call.get("id"),messages.get(j+1).get("tool_call_id"));
                }
            }
        } else {
            assertEquals(1,client.requests.size()); assertTrue(queries.isEmpty()); assertTrue(answer.contains("联调成功"));
            verifyNoInteractions(retrieval);
        }
    }
    @Test void directAnswerUsesRealStreamingWithZeroSearch() throws Exception {
        run("direct","请仅回复：联调成功。","你是简洁的助手。通用对话直接回答，只有明确要求私有资料才搜索知识库。",false);
    }
    @Test void realModelChoosesTwoConsecutiveKnowledgeBaseSearches() throws Exception {
        run("two-searches","请先查询内部知识库的报告A，再查询报告B，比较2025与2026营业收入及增长百分比，用阿拉伯数字回答并引用来源。",
                "你是内部报告助手，所有收入数字必须以工具结果为依据。必须严格按顺序执行：第一轮只调用一次search_knowledge_base，query必须为报告A；收到结果后，第二轮只调用一次，query必须为报告B；收到两份资料后才生成最终比较。每轮至多一个调用，不得提前合并查询。中间说明不要当作最终结论。最终答案80字以内，引用格式为(来源#编号: 文件名)。",true);
    }
}
