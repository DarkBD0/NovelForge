package com.novelforge.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.WordCounter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Incrementally synchronizes mutable aggregate rows without rewriting unrelated history. */
@Component
public class NormalizedIncrementalWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WordCounter words;

    public NormalizedIncrementalWriter(JdbcTemplate jdbc,ObjectMapper mapper,WordCounter words) {
        this.jdbc=jdbc; this.mapper=mapper; this.words=words;
    }

    public void sync(Novel novel) {
        syncArtifacts(novel);
        syncTasks(novel); syncSnapshots(novel); syncAgentRuns(novel); syncShadowReviews(novel);
        syncProfessionalReplays(novel); syncOutlinePipelines(novel); syncConversations(novel);
        syncConversationBriefs(novel); syncProjectBriefChanges(novel); syncChangeRequests(novel);
        syncApprovals(novel); syncBudgetChanges(novel); syncCompletionChecks(novel);
    }

    private void syncArtifacts(Novel novel) {
        Set<String> artifactIds=new HashSet<>(); Set<String> versionIds=new HashSet<>();
        prepareOrdinalReorder("artifact","novel_id",novel.id,novel.artifacts.stream().map(item->item.id).toList());
        for(int ai=0;ai<novel.artifacts.size();ai++) {
            Novel.Artifact artifact=novel.artifacts.get(ai); artifactIds.add(artifact.id);
            String artifactHash=hash(ai+"\u0000"+json(Map.of(
                    "kind",name(artifact.kind),"chapter",artifact.chapterNumber,"batch",artifact.batchNumber,
                    "approved",value(artifact.approvedVersionId),"needsRevision",artifact.needsRevision)));
            write("artifact",novel.id,artifact.id,artifactHash,
                    "UPDATE artifact SET ordinal_no=?,kind=?,chapter_number=?,batch_number=?,approved_version_id=?,needs_revision=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(ai,name(artifact.kind),artifact.chapterNumber,artifact.batchNumber,artifact.approvedVersionId,artifact.needsRevision,artifactHash,artifact.id,novel.id),
                    "INSERT INTO artifact(id,novel_id,ordinal_no,kind,chapter_number,batch_number,approved_version_id,needs_revision,record_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                    args(artifact.id,novel.id,ai,name(artifact.kind),artifact.chapterNumber,artifact.batchNumber,artifact.approvedVersionId,artifact.needsRevision,artifactHash));
            prepareOrdinalReorder("artifact_version","artifact_id",artifact.id,artifact.versions.stream().map(item->item.id).toList());
            for(int vi=0;vi<artifact.versions.size();vi++) {
                Novel.Version version=artifact.versions.get(vi); versionIds.add(version.id);
                Map<String,Object> metadata=objectMap(version);
                metadata.remove("id"); metadata.remove("baseVersionId"); metadata.remove("source"); metadata.remove("title");
                metadata.remove("content"); metadata.remove("summary"); metadata.remove("basedOnRevision");
                metadata.remove("dismissed"); metadata.remove("createdAt");
                String metadataJson=json(metadata); String content=value(version.content); String contentHash=hash(content);
                String recordHash=hash(artifact.id+"\u0000"+vi+"\u0000"+json(version));
                write("artifact_version",novel.id,version.id,recordHash,
                        "UPDATE artifact_version SET artifact_id=?,ordinal_no=?,base_version_id=?,source=?,title=?,content=?,summary=?,metadata_json=?,content_hash=?,word_count=?,based_on_revision=?,dismissed=?,created_at=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(artifact.id,vi,version.baseVersionId,version.source,version.title,content,value(version.summary),metadataJson,
                                contentHash,words.count(content),version.basedOnRevision,version.dismissed,value(version.createdAt),recordHash,version.id,novel.id),
                        "INSERT INTO artifact_version(id,novel_id,artifact_id,ordinal_no,base_version_id,source,title,content,summary,metadata_json,content_hash,word_count,based_on_revision,dismissed,created_at,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        args(version.id,novel.id,artifact.id,vi,version.baseVersionId,version.source,version.title,content,
                                value(version.summary),metadataJson,contentHash,words.count(content),version.basedOnRevision,
                                version.dismissed,value(version.createdAt),recordHash));
            }
        }
        deleteMissing("artifact_version",novel.id,versionIds);
        deleteMissing("artifact",novel.id,artifactIds);
    }

    private void syncTasks(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.tasks.size();i++) {
            Novel.Task item=novel.tasks.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("generation_task",novel.id,item.id,recordHash,
                    "UPDATE generation_task SET ordinal_no=?,action=?,status=?,artifact_id=?,request_key=?,created_at=?,finished_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,name(item.action),name(item.status),item.artifactId,item.requestKey,item.createdAt,item.finishedAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO generation_task(id,novel_id,ordinal_no,action,status,artifact_id,request_key,created_at,finished_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,name(item.action),name(item.status),item.artifactId,item.requestKey,item.createdAt,item.finishedAt,payload,recordHash));
        }
        deleteMissing("generation_task",novel.id,ids);
    }

    private void syncSnapshots(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.sourceSnapshots.size();i++) {
            Novel.SourceSnapshot item=novel.sourceSnapshots.get(i); ids.add(item.id);
            Map<String,Object> metadata=objectMap(item); metadata.remove("contextJson"); String payload=json(metadata); String recordHash=rowHash(i,json(item));
            write("source_snapshot",novel.id,item.id,recordHash,
                    "UPDATE source_snapshot SET ordinal_no=?,task_id=?,context_hash=?,context_json=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.taskId,item.contextHash,value(item.contextJson),item.createdAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO source_snapshot(id,novel_id,ordinal_no,task_id,context_hash,context_json,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.taskId,item.contextHash,value(item.contextJson),item.createdAt,payload,recordHash));
        }
        deleteMissing("source_snapshot",novel.id,ids);
    }

    private void syncAgentRuns(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.agentRuns.size();i++) {
            Novel.AgentRun item=novel.agentRuns.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("agent_run",novel.id,item.id,recordHash,
                    "UPDATE agent_run SET ordinal_no=?,task_id=?,source_snapshot_id=?,role=?,operation=?,status=?,started_at=?,finished_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.taskId,item.sourceSnapshotId,item.role,item.operation,name(item.status),item.startedAt,item.finishedAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO agent_run(id,novel_id,ordinal_no,task_id,source_snapshot_id,role,operation,status,started_at,finished_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.taskId,item.sourceSnapshotId,item.role,item.operation,name(item.status),item.startedAt,item.finishedAt,payload,recordHash));
        }
        deleteMissing("agent_run",novel.id,ids);
    }

    private void syncShadowReviews(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.shadowReviews.size();i++) {
            Novel.ShadowReview item=novel.shadowReviews.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("shadow_review",novel.id,item.id,recordHash,
                    "UPDATE shadow_review SET ordinal_no=?,artifact_id=?,version_id=?,checker=?,status=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.artifactId,item.versionId,item.checker,name(item.status),item.createdAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO shadow_review(id,novel_id,ordinal_no,artifact_id,version_id,checker,status,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.artifactId,item.versionId,item.checker,name(item.status),item.createdAt,payload,recordHash));
        }
        deleteMissing("shadow_review",novel.id,ids);
    }

    private void syncProfessionalReplays(Novel novel) {
        Set<String> batchIds=new HashSet<>(); Set<String> itemIds=new HashSet<>();
        for(int i=0;i<novel.professionalReviewReplays.size();i++) {
            Novel.ProfessionalReviewReplayBatch batch=novel.professionalReviewReplays.get(i); batchIds.add(batch.id);
            Map<String,Object> metadata=objectMap(batch); metadata.remove("items"); String payload=json(metadata); String recordHash=rowHash(i,json(batch));
            write("professional_replay_batch",novel.id,batch.id,recordHash,
                    "UPDATE professional_replay_batch SET ordinal_no=?,status=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,name(batch.status),batch.createdAt,payload,recordHash,batch.id,novel.id),
                    "INSERT INTO professional_replay_batch(id,novel_id,ordinal_no,status,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?)",
                    args(batch.id,novel.id,i,name(batch.status),batch.createdAt,payload,recordHash));
            for(int j=0;j<batch.items.size();j++) {
                Novel.ProfessionalReviewReplayItem item=batch.items.get(j); itemIds.add(item.id);
                String itemPayload=json(item); String itemHash=hash(batch.id+"\u0000"+j+"\u0000"+itemPayload);
                write("professional_replay_item",novel.id,item.id,itemHash,
                        "UPDATE professional_replay_item SET batch_id=?,ordinal_no=?,status=?,checker=?,artifact_id=?,version_id=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(batch.id,j,name(item.status),item.checker,item.artifactId,item.versionId,itemPayload,itemHash,item.id,novel.id),
                        "INSERT INTO professional_replay_item(id,novel_id,batch_id,ordinal_no,status,checker,artifact_id,version_id,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,batch.id,j,name(item.status),item.checker,item.artifactId,item.versionId,itemPayload,itemHash));
            }
        }
        deleteMissing("professional_replay_item",novel.id,itemIds);
        deleteMissing("professional_replay_batch",novel.id,batchIds);
    }

    private void syncOutlinePipelines(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.outlinePipelines.size();i++) {
            Novel.OutlinePipelineWorkspace item=novel.outlinePipelines.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("outline_pipeline_workspace",novel.id,item.id,recordHash,
                    "UPDATE outline_pipeline_workspace SET ordinal_no=?,task_id=?,status=?,current_step=?,created_at=?,finished_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.taskId,name(item.status),item.currentStep,item.createdAt,item.finishedAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO outline_pipeline_workspace(id,novel_id,ordinal_no,task_id,status,current_step,created_at,finished_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.taskId,name(item.status),item.currentStep,item.createdAt,item.finishedAt,payload,recordHash));
        }
        deleteMissing("outline_pipeline_workspace",novel.id,ids);
    }

    private void syncConversations(Novel novel) {
        Set<String> sessionIds=new HashSet<>(),messageIds=new HashSet<>(),turnIds=new HashSet<>(),decisionIds=new HashSet<>(),proposalIds=new HashSet<>(),updateIds=new HashSet<>();
        for(int ordinal=0;ordinal<novel.conversationSessions.size();ordinal++) {
            Novel.ConversationSession session=novel.conversationSessions.get(ordinal); sessionIds.add(session.id);
            Map<String,Object> metadata=objectMap(session); metadata.remove("messages"); metadata.remove("turns"); metadata.remove("decisions"); metadata.remove("proposals"); metadata.remove("projectUpdates");
            String payload=json(metadata); String recordHash=rowHash(ordinal,payload);
            write("conversation_session",novel.id,session.id,recordHash,
                    "UPDATE conversation_session SET ordinal_no=?,thread_id=?,scope=?,target_artifact_id=?,base_version_id=?,base_revision=?,created_at=?,updated_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(ordinal,session.threadId,name(session.scope),session.targetArtifactId,session.baseVersionId,session.baseRevision,session.createdAt,session.updatedAt,payload,recordHash,session.id,novel.id),
                    "INSERT INTO conversation_session(id,novel_id,ordinal_no,thread_id,scope,target_artifact_id,base_version_id,base_revision,created_at,updated_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    args(session.id,novel.id,ordinal,session.threadId,name(session.scope),session.targetArtifactId,session.baseVersionId,session.baseRevision,session.createdAt,session.updatedAt,payload,recordHash));
            for(int i=0;i<session.messages.size();i++) {
                Novel.ConversationMessage item=session.messages.get(i); messageIds.add(item.id); String itemHash=hash(session.id+"\u0000"+i+"\u0000"+json(item));
                write("conversation_message",novel.id,item.id,itemHash,
                        "UPDATE conversation_message SET session_id=?,ordinal_no=?,role=?,content=?,created_at=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(session.id,i,name(item.role),value(item.content),item.createdAt,itemHash,item.id,novel.id),
                        "INSERT INTO conversation_message(id,novel_id,session_id,ordinal_no,role,content,created_at,record_hash) VALUES(?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,session.id,i,name(item.role),value(item.content),item.createdAt,itemHash));
            }
            for(int i=0;i<session.turns.size();i++) {
                Novel.ConversationTurn item=session.turns.get(i); turnIds.add(item.id); String itemPayload=json(item); String itemHash=hash(session.id+"\u0000"+i+"\u0000"+itemPayload);
                write("conversation_turn",novel.id,item.id,itemHash,
                        "UPDATE conversation_turn SET session_id=?,ordinal_no=?,status=?,stage=?,request_key=?,started_at=?,finished_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(session.id,i,name(item.status),item.stage,item.requestKey,item.startedAt,item.finishedAt,itemPayload,itemHash,item.id,novel.id),
                        "INSERT INTO conversation_turn(id,novel_id,session_id,ordinal_no,status,stage,request_key,started_at,finished_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,session.id,i,name(item.status),item.stage,item.requestKey,item.startedAt,item.finishedAt,itemPayload,itemHash));
            }
            for(int i=0;i<session.decisions.size();i++) {
                Novel.ConversationDecision item=session.decisions.get(i); decisionIds.add(item.id); String itemPayload=json(item); String itemHash=hash(session.id+"\u0000"+i+"\u0000"+itemPayload);
                write("conversation_decision",novel.id,item.id,itemHash,
                        "UPDATE conversation_decision SET session_id=?,ordinal_no=?,decision_type=?,status=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(session.id,i,name(item.type),name(item.status),itemPayload,itemHash,item.id,novel.id),
                        "INSERT INTO conversation_decision(id,novel_id,session_id,ordinal_no,decision_type,status,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,session.id,i,name(item.type),name(item.status),itemPayload,itemHash));
            }
            for(int i=0;i<session.proposals.size();i++) {
                Novel.ActionProposal item=session.proposals.get(i); proposalIds.add(item.id); String itemPayload=json(item); String itemHash=hash(session.id+"\u0000"+i+"\u0000"+itemPayload);
                write("action_proposal",novel.id,item.id,itemHash,
                        "UPDATE action_proposal SET session_id=?,ordinal_no=?,operation=?,status=?,base_revision=?,task_id=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(session.id,i,item.operation,name(item.status),item.baseRevision,item.taskId,itemPayload,itemHash,item.id,novel.id),
                        "INSERT INTO action_proposal(id,novel_id,session_id,ordinal_no,operation,status,base_revision,task_id,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,session.id,i,item.operation,name(item.status),item.baseRevision,item.taskId,itemPayload,itemHash));
            }
            for(int i=0;i<session.projectUpdates.size();i++) {
                Novel.ProjectUpdateProposal item=session.projectUpdates.get(i); updateIds.add(item.id); String itemPayload=json(item); String itemHash=hash(session.id+"\u0000"+i+"\u0000"+itemPayload);
                write("project_update_proposal",novel.id,item.id,itemHash,
                        "UPDATE project_update_proposal SET session_id=?,ordinal_no=?,project_field=?,status=?,base_revision=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                        args(session.id,i,name(item.field),name(item.status),item.baseRevision,itemPayload,itemHash,item.id,novel.id),
                        "INSERT INTO project_update_proposal(id,novel_id,session_id,ordinal_no,project_field,status,base_revision,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                        args(item.id,novel.id,session.id,i,name(item.field),name(item.status),item.baseRevision,itemPayload,itemHash));
            }
        }
        deleteMissing("conversation_message",novel.id,messageIds); deleteMissing("conversation_turn",novel.id,turnIds);
        deleteMissing("conversation_decision",novel.id,decisionIds); deleteMissing("action_proposal",novel.id,proposalIds);
        deleteMissing("project_update_proposal",novel.id,updateIds); deleteMissing("conversation_session",novel.id,sessionIds);
    }

    private void syncConversationBriefs(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.conversationBriefs.size();i++) {
            Novel.ConversationBrief item=novel.conversationBriefs.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("conversation_brief",novel.id,item.id,recordHash,
                    "UPDATE conversation_brief SET ordinal_no=?,session_id=?,scope=?,target_artifact_id=?,base_version_id=?,base_revision=?,hash=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.sessionId,name(item.scope),item.targetArtifactId,item.baseVersionId,item.baseRevision,item.hash,item.createdAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO conversation_brief(id,novel_id,ordinal_no,session_id,scope,target_artifact_id,base_version_id,base_revision,hash,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.sessionId,name(item.scope),item.targetArtifactId,item.baseVersionId,item.baseRevision,item.hash,item.createdAt,payload,recordHash));
        }
        deleteMissing("conversation_brief",novel.id,ids);
    }

    private void syncProjectBriefChanges(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.projectBriefChanges.size();i++) {
            Novel.ProjectBriefChange item=novel.projectBriefChanges.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("project_brief_change",novel.id,item.id,recordHash,
                    "UPDATE project_brief_change SET ordinal_no=?,previous_revision=?,new_revision=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.previousRevision,item.newRevision,item.createdAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO project_brief_change(id,novel_id,ordinal_no,previous_revision,new_revision,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.previousRevision,item.newRevision,item.createdAt,payload,recordHash));
        }
        deleteMissing("project_brief_change",novel.id,ids);
    }

    private void syncChangeRequests(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.changes.size();i++) {
            Novel.Change item=novel.changes.get(i); ids.add(item.id); String payload=json(item); String recordHash=rowHash(i,payload);
            write("change_request",novel.id,item.id,recordHash,
                    "UPDATE change_request SET ordinal_no=?,source_artifact_id=?,created_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.sourceArtifactId,item.createdAt,payload,recordHash,item.id,novel.id),
                    "INSERT INTO change_request(id,novel_id,ordinal_no,source_artifact_id,created_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?)",
                    args(item.id,novel.id,i,item.sourceArtifactId,item.createdAt,payload,recordHash));
        }
        deleteMissing("change_request",novel.id,ids);
    }

    private void syncApprovals(Novel novel) {
        for(int i=0;i<novel.approvals.size();i++) {
            Novel.Approval item=novel.approvals.get(i); String payload=json(item); String recordHash=rowHash(i,payload);
            writeOrdinal("approval",novel.id,i,recordHash,
                    "UPDATE approval SET artifact_id=?,version_id=?,revision=?,approved_at=?,payload_json=?,record_hash=? WHERE novel_id=? AND ordinal_no=?",
                    args(item.artifactId(),item.versionId(),item.revision(),item.time(),payload,recordHash,novel.id,i),
                    "INSERT INTO approval(novel_id,ordinal_no,artifact_id,version_id,revision,approved_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?)",
                    args(novel.id,i,item.artifactId(),item.versionId(),item.revision(),item.time(),payload,recordHash));
        }
        jdbc.update("DELETE FROM approval WHERE novel_id=? AND ordinal_no>=?",novel.id,novel.approvals.size());
    }

    private void syncBudgetChanges(Novel novel) {
        for(int i=0;i<novel.budgetChanges.size();i++) {
            Novel.BudgetChange item=novel.budgetChanges.get(i); String payload=json(item); String recordHash=rowHash(i,payload);
            writeOrdinal("budget_change",novel.id,i,recordHash,
                    "UPDATE budget_change SET changed_at=?,payload_json=?,record_hash=? WHERE novel_id=? AND ordinal_no=?",
                    args(item.time(),payload,recordHash,novel.id,i),
                    "INSERT INTO budget_change(novel_id,ordinal_no,changed_at,payload_json,record_hash) VALUES(?,?,?,?,?)",
                    args(novel.id,i,item.time(),payload,recordHash));
        }
        jdbc.update("DELETE FROM budget_change WHERE novel_id=? AND ordinal_no>=?",novel.id,novel.budgetChanges.size());
    }

    private void syncCompletionChecks(Novel novel) {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<novel.completionChecks.size();i++) {
            Novel.Completion item=novel.completionChecks.get(i); ids.add(item.id()); String payload=json(item); String recordHash=rowHash(i,payload);
            write("completion_check",novel.id,item.id(),recordHash,
                    "UPDATE completion_check SET ordinal_no=?,revision=?,word_count=?,checked_at=?,payload_json=?,record_hash=? WHERE id=? AND novel_id=?",
                    args(i,item.revision(),item.wordCount(),item.time(),payload,recordHash,item.id(),novel.id),
                    "INSERT INTO completion_check(id,novel_id,ordinal_no,revision,word_count,checked_at,payload_json,record_hash) VALUES(?,?,?,?,?,?,?,?)",
                    args(item.id(),novel.id,i,item.revision(),item.wordCount(),item.time(),payload,recordHash));
        }
        deleteMissing("completion_check",novel.id,ids);
    }

    private void write(String table,String novelId,String id,String recordHash,String updateSql,Object[] updateArgs,
                       String insertSql,Object[] insertArgs) {
        List<String> existing=jdbc.query("SELECT record_hash FROM "+table+" WHERE id=? AND novel_id=?",
                (rs,index)->rs.getString(1),id,novelId);
        if(existing.isEmpty()) jdbc.update(insertSql,insertArgs);
        else if(!Objects.equals(existing.getFirst(),recordHash)) jdbc.update(updateSql,updateArgs);
    }

    private void writeOrdinal(String table,String novelId,int ordinal,String recordHash,String updateSql,Object[] updateArgs,
                              String insertSql,Object[] insertArgs) {
        List<String> existing=jdbc.query("SELECT record_hash FROM "+table+" WHERE novel_id=? AND ordinal_no=?",
                (rs,index)->rs.getString(1),novelId,ordinal);
        if(existing.isEmpty()) jdbc.update(insertSql,insertArgs);
        else if(!Objects.equals(existing.getFirst(),recordHash)) jdbc.update(updateSql,updateArgs);
    }

    private void deleteMissing(String table,String novelId,Set<String> expected) {
        List<String> existing=jdbc.query("SELECT id FROM "+table+" WHERE novel_id=?",(rs,index)->rs.getString(1),novelId);
        for(String id:existing) if(!expected.contains(id)) jdbc.update("DELETE FROM "+table+" WHERE id=? AND novel_id=?",id,novelId);
    }

    private void prepareOrdinalReorder(String table,String parentColumn,String parentId,List<String> expectedOrder) {
        Map<String,Integer> existing=new LinkedHashMap<>();
        jdbc.query("SELECT id,ordinal_no FROM "+table+" WHERE "+parentColumn+"=?",
                rs->{existing.put(rs.getString(1),rs.getInt(2));},parentId);
        boolean reordered=false;
        for(int i=0;i<expectedOrder.size();i++) {
            Integer actual=existing.get(expectedOrder.get(i));
            if(actual!=null && actual!=i) { reordered=true; break; }
        }
        if(reordered) jdbc.update("UPDATE "+table+" SET ordinal_no=ordinal_no+1000000,record_hash=NULL WHERE "+parentColumn+"=?",parentId);
    }

    private String rowHash(int ordinal,String payload) { return hash(ordinal+"\u0000"+payload); }
    private Object[] args(Object... values) { return values; }
    private Map<String,Object> objectMap(Object value) { return mapper.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法序列化增量存储记录",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String name(Enum<?> value) { return value==null?null:value.name(); }
    private String value(String value) { return value==null?"":value; }
}
