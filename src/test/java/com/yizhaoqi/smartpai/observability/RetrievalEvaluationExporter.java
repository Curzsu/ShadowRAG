package com.yizhaoqi.smartpai.observability;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Test-only adapter that projects actual ordered results and the service's recording span status. */
final class RetrievalEvaluationExporter {
    private final LangfuseTracing tracing;
    RetrievalEvaluationExporter(LangfuseTracing tracing) { this.tracing=tracing; }

    Map<String,Object> export(String run, String sample, String strategy, String query,
                              String configHash, Supplier<List<SearchResult>> search) {
        return export(run,sample,strategy,query,configHash,null,search);
    }

    Map<String,Object> export(String run, String sample, String strategy, String query,
                              String configHash, JsonNode item, Supplier<List<SearchResult>> search) {
        if(!Set.of("bm25","hybrid_rerank").contains(strategy)) throw new IllegalArgumentException("INVALID_STRATEGY");
        boolean experiment=item!=null && item.has("langfuse_dataset_id");
        String experimentId=java.util.UUID.nameUUIDFromBytes((run+":"+strategy).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Baggage baggage=Baggage.empty();
        if(experiment) {
            for(String field:List.of("langfuse_dataset_id","langfuse_dataset_item_id","langfuse_dataset_version"))
                if(item.path(field).asText().isBlank()) throw new IllegalArgumentException("INVALID_EXPERIMENT_ITEM");
            baggage=Baggage.builder().put("langfuse.experiment.id",experimentId)
                    .put("langfuse.experiment.name",run+"-"+strategy)
                    .put("langfuse.experiment.dataset.id",item.path("langfuse_dataset_id").asText())
                    .put("langfuse.environment","experiment").build();
        }
        // Evaluations must be separate roots, even if the caller already has an active trace.
        try(Scope root=baggage.storeInContext(Context.root()).makeCurrent()) {
            Span span=tracing.start("retrieval.evaluation","span");
            if(!(span instanceof ReadableSpan readable) || !span.isRecording()) {
                span.end();
                throw new IllegalStateException("EVALUATION_REQUIRES_RECORDING_TRACER");
            }
            span.setAttribute("langfuse.session.id",run);
            LangfuseTracing.metadata(span,"run_id",run);
            LangfuseTracing.metadata(span,"sample_id",sample);
            LangfuseTracing.metadata(span,"strategy",strategy);
            LangfuseTracing.metadata(span,"query_sha256",LangfuseTracing.hash(query));
            LangfuseTracing.metadata(span,"config_sha256",configHash);
            span.setAttribute("langfuse.observation.metadata.top_k",10L);
            Context execution=Context.current().with(span);
            if(experiment) {
                String rootId=span.getSpanContext().getSpanId();
                String itemId=item.path("langfuse_dataset_item_id").asText();
                String version=item.path("langfuse_dataset_version").asText();
                span.setAttribute("langfuse.experiment.item.id",itemId)
                        .setAttribute("langfuse.experiment.item.root_observation_id",rootId)
                        .setAttribute("langfuse.experiment.item.version",version)
                        .setAttribute("langfuse.observation.input",json(Map.of("query",query)))
                        .setAttribute("langfuse.experiment.item.expected_output",json(Map.of("relevant_chunk_keys",item.get("relevant"))))
                        .setAttribute("langfuse.experiment.item.metadata.sample_id",sample)
                        .setAttribute("langfuse.experiment.item.metadata.difficulty",item.path("difficulty").asText());
                execution=baggage.toBuilder().put("langfuse.experiment.item.id",itemId)
                        .put("langfuse.experiment.item.root_observation_id",rootId)
                        .put("langfuse.experiment.item.version",version).build().storeInContext(execution);
            }
            List<SearchResult> results=List.of();
            try(Scope active=execution.makeCurrent()) {
                try {
                    results=search.get();
                    if(results==null) { results=List.of(); LangfuseTracing.error(span,"SEARCH_ERROR"); }
                } catch(RuntimeException failure) {
                    // Never serialize exception messages or causes (they can contain source text/credentials).
                    LangfuseTracing.error(span,"SEARCH_ERROR");
                }
                String error=attribute(readable,"error_code");
                String retrieval=error!=null ? "error" : attribute(readable,"retrieval_status");
                if(retrieval==null) retrieval="success";
                String rerank=strategy.equals("bm25") ? "not_applicable" : attribute(readable,"rerank_status");
                if(rerank==null) rerank=retrieval.equals("bm25_fallback") ? "skipped" : "unknown";
                var ordered=new ArrayList<Map<String,Object>>();
                for(var result:results) {
                    var hit=new LinkedHashMap<String,Object>();
                    hit.put("key",result.getFileMd5()+":"+result.getChunkId()); hit.put("score",result.getScore());
                    ordered.add(hit);
                }
                var row=new LinkedHashMap<String,Object>();
                row.put("run_id",run); row.put("sample_id",sample); row.put("strategy",strategy);
                row.put("trace_id",span.getSpanContext().getTraceId()); row.put("retrieved",ordered);
                row.put("rerank_status",rerank); row.put("retrieval_status",retrieval);
                row.put("error",error==null ? null : "SEARCH_ERROR"); row.put("top_k",10);
                if(experiment) {
                    row.put("observation_id",span.getSpanContext().getSpanId());
                    row.put("experiment_id",experimentId); row.put("experiment_name",run+"-"+strategy);
                    row.put("dataset_id",item.path("langfuse_dataset_id").asText());
                    row.put("dataset_item_id",item.path("langfuse_dataset_item_id").asText());
                    row.put("dataset_version",item.path("langfuse_dataset_version").asText());
                    span.setAttribute("langfuse.observation.output",json(Map.of("retrieved",ordered,"retrieval_status",retrieval,"rerank_status",rerank)));
                }
                LangfuseTracing.metadata(span,"retrieval_status",retrieval);
                LangfuseTracing.metadata(span,"rerank_status",rerank);
                return row;
            } finally { span.end(); }
        }
    }

    private static String json(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); }
        catch(Exception invalid) { throw new IllegalArgumentException("INVALID_EXPERIMENT_JSON"); }
    }

    private String attribute(ReadableSpan span,String key) {
        return span.getAttribute(AttributeKey.stringKey("langfuse.observation.metadata."+key));
    }
}
