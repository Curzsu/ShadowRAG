package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentLoopServiceTest {
    MockModelSseServer server; AiProperties p; HybridSearchService search;
    AgentLoopService loop; final ObjectMapper mapper=new ObjectMapper();
    final ChatCommand command=new ChatCommand("alice","conversation",UUID.randomUUID(),"比较报告A和B");
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    final List<ChatOutput> events=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        server=new MockModelSseServer(); p=new AiProperties(); p.getContext().setSafetyMarginTokens(50); p.getGeneration().setMaxTokens(200);
        search=mock(HybridSearchService.class); rebuild();
    }
    void rebuild() {
        var tokens=new TokenEstimator(mapper);
        loop=new AgentLoopService(new DeepSeekClient(server.url(),"","test",p,mapper),
                new KnowledgeBaseSearchTool(search,mapper,p),mapper,p,new ContextBudgetService(p,tokens),tokens);
    }
    @AfterEach void close() { server.close(); }
    void response(String content,String...queries) {
        server.enqueue(s -> {
            requests.add(mapper.readTree(s.requestBody()));
            if(!content.isEmpty()) s.content(content);
            var calls=new ArrayList<Map<String,Object>>();
            for(int i=0;i<queries.length;i++) calls.add(Map.of("index",i,"id","call-"+requests.size()+"-"+i,"type","function",
                    "function",Map.of("name","search_knowledge_base","arguments",mapper.writeValueAsString(Map.of("query",queries[i])))));
            if(!calls.isEmpty()) s.data(mapper.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("tool_calls",calls))))));
            s.data("[DONE]");
        });
    }
    void run(ChatRequestContext context) {
        loop.generate(command,context,new ArrayList<>(List.of(Map.of("role","system","content","rules"),
                Map.of("role","user","content",command.message()))),events::add);
    }
    List<ChatOutput> ends() { return events.stream().filter(e -> "round_end".equals(e.type())).toList(); }
    @Test void generalQuestionStreamsFinalRoundWithZeroSearches() {
        response("直接答案"); run(new ChatRequestContext(command));
        assertEquals(Map.of("chunk","直接答案","roundId",1),events.get(0).data());
        assertEquals(Map.of("roundId",1,"kind","final"),ends().get(0).data()); verifyNoInteractions(search);
    }
    @Test void twoSearchRoundsReplayCompleteMessagesAndUseAuthenticatedOwner() {
        response("先找A","A"); response("再找B","B"); response("最终比较");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of(new SearchResult("file-a",1,"收入100",1.0,"A.pdf")));
        when(search.searchWithPermission("B","alice",10)).thenReturn(List.of(new SearchResult("file-b",2,"收入120",1.0,"B.pdf")));
        run(new ChatRequestContext(command));
        assertEquals(3,server.requests()); verify(search).searchWithPermission("A","alice",10); verify(search).searchWithPermission("B","alice",10);
        assertEquals(List.of("intermediate","intermediate","final"),ends().stream().map(e -> e.data().get("kind")).toList());
        assertEquals(List.of(1,2,3),ends().stream().map(e -> e.data().get("roundId")).toList());
        var messages=requests.get(2).path("messages");
        assertEquals(List.of("system","user","assistant","tool","assistant","tool"),roles(messages));
        assertEquals("先找A",messages.get(2).path("content").asText());
        assertEquals("call-1-0",messages.get(3).path("tool_call_id").asText());
        assertTrue(messages.get(3).path("content").asText().contains("file-a:1"));
        assertTrue(messages.get(5).path("content").asText().contains("file-b:2"));
        assertTrue(requests.get(2).has("tools"));
    }
    List<String> roles(JsonNode messages) { var roles=new ArrayList<String>(); messages.forEach(m -> roles.add(m.path("role").asText())); return roles; }
    @Test void sameRoundCallsExecuteSeriallyAndAllResultsPairBeforeNextModel() {
        response("","A","B"); response("答案");
        when(search.searchWithPermission(anyString(),eq("alice"),eq(10))).thenReturn(List.of()); run(new ChatRequestContext(command));
        var order=inOrder(search); order.verify(search).searchWithPermission("A","alice",10); order.verify(search).searchWithPermission("B","alice",10);
        var messages=requests.get(1).path("messages"); assertEquals(List.of("system","user","assistant","tool","tool"),roles(messages));
        assertEquals("call-1-0",messages.get(3).path("tool_call_id").asText()); assertEquals("call-1-1",messages.get(4).path("tool_call_id").asText());
        assertTrue(messages.get(3).path("content").asText().contains("未找到"));
    }
    @Test void recoverableSearchErrorReachesModelButPrivateExceptionDoesNot() {
        response("","A"); response("","B"); response("恢复后的答案");
        when(search.searchWithPermission("A","alice",10)).thenThrow(new IllegalStateException("private database password"));
        when(search.searchWithPermission("B","alice",10)).thenReturn(List.of()); run(new ChatRequestContext(command));
        assertEquals(3,server.requests()); var body=requests.get(1).toString(); assertTrue(body.contains("SEARCH_ERROR")); assertFalse(body.contains("password"));
    }
    @Test void threeToolRoundsUseExactlyOneFourthRequestWithoutTools() {
        response("","A"); response("","B"); response("","C"); response("仅基于现有资料");
        when(search.searchWithPermission(anyString(),eq("alice"),eq(10))).thenReturn(List.of()); run(new ChatRequestContext(command));
        assertEquals(4,server.requests()); assertFalse(requests.get(3).has("tools"));
        assertEquals(3,mockingDetails(search).getInvocations().size());
        assertTrue(events.stream().anyMatch(e -> "chunk".equals(e.type()) && e.data().get("chunk").toString().contains("部分完成")));
    }
    @Test void sixActualCallsAndUnexecutedCallsReceiveBudgetErrors() {
        response("","A","B","C","D"); response("","E","F","G"); response("收尾");
        when(search.searchWithPermission(anyString(),eq("alice"),eq(10))).thenReturn(List.of()); run(new ChatRequestContext(command));
        assertEquals(6,mockingDetails(search).getInvocations().size()); assertEquals(3,server.requests()); assertFalse(requests.get(2).has("tools"));
        var last=requests.get(2).path("messages"); assertTrue(last.toString().contains("TOOL_BUDGET"));
        assertEquals("call-2-2",last.get(last.size()-1).path("tool_call_id").asText());
    }
    @Test void thirdIdenticalNormalizedQueryIsBlockedBeforeExecution() {
        response(""," A "); response("","A"); response("","A"); response("收尾");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of()); run(new ChatRequestContext(command));
        verify(search,times(2)).searchWithPermission("A","alice",10); assertFalse(requests.get(3).has("tools"));
        assertTrue(requests.get(3).toString().contains("REPEATED_CALL"));
    }
    @Test void reserveTimeStartsNoToolFinalizationAndExpiredRequestNeverCallsModel() {
        response("收尾"); var context=new ChatRequestContext(command,System.nanoTime()+TimeUnit.SECONDS.toNanos(5)); run(context);
        assertFalse(requests.get(0).has("tools")); verifyNoInteractions(search);
        assertThrows(ChatHandler.GenerationException.class,() -> run(new ChatRequestContext(command,System.nanoTime()-1)));
        assertEquals(1,server.requests());
    }
    @Test void cancellationDuringSearchPreventsLaterOutputAndModel() {
        response("","A"); var context=new ChatRequestContext(command);
        when(search.searchWithPermission("A","alice",10)).thenAnswer(call -> { context.generationResources().stop(); return List.of(); });
        assertThrows(CancellationException.class,() -> run(context)); assertEquals(1,server.requests());
        assertEquals(0,events.stream().filter(e -> "tool_progress".equals(e.type()) && "finished".equals(e.data().get("status"))).count());
    }
    @Test void noToolFinalizationResponseReturningToolsFailsWithoutExecutingThem() {
        p.getAgent().setMaxToolRounds(1); rebuild(); response("","A"); response("","B");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of());
        var error=assertThrows(ChatHandler.GenerationException.class,() -> run(new ChatRequestContext(command)));
        assertEquals("MODEL_ERROR",error.getErrorCode()); verify(search,never()).searchWithPermission("B","alice",10);
        assertEquals(1,ends().size());
    }
    @Test void invalidArgumentsAndUnknownToolReturnOriginalIdsWithoutSearch() throws Exception {
        server.enqueue(s -> {
            requests.add(mapper.readTree(s.requestBody()));
            s.data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"bad\",\"function\":{\"name\":\"search_knowledge_base\",\"arguments\":\"{\\\"query\\\":123}\"}},{\"index\":1,\"id\":\"unknown\",\"function\":{\"name\":\"unknown_tool\",\"arguments\":\"{}\"}}]}}]}"); s.data("[DONE]");
        }); response("纠正后回答"); run(new ChatRequestContext(command)); verifyNoInteractions(search);
        var messages=requests.get(1).path("messages"); assertEquals("bad",messages.get(3).path("tool_call_id").asText());
        assertTrue(messages.get(3).path("content").asText().contains("INVALID_ARGUMENTS")); assertTrue(messages.get(4).path("content").asText().contains("UNSUPPORTED_TOOL"));
    }
    @Test void cancellingThirdModelRoundClosesItsConnectionAndNeverConfirmsFinal() throws Exception {
        response("","A"); response("","B");
        server.enqueue(s -> { requests.add(mapper.readTree(s.requestBody())); s.content("partial final"); s.probeUntilDisconnected(); });
        when(search.searchWithPermission(anyString(),eq("alice"),eq(10))).thenReturn(List.of());
        var context=new ChatRequestContext(command);
        assertThrows(CancellationException.class,() -> loop.generate(command,context,new ArrayList<>(List.of(
                Map.of("role","system","content","rules"),Map.of("role","user","content",command.message()))),event -> {
            events.add(event);
            if("chunk".equals(event.type()) && Integer.valueOf(3).equals(event.data().get("roundId"))) context.generationResources().stop();
        }));
        assertEquals(3,server.requests()); assertEquals(2,ends().size()); assertTrue(server.awaitDisconnect(java.time.Duration.ofSeconds(2)));
    }
    @Test void actualNextRequestRetainsAllToolPairsAfterContextTruncation() {
        p.getContext().setWindowTokens(1500); rebuild(); response("","A","B"); response("回答");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of(new SearchResult("a",1,"资料甲 ".repeat(6000),1.0,"A.pdf")));
        when(search.searchWithPermission("B","alice",10)).thenReturn(List.of(new SearchResult("b",2,"资料乙 ".repeat(6000),1.0,"B.pdf")));
        run(new ChatRequestContext(command)); var messages=requests.get(1).path("messages");
        assertEquals(List.of("system","user","assistant","tool","tool"),roles(messages));
        assertEquals(command.message(),messages.get(1).path("content").asText());
        assertTrue(messages.get(3).path("content").asText().contains("a:1")); assertTrue(messages.get(4).path("content").asText().contains("b:2"));
        assertTrue(messages.get(3).path("content").asText().contains("已按上下文预算截断"));
        assertTrue(messages.get(4).path("content").asText().contains("已按上下文预算截断"));
    }
    @Test void followupCanRestoreSourceFactsAfterActualContextTruncation() {
        p.getContext().setWindowTokens(1500); rebuild(); response("","A"); response("","refine A"); response("回答");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of(new SearchResult("a",1,
                "irrelevant ".repeat(6000)+"唯一事实收入120",1.0,"A.pdf")));
        when(search.searchWithPermission("refine A","alice",10)).thenReturn(List.of(new SearchResult("a",1,"唯一事实收入120",1.0,"A.pdf")));
        run(new ChatRequestContext(command));
        String first=requests.get(1).path("messages").get(3).path("content").asText();
        assertTrue(first.contains("已按上下文预算截断")); assertFalse(first.contains("唯一事实收入120"));
        var messages=requests.get(2).path("messages");
        assertEquals("call-2-0",messages.get(5).path("tool_call_id").asText());
        assertTrue(messages.get(5).path("content").asText().contains("唯一事实收入120"),
                () -> "Restored tool body was: "+messages.get(5).path("content").asText());
        assertTrue(messages.get(5).path("content").asText().contains("\"source\":1"));
    }
    @Test void overlappingIdFragmentsArePairedWithTheSupplierIdInActualNextRequest() throws Exception {
        server.enqueue(s -> {
            requests.add(mapper.readTree(s.requestBody()));
            s.data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_\",\"function\":{\"name\":\"search_knowledge_base\",\"arguments\":\"{\\\"query\\\":\\\"A\\\"}\"}}]}}]}");
            s.data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_42\"}]}}]}");
            s.data("[DONE]");
        }); response("回答");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of()); run(new ChatRequestContext(command));
        var messages=requests.get(1).path("messages");
        assertEquals("call_call_42",messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("call_call_42",messages.get(3).path("tool_call_id").asText());
    }
    @Test void unindexedCompleteSupplierCallKeepsItsOriginalIdInNextActualRequest() throws Exception {
        server.enqueue(s -> {
            requests.add(mapper.readTree(s.requestBody()));
            s.data("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"query\\\":\\\"A\\\"}\",\"name\":\"search_knowledge_base\"},\"id\":\"function-call-google-original\",\"type\":\"function\"}]},\"finish_reason\":\"tool_calls\",\"index\":0}]}");
            s.data("[DONE]");
        }); response("回答");
        when(search.searchWithPermission("A","alice",10)).thenReturn(List.of()); run(new ChatRequestContext(command));
        var messages=requests.get(1).path("messages");
        assertEquals("function-call-google-original",messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("function-call-google-original",messages.get(3).path("tool_call_id").asText());
        assertEquals(2,server.requests()); verify(search).searchWithPermission("A","alice",10);
    }
}
