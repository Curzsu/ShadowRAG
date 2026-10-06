package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import org.springframework.stereotype.Service;
import java.net.http.HttpTimeoutException;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/** Request-local, sequential model/tool loop; termination and persistence belong to the stream owner. */
@Service
public class AgentLoopService {
    private final DeepSeekClient model;
    private final KnowledgeBaseSearchTool search;
    private final ObjectMapper mapper;
    private final AiProperties properties;
    private final ContextBudgetService budget;
    private final TokenEstimator tokens;
    public AgentLoopService(DeepSeekClient model,KnowledgeBaseSearchTool search,ObjectMapper mapper,AiProperties properties,
                            ContextBudgetService budget,TokenEstimator tokens) {
        properties.getAgent().validate();
        this.model=model; this.search=search; this.mapper=mapper; this.properties=properties; this.budget=budget; this.tokens=tokens;
    }
    public static void checkRunning(ChatRequestContext context) {
        try { context.generationResources().checkRunning(); }
        catch(HttpTimeoutException error) { throw new ChatHandler.GenerationException("STREAM_TIMEOUT","回答生成超时，请稍后重试",error); }
    }
    public void generate(ChatCommand command,ChatRequestContext context,List<Map<String,Object>> initial,Consumer<ChatOutput> output) {
        var messages=new ArrayList<>(initial); var sources=new LinkedHashMap<String,Integer>();
        var limits=properties.getAgent(); int rounds=0,executed=0,repeated=0; String previous=null;
        boolean finalize=false;
        int toolTokens;
        try { toolTokens=tokens.countText(mapper.writeValueAsString(KnowledgeBaseSearchTool.DEFINITIONS)); }
        catch(java.io.IOException failure) { throw new IllegalStateException("Tool definitions unavailable",failure); }
        Integer configured=properties.getGeneration().getMaxTokens(); int reserve=configured==null ? 2000 : Math.max(0,configured);
        for(int roundId=1;roundId<=limits.getMaxToolRounds()+1;roundId++) {
            checkRunning(context);
            finalize=finalize || rounds>=limits.getMaxToolRounds() || executed>=limits.getMaxToolCalls()
                    || context.generationResources().remaining().toMillis()<=limits.getFinalizationReserveMs();
            if(finalize) {
                var system=new LinkedHashMap<>(messages.get(0));
                system.put("content",Objects.toString(system.get("content"),"")+"\n本次工具预算已到，停止工具调用。仅根据已有资料给出部分完成的回答，说明未完成部分，不编造未检索事实。");
                messages.set(0,system);
            }
            messages=new ArrayList<>(budget.fitAgent(messages,reserve,finalize ? 0 : toolTokens));
            final int id=roundId;
            ModelRoundResult result;
            try {
                if(finalize) output.accept(chunk(id,"[部分完成：已达到检索预算，仅依据现有资料回答]\n\n"));
                result=model.streamWithTools(messages,finalize ? List.of() : KnowledgeBaseSearchTool.DEFINITIONS,context,delta -> {
                    checkRunning(context);
                    if(delta.kind()==ModelDelta.Kind.CONTENT) output.accept(chunk(id,delta.value()));
                });
                checkRunning(context);
                if(finalize && !result.toolCalls().isEmpty()) throw new IllegalStateException("Tools returned during finalization");
            } catch(RuntimeException failure) {
                checkRunning(context); throw new ChatHandler.GenerationException("MODEL_ERROR","模型服务暂时不可用，请稍后重试",failure);
            }
            output.accept(new ChatOutput("round_end",Map.of("roundId",id,"kind",result.toolCalls().isEmpty() ? "final" : "intermediate")));
            checkRunning(context);
            if(result.toolCalls().isEmpty()) return;
            var assistant=new LinkedHashMap<String,Object>(); assistant.put("role","assistant"); assistant.put("content",result.content());
            if(result.reasoningContent()!=null && !result.reasoningContent().isEmpty()) assistant.put("reasoning_content",result.reasoningContent());
            assistant.put("tool_calls",result.toolCalls().stream().map(ModelToolCall::assistantToolCall).toList());
            messages.add(assistant); rounds++;
            for(var call:result.toolCalls()) {
                checkRunning(context); String content;
                if(finalize || executed>=limits.getMaxToolCalls()
                        || context.generationResources().remaining().toMillis()<=limits.getFinalizationReserveMs()) {
                    content=KnowledgeBaseSearchTool.error("TOOL_BUDGET"); finalize=true;
                } else {
                    String key=call.name()+":"+normalizedArguments(call);
                    repeated=key.equals(previous) ? repeated+1 : 1; previous=key;
                    if(repeated>=limits.getRepeatedCallLimit()) { content=KnowledgeBaseSearchTool.error("REPEATED_CALL"); finalize=true; }
                    else {
                        if(KnowledgeBaseSearchTool.NAME.equals(call.name())) output.accept(progress(id,call.id(),"started"));
                        checkRunning(context); var tool=search.execute(command,context,call,sources);
                        checkRunning(context); content=tool.content(); if(tool.executed()) executed++;
                        if(KnowledgeBaseSearchTool.NAME.equals(call.name())) output.accept(progress(id,call.id(),"finished"));
                    }
                }
                messages.add(Map.of("role","tool","tool_call_id",call.id(),"content",content));
            }
        }
        throw new ChatHandler.GenerationException("MODEL_ERROR","模型未能在预算内完成回答",null);
    }
    private String normalizedArguments(ModelToolCall call) {
        try { return Normalizer.normalize(search.query(call),Normalizer.Form.NFKC).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
        catch(Exception invalid) { return call.argumentsJson(); }
    }
    private ChatOutput chunk(int roundId,String text) { return new ChatOutput("chunk",Map.of("roundId",roundId,"chunk",text)); }
    private ChatOutput progress(int roundId,String callId,String status) {
        return new ChatOutput("tool_progress",Map.of("tool",KnowledgeBaseSearchTool.NAME,"roundId",roundId,"callId",callId,"status",status));
    }
}
