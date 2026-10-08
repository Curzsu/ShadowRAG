package com.yizhaoqi.smartpai.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.client.ModelRoundResult;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.service.KnowledgeBaseSearchTool;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Test-only runner: uses the real production client, never executes a search tool. */
final class RoutingEvaluationExporter {
    interface Call { ModelRoundResult invoke(ChatRequestContext context, LangfuseTracing.ModelObservation observation); }
    private final LangfuseTracing tracing;
    private final AiProperties ai;
    private final String model;
    RoutingEvaluationExporter(LangfuseTracing tracing,AiProperties ai,String model) { this.tracing=tracing; this.ai=ai; this.model=model; }

    static String route(ModelRoundResult result) {
        if (!Set.of("stop","tool_calls").contains(Objects.toString(result.finishReason(),""))) throw new IllegalArgumentException("INCOMPLETE_RESPONSE");
        if(result.toolCalls().stream().anyMatch(t -> KnowledgeBaseSearchTool.NAME.equals(t.name()))) return "SEARCH";
        if(!result.toolCalls().isEmpty()) throw new IllegalArgumentException("UNSUPPORTED_TOOL");
        if(result.content()==null || result.content().isBlank()) throw new IllegalArgumentException("EMPTY_RESPONSE");
        return "DIRECT";
    }

    Map<String,Object> export(String run,JsonNode sample,String policyHash,String messagesHash,int retries,Call call) {
        if(retries<0 || retries>2) throw new IllegalArgumentException("Invalid retry count");
        String id=sample.path("id").asText(),expected=sample.path("expectedRoute").asText();
        if(id.isBlank() || !Set.of("SEARCH","DIRECT").contains(expected)) throw new IllegalArgumentException("Invalid label");
        Span root;
        try(var ignored=Context.root().makeCurrent()) { root=tracing.start("routing.evaluation","span"); }
        root.setAttribute("langfuse.session.id",run);
        var row=new LinkedHashMap<String,Object>();
        row.put("id",id); row.put("expectedRoute",expected); row.put("policy_sha256",policyHash);
        row.put("messages_sha256",messagesHash); row.put("annotationStatus",sample.path("annotationStatus").asText());
        row.put("trace_id",root.getSpanContext().getTraceId()); row.put("observation_id",root.getSpanContext().getSpanId());
        for(String key:List.of("id","expectedRoute","policy_sha256","messages_sha256","annotationStatus")) LangfuseTracing.metadata(root,key,Objects.toString(row.get(key)));
        LangfuseTracing.metadata(root,"query_sha256",LangfuseTracing.hash(sample.path("query").asText()));
        List<Map<String,Object>> attempts=new ArrayList<>(); String prediction=null,error=null; Integer selected=null;
        long start=System.nanoTime();
        try {
            for(int attempt=1;attempt<=retries+1;attempt++) {
                var context=new ChatRequestContext(new ChatCommand("routing-evaluation",run,UUID.randomUUID(),sample.path("query").asText()),System.nanoTime()+TimeUnit.SECONDS.toNanos(90));
                context.setTraceContext(Context.root().with(root));
                var record=new LinkedHashMap<String,Object>(); record.put("attempt",attempt);
                try(var observation=tracing.startModel(context,1,model,ai.getGeneration())) {
                    record.put("observation_id",observation.span().getSpanContext().getSpanId());
                    LangfuseTracing.metadata(observation.span(),"attempt",Integer.toString(attempt));
                    LangfuseTracing.metadata(observation.span(),"policy_sha256",policyHash);
                    try { prediction=route(call.invoke(context,observation)); error=null; selected=attempt; }
                    catch(RuntimeException failure) {
                        error=failure instanceof IllegalArgumentException ? "INVALID_ROUTE_RESPONSE" : "MODEL_ERROR";
                        LangfuseTracing.error(observation.span(),error);
                    }
                    record.put("predictedRoute",prediction); record.put("error",error); attempts.add(record);
                } finally { context.generationResources().stop(); }
                if(prediction!=null || Thread.currentThread().isInterrupted()) break;
            }
            row.put("predictedRoute",prediction); row.put("error",error); row.put("selectedAttempt",selected); row.put("attempts",attempts);
            LangfuseTracing.metadata(root,"predictedRoute",prediction); LangfuseTracing.metadata(root,"selectedAttempt",Objects.toString(selected,"none"));
            if(error!=null) LangfuseTracing.error(root,error);
            return row;
        } finally {
            row.put("latencyMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); root.end();
        }
    }
}
