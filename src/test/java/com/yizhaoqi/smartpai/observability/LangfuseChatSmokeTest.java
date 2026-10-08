package com.yizhaoqi.smartpai.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.LangfuseConfiguration;
import com.yizhaoqi.smartpai.config.LangfuseProperties;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ProxySelector;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-service verification: paid model calls and two temporary conversations. */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_CHAT_SMOKE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "spring.kafka.listener.auto-startup=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class LangfuseChatSmokeTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ConversationService conversations;
    @Autowired ConversationRepository conversationRepository;
    @Autowired JwtUtils jwt;
    @Autowired LangfuseTracing tracing;
    @Autowired LangfuseProperties props;

    @Test void directAndSearchChatsReachCloudWithNativeTtftAndCommittedState() throws Exception {
        var user = users.findAll(PageRequest.of(0, 1, Sort.by("id"))).getContent();
        assertFalse(user.isEmpty(), "Real chat smoke needs an existing local test user");
        String username = user.get(0).getUsername();
        String token = jwt.generateToken(username);
        var created = new ArrayList<String>();
        var evidence = new ArrayList<Map<String,Object>>();
        try {
            evidence.add(runChat(username, token, "只做纯计算：17乘以19等于多少？只输出数字。", false, created));
            evidence.add(runChat(username, token, "请先调用 search_knowledge_base 检索知识库中的「派聪明」，然后用一句话说明检索结果；没找到就说明未找到。", true, created));
            var output = Path.of("target/langfuse-work/cloud-chat-smoke.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {
            jwt.invalidateToken(token);
            // Remove only the two conversations this verification created; never modify existing messages.
            for (String id : created) conversations.deleteConversation(username, id);
        }
    }

    private Map<String,Object> runChat(String username, String token, String question, boolean search,
                                       List<String> created) throws Exception {
        String session = createIsolatedConversation(username).getConversationId();
        created.add(session);
        var requestId = UUID.randomUUID();
        Instant began = Instant.now().minusSeconds(5);
        var body = mapper.writeValueAsString(Map.of("conversationId",session,"requestId",requestId,"message",question));
        var local = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var response = local.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                .timeout(Duration.ofSeconds(180)).header("Authorization","Bearer " + token)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),"Real chat HTTP request failed");
        assertTrue(response.body().contains("\"status\":\"finished\""),"Chat did not finish successfully");
        assertTrue(conversations.loadHistoryForChat(username,session).size() >= 2,"Chat was not persisted");
        tracing.forceFlush().join(5, TimeUnit.SECONDS);
        var cloud = cloudClient();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (props.getPublicKey()+":"+props.getSecretKey()).getBytes(StandardCharsets.UTF_8));
        var lookup = HttpRequest.newBuilder(URI.create(props.getBaseUrl()+"/api/public/v2/observations?sessionId="+session
                +"&fromStartTime="+encode(began.toString())+"&toStartTime="+encode(Instant.now().plusSeconds(120).toString())
                +"&fields=core,basic,time,model,metrics,metadata,io&limit=100"))
                .timeout(Duration.ofSeconds(10)).header("Authorization",auth).GET().build();
        JsonNode rows = null, root = null;
        for(int attempt=0;attempt<15;attempt++) {
            var result=cloud.send(lookup,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,result.statusCode(),"Cloud observations lookup failed");
            rows=mapper.readTree(result.body()).path("data");
            for(var row:rows) if("chat.request".equals(row.path("name").asText())) root=row;
            var arrivedStages=new HashSet<String>();
            for(var row:rows) if(Set.of("embedding","retrieval","rerank").contains(row.path("name").asText()))
                arrivedStages.add(row.path("name").asText());
            if(root!=null && (!search || arrivedStages.size()==3)) break;
            Thread.sleep(2000);
        }
        assertNotNull(root,"Chat root did not arrive in Cloud");
        String traceId=root.path("traceId").asText(), rootId=root.path("id").asText();
        var rootMetadata=metadata(root);
        assertEquals("FINISHED",rootMetadata.path("state").asText());
        assertTrue(rootMetadata.path("durable_committed").asBoolean());
        assertTrue(rootMetadata.path("network_terminal_delivered").asBoolean());
        assertEquals(requestId.toString(),rootMetadata.path("request_id").asText());
        assertEquals(search ? "SEARCH" : "DIRECT",rootMetadata.path("route").asText());
        int models=0, tools=0, answerTimestamps=0;
        String toolId=null;
        for(var row:rows) if("tool.knowledge_search".equals(row.path("name").asText())) toolId=row.path("id").asText();
        var stages=new HashSet<String>();
        var summaries=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            assertEquals(traceId,row.path("traceId").asText(),"Chat observations crossed traces");
            assertTrue(row.path("input").isNull() || row.path("input").isMissingNode() || row.path("input").asText().isEmpty());
            assertTrue(row.path("output").isNull() || row.path("output").isMissingNode() || row.path("output").asText().isEmpty());
            String name=row.path("name").asText();
            if("llm.round".equals(name)) {
                models++;
                assertEquals(rootId,row.path("parentObservationId").asText());
                assertEquals("GENERATION",row.path("type").asText());
                if(!row.path("completionStartTime").isNull() && !row.path("completionStartTime").isMissingNode()) {
                    answerTimestamps++;
                    assertTrue(row.path("timeToFirstToken").isNumber(),"Native TTFT missing");
                    assertTrue(row.path("timeToFirstToken").asDouble() >= 0);
                }
            } else if("tool.knowledge_search".equals(name)) {
                tools++;
                assertEquals(rootId,row.path("parentObservationId").asText());
                assertTrue(Set.of("success","skipped","fallback").contains(metadata(row).path("rerank_status").asText()));
            } else if(Set.of("embedding","retrieval","rerank").contains(name)) {
                stages.add(name);
                assertNotNull(toolId,"Search stage has no tool parent");
                assertEquals(toolId,row.path("parentObservationId").asText());
                assertEquals(session,row.path("sessionId").asText());
                assertEquals(requestId.toString(),metadata(row).path("request_id").asText());
                assertTrue(row.path("latency").isNumber(),"Search stage duration missing");
                assertTrue(row.path("latency").asDouble()>=0);
            }
            var item=new LinkedHashMap<String,Object>(); item.put("name",name); item.put("type",row.path("type").asText());
            if(row.path("latency").isNumber()) item.put("durationSeconds",row.path("latency").asDouble());
            if(row.path("timeToFirstToken").isNumber()) item.put("modelTtftSeconds",row.path("timeToFirstToken").asDouble());
            if("tool.knowledge_search".equals(name)) item.put("rerankStatus",metadata(row).path("rerank_status").asText());
            summaries.add(item);
        }
        assertTrue(models >= (search ? 2 : 1));
        assertTrue(answerTimestamps > 0);
        assertEquals(search, tools > 0);
        assertEquals(search ? Set.of("embedding","retrieval","rerank") : Set.of(),stages);
        String projectId=root.path("projectId").asText();
        return Map.of("requestId",requestId.toString(),"traceId",traceId,"scenario",search ? "search" : "direct",
                "traceUrl",props.getBaseUrl()+"/project/"+projectId+"/traces/"+traceId,
                "verified",true,"observations",summaries);
    }

    Conversation createIsolatedConversation(String username) {
        var conversation = new Conversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setUser(users.findByUsername(username).orElseThrow());
        conversation.setTitle("Langfuse smoke");
        conversation.setMessages("[]");
        // Chat uses an explicit conversation ID; never switch the user's pointer or rebuild old memory.
        return conversationRepository.save(conversation);
    }

    private HttpClient cloudClient() {
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        String proxyUrl=System.getenv("HTTPS_PROXY");
        if(proxyUrl==null || proxyUrl.isBlank()) proxyUrl=System.getenv("HTTP_PROXY");
        var proxy=new LangfuseConfiguration().proxyFor(URI.create(props.getBaseUrl()),proxyUrl);
        if(proxy!=null) builder.proxy(ProxySelector.of((InetSocketAddress)proxy.address()));
        return builder.build();
    }
    private JsonNode metadata(JsonNode row) throws Exception {
        var value=row.path("metadata");
        return value.isTextual() ? mapper.readTree(value.asText()) : value;
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
