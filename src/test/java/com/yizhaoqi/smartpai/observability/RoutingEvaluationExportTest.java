package com.yizhaoqi.smartpai.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.KnowledgeBaseSearchTool;
import com.yizhaoqi.smartpai.service.ContextBudgetService;
import com.yizhaoqi.smartpai.service.TokenEstimator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingEvaluationExportTest {
    @Test void retryKeepsFailedGenerationAndSelectsSuccessfulAttempt() throws Exception {
        var memory=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(memory)).build()) {
            var tracing=new LangfuseTracing(provider.get("test"),provider,"test");
            var sample=new ObjectMapper().readTree("{\"id\":\"test\",\"query\":\"private query\",\"expectedRoute\":\"DIRECT\",\"annotationStatus\":\"pending_human_review\"}");
            var count=new java.util.concurrent.atomic.AtomicInteger();
            var row=new RoutingEvaluationExporter(tracing,new AiProperties(),"model").export("run",sample,"policy","messages",1,(context,observation) -> {
                if(count.incrementAndGet()==1) throw new IllegalStateException("private supplier exception");
                return new ModelRoundResult("private answer","",List.of(),"stop");
            });
            assertEquals(2,row.get("selectedAttempt")); assertNull(row.get("error"));
            var spans=memory.getFinishedSpanItems();
            var root=spans.stream().filter(s -> s.getName().equals("routing.evaluation")).findFirst().orElseThrow();
            assertFalse(root.getParentSpanContext().isValid());
            assertEquals(2,spans.stream().filter(s -> s.getParentSpanId().equals(root.getSpanId())).count());
            assertFalse(spans.toString().contains("private query")); assertFalse(spans.toString().contains("private answer")); assertFalse(spans.toString().contains("private supplier"));
        }
    }
    @Test void recognizesOnlyProductionToolAndCompletedResponses() {
        assertEquals("DIRECT", RoutingEvaluationExporter.route(new ModelRoundResult("hello", "", List.of(), "stop")));
        assertEquals("SEARCH", RoutingEvaluationExporter.route(new ModelRoundResult("", "", List.of(new ModelToolCall(0,"id",KnowledgeBaseSearchTool.NAME,"{}")), "tool_calls")));
        assertThrows(IllegalArgumentException.class, () -> RoutingEvaluationExporter.route(new ModelRoundResult("", "", List.of(new ModelToolCall(0,"id","unknown","{}")), "tool_calls")));
        assertThrows(IllegalArgumentException.class, () -> RoutingEvaluationExporter.route(new ModelRoundResult("", "", List.of(), "length")));
        assertThrows(IllegalArgumentException.class, () -> RoutingEvaluationExporter.route(new ModelRoundResult("", "", List.of(), "stop")));
    }

    @Nested
    @EnabledIfEnvironmentVariable(named="LANGFUSE_ROUTING_EVAL", matches="true")
    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.kafka.listener.auto-startup=false", "spring.jpa.show-sql=false"})
    class Real {
        @Autowired DeepSeekClient client;
        @Autowired ChatHandler handler;
        @Autowired AiProperties ai;
        @Autowired LangfuseTracing tracing;
        @Autowired ObjectMapper mapper;
        @Autowired ContextBudgetService budget;
        @Autowired TokenEstimator tokens;

        @Test @Timeout(1200) void exportRealFirstTurnRoutes() throws Exception {
            Path dir=Path.of(System.getenv("ROUTING_EVAL_OUTPUT_DIR"));
            String run=System.getenv("ROUTING_EVAL_RUN_ID");
            var cases=Files.readAllLines(Path.of(System.getenv("ROUTING_EVAL_DATASET"))).stream().filter(s -> !s.isBlank()).map(s -> {
                try { return mapper.readTree(s); } catch(Exception e) { throw new IllegalArgumentException("Invalid dataset",e); }
            }).toList();
            assertEquals(10,cases.size(),"This runner is a ten-case smoke evaluation");
            Map<String,Object> snapshot=new TreeMap<>();
            snapshot.put("system_prompt",ai.getPrompt().getRules()); snapshot.put("tools",KnowledgeBaseSearchTool.DEFINITIONS);
            snapshot.put("model",client.modelName()); snapshot.put("generation",ai.getGeneration());
            snapshot.put("scope","production first model turn; retrieval and final answer are not executed");
            for(String file: List.of("service/ChatHandler.java","service/AgentLoopService.java","client/DeepSeekClient.java","service/KnowledgeBaseSearchTool.java")) {
                snapshot.put(file,LangfuseTracing.hash(Files.readString(Path.of("src/main/java/com/yizhaoqi/smartpai/"+file))));
            }
            String policyHash=LangfuseTracing.hash(mapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(snapshot));
            snapshot.put("policy_sha256",policyHash); snapshot.put("run_id",run);
            Files.writeString(dir.resolve("policy.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),StandardOpenOption.CREATE_NEW);
            var exporter=new RoutingEvaluationExporter(tracing,ai,client.modelName());
            for(var sample:cases) {
                List<Map<String,String>> history=mapper.convertValue(sample.path("history"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                List<Map<String,Object>> messages=ReflectionTestUtils.invokeMethod(handler,"buildMessagesForAgenticRAG",history,sample.path("query").asText());
                int reserve=ai.getGeneration().getMaxTokens()==null ? 2000 : Math.max(0,ai.getGeneration().getMaxTokens());
                messages=budget.fitAgent(messages,reserve,tokens.countText(mapper.writeValueAsString(KnowledgeBaseSearchTool.DEFINITIONS)));
                final var requestMessages=messages;
                var row=exporter.export(run,sample,policyHash,LangfuseTracing.hash(mapper.writeValueAsString(messages)),0,
                    (context, observation) -> client.streamWithTools(requestMessages,KnowledgeBaseSearchTool.DEFINITIONS,context,
                        delta -> { if(delta.kind()==ModelDelta.Kind.CONTENT) observation.firstContent(delta.value()); }));
                assertTrue(row.get("trace_id").toString().matches("[a-f0-9]{32}"),"Recording tracing required");
                Files.writeString(dir.resolve("predictions.jsonl"),mapper.writeValueAsString(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
            assertTrue(tracing.forceFlush().join(15,java.util.concurrent.TimeUnit.SECONDS).isSuccess());
        }
    }
}
