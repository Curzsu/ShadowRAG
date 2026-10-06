package com.yizhaoqi.smartpai.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** A complete, supplier-identified call; arguments are validated by the tool executor. */
public record ModelToolCall(int index, String id, String name, String argumentsJson, String thoughtSignature) {
    public ModelToolCall(int index, String id, String name, String argumentsJson) {
        this(index, id, name, argumentsJson, null);
    }

    /** Opaque protocol state belongs only in the next model request, never in user output. */
    public Map<String,Object> assistantToolCall() {
        var call=new LinkedHashMap<String,Object>();
        call.put("id",id); call.put("type","function");
        call.put("function",Map.of("name",name,"arguments",argumentsJson));
        if(thoughtSignature!=null) call.put("extra_content",Map.of("google",Map.of("thought_signature",thoughtSignature)));
        return call;
    }
}
