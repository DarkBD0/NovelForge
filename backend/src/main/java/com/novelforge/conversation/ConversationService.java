package com.novelforge.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.generation.AgentOrchestrator;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.RequirementAuthority;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.VisibleContentPolicy;
import com.novelforge.shared.Problem;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.novelforge.shared.Problem.require;

/** User-led outline discussion. It can prepare a formal task but cannot mutate content itself. */
@Service
public class ConversationService {
    private final NovelRepository repository;
    private final AgentOrchestrator agents;
    private final TaskService tasks;
    private final ObjectMapper mapper;
    private final WorkflowService workflow;
    private final ConversationEventHub events;
    private final RequirementAuthority requirementAuthority;
    private final ConcurrentMap<String,TurnControl> activeTurns=new ConcurrentHashMap<>();

    public ConversationService(NovelRepository repository,AgentOrchestrator agents,TaskService tasks,ObjectMapper mapper,
                               WorkflowService workflow,ConversationEventHub events,RequirementAuthority requirementAuthority) {
        this.repository=repository; this.agents=agents; this.tasks=tasks; this.mapper=mapper; this.workflow=workflow;
        this.events=events; this.requirementAuthority=requirementAuthority;
    }

    public List<ConversationSession> list(String novelId) {
        return List.copyOf(sessions(repository.get(novelId)));
    }

    public ConversationSession startOutline(String novelId,long revision) {
        return startOutline(novelId,revision,null,false);
    }

    public ConversationSession startOutline(String novelId,long revision,String requestedThreadId,boolean newThread) {
        return repository.update(novelId,n->{
            require(n.revision==revision,"小说版本已经变化，请刷新后重新开始对话");
            Artifact outline=currentOutline(n);
            String artifactId=outline==null?null:outline.id;
            String versionId=outline==null||outline.latest()==null?null:outline.latest().id;
            if (newThread) {
                ConversationSession fresh=new ConversationSession();
                fresh.threadId=Novel.uid(); fresh.threadTitle=nextThreadTitle(n);
                fresh.scope=ConversationScope.OUTLINE; fresh.baseRevision=n.revision;
                fresh.targetArtifactId=artifactId; fresh.baseVersionId=versionId;
                sessions(n).add(fresh); return fresh;
            }
            String threadId=requestedThreadId==null||requestedThreadId.isBlank()?null:requestedThreadId.strip();
            ConversationSession previous;
            if (threadId==null) {
                previous=sessions(n).stream().filter(s->s.scope==ConversationScope.OUTLINE)
                        .reduce((first,second)->second).orElse(null);
                threadId=previous==null?Novel.uid():previous.threadId;
            } else {
                String selectedThreadId=threadId;
                previous=sessions(n).stream().filter(s->s.scope==ConversationScope.OUTLINE)
                        .filter(s->Objects.equals(s.threadId,selectedThreadId))
                        .reduce((first,second)->second).orElseThrow(()->new Problem(404,"选择的创作对话不存在"));
            }
            String selectedThreadId=threadId;
            ConversationSession existing=sessions(n).stream()
                    .filter(s->s.scope==ConversationScope.OUTLINE&&Objects.equals(s.threadId,selectedThreadId)
                            &&s.baseRevision==n.revision)
                    .filter(s->Objects.equals(s.targetArtifactId,artifactId)&&Objects.equals(s.baseVersionId,versionId))
                    .reduce((first,second)->second).orElse(null);
            if (existing!=null) return existing;
            ConversationSession session;
            if (previous==null) {
                session=new ConversationSession(); session.threadId=threadId; session.threadTitle=nextThreadTitle(n);
            } else session=rebaseSession(n,previous);
            session.scope=ConversationScope.OUTLINE; session.baseRevision=n.revision;
            session.targetArtifactId=artifactId; session.baseVersionId=versionId;
            sessions(n).add(session); return session;
        });
    }

    private record TurnStart(String turnId,String userMessageId,boolean execute) {}

    public ConversationSession send(String novelId,String sessionId,String content) {
        return send(novelId,sessionId,content,Novel.uid());
    }

    public ConversationSession send(String novelId,String sessionId,String content,String requestKey) {
        require(content!=null && !content.isBlank(),"请输入想讨论的内容");
        require(content.length()<=10000,"单条消息不能超过10000字符");
        require(requestKey!=null&&!requestKey.isBlank()&&requestKey.length()<=100,"消息请求需要不超过100字符的幂等键");
        String normalized=content.strip();
        TurnStart start=repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId);
            requireCurrent(n,session);
            ConversationTurn existing=session.turns.stream().filter(item->requestKey.equals(item.requestKey)).findFirst().orElse(null);
            if (existing!=null) {
                ConversationMessage message=message(session,existing.userMessageId);
                require(Objects.equals(message.content,normalized),"同一消息请求不能用于不同内容");
                return new TurnStart(existing.id,message.id,false);
            }
            require(session.turns.stream().noneMatch(item->item.status==ConversationTurnStatus.RUNNING),
                    "上一条消息仍在处理中，请等待完成后再发送");
            ConversationMessage message=new ConversationMessage(); message.role=ConversationRole.USER;
            message.content=normalized; session.messages.add(message);
            ConversationTurn turn=new ConversationTurn(); turn.requestKey=requestKey; turn.userMessageId=message.id;
            session.turns.add(turn); session.updatedAt=Novel.now();
            if (session.messages.stream().filter(item->item.role==ConversationRole.USER).count()==1) {
                String title=threadTitle(message.content);
                sessions(n).stream().filter(item->Objects.equals(item.threadId,session.threadId))
                        .forEach(item->item.threadTitle=title);
            }
            return new TurnStart(turn.id,message.id,true);
        });
        if (!start.execute) return session(repository.get(novelId),sessionId);
        events.publish(novelId,sessionId,"STARTED",start.turnId,"UNDERSTANDING",null,null);
        return completeTurn(novelId,sessionId,start.turnId,start.userMessageId);
    }

    public ConversationSession retry(String novelId,String sessionId,String turnId) {
        TurnStart start=repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId); requireCurrent(n,session);
            ConversationTurn turn=turn(session,turnId);
            require(turn.status==ConversationTurnStatus.FAILED||turn.status==ConversationTurnStatus.INTERRUPTED,
                    "只有失败或中断的消息可以重试");
            require(session.turns.stream().noneMatch(item->item.status==ConversationTurnStatus.RUNNING),
                    "已有消息正在处理中，请等待完成后再重试");
            message(session,turn.userMessageId);
            turn.status=ConversationTurnStatus.RUNNING; turn.stage="UNDERSTANDING"; turn.error=null; turn.finishedAt=null;
            turn.startedAt=Novel.now(); turn.attempt++; session.updatedAt=Novel.now();
            return new TurnStart(turn.id,turn.userMessageId,true);
        });
        events.publish(novelId,sessionId,"STARTED",start.turnId,"UNDERSTANDING",null,null);
        return completeTurn(novelId,sessionId,start.turnId,start.userMessageId);
    }

    public ConversationSession cancel(String novelId,String sessionId,String turnId) {
        ConversationSession saved=repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId);
            ConversationTurn turn=turn(session,turnId);
            require(turn.status==ConversationTurnStatus.RUNNING,"只有正在处理的消息可以停止");
            turn.status=ConversationTurnStatus.INTERRUPTED; turn.stage="INTERRUPTED";
            turn.error="已由你停止本轮生成；用户消息已保留，可以直接重试";
            turn.finishedAt=Novel.now(); session.updatedAt=Novel.now(); return session;
        });
        TurnControl control=activeTurns.get(turnId);
        if (control!=null) control.cancel();
        events.publish(novelId,sessionId,"INTERRUPTED",turnId,"INTERRUPTED",null,"已停止本轮生成");
        return saved;
    }

    private ConversationSession completeTurn(String novelId,String sessionId,String turnId,String userMessageId) {
        TurnControl control=new TurnControl(novelId,sessionId,turnId);
        activeTurns.put(turnId,control);
        try {
        Novel snapshot=repository.get(novelId);
        ConversationSession frozen=session(snapshot,sessionId);
        ConversationMessage user=message(frozen,userMessageId);
        ModelGateway.DialogueResponse response=agents.dialogue(
                new ModelGateway.DialogueRequest(dialogueContext(snapshot,frozen,user)),control);
        require(response.reply()!=null&&!response.reply().isBlank(),"创作对话没有返回回复");
        require(response.reply().length()<=20000,"创作对话回复过长，未保存不完整结果");
        boolean fallbackExecution=immediateOutlineExecutionRequested(user.content);
        boolean structuredExecution="EXECUTE".equals(response.intent());
        // UNCLEAR is an explicit request for one more author choice and must not be
        // overridden by keywords. The deterministic fallback only rescues a missed
        // EXECUTE classification from DISCUSS/legacy responses.
        boolean executionRequested=structuredExecution
                ||("DISCUSS".equals(response.intent())&&fallbackExecution);
        List<ModelGateway.ProjectUpdateCandidate> projectUpdates=response.projectUpdateCandidates().stream()
                .filter(item->explicitProjectFieldChangeRequested(user.content,item.field()))
                .limit(4).toList();
        ModelGateway.DialogueProposal modelAction=response.actionProposal();
        if (!executionRequested) modelAction=null;
        if (modelAction==null&&executionRequested&&projectUpdates.isEmpty())
            modelAction=new ModelGateway.DialogueProposal("GENERATE_OR_REVISE_OUTLINE",
                    "根据作者本轮明确要求生成或修订全书大纲："+user.content);
        ModelGateway.DialogueProposal requestedAction=modelAction;
        ConversationSession saved=repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId);
            requireCurrent(n,session);
            ConversationTurn turn=turn(session,turnId);
            require(turn.status==ConversationTurnStatus.RUNNING,"这条消息已经结束，不能重复写入回复");
            ConversationMessage assistant=new ConversationMessage(); assistant.role=ConversationRole.ASSISTANT;
            assistant.content=response.reply().strip(); session.messages.add(assistant);
            turn.assistantMessageId=assistant.id; turn.status=ConversationTurnStatus.SUCCEEDED;
            turn.intent=response.intent(); turn.stage="COMPLETED";
            turn.error=null; turn.finishedAt=Novel.now();
            for (ModelGateway.DialogueDecision item:response.decisionCandidates().stream().limit(12).toList()) {
                if (item.text()==null||item.text().isBlank()||item.text().length()>2000) continue;
                ConversationDecision decision=new ConversationDecision(); decision.sourceMessageId=assistant.id;
                decision.type=DecisionType.valueOf(item.type()); decision.text=item.text().strip();
                session.decisions.add(decision);
            }
            Set<ProjectField> updateFields=EnumSet.noneOf(ProjectField.class);
            for (ModelGateway.ProjectUpdateCandidate item:projectUpdates) {
                ProjectField field;
                try { field=ProjectField.valueOf(item.field()); } catch (RuntimeException ignored) { continue; }
                if (!updateFields.add(field)||item.proposedValue()==null||item.proposedValue().isBlank()) continue;
                session.projectUpdates.stream()
                        .filter(existing->existing.field==field)
                        .filter(existing->existing.status==ProjectUpdateStatus.PROPOSED||existing.status==ProjectUpdateStatus.ACCEPTED)
                        .forEach(existing->existing.status=ProjectUpdateStatus.STALE);
                ProjectUpdateProposal update=new ProjectUpdateProposal(); update.sourceMessageId=assistant.id;
                update.field=field; update.previousValue=projectValue(snapshot,field);
                update.proposedValue=item.proposedValue().strip();
                update.reason=item.reason()==null?"":item.reason().strip(); update.baseRevision=session.baseRevision;
                session.projectUpdates.add(update);
            }
            if (requestedAction!=null) {
                ActionProposal proposal=new ActionProposal(); proposal.sourceMessageId=assistant.id;
                proposal.operation=requestedAction.operation();
                proposal.instructions=requestedAction.instructions()==null?"":requestedAction.instructions().strip();
                proposal.baseRevision=session.baseRevision; proposal.baseVersionId=session.baseVersionId;
                proposal.requestedByUserMessageId=user.id;
                // Returning an action proposal is the dialogue Agent's structured acknowledgement that
                // this user message is an imperative, not a discussion. Generating a candidate is safe:
                // the candidate still needs one final author approval before it becomes the active version.
                proposal.automaticExecution=executionRequested&&projectUpdates.isEmpty();
                session.proposals.add(proposal);
            }
            session.updatedAt=Novel.now(); return session;
        });
        ActionProposal automatic=saved.proposals.stream().filter(item->item.automaticExecution)
                .filter(item->item.status==ProposalStatus.DRAFT&&Objects.equals(item.requestedByUserMessageId,user.id))
                .reduce((first,second)->second).orElse(null);
        if (automatic!=null) {
            try {
                execute(novelId,sessionId,automatic.id,Novel.uid(),snapshot.revision);
            } catch (RuntimeException problem) {
                repository.update(novelId,n->{
                    ConversationSession session=session(n,sessionId);
                    session.proposals.stream().filter(item->item.id.equals(automatic.id)).findFirst()
                            .ifPresent(item->item.executionError=problem.getMessage()==null?"未能开始正式任务":problem.getMessage());
                    session.updatedAt=Novel.now(); return null;
                });
            }
        }
        ConversationSession result=session(repository.get(novelId),sessionId);
        events.publish(novelId,sessionId,"COMPLETED",turnId,"COMPLETED",null,null);
        return result;
        } catch (RuntimeException problem) {
            ConversationTurnStatus status=repository.update(novelId,n->{
                ConversationSession session=session(n,sessionId);
                ConversationTurn turn=turn(session,turnId);
                if (turn.status==ConversationTurnStatus.RUNNING) {
                    turn.status=ConversationTurnStatus.FAILED;
                    turn.stage="FAILED";
                    turn.error=problem.getMessage()==null?"创作助手回复失败，请重试":problem.getMessage();
                    turn.finishedAt=Novel.now(); session.updatedAt=Novel.now();
                }
                return turn.status;
            });
            if (status==ConversationTurnStatus.FAILED)
                events.publish(novelId,sessionId,"FAILED",turnId,"FAILED",null,
                        problem.getMessage()==null?"创作助手回复失败，请重试":problem.getMessage());
            throw problem;
        } finally {
            activeTurns.remove(turnId,control);
        }
    }

    private void updateStage(String novelId,String sessionId,String turnId,String stage) {
        boolean updated=repository.update(novelId,n->{
            ConversationTurn turn=turn(session(n,sessionId),turnId);
            if (turn.status!=ConversationTurnStatus.RUNNING) return false;
            turn.stage=stage; return true;
        });
        if (updated) events.publish(novelId,sessionId,"STAGE",turnId,stage,null,null);
    }

    private final class TurnControl implements ModelGateway.DialogueStream {
        private final String novelId,sessionId,turnId;
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private final AtomicReference<Runnable> cancelAction=new AtomicReference<>();
        private TurnControl(String novelId,String sessionId,String turnId) {
            this.novelId=novelId; this.sessionId=sessionId; this.turnId=turnId;
        }
        @Override public void stage(String stage) {
            if (!cancelled()) updateStage(novelId,sessionId,turnId,stage);
        }
        @Override public void text(String delta) {
            if (!cancelled()&&delta!=null&&!delta.isEmpty())
                events.publish(novelId,sessionId,"DELTA",turnId,null,delta,null);
        }
        @Override public boolean cancelled() { return cancelled.get(); }
        @Override public void onCancel(Runnable action) {
            cancelAction.set(action);
            if (cancelled()&&action!=null) action.run();
        }
        private void cancel() {
            if (!cancelled.compareAndSet(false,true)) return;
            Runnable action=cancelAction.get(); if (action!=null) action.run();
        }
    }

    public ConversationSession decide(String novelId,String sessionId,String decisionId,DecisionStatus status) {
        require(status==DecisionStatus.ACCEPTED||status==DecisionStatus.REJECTED||status==DecisionStatus.WITHDRAWN,
                "不支持的决定状态");
        return repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId);
            ConversationDecision decision=session.decisions.stream().filter(d->d.id.equals(decisionId)).findFirst()
                    .orElseThrow(()->new Problem(404,"对话决定不存在"));
            decision.status=status; decision.decidedAt=Novel.now(); session.updatedAt=Novel.now(); return session;
        });
    }

    public ConversationSession decideProjectUpdate(String novelId,String sessionId,String updateId,ProjectUpdateStatus status) {
        require(status==ProjectUpdateStatus.ACCEPTED||status==ProjectUpdateStatus.REJECTED,
                "不支持的项目信息决定状态");
        return repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId); requireCurrent(n,session);
            ProjectUpdateProposal update=session.projectUpdates.stream().filter(item->item.id.equals(updateId)).findFirst()
                    .orElseThrow(()->new Problem(404,"项目信息修改提案不存在"));
            require(update.status==ProjectUpdateStatus.PROPOSED||update.status==ProjectUpdateStatus.ACCEPTED
                    ||update.status==ProjectUpdateStatus.REJECTED,"这个修改提案已经应用或失效");
            update.status=status; update.decidedAt=Novel.now(); session.updatedAt=Novel.now(); return session;
        });
    }

    public ConversationSession rejectProjectUpdates(String novelId,String sessionId) {
        return repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId); requireCurrent(n,session);
            List<ProjectUpdateProposal> pending=session.projectUpdates.stream()
                    .filter(item->item.status==ProjectUpdateStatus.PROPOSED||item.status==ProjectUpdateStatus.ACCEPTED)
                    .toList();
            require(!pending.isEmpty(),"没有等待撤销的项目信息修改");
            pending.forEach(item->{item.status=ProjectUpdateStatus.REJECTED;item.decidedAt=Novel.now();});
            session.updatedAt=Novel.now(); return session;
        });
    }

    public record ProjectApplyResult(ConversationSession session,ProjectBriefChange change) {}
    public ProjectApplyResult applyProjectUpdates(String novelId,String sessionId,long revision) {
        ProjectApplyResult applied=repository.update(novelId,n->{
            workflow.expected(n,revision); workflow.idle(n);
            ConversationSession session=session(n,sessionId); requireCurrent(n,session);
            require(n.artifacts.stream().allMatch(a->a.kind==Kind.OUTLINE),
                    "人物设定或正文已经开始后，暂不能通过大纲对话修改项目信息");
            List<ProjectUpdateProposal> accepted=session.projectUpdates.stream()
                    .filter(item->item.status==ProjectUpdateStatus.PROPOSED||item.status==ProjectUpdateStatus.ACCEPTED).toList();
            require(!accepted.isEmpty(),"没有等待应用的项目信息修改");
            require(accepted.stream().map(item->item.field).distinct().count()==accepted.size(),
                    "同一字段存在多条待处理修改，请重新对话生成一份完整修改");
            ProjectBriefChange change=new ProjectBriefChange(); change.conversationSessionId=session.id;
            change.previousRevision=n.revision;
            for (ProjectUpdateProposal update:accepted) {
                require(update.baseRevision==n.revision&&Objects.equals(update.previousValue,projectValue(n,update.field)),
                        "项目信息已经变化，请基于最新内容重新讨论");
                String proposed=validateProjectValue(n,update.field,update.proposedValue);
                require(!Objects.equals(update.previousValue,proposed),"提案内容与当前项目信息相同，无需保存");
                change.fields.add(new ProjectFieldChange(update.field,update.previousValue,proposed));
                if (update.field==ProjectField.TARGET_WORDS) {
                    long nextTarget=Long.parseLong(proposed);
                    n.budgetChanges.add(new BudgetChange(n.targetWords,nextTarget,n.approvedMaxWords,n.approvedMaxWords,
                            "创作对话中采纳目标字数修改",Novel.now()));
                }
                setProjectValue(n,update.field,proposed);
                update.status=ProjectUpdateStatus.APPLIED; update.appliedAt=Novel.now();
            }
            n.revision++; change.newRevision=n.revision; projectChanges(n).add(change);
            Artifact outline=currentOutline(n);
            if (outline!=null) {
                outline.needsRevision=true;
                Change invalidation=new Change(); invalidation.sourceArtifactId="PROJECT_BRIEF";
                invalidation.reason="书名、简介、补充要求或目标字数已由作者更新，需要基于新资料复核大纲";
                invalidation.affectedArtifactIds.add(outline.id); n.changes.add(invalidation); n.status="WRITING";
            }
            ConversationSession rebased=rebaseSession(n,session,true);
            sessions(n).forEach(item->{
                item.proposals.stream().filter(p->p.status==ProposalStatus.DRAFT).forEach(p->p.status=ProposalStatus.STALE);
                item.projectUpdates.stream().filter(p->p.status==ProjectUpdateStatus.PROPOSED||p.status==ProjectUpdateStatus.ACCEPTED)
                        .forEach(p->p.status=ProjectUpdateStatus.STALE);
            });
            sessions(n).add(rebased); return new ProjectApplyResult(rebased,change);
        });
        ActionProposal automatic=applied.session().proposals.stream()
                .filter(item->item.status==ProposalStatus.DRAFT&&item.automaticExecution)
                .reduce((first,second)->second).orElse(null);
        if (automatic!=null) {
            try {
                execute(novelId,applied.session().id,automatic.id,Novel.uid(),applied.change().newRevision);
            } catch (RuntimeException problem) {
                repository.update(novelId,n->{
                    ConversationSession current=session(n,applied.session().id);
                    current.proposals.stream().filter(item->item.id.equals(automatic.id)).findFirst()
                            .ifPresent(item->item.executionError=problem.getMessage()==null?"资料已保存，但未能继续生成大纲":problem.getMessage());
                    current.updatedAt=Novel.now(); return null;
                });
            }
        }
        return new ProjectApplyResult(session(repository.get(novelId),applied.session().id),applied.change());
    }

    public Task execute(String novelId,String sessionId,String proposalId,String requestKey,long revision) {
        ConversationBrief brief=repository.update(novelId,n->{
            require(n.revision==revision,"小说版本已经变化，旧操作提案不能执行");
            ConversationSession session=session(n,sessionId); requireCurrent(n,session);
            ActionProposal proposal=session.proposals.stream().filter(p->p.id.equals(proposalId)).findFirst()
                    .orElseThrow(()->new Problem(404,"操作提案不存在"));
            require(proposal.status==ProposalStatus.DRAFT,"这个操作提案已经执行或失效");
            require("GENERATE_OR_REVISE_OUTLINE".equals(proposal.operation),"当前只支持生成或修订大纲");
            if (proposal.conversationBriefId!=null) return briefs(n).stream()
                    .filter(item->item.id.equals(proposal.conversationBriefId)).findFirst()
                    .orElseThrow(()->new Problem(409,"操作提案引用的对话简报不存在，请重新开始对话"));
            List<ConversationDecision> accepted=session.decisions.stream()
                    .filter(d->d.status==DecisionStatus.ACCEPTED).toList();
            require(session.projectUpdates.stream().noneMatch(item->item.status==ProjectUpdateStatus.PROPOSED
                            ||item.status==ProjectUpdateStatus.ACCEPTED),
                    "请先同意或撤销书名、简介、补充要求或目标字数修改");
            rejectDirectContradictions(accepted);
            ConversationBrief result=new ConversationBrief(); result.sessionId=session.id;
            result.scope=session.scope; result.targetArtifactId=session.targetArtifactId;
            result.baseVersionId=session.baseVersionId; result.baseRevision=session.baseRevision;
            result.acceptedDecisionIds=new ArrayList<>(accepted.stream().map(d->d.id).toList());
            result.acceptedDecisions=new ArrayList<>(accepted.stream().map(d->decisionText(d)).toList());
            ConversationMessage requestedBy=message(session,proposal.requestedByUserMessageId);
            result.acceptedDecisionIds.add(proposal.requestedByUserMessageId);
            result.acceptedDecisions.add("作者本轮原始要求："+requestedBy.content);
            if (!proposal.instructions.isBlank())
                result.acceptedDecisions.add("创作对话整理的本次执行范围："+proposal.instructions);
            result.hash=hash(result); briefs(n).add(result); proposal.conversationBriefId=result.id;
            session.updatedAt=Novel.now(); return result;
        });
        Action action=brief.targetArtifactId==null?Action.OUTLINE:Action.REWRITE;
        Task task=tasks.submitConversation(novelId,action,brief.targetArtifactId,brief,requestKey,revision);
        repository.update(novelId,n->{
            ConversationSession session=session(n,sessionId);
            ActionProposal proposal=session.proposals.stream().filter(p->p.id.equals(proposalId)).findFirst()
                    .orElseThrow(()->new Problem(404,"操作提案不存在"));
            proposal.status=ProposalStatus.EXECUTED; proposal.taskId=task.id; proposal.executedAt=Novel.now();
            session.updatedAt=Novel.now(); return null;
        });
        return task;
    }

    public boolean current(Novel novel,ConversationSession session) {
        if (session.baseRevision!=novel.revision) return false;
        Artifact outline=currentOutline(novel);
        String artifactId=outline==null?null:outline.id;
        String versionId=outline==null||outline.latest()==null?null:outline.latest().id;
        return Objects.equals(session.targetArtifactId,artifactId)&&Objects.equals(session.baseVersionId,versionId);
    }

    private void requireCurrent(Novel novel,ConversationSession session) {
        if (current(novel,session)) return;
        session.proposals.stream().filter(p->p.status==ProposalStatus.DRAFT).forEach(p->p.status=ProposalStatus.STALE);
        throw new Problem(409,"大纲版本已经变化，这个操作提案已过期；请基于最新版本重新开始对话");
    }

    private String dialogueContext(Novel novel,ConversationSession session,ConversationMessage latestUser) {
        try {
            var root=new LinkedHashMap<String,Object>();
            root.put("scope","OUTLINE"); root.put("baseRevision",session.baseRevision);
            root.put("baseVersionId",session.baseVersionId==null?"":session.baseVersionId);
            RequirementAuthority.Snapshot requirementSnapshot=requirementAuthority.resolve(novel);
            root.put("novel",Map.of("title",novel.title,"synopsis",novel.synopsis,"requirements",requirementSnapshot.text(),
                    "targetWords",novel.targetWords));
            root.put("requirementAuthority",Map.of("strategy",requirementSnapshot.authority(),
                    "sourceVersionId",requirementSnapshot.sourceVersionId()==null?"":requirementSnapshot.sourceVersionId(),
                    "rule","当前 requirements 已完成新旧替代，只能按当前有效要求讨论和修改"));
            Artifact outline=currentOutline(novel);
            if (outline!=null&&outline.latest()!=null) {
                Version version=outline.latest();
                var currentOutline=new LinkedHashMap<String,Object>();
                currentOutline.put("artifactId",outline.id); currentOutline.put("versionId",version.id);
                currentOutline.put("title",version.title); currentOutline.put("summary",version.summary);
                // Structured outlines already contain the full author-visible meaning. Sending both the
                // structure and its rendered prose doubles large prompts and delays interactive replies.
                if (version.outlineSpec!=null) currentOutline.put("outlineSpec",version.outlineSpec);
                else currentOutline.put("content",version.content);
                root.put("currentOutline",currentOutline);
            }
            root.put("acceptedDecisions",session.decisions.stream().filter(d->d.status==DecisionStatus.ACCEPTED)
                    .map(this::decisionText).toList());
            root.put("recentMessages",recentDialogueHistory(session,latestUser));
            root.put("latestUserMessage",latestUser.content);
            root.put("authorityRule","讨论不会修改作品；用户明确要求生成、修改或重写时，应返回 actionProposal，系统据此生成候选版本。候选版本仍需作者统一同意或撤销，不要要求作者逐条采纳创作要求");
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) { throw new Problem(500,"创作对话上下文无法编码"); }
    }

    private List<Map<String,String>> recentDialogueHistory(ConversationSession session,ConversationMessage latestUser) {
        final int maxMessages=8,maxCharacters=12000;
        int latestIndex=session.messages.indexOf(latestUser);
        if (latestIndex<0) latestIndex=session.messages.size();
        int remaining=maxCharacters;
        Deque<Map<String,String>> selected=new ArrayDeque<>();
        for (int index=latestIndex-1;index>=0&&selected.size()<maxMessages&&remaining>0;index--) {
            ConversationMessage message=session.messages.get(index);
            String content=message.content==null?"":message.content;
            if (content.length()>remaining) {
                if (remaining<=6) content=content.substring(content.length()-remaining);
                else {
                    int keep=remaining-6;
                    content="[前文省略]"+content.substring(content.length()-keep);
                }
            }
            selected.addFirst(Map.of("role",message.role.name(),"content",content));
            remaining-=content.length();
        }
        return List.copyOf(selected);
    }

    private String decisionText(ConversationDecision decision) {
        return switch (decision.type) {
            case MUST_KEEP -> "必须保留：";
            case MUST_CHANGE -> "必须修改：";
            case FORBID -> "禁止出现：";
            case PREFERENCE -> "作者偏好：";
            case OPEN_QUESTION -> "待解决问题：";
            case ASSUMPTION -> "暂定假设：";
        }+decision.text;
    }

    private ConversationSession rebaseSession(Novel novel,ConversationSession previous) {
        return rebaseSession(novel,previous,false);
    }

    private ConversationSession rebaseSession(Novel novel,ConversationSession previous,boolean carryDraftProposal) {
        ConversationSession rebased=new ConversationSession(); rebased.scope=previous.scope;
        rebased.threadId=previous.threadId; rebased.threadTitle=previous.threadTitle;
        rebased.previousSessionId=previous.id;
        rebased.baseRevision=novel.revision; Artifact outline=currentOutline(novel);
        rebased.targetArtifactId=outline==null?null:outline.id;
        rebased.baseVersionId=outline==null||outline.latest()==null?null:outline.latest().id;
        int from=Math.max(0,previous.messages.size()-20);
        Map<String,String> messageIds=new HashMap<>();
        for (ConversationMessage source:previous.messages.subList(from,previous.messages.size())) {
            ConversationMessage copy=new ConversationMessage(); copy.role=source.role; copy.content=source.content;
            rebased.messages.add(copy); messageIds.put(source.id,copy.id);
        }
        for (ConversationDecision source:previous.decisions.stream().filter(d->d.status==DecisionStatus.ACCEPTED).toList()) {
            ConversationDecision copy=new ConversationDecision(); copy.sourceMessageId=messageIds.get(source.sourceMessageId);
            copy.type=source.type; copy.status=DecisionStatus.ACCEPTED; copy.text=source.text; copy.decidedAt=Novel.now();
            rebased.decisions.add(copy);
        }
        for (ActionProposal source:previous.proposals.stream()
                .filter(p->p.status==ProposalStatus.EXECUTED || carryDraftProposal&&p.status==ProposalStatus.DRAFT).toList()) {
            ActionProposal copy=new ActionProposal(); copy.sourceMessageId=messageIds.get(source.sourceMessageId);
            copy.operation=source.operation; copy.instructions=source.instructions;
            boolean deferred=source.status==ProposalStatus.DRAFT;
            copy.baseRevision=deferred?rebased.baseRevision:source.baseRevision;
            copy.baseVersionId=deferred?rebased.baseVersionId:source.baseVersionId;
            copy.status=source.status; copy.conversationBriefId=deferred?null:source.conversationBriefId;
            copy.taskId=deferred?null:source.taskId;
            copy.automaticExecution=deferred||source.automaticExecution;
            copy.requestedByUserMessageId=messageIds.get(source.requestedByUserMessageId);
            copy.executionError=deferred?null:source.executionError; copy.executedAt=deferred?null:source.executedAt;
            rebased.proposals.add(copy);
        }
        ConversationMessage notice=new ConversationMessage(); notice.role=ConversationRole.ASSISTANT;
        notice.content="已基于最新内容继续这次对话。此前已采纳的创作决定已经保留，你可以继续讨论或直接要求生成。";
        rebased.messages.add(notice); return rebased;
    }

    private String projectValue(Novel novel,ProjectField field) {
        return switch (field) {
            case TITLE -> novel.title;
            case SYNOPSIS -> novel.synopsis;
            case REQUIREMENTS -> novel.requirements==null?"":novel.requirements;
            case TARGET_WORDS -> Long.toString(novel.targetWords);
        };
    }
    private String validateProjectValue(Novel novel,ProjectField field,String value) {
        String result=value==null?"":value.strip();
        switch (field) {
            case TITLE -> require(!result.isBlank()&&result.length()<=200,"书名必须为1至200个字符");
            case SYNOPSIS -> require(!result.isBlank()&&result.length()<=20000,"简介必须为1至20000个字符");
            case REQUIREMENTS -> require(result.length()<=20000,"补充要求不能超过20000个字符");
            case TARGET_WORDS -> {
                long target;
                try { target=Long.parseLong(result); } catch (NumberFormatException e) { throw new Problem(409,"目标字数必须是整数"); }
                require(target>=10&&target<=50_000_000,"目标字数必须在10字到5000万字之间");
                require(target<novel.approvedMaxWords,"目标字数必须低于当前字数上限；请先在字数设置中调整上限");
                result=Long.toString(target);
            }
        }
        if (field!=ProjectField.TARGET_WORDS) VisibleContentPolicy.validate(projectFieldName(field),result);
        return result;
    }
    private void setProjectValue(Novel novel,ProjectField field,String value) {
        switch (field) {
            case TITLE -> novel.title=value;
            case SYNOPSIS -> novel.synopsis=value;
            case REQUIREMENTS -> novel.requirements=value;
            case TARGET_WORDS -> novel.targetWords=Long.parseLong(value);
        }
    }
    private String projectFieldName(ProjectField field) {
        return switch (field) { case TITLE->"书名";case SYNOPSIS->"简介";case REQUIREMENTS->"补充要求";case TARGET_WORDS->"目标字数"; };
    }

    private void rejectDirectContradictions(List<ConversationDecision> accepted) {
        Map<String,Set<DecisionType>> byText=new HashMap<>();
        for (ConversationDecision decision:accepted) byText.computeIfAbsent(normalize(decision.text),key->new HashSet<>()).add(decision.type);
        boolean conflict=byText.values().stream().anyMatch(types->types.contains(DecisionType.FORBID)
                && (types.contains(DecisionType.MUST_KEEP)||types.contains(DecisionType.MUST_CHANGE)));
        require(!conflict,"已采纳的决定互相冲突，请先撤销其中一项");
    }

    private String normalize(String text) { return text==null?"":text.replaceAll("[\\s，。；、！？]","").toLowerCase(Locale.ROOT); }

    private boolean immediateOutlineExecutionRequested(String text) {
        String value=text==null?"":text.replaceAll("\\s+","");
        if (value.isBlank()||value.matches(".*(不要|不需要|先别|暂不|别急着)(生成|写|修改|重写).*")) return false;
        if (value.endsWith("吗")||value.endsWith("呢")||value.endsWith("？")||value.endsWith("?")
                ||value.contains("能不能")||value.contains("是否可以")||value.contains("好不好")
                ||value.contains("怎么样")||value.contains("怎么改")||value.contains("如何改")) return false;
        return value.matches(".*(生成|开始写|直接写|重写|改写|开始修改|直接修改|帮我修改|帮我改|按.+修改|照.+修改|把.+改成).*(大纲|全书规划).*" )
                ||value.matches("^(生成|开始生成|直接生成|生成吧)[！。]?")
                ||value.matches("^(请|请你|麻烦|麻烦你)?(帮我)?(现在)?(修改|调整|重写|改写|完善|补充|删除|压缩).+")
                ||value.matches(".*(按这个来|按刚才.+来|就这样改|开始吧|动手吧)[！。]?")
                ||value.matches(".*(把|将).+(改成|改为|删掉|删除|补上|加入|增加|调整).*");
    }

    /**
     * A model may mistake story instructions for project metadata. Project fields are therefore
     * writable only when the author's current message names that field and asks to change it.
     */
    private boolean explicitProjectFieldChangeRequested(String text,String candidateField) {
        if (text==null||text.isBlank()||candidateField==null) return false;
        ProjectField field;
        try { field=ProjectField.valueOf(candidateField); }
        catch (RuntimeException ignored) { return false; }
        String value=text.replaceAll("\\s+","");
        String fieldTerm=switch (field) {
            case TITLE -> "(?:书名|小说名|作品名|作品标题)";
            case SYNOPSIS -> "(?:作品简介|小说简介|故事简介|简介)";
            case REQUIREMENTS -> "(?:补充要求|创作要求|写作要求|项目要求|要求栏|要求字段)";
            case TARGET_WORDS -> "(?:目标字数|字数上限|总字数|篇幅设置)";
        };
        String change="(?:改|修改|更改|调整|更新|替换|换成|改成|改为|设为|设置|补充|增加|添加|删掉|删除|重写)";
        String negative="(?:不要|别|无需|不需要|不必|保持|保留|维持)";
        if (value.matches(".*"+negative+".{0,8}"+fieldTerm+".*")
                ||value.matches(".*"+fieldTerm+".{0,8}(?:不要改|别改|无需修改|不需要修改|不必修改|保持不变|不要变化).*"))
            return false;
        return value.matches(".*"+change+".{0,16}"+fieldTerm+".*")
                ||value.matches(".*"+fieldTerm+".{0,16}"+change+".*");
    }

    private String hash(ConversationBrief brief) {
        String value=brief.sessionId+"|"+brief.scope+"|"+brief.targetArtifactId+"|"+brief.baseVersionId+"|"
                +brief.baseRevision+"|"+String.join("|",brief.acceptedDecisionIds)+"|"+String.join("|",brief.acceptedDecisions);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Artifact currentOutline(Novel novel) {
        return novel.artifacts.stream().filter(a->a.kind==Kind.OUTLINE&&a.latest()!=null)
                .reduce((first,second)->second).orElse(null);
    }
    private ConversationSession session(Novel novel,String id) {
        return sessions(novel).stream().filter(item->item.id.equals(id)).findFirst()
                .orElseThrow(()->new Problem(404,"创作对话不存在"));
    }
    private ConversationMessage message(ConversationSession session,String id) {
        return session.messages.stream().filter(item->Objects.equals(item.id,id)).findFirst()
                .orElseThrow(()->new Problem(404,"对话消息不存在"));
    }
    private ConversationTurn turn(ConversationSession session,String id) {
        return session.turns.stream().filter(item->Objects.equals(item.id,id)).findFirst()
                .orElseThrow(()->new Problem(404,"对话轮次不存在"));
    }
    private List<ConversationSession> sessions(Novel novel) {
        if (novel.conversationSessions==null) novel.conversationSessions=new ArrayList<>();
        String legacyThreadId=novel.conversationSessions.stream()
                .filter(item->item.threadId==null||item.threadId.isBlank())
                .map(item->item.id).filter(Objects::nonNull).findFirst().orElseGet(Novel::uid);
        for (ConversationSession item:novel.conversationSessions) {
            if (item.threadId==null||item.threadId.isBlank()) item.threadId=legacyThreadId;
            if (item.threadTitle==null||item.threadTitle.isBlank()) item.threadTitle="原有对话";
            if (item.turns==null) item.turns=new ArrayList<>();
        }
        return novel.conversationSessions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTurns() {
        for (Novel novel:repository.list()) repository.update(novel.id,current->{
            for (ConversationSession session:sessions(current)) for (ConversationTurn turn:session.turns) {
                if (turn.status!=ConversationTurnStatus.RUNNING) continue;
                turn.status=ConversationTurnStatus.INTERRUPTED;
                turn.error="服务重启导致本轮对话中断；用户消息已经保存，可以直接重试";
                turn.finishedAt=Novel.now(); session.updatedAt=Novel.now();
            }
            return null;
        });
    }

    private String nextThreadTitle(Novel novel) {
        long count=sessions(novel).stream().map(item->item.threadId).distinct().count()+1;
        return "新对话 "+count;
    }

    private String threadTitle(String content) {
        String value=content==null?"":content.replaceAll("\\s+"," ").strip();
        if (value.isBlank()) return "新对话";
        int limit=24;
        return value.length()<=limit?value:value.substring(0,limit)+"…";
    }
    private List<ConversationBrief> briefs(Novel novel) {
        if (novel.conversationBriefs==null) novel.conversationBriefs=new ArrayList<>(); return novel.conversationBriefs;
    }
    private List<ProjectBriefChange> projectChanges(Novel novel) {
        if (novel.projectBriefChanges==null) novel.projectBriefChanges=new ArrayList<>(); return novel.projectBriefChanges;
    }
}
