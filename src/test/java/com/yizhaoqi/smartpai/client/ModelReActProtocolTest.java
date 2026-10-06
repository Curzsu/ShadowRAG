package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import com.yizhaoqi.smartpai.service.chat.ChatGenerationResources;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ModelReActProtocolTest {
    final ObjectMapper mapper = new ObjectMapper();
    String frame(Map<String,Object> delta) throws Exception {
        return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("delta",delta))));
    }
    Map<String,Object> call(int index,String id,String name,String arguments) {
        var call = new LinkedHashMap<String,Object>(); call.put("index",index);
        if(id!=null) call.put("id",id);
        var function = new LinkedHashMap<String,Object>();
        if(name!=null) function.put("name",name);
        if(arguments!=null) function.put("arguments",arguments);
        call.put("function",function); return call;
    }
    ModelRoundResult read(MockModelSseServer server,ModelHttpProperties properties,List<ModelDelta> output) throws Exception {
        var client = new BlockingModelHttpClient(server.url(),"","",properties,mapper);
        return client.stream(Map.of("stream",true),new ChatGenerationResources(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)),output::add);
    }
    @Test void interleavedCallsPreserveIndexAndReplayReasoningWithoutDisplayingIt() throws Exception {
        try(var server = new MockModelSseServer()) {
            server.enqueue(s -> {
                s.data(frame(Map.of("content","查找资料","reasoning_content","private reasoning",
                        "tool_calls",List.of(call(1,"b","search_knowledge_","{\"query\":"),call(0,"a","search_knowledge_base","{\"query\":")))));
                s.data(frame(Map.of("tool_calls",List.of(call(0,null,"search_knowledge_base","\"A\"}"),call(1,null,"base","\"B\"}")))));
                s.data("[DONE]");
            });
            var output = new ArrayList<ModelDelta>(); var result = read(server,new ModelHttpProperties(),output);
            assertEquals("查找资料",result.content()); assertEquals("private reasoning",result.reasoningContent());
            assertEquals(List.of("a","b"),result.toolCalls().stream().map(ModelToolCall::id).toList());
            assertEquals(List.of("search_knowledge_base","search_knowledge_base"),result.toolCalls().stream().map(ModelToolCall::name).toList());
            assertEquals(List.of("{\"query\":\"A\"}","{\"query\":\"B\"}"),result.toolCalls().stream().map(ModelToolCall::argumentsJson).toList());
            assertTrue(output.stream().noneMatch(d -> d.value().contains("private reasoning")));
            assertTrue(output.stream().anyMatch(d -> Objects.equals(d.toolCallIndex(),1)));
        }
    }
    @Test void missingDuplicateIdsInvalidIndexAndMissingNameFailAfterDone() throws Exception {
        for(var calls : List.of(List.of(call(0,null,"search_knowledge_base","{}")),
                List.of(call(0,"same","search_knowledge_base","{}"),call(1,"same","search_knowledge_base","{}")),
                List.of(call(-1,"a","search_knowledge_base","{}")),List.of(call(0,"a",null,"{}")))) {
            try(var server = new MockModelSseServer()) {
                server.enqueue(s -> { s.data(frame(Map.of("tool_calls",calls))); s.data("[DONE]"); });
                assertThrows(IOException.class,() -> read(server,new ModelHttpProperties(),new ArrayList<>()));
                assertEquals(1,server.requests());
            }
        }
    }
    @Test void unsupportedNameAndInvalidJsonRemainAvailableForToolErrorResponse() throws Exception {
        try(var server = new MockModelSseServer()) {
            server.enqueue(s -> { s.data(frame(Map.of("tool_calls",List.of(call(0,"a","unknown_tool","broken"))))); s.data("[DONE]"); });
            var result=read(server,new ModelHttpProperties(),new ArrayList<>());
            assertEquals("unknown_tool",result.toolCalls().get(0).name()); assertEquals("broken",result.toolCalls().get(0).argumentsJson());
        }
    }
    @Test void aggregateArgumentsReasoningAndCallCountAreBounded() throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            var p = new ModelHttpProperties(); p.setMaxToolArgumentsChars(12); p.setMaxReasoningChars(8); p.setMaxToolCallsPerRound(2);
            var delta = scenario==0 ? Map.<String,Object>of("tool_calls",List.of(call(0,"a","search_knowledge_base","1234567"),call(1,"b","search_knowledge_base","1234567")))
                    : scenario==1 ? Map.<String,Object>of("reasoning_content","123456789")
                    : Map.<String,Object>of("tool_calls",List.of(call(0,"a","x","{}"),call(1,"b","x","{}"),call(2,"c","x","{}")));
            try(var server = new MockModelSseServer()) {
                server.enqueue(s -> { s.data(frame(delta)); s.data("[DONE]"); });
                assertThrows(IOException.class,() -> read(server,p,new ArrayList<>()));
            }
        }
    }
    @Test void multipleJsonValuesInOneFrameAreNotACompleteModelResponse() throws Exception {
        try(var server=new MockModelSseServer()) {
            server.enqueue(s -> { s.data(frame(Map.of("content","unsafe"))+" {}"); s.data("[DONE]"); });
            assertThrows(IOException.class,() -> read(server,new ModelHttpProperties(),new ArrayList<>()));
        }
    }
    @Test void overlappingAndRepeatedIdFragmentsAreAppendedWithoutNameDeduplication() throws Exception {
        try(var server=new MockModelSseServer()) {
            server.enqueue(s -> {
                s.data(frame(Map.of("tool_calls",List.of(call(0,"call_","search_knowledge_base","{\"query\":\"A\"}")))));
                s.data(frame(Map.of("tool_calls",List.of(call(0,"call_42",null,null)))));
                s.data(frame(Map.of("tool_calls",List.of(call(0,"call_42",null,null)))));
                s.data("[DONE]");
            });
            var output=new ArrayList<ModelDelta>(); var result=read(server,new ModelHttpProperties(),output);
            assertEquals("call_call_42call_42",result.toolCalls().get(0).id());
            assertEquals(List.of("call_","call_42","call_42"),output.stream()
                    .filter(d -> d.kind()==ModelDelta.Kind.TOOL_CALL_ID).map(ModelDelta::value).toList());
        }
    }
    Map<String,Object> wholeCall(String id,String query) throws Exception {
        return Map.of("id",id,"type","function","function",Map.of("name","search_knowledge_base","arguments",mapper.writeValueAsString(Map.of("query",query))));
    }
    @Test void completeUnindexedBatchRetainsSupplierIdsAndArgumentPairs() throws Exception {
        try(var server=new MockModelSseServer()) {
            server.enqueue(s -> {
                s.data(frame(Map.of("tool_calls",List.of(wholeCall("google-a","A"),wholeCall("google-b","B")))));
                s.data("[DONE]");
            });
            var result=read(server,new ModelHttpProperties(),new ArrayList<>());
            assertEquals(List.of(0,1),result.toolCalls().stream().map(ModelToolCall::index).toList());
            assertEquals(List.of("google-a","google-b"),result.toolCalls().stream().map(ModelToolCall::id).toList());
            assertEquals(List.of("{\"query\":\"A\"}","{\"query\":\"B\"}"),result.toolCalls().stream().map(ModelToolCall::argumentsJson).toList());
        }
    }
    @Test void ambiguousUnindexedFragmentsMixedIndicesAndRepeatedBatchesFailSafely() throws Exception {
        var incomplete=Map.<String,Object>of("id","google-a","type","function","function",Map.of("name","search_knowledge_base","arguments","{\"query\":"));
        var missingId=Map.<String,Object>of("type","function","function",Map.of("name","search_knowledge_base","arguments","{}"));
        var valid=wholeCall("google-a","A");
        for(var batch:List.of(List.of(incomplete),List.of(missingId),List.of(valid,call(1,"b","search_knowledge_base","{}")),List.of(valid,valid))) {
            try(var server=new MockModelSseServer()) {
                server.enqueue(s -> { s.data(frame(Map.of("tool_calls",batch))); s.data("[DONE]"); });
                assertThrows(IOException.class,() -> read(server,new ModelHttpProperties(),new ArrayList<>()));
            }
        }
        try(var server=new MockModelSseServer()) {
            server.enqueue(s -> { s.data(frame(Map.of("tool_calls",List.of(valid)))); s.data(frame(Map.of("tool_calls",List.of(valid)))); s.data("[DONE]"); });
            assertThrows(IOException.class,() -> read(server,new ModelHttpProperties(),new ArrayList<>()));
        }
    }
}
