package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.model.chat.ChatOutput;

/** Owned by the serial sender. Only a completely delivered, confirmed final round is durable. */
final class ChatRoundAccumulator {
    private final StringBuilder draft=new StringBuilder();
    private String answer="";
    private int ended,active;
    private boolean roundMode,legacy,finalSeen;
    void accept(ChatOutput output) {
        if("chunk".equals(output.type())) {
            if(!(output.data().get("chunk") instanceof String text)) throw new IllegalArgumentException("Invalid chunk");
            if(!output.data().containsKey("roundId")) {
                if(roundMode) throw new IllegalArgumentException("Mixed round protocol");
                legacy=true; draft.append(text); answer=draft.toString(); return;
            }
            start(roundId(output)); draft.append(text);
        } else if("round_end".equals(output.type())) {
            start(roundId(output));
            Object kind=output.data().get("kind");
            if(!"final".equals(kind) && !"intermediate".equals(kind)) throw new IllegalArgumentException("Invalid round kind");
            if("final".equals(kind)) { finalSeen=true; answer=draft.toString(); }
            ended=active; active=0; draft.setLength(0);
        } else if(output.data().containsKey("roundId")) {
            if(finalSeen || !roundMode || active!=0 || roundId(output)!=ended) throw new IllegalArgumentException("Invalid progress round");
        }
    }
    private void start(int id) {
        if(legacy || finalSeen || id!=ended+1 || (active!=0 && active!=id)) throw new IllegalArgumentException("Invalid round order");
        roundMode=true; active=id;
    }
    private int roundId(ChatOutput output) {
        if(!(output.data().get("roundId") instanceof Integer id) || id<1) throw new IllegalArgumentException("Invalid round id");
        return id;
    }
    void requireFinal() {
        if(roundMode && !finalSeen) throw new IllegalStateException("No confirmed final round");
    }
    String answer() { return answer; }
}
