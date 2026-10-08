package com.novelforge.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel;
import com.novelforge.novel.WordCounter;
import com.novelforge.canon.FormalMemoryStore;
import com.novelforge.projection.ConfirmedTextProjectionStore;
import com.novelforge.projection.Neo4jProjectionSourceStore;
import com.novelforge.shared.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transitional relational projection for the legacy Novel aggregate.
 *
 * <p>The projection is written in the same database transaction as the legacy
 * document. It deliberately remains a projection until row-level reads and
 * updates have passed migration verification.</p>
 */
@Component
public class NormalizedNovelProjection {
    public record ProjectionCheck(Map<String,Integer> counts,long approvedWordCount) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WordCounter words;
    private final FormalMemoryStore formalMemory;
    private final ConfirmedTextProjectionStore confirmedTextProjection;
    private final NormalizedIncrementalWriter incrementalWriter;
    private final Neo4jProjectionSourceStore neo4jProjectionSource;
    private final boolean enabled;

    public NormalizedNovelProjection(JdbcTemplate jdbc, ObjectMapper mapper, WordCounter words,FormalMemoryStore formalMemory,
                                     ConfirmedTextProjectionStore confirmedTextProjection,
                                     NormalizedIncrementalWriter incrementalWriter,
                                     Neo4jProjectionSourceStore neo4jProjectionSource,
                                     @Value("${novelforge.storage.normalized-shadow-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.words = words;
        this.formalMemory=formalMemory;
        this.confirmedTextProjection=confirmedTextProjection;
        this.incrementalWriter=incrementalWriter;
        this.neo4jProjectionSource=neo4jProjectionSource;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Novel> list() {
        return jdbc.query("SELECT id FROM novel_project ORDER BY created_at DESC",(rs,index)->rs.getString(1))
                .stream().map(id->load(id,false)).toList();
    }

    public Novel load(String id,boolean lock) {
        List<Novel> projects=jdbc.query("SELECT * FROM novel_project WHERE id=?"+(lock?" FOR UPDATE":""),(rs,index)->{
            Novel novel=new Novel(); novel.id=rs.getString("id"); novel.ownerId=rs.getString("owner_id");
            novel.title=rs.getString("title"); novel.synopsis=rs.getString("synopsis"); novel.requirements=rs.getString("requirements");
            novel.targetWords=rs.getLong("target_words"); novel.approvedMaxWords=rs.getLong("approved_max_words");
            Object autoStyle=rs.getObject("auto_style_enabled"); novel.autoStyleEnabled=autoStyle==null?null:rs.getBoolean("auto_style_enabled");
            novel.revision=rs.getLong("revision"); novel.status=rs.getString("status"); novel.createdAt=rs.getString("created_at");
            return novel;
        },id);
        if(projects.isEmpty()) throw new Problem(404,"小说不存在");
        Novel novel=projects.getFirst();
        novel.artifacts.addAll(jdbc.query("SELECT * FROM artifact WHERE novel_id=? ORDER BY ordinal_no",(rs,index)->{
            Novel.Artifact artifact=new Novel.Artifact(); artifact.id=rs.getString("id");
            artifact.kind=Novel.Kind.valueOf(rs.getString("kind")); artifact.chapterNumber=rs.getInt("chapter_number");
            artifact.batchNumber=rs.getInt("batch_number"); artifact.approvedVersionId=rs.getString("approved_version_id");
            artifact.needsRevision=rs.getBoolean("needs_revision");
            artifact.versions.addAll(jdbc.query("SELECT * FROM artifact_version WHERE artifact_id=? ORDER BY ordinal_no",(vr,vi)->{
                ObjectNode node=objectNode(vr.getString("metadata_json"));
                put(node,"id",vr.getString("id")); put(node,"baseVersionId",vr.getString("base_version_id"));
                put(node,"source",vr.getString("source")); put(node,"title",vr.getString("title"));
                put(node,"content",vr.getString("content")); put(node,"summary",vr.getString("summary"));
                node.put("basedOnRevision",vr.getLong("based_on_revision")); node.put("dismissed",vr.getBoolean("dismissed"));
                put(node,"createdAt",vr.getString("created_at"));
                return tree(node,Novel.Version.class);
            },artifact.id));
            return artifact;
        },id));
        novel.tasks.addAll(readRows("generation_task",id,Novel.Task.class));
        novel.sourceSnapshots.addAll(jdbc.query("SELECT context_json,payload_json FROM source_snapshot WHERE novel_id=? ORDER BY ordinal_no",(rs,index)->{
            ObjectNode node=objectNode(rs.getString("payload_json")); put(node,"contextJson",rs.getString("context_json"));
            return tree(node,Novel.SourceSnapshot.class);
        },id));
        novel.agentRuns.addAll(readRows("agent_run",id,Novel.AgentRun.class));
        novel.shadowReviews.addAll(readRows("shadow_review",id,Novel.ShadowReview.class));
        novel.professionalReviewReplays.addAll(jdbc.query("SELECT id,payload_json FROM professional_replay_batch WHERE novel_id=? ORDER BY ordinal_no",(rs,index)->{
            Novel.ProfessionalReviewReplayBatch batch=read(rs.getString("payload_json"),Novel.ProfessionalReviewReplayBatch.class);
            batch.items.addAll(readRowsByParent("professional_replay_item","batch_id",rs.getString("id"),Novel.ProfessionalReviewReplayItem.class));
            return batch;
        },id));
        novel.outlinePipelines.addAll(readRows("outline_pipeline_workspace",id,Novel.OutlinePipelineWorkspace.class));
        novel.conversationSessions.addAll(jdbc.query("SELECT id,payload_json FROM conversation_session WHERE novel_id=? ORDER BY ordinal_no",(rs,index)->{
            String sessionId=rs.getString("id");
            Novel.ConversationSession session=read(rs.getString("payload_json"),Novel.ConversationSession.class);
            session.messages.addAll(jdbc.query("SELECT * FROM conversation_message WHERE session_id=? ORDER BY ordinal_no",(mr,mi)->{
                Novel.ConversationMessage message=new Novel.ConversationMessage(); message.id=mr.getString("id");
                message.role=Novel.ConversationRole.valueOf(mr.getString("role")); message.content=mr.getString("content");
                message.createdAt=mr.getString("created_at"); return message;
            },sessionId));
            session.turns.addAll(readRowsByParent("conversation_turn","session_id",sessionId,Novel.ConversationTurn.class));
            session.decisions.addAll(readRowsByParent("conversation_decision","session_id",sessionId,Novel.ConversationDecision.class));
            session.proposals.addAll(readRowsByParent("action_proposal","session_id",sessionId,Novel.ActionProposal.class));
            session.projectUpdates.addAll(readRowsByParent("project_update_proposal","session_id",sessionId,Novel.ProjectUpdateProposal.class));
            return session;
        },id));
        novel.conversationBriefs.addAll(readRows("conversation_brief",id,Novel.ConversationBrief.class));
        novel.projectBriefChanges.addAll(readRows("project_brief_change",id,Novel.ProjectBriefChange.class));
        novel.changes.addAll(readRows("change_request",id,Novel.Change.class));
        novel.approvals.addAll(readRows("approval",id,Novel.Approval.class));
        novel.budgetChanges.addAll(readRows("budget_change",id,Novel.BudgetChange.class));
        novel.completionChecks.addAll(readRows("completion_check",id,Novel.Completion.class));
        return novel;
    }

    private <T> List<T> readRows(String table,String novelId,Class<T> type) {
        return jdbc.query("SELECT payload_json FROM "+table+" WHERE novel_id=? ORDER BY ordinal_no",
                (rs,index)->read(rs.getString(1),type),novelId);
    }
    private <T> List<T> readRowsByParent(String table,String parentColumn,String parentId,Class<T> type) {
        return jdbc.query("SELECT payload_json FROM "+table+" WHERE "+parentColumn+"=? ORDER BY ordinal_no",
                (rs,index)->read(rs.getString(1),type),parentId);
    }

    public void replace(Novel novel) {
        if (!enabled) return;
        String projectHash = hash(json(Map.ofEntries(
                Map.entry("id", value(novel.id)), Map.entry("ownerId", value(novel.ownerId)),
                Map.entry("title", value(novel.title)), Map.entry("synopsis", value(novel.synopsis)),
                Map.entry("requirements", value(novel.requirements)), Map.entry("targetWords", novel.targetWords),
                Map.entry("approvedMaxWords", novel.approvedMaxWords),
                Map.entry("autoStyleEnabled", novel.autoStyleEnabled == null ? "" : novel.autoStyleEnabled),
                Map.entry("revision", novel.revision), Map.entry("status", value(novel.status)),
                Map.entry("createdAt", value(novel.createdAt)))));
        int updated=jdbc.update("""
                UPDATE novel_project SET owner_id=?,title=?,synopsis=?,requirements=?,target_words=?,approved_max_words=?,
                  auto_style_enabled=?,revision=?,status=?,created_at=?,record_hash=? WHERE id=?
                """,value(novel.ownerId),value(novel.title),value(novel.synopsis),value(novel.requirements),
                novel.targetWords,novel.approvedMaxWords,novel.autoStyleEnabled,novel.revision,value(novel.status),
                value(novel.createdAt),projectHash,novel.id);
        if(updated==0) jdbc.update("""
                INSERT INTO novel_project(id,owner_id,title,synopsis,requirements,target_words,approved_max_words,
                  auto_style_enabled,revision,status,created_at,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,novel.id,value(novel.ownerId),value(novel.title),value(novel.synopsis),value(novel.requirements),
                novel.targetWords,novel.approvedMaxWords,novel.autoStyleEnabled,novel.revision,value(novel.status),
                value(novel.createdAt),projectHash);
        incrementalWriter.sync(novel);
        verify(novel);
        formalMemory.synchronize(novel);
        confirmedTextProjection.synchronize(novel);
        neo4jProjectionSource.synchronize(novel.id);
    }

    public ProjectionCheck verify(Novel novel) {
        if (!enabled) throw new IllegalStateException("拆分存储投影未启用");
        Map<String,Object> project=jdbc.queryForMap("SELECT revision,title,record_hash FROM novel_project WHERE id=?",novel.id);
        if(((Number)project.get("revision")).longValue()!=novel.revision || !value(novel.title).equals(project.get("title"))) {
            throw new IllegalStateException("拆分存储项目字段校验失败");
        }
        Map<String,Integer> expected=new LinkedHashMap<>();
        expected.put("artifact",novel.artifacts.size());
        expected.put("artifact_version",novel.artifacts.stream().mapToInt(a->a.versions.size()).sum());
        expected.put("generation_task",novel.tasks.size()); expected.put("source_snapshot",novel.sourceSnapshots.size());
        expected.put("agent_run",novel.agentRuns.size()); expected.put("shadow_review",novel.shadowReviews.size());
        expected.put("professional_replay_batch",novel.professionalReviewReplays.size());
        expected.put("professional_replay_item",novel.professionalReviewReplays.stream().mapToInt(b->b.items.size()).sum());
        expected.put("outline_pipeline_workspace",novel.outlinePipelines.size());
        expected.put("conversation_session",novel.conversationSessions.size());
        expected.put("conversation_message",novel.conversationSessions.stream().mapToInt(s->s.messages.size()).sum());
        expected.put("conversation_turn",novel.conversationSessions.stream().mapToInt(s->s.turns.size()).sum());
        expected.put("conversation_decision",novel.conversationSessions.stream().mapToInt(s->s.decisions.size()).sum());
        expected.put("action_proposal",novel.conversationSessions.stream().mapToInt(s->s.proposals.size()).sum());
        expected.put("project_update_proposal",novel.conversationSessions.stream().mapToInt(s->s.projectUpdates.size()).sum());
        expected.put("conversation_brief",novel.conversationBriefs.size());
        expected.put("project_brief_change",novel.projectBriefChanges.size()); expected.put("change_request",novel.changes.size());
        expected.put("approval",novel.approvals.size()); expected.put("budget_change",novel.budgetChanges.size());
        expected.put("completion_check",novel.completionChecks.size());
        for(Map.Entry<String,Integer> entry:expected.entrySet()) {
            Integer actual=jdbc.queryForObject("SELECT COUNT(*) FROM "+entry.getKey()+" WHERE novel_id=?",Integer.class,novel.id);
            if(actual==null || actual.intValue()!=entry.getValue().intValue()) {
                throw new IllegalStateException("拆分存储行数校验失败："+entry.getKey()
                        +"，期望="+entry.getValue()+"，实际="+actual+"，小说="+novel.id);
            }
        }
        for(Novel.Artifact artifact:novel.artifacts) for(Novel.Version version:artifact.versions) {
            String actual=jdbc.queryForObject("SELECT content_hash FROM artifact_version WHERE id=? AND novel_id=?",String.class,version.id,novel.id);
            if(!hash(value(version.content)).equals(actual)) throw new IllegalStateException("拆分存储内容哈希校验失败："+version.id);
        }
        return new ProjectionCheck(Map.copyOf(expected),words.approvedWords(novel));
    }

    private Map<String,Object> objectMap(Object value) {
        return mapper.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});
    }
    private ObjectNode objectNode(String value) {
        try { return (ObjectNode)mapper.readTree(value); }
        catch(Exception e) { throw new IllegalStateException("无法读取拆分存储元数据",e); }
    }
    private void put(ObjectNode node,String field,String value) {
        if(value==null) node.putNull(field); else node.put(field,value);
    }
    private <T> T tree(ObjectNode node,Class<T> type) {
        try { return mapper.treeToValue(node,type); }
        catch(Exception e) { throw new IllegalStateException("无法重建拆分存储记录",e); }
    }
    private <T> T read(String value,Class<T> type) {
        try { return mapper.readValue(value,type); }
        catch(Exception e) { throw new IllegalStateException("无法读取拆分存储记录",e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化拆分存储记录",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String name(Enum<?> value) { return value==null?null:value.name(); }
    private String value(String value) { return value==null?"":value; }
}
