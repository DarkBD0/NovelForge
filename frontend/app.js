import { compactLegacyIssue, humanizeReviewText } from './review-text.js';

const $ = (selector) => document.querySelector(selector);
const chatDraftStorageKey='novelforge.chat-drafts.v1';
function loadChatDrafts() { try{return JSON.parse(localStorage.getItem(chatDraftStorageKey)||'{}');}catch{return {};} }
const state = { books: [], view: null, selected: null, version: null, editing: false, working: false, poll: null, shadowPollUntil: 0, mode: 'demo', conversations: [], conversationThreadId: null, inspectorTab: 'chat', chatPendingThreadId: null, chatStickBottom: true, chatScrollTop: 0, chatDrafts: loadChatDrafts(), chatEvents: null, chatEventKey: null, chatEventReady: null, chatEventSequence: 0, chatStream: null, chatRenderTimer: null };
const labels = { OUTLINE:'全书大纲', CHARACTERS:'人物设定', PLAN:'章节规划', CHAPTER:'章节正文', REWRITE:'修订内容', REVIEW:'一致性检查', STYLE_REVIEW:'文风检查', COMPLETE:'完结检查', CONFIRM:'等待确认', REPAIR:'处理关联修订', BUDGET:'需要调整预算', COMPLETED:'已完结' };
const statuses = {QUEUED:'排队中', RUNNING:'生成与检查中', SUCCEEDED:'完成', FAILED:'失败', CANCELLED:'已取消', STALE:'结果已过期', INTERRUPTED:'服务中断'};
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number = value => Number(value).toLocaleString('zh-CN');
const latest = a => a?.versions?.filter(version=>!version.dismissed).at(-1);
const clean = a => a && !a.needsRevision && a.approvedVersionId === latest(a)?.id;
const active = t => ['QUEUED','RUNNING'].includes(t.status);
const activeConversationTurn = () => state.conversations.some(session=>(session.turns||[]).some(turn=>turn.status==='RUNNING'));
const busy = () => state.working || Boolean(state.view?.novel.tasks.some(active)) || activeConversationTurn();
const chatDraftKey = (novelId=state.view?.novel.id,threadId=state.conversationThreadId) => novelId&&threadId?`${novelId}:${threadId}`:'';
const chatDraft = (novelId,threadId) => state.chatDrafts[chatDraftKey(novelId,threadId)]||'';
function saveChatDraft(value,novelId=state.view?.novel.id,threadId=state.conversationThreadId) {
  const key=chatDraftKey(novelId,threadId); if(!key)return;
  if(value)state.chatDrafts[key]=value;else delete state.chatDrafts[key];
  const entries=Object.entries(state.chatDrafts); if(entries.length>100)state.chatDrafts=Object.fromEntries(entries.slice(-100));
  try{localStorage.setItem(chatDraftStorageKey,JSON.stringify(state.chatDrafts));}catch{}
}
const taskName = t => t.automationKind==='OUTLINE_AUTO_REPAIR'?'大纲自动修订（唯一一轮）':t.automationKind==='STATE_EXTRACTION_ONLY'?'重新提取章节状态':labels[t.action];
const countWords = text => [...text.matchAll(/\p{Script=Han}/gu)].length + [...text.matchAll(/[A-Za-z]+(?:['’][A-Za-z]+)*/g)].length;
const artifactName = a => a?.kind==='CHAPTER'?`第${a.chapterNumber}章正文`:a?.kind==='PLAN'?`第${a.batchNumber}批章节规划`:labels[a?.kind]||'当前内容';
const factTypeName = type => ({CHARACTER:'人物',RELATIONSHIP:'人物关系',FORESHADOW:'伏笔',EVENT:'事件',TIMELINE:'时间线',WORLD:'世界设定',LOCATION:'地点',ITEM:'物品'}[type]||'设定');
const factStateName = state => ({ACTIVE:'有效',OPEN:'待回收',RESOLVED:'已回收'}[state]||'有效');
function knownFacts(version) {
  const facts=(state.view?.novel.artifacts||[]).flatMap(a=>latest(a)?.facts||[]);
  return [...(version?.facts||[]),...facts].filter((f,i,all)=>f?.key&&all.findIndex(x=>x?.key===f.key)===i);
}
function humanizeIssue(issue,version) {
  return humanizeReviewText(issue,knownFacts(version));
}
const legacyReviewDetail = detail => detail?.evidence==='旧版检查报告未单独记录依据'&&detail?.suggestion==='按问题说明修改或由作者复核';
const legacyIssueText = (detail,version) => compactLegacyIssue(detail?.problem,knownFacts(version));
function reviewLocation(issue,a) {
  const raw=String(issue??'');
  const field=/candidate\.facts|档案增量|\bfacts\b/i.test(raw)?'档案增量':/candidate\.summary|内容摘要|\bsummary\b/i.test(raw)?'内容摘要':/candidate\.plan|章节安排|\bplan\b/i.test(raw)?'章节安排':/candidate\.title|标题|\btitle\b/i.test(raw)?'标题':'内容正文';
  return `${artifactName(a)} → ${field}`;
}
function renderReviewIssue(issue,a,version) {
  const readable=humanizeIssue(issue,version);
  return /修改位置[：:]/.test(readable)?esc(readable):`<strong>修改位置：${esc(reviewLocation(issue,a))}</strong><br>${esc(readable)}`;
}
function renderReviewDetail(detail,a,version) {
  if(!detail) return '';
  if(legacyReviewDetail(detail)) {
    return `<strong>${esc(detail.severity||'必须修正')} · 修改位置：${esc(reviewLocation(detail.problem,a))}</strong><br><span>问题：${esc(legacyIssueText(detail,version))}</span><br><span>依据：旧版报告没有分栏保存依据，请结合已确认内容复核</span><br><span>建议：按上面指出的位置修改当前版本</span>`;
  }
  const location=humanizeIssue(detail.location||reviewLocation('',a),version);
  const severity=detail.severity||'必须修正';
  return `<strong>${esc(severity)} · 修改位置：${esc(location)}</strong><br><span>问题：${esc(humanizeIssue(detail.problem,version))}</span><br><span>依据：${esc(humanizeIssue(detail.evidence,version))}</span><br><span>建议：${esc(humanizeIssue(detail.suggestion,version))}</span>`;
}
const reviewIssueText = (report,version) => report.issueDetails?.length
  ? report.issueDetails.filter(d=>(d.severity||'必须修正')==='必须修正').map(d=>legacyReviewDetail(d)?legacyIssueText(d,version):`修改位置：${humanizeIssue(d.location,version)}；问题：${humanizeIssue(d.problem,version)}；依据：${humanizeIssue(d.evidence,version)}；建议：${humanizeIssue(d.suggestion,version)}`)
  : (report.issues||[]).map(i=>compactLegacyIssue(i,knownFacts(version)));
const styleReviewIssueText = (report,version) => report.issueDetails?.length
  ? report.issueDetails.map(d=>`修改位置：${humanizeIssue(d.location,version)}；问题：${humanizeIssue(d.problem,version)}；原文依据：${humanizeIssue(d.evidence,version)}；建议：${humanizeIssue(d.suggestion,version)}`)
  : (report.issues||[]).map(i=>compactLegacyIssue(i,knownFacts(version)));
function rewriteReviewPreamble(a) {
  const common=`请只修改当前的“${artifactName(a)}”，逐条解决下面的必须修正问题。只修改确实受影响的部分，不要照抄检查意见，也不要修改作为依据的已确认内容。`;
  if(a?.kind==='CHARACTERS') return common+' 人物档案只保留静态设定；删除“第一次、初次、首次、随后、后来、第几章”等剧情顺序断言。';
  if(a?.kind==='CHAPTER') return common+' 正文、内容摘要和档案增量必须互相有依据；章节规划只约束主事件和结果，不要求正文逐字复现。';
  if(a?.kind==='PLAN') return common+' 保持与已确认大纲一致，并保留与前后批次的衔接。';
  return common;
}
function renderFacts(facts) {
  if(!facts?.length) return '<p class="muted">本项没有新增小说档案。</p>';
  return facts.map(f=>`<div class="fact"><strong>${esc(f.detail)}</strong><small>类别：${esc(factTypeName(f.type))} · 状态：${esc(factStateName(f.state))}</small></div>`).join('');
}
function renderStateEntities(entities) {
  if(!entities?.length) return '';
  return `<h4>实体候选</h4>${entities.map(e=>`<div class="fact"><strong>${esc(e.name)}</strong><small>类别：${esc(factTypeName(e.type))}${e.aliases?.length?` · 别名：${esc(e.aliases.join('、'))}`:''}</small>${e.description?`<p>${esc(e.description)}</p>`:''}</div>`).join('')}`;
}
function renderStateRelations(relations,entities) {
  if(!relations?.length) return '';
  const names=Object.fromEntries((entities||[]).map(e=>[e.key,e.name]));
  return `<h4>关系候选</h4>${relations.map(r=>`<div class="fact"><strong>${esc(names[r.fromEntityKey]||r.fromEntityKey)} → ${esc(names[r.toEntityKey]||r.toEntityKey)}</strong><small>关系：${esc(r.type)} · 状态：${esc(r.state)}</small><p>${esc(r.detail)}</p></div>`).join('')}`;
}
function friendlyError(message) {
  let text=String(message??'');
  text=text.replace(/\s*\[诊断=.*$/s,'');
  const words=[['MODEL_JSON','模型结果格式'],['EMPTY_FINAL_CONTENT','最终内容为空'],['OUTPUT_TRUNCATED','输出未完成'],
    ['ENVELOPE_JSON','接口返回格式'],['UPSTREAM_HTML','上游服务网页响应'],['UPSTREAM_ERROR','上游服务错误'],
    ['MODEL_CONNECT','模型连接'],['MODEL_TIMEOUT','模型响应超时'],['MODEL_TLS','安全连接'],['MODEL_IO','模型通信'],
    ['HTTP','接口状态'],['SSE','流式响应'],['JSON','结构化数据'],['content','最终内容'],['token','输出额度'],
    ['OUTLINE','全书大纲'],['CHARACTERS','人物设定'],['PLAN','章节规划'],['CHAPTER','章节正文'],['REWRITE','修订内容'],['REVIEW','一致性检查'],['COMPLETE','完结检查']];
  words.forEach(([internal,readable])=>text=text.split(internal).join(readable));
  return text.replace(/\[[A-Z_]+\]\s*/g,'').trim();
}
function technicalError(message) {
  const text=String(message??''),match=text.match(/\[诊断=.*$/s); return match?match[0]:'';
}

async function api(path, body) {
  const response = await fetch('/api' + path, { cache:'no-store', ...(body === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/json','X-NovelForge-Request':'1'},body:JSON.stringify(body)}) });
  let data; try { data = await response.json(); } catch { throw new Error('服务没有返回有效数据，请检查服务是否仍在运行。'); }
  if (!response.ok) throw new Error(data.message || `请求失败：${response.status}`);
  return data;
}
function error(e) { $('#errorBar').textContent = friendlyError(e.message || String(e)); $('#errorBar').hidden = false; }
function clearError() { $('#errorBar').hidden = true; }
function toast(message) { $('#toast').textContent=message; $('#toast').hidden=false; setTimeout(() => $('#toast').hidden=true,3500); }
async function perform(fn) {
  if (state.working) return;
  state.working=true; clearError();
  try { await fn(); } catch(e) { error(e); }
  finally { state.working=false; if (state.view && !state.editing) render(); }
}
function canLeave() { if (!state.editing) return true; if (!confirm('未保存的编辑将丢失，确定离开吗？')) return false; state.editing=false; return true; }
async function listBooks() { state.books=await api('/novels'); renderBooks(); }
function renderBooks() {
  $('#bookList').innerHTML=state.books.length ? state.books.map(b=>`<button class="book ${state.view?.novel.id===b.id?'active':''}" data-book="${b.id}"><strong>${esc(b.title)}</strong><small>${b.status==='COMPLETED'?'已完结':'创作中'} · ${number(b.words)} / ${number(b.targetWords)} 字</small></button>`).join('') : '<p class="muted">还没有作品，从新建开始。</p>';
}
function closeConversationEvents() {
  state.chatEvents?.close();state.chatEvents=null;state.chatEventKey=null;state.chatEventReady=null;state.chatEventSequence=0;state.chatStream=null;
}
function queueChatStreamRender() {
  if(state.chatRenderTimer)return;
  state.chatRenderTimer=setTimeout(()=>{state.chatRenderTimer=null;if(state.view&&!state.editing&&state.inspectorTab==='chat')render();},45);
}
function ensureConversationEvents(session) {
  const novelId=state.view?.novel.id;if(!novelId||!session)return Promise.resolve();
  const key=`${novelId}:${session.id}`;
  if(state.chatEvents&&state.chatEventKey===key)return state.chatEventReady||Promise.resolve();
  closeConversationEvents();state.chatEventKey=key;
  const source=new EventSource(`/api/novels/${novelId}/conversations/${session.id}/events`);state.chatEvents=source;
  state.chatEventReady=new Promise(resolve=>{
    let settled=false;const finish=()=>{if(!settled){settled=true;resolve();}};
    source.addEventListener('ready',finish,{once:true});source.addEventListener('open',finish,{once:true});setTimeout(finish,1200);
  });
  source.addEventListener('conversation',event=>{
    if(state.chatEventKey!==key||state.view?.novel.id!==novelId)return;
    let update;try{update=JSON.parse(event.data);}catch{return;}
    if(update.sequence&&update.sequence<=state.chatEventSequence)return;
    state.chatEventSequence=Number(update.sequence||state.chatEventSequence);
    if(['STARTED','STAGE','DELTA'].includes(update.type)){
      if(!state.chatStream||state.chatStream.turnId!==update.turnId)
        state.chatStream={sessionId:session.id,turnId:update.turnId,text:'',stage:update.stage||'UNDERSTANDING'};
      if(update.stage)state.chatStream.stage=update.stage;
      if(update.delta)state.chatStream.text+=update.delta;
      state.chatPendingThreadId=sessionThreadId(session);state.chatStickBottom=true;queueChatStreamRender();
    }
    if(['COMPLETED','FAILED','INTERRUPTED'].includes(update.type)){
      if(state.chatStream?.turnId===update.turnId)state.chatStream={...state.chatStream,stage:update.stage||update.type,finished:true};
      state.chatPendingThreadId=null;queueChatStreamRender();
      setTimeout(()=>refresh(true).catch(error),80);
    }
  });
  return state.chatEventReady;
}
async function selectBook(id) { if(!canLeave()) return; clearTimeout(state.poll);closeConversationEvents();state.shadowPollUntil=0; state.conversationThreadId=null; [state.view,state.conversations]=await Promise.all([api('/novels/'+id),api(`/novels/${id}/conversations`)]); syncConversationThread(); state.selected=state.view.pendingArtifactId || state.view.novel.artifacts.filter(a=>latest(a)).at(-1)?.id; state.version=null; render(); ensureConversationEvents(currentOutlineSession(state.view.novel)||latestOutlineSession()); schedulePoll(); }
async function refresh(selectPending=false) {
  if (!state.view) return;
  const id=state.view.novel.id;
  const [view,conversations]=await Promise.all([api('/novels/'+id),api(`/novels/${id}/conversations`)]);
  if(state.view?.novel.id!==id) return;
  state.view=view; state.conversations=conversations;
  syncConversationThread();
  ensureConversationEvents(currentOutlineSession(view.novel)||latestOutlineSession());
  if(selectPending && view.pendingArtifactId) {state.selected=view.pendingArtifactId;state.version=null;}
  if(!state.editing) render();
  await listBooks(); schedulePoll();
}
function schedulePoll() {
  clearTimeout(state.poll);
  const formalRunning=Boolean(state.view?.novel.tasks.some(active));
  const professionalRunning=Number(state.view?.shadowReviewSummary?.running||0)>0;
  const conversationRunning=activeConversationTurn();
  if(formalRunning||professionalRunning||conversationRunning) state.shadowPollUntil=Date.now()+5000;
  if(formalRunning||professionalRunning||conversationRunning||Date.now()<state.shadowPollUntil)
    state.poll=setTimeout(async()=>{ try{ await refresh(true); }catch(e){ error(e); state.poll=setTimeout(schedulePoll,2500); } },1300);
}
function openCreate() {
  if(!canLeave()) return;
  const f=$('#createForm'); f.reset(); f.elements.targetWords.value=state.mode==='demo'?1000:100000;
  $('#targetHint').textContent=state.mode==='demo'?'演示建议1000字，可在五章内验证完整闭环。目标字数不阻止完整故事提前完结。':'目标字数是篇幅参考；故事完整度优先，不会为了达标强制扩写。';
  $('#createDialog').showModal();
}
function renderWordSettings(n) {
  return `<details class="word-settings"><summary>修改字数</summary><div class="budget-popover"><form class="budget-form" id="budgetForm"><label>目标字数<input name="target" type="number" min="10" max="50000000" value="${n.targetWords}" required></label><label>字数上限<input name="max" type="number" min="11" max="100000000" value="${n.approvedMaxWords}" required></label><label>调整理由<input name="reason" maxlength="2000" required placeholder="例如：根据剧情调整整体篇幅"></label><button ${busy()?'disabled':''}>保存字数设置</button></form><p class="hint">目标是篇幅参考，不影响完整故事提前完结；上限是硬限制，必须高于目标且不能低于已确认正文。已完结作品修改后仍保持完结，不会重新执行完结检查。</p>${n.budgetChanges.length?`<p class="muted">已记录 ${n.budgetChanges.length} 次人工调整。</p>`:''}</div></details>`;
}
function renderAutoStyleSetting(n) {
  return `<label class="auto-style-setting" title="章节内容检查通过后，自动检查文风；只安全删除一次明确赘句"><input id="autoStyleToggle" type="checkbox" ${n.autoStyleEnabled?'checked':''} ${busy()?'disabled':''}><span>自动文风</span></label><small class="auto-style-note">${n.autoStyleEnabled?'已开启 · 每个候选最多处理一轮':'已关闭 · 可手动检查和优化'}</small>`;
}
function render() {
  const oldChat=$('.chat-scroll');
  if(oldChat){state.chatStickBottom=oldChat.scrollHeight-oldChat.scrollTop-oldChat.clientHeight<36;state.chatScrollTop=oldChat.scrollTop;}
  const view=state.view; if(!view) return;
  const n=view.novel; const next=view.nextAction;
  const visibleArtifacts=n.artifacts.filter(item=>latest(item));
  let a=visibleArtifacts.find(a=>a.id===state.selected) || visibleArtifacts.find(a=>a.id===view.pendingArtifactId) || visibleArtifacts.at(-1);
  state.selected=a?.id;
  const pending=n.artifacts.find(a=>a.id===view.pendingArtifactId);
  const nextText={CONFIRM:'阅读并确认当前内容',REPAIR:'修订下一项关联内容',BUDGET:'请修订内容或批准扩充',COMPLETED:'故事已完成',COMPLETE:'执行全书完结检查'}[next] || `生成${labels[next]}`;
  const tip=next==='PLAN'?'本批尚未写完也会提前规划，确认后用于衔接后续剧情。':next==='REPAIR'?'修改会保留历史版本；受影响内容需要依次修订并重新确认。':next==='CONFIRM'?'只有你能确认。模型不会替你跳过这一阶段。':'正文逐章确认，档案只记录已确认内容。';
  $('#main').innerHTML=`<div class="project-header"><div><div class="eyebrow">${n.status==='COMPLETED'?'完整作品':'正在创作'} / ${esc(labels[next])}</div><h1>${esc(n.title)}</h1><p>${esc(n.synopsis)}</p></div><div class="metrics"><strong>${number(view.wordCount)} <small>字</small></strong><progress max="${n.approvedMaxWords}" value="${view.wordCount}" aria-label="字数进度"></progress><small>目标 ${number(n.targetWords)} · 上限 ${number(n.approvedMaxWords)}</small><div class="project-settings">${renderWordSettings(n)}${renderAutoStyleSetting(n)}</div></div></div>
  <div class="workflow-bar"><div><strong>${esc(busy()?'任务正在执行，可关闭浏览器稍后回来':nextText)}</strong><p>${esc(tip)}</p></div><button class="primary" id="nextButton" ${busy()||['COMPLETED','BUDGET'].includes(next)?'disabled':''}>${esc(nextText)}</button></div>
  <div class="work-area"><section class="writing"><nav class="artifact-nav" aria-label="创作内容">${visibleArtifacts.map(x=>`<button class="artifact-button ${x.id===a?.id?'selected':''} ${x.needsRevision?'invalid':''}" data-artifact="${x.id}">${esc(x.kind==='CHAPTER'?`第${x.chapterNumber}章`:x.kind==='PLAN'?`第${x.batchNumber}批规划`:labels[x.kind])}${clean(x)?' ✓':x.needsRevision?' · 待修订':' · 待确认'}</button>`).join('')}</nav>
  ${a?renderArtifact(a,pending):'<div class="sheet empty-sheet">还没有生成内容。<br>点击上方按钮，先为故事建立大纲。</div>'}
  ${renderCompletion(n)}</section><aside class="inspector">${renderInspector(view)}</aside></div>`;
  renderBooks(); wireWorkspace();
  const chat=$('.chat-scroll');
  if(chat){chat.scrollTop=state.chatStickBottom?chat.scrollHeight:Math.min(state.chatScrollTop,chat.scrollHeight);chat.addEventListener('scroll',()=>{state.chatStickBottom=chat.scrollHeight-chat.scrollTop-chat.clientHeight<36;state.chatScrollTop=chat.scrollTop;});}
}
function renderArtifact(a,pending) {
  const v=a.versions.find(v=>v.id===state.version)||latest(a);
  const baseVersion=v.baseVersionId?a.versions.find(version=>version.id===v.baseVersionId):null;
  const incomplete=(v.draftIssues||[]).length>0;
  const summaryMissing=!String(v.summary||'').trim();
  const historical=v.id!==latest(a).id;
  const selectedPending=a.id===pending?.id;
  const canOperate=!busy()&&(!pending||selectedPending);
  const canRewrite=!busy()&&(!pending||state.view.novel.artifacts.indexOf(a)<=state.view.novel.artifacts.indexOf(pending));
  const editable=!busy();
  const badge=incomplete?'待完善':a.needsRevision?'待修订':historical?'历史版本':clean(a)?'已确认':'待确认';
  const report=v.review;
  const styleReport=v.styleReview;
  const reviewCurrent=v.reviewRevision===state.view.novel.revision
    && v.reviewPolicyVersion===state.view.reviewPolicyVersion;
  const stateExtractionReady=!v.stateExtractionRequired
    ||(v.stateExtractionStatus==='SUCCEEDED'&&v.stateExtractionPolicyVersion===state.view.stateExtractionPolicyVersion);
  const canRetryState=a.kind==='CHAPTER'&&!historical&&!clean(a)&&v.stateExtractionRequired
    && !stateExtractionReady&&reviewCurrent&&report?.passed;
  const styleReviewCurrent=v.styleReviewPolicyVersion===state.view.styleReviewPolicyVersion;
  const requiredIssues=report?.issueDetails?.filter(d=>(d.severity||'必须修正')==='必须修正')||[];
  const authorDecisionIssues=report?.issueDetails?.filter(d=>d.severity==='作者决定')||[];
  const fixRound=Number(v.contentFixRound||0);
  const outlineRepairUsed=a.kind==='OUTLINE'&&Number(v.outlineAutoRepairRound||0)>=1;
  const reviewHelp=report&&!report.passed&&!historical&&!clean(a)?`<p class="review-help">请优先修改当前的“${esc(artifactName(a))}”，不要直接改作为依据的已确认内容。系统只会处理“必须修正”，作者判断项不会自动改。</p>${outlineRepairUsed?'<p class="hint">唯一一轮大纲自动修订已经完成；系统不会继续循环，请查看当前版本后决定是否再次手动修订。</p>':''}${requiredIssues.length&&reviewCurrent&&fixRound<2?`<button type="button" id="autoFixButton">定点修订必须问题 · 第 ${fixRound+1}/2 轮</button>`:''}<button type="button" id="copyReviewButton">把检查意见填入修改要求</button>${fixRound>=2?'<p class="hint">已达到两轮自动修订上限，请由你判断下一步。</p>':''}`:'';
  const convergenceHelp=!historical&&fixRound>=2&&authorDecisionIssues.length
    ? '<p class="review-help">自动修订已停止：仍有问题被检查器重复报告，但不再阻塞你查看和确认。请阅读当前文章后决定直接确认，或手动修改。</p>':'';
  const stylePolishUsed=Number(v.stylePolishRound||0)>=1;
  const styleHelp=styleReport?.issueDetails?.length&&!historical
    ? `<p class="review-help">${stylePolishUsed?'已完成唯一一轮自动文风优化；剩余建议只供参考，不会继续循环修改。':'这些是阅读体验建议，不影响版本确认。安全自动修改只允许删除可精确定位的明确赘句；其他建议留给你判断。完成后会重新检查内容并复查一次文风。'}</p>${!stylePolishUsed&&styleReviewCurrent&&reviewCurrent&&report?.passed&&!clean(a)?'<button type="button" id="stylePolishButton">应用一次安全文风修改</button>':''}<button type="button" id="copyStyleReviewButton">把文风建议填入局部修改要求</button>`:'';
  const stateHelp=canRetryState?`<p class="review-help">正文和内容检查都已保留，但章节状态整理${v.stateExtractionStatus==='FAILED'?'失败':'尚未完成'}。只需重试状态提取，不会重新生成或修改正文。</p>`:'';
  const comparison=baseVersion&&baseVersion.content!==v.content?`<details class="version-compare"><summary>对照修改前后</summary><p class="hint">左侧是本次修改所依据的版本，右侧是当前版本。这里不会修改任何内容。</p><div class="version-compare-grid"><section><strong>修改前</strong><div class="compare-prose">${esc(baseVersion.content)}</div></section><section><strong>修改后</strong><div class="compare-prose">${esc(v.content)}</div></section></div></details>`:'';
  return `<article class="sheet"><div class="sheet-head"><h2>${esc(v.title)}</h2><span class="badge ${a.needsRevision||incomplete?'warn':''}">${badge}</span></div><div class="sheet-body"><div class="version-line"><span>版本</span><select id="versionSelect" aria-label="内容版本">${a.versions.map((x,i)=>`<option value="${x.id}" ${x.id===v.id?'selected':''}>v${i+1} · ${Number(x.outlineAutoRepairRound||0)>=1?'大纲自动修订':x.source==='DEMO'?'离线演示':x.source==='USER'?'手动编辑':x.source==='SYSTEM'?'安全自动修改':'模型生成'}${x.id===a.approvedVersionId?' · 已确认':x.dismissed?' · 已放弃':''}</option>`).join('')}</select>${a.kind==='CHAPTER'?`<span>${number(countWords(v.content))} 字</span>`:''}</div>
  <div id="contentArea"><div class="prose">${esc(v.content)}</div>${v.plan?renderPlan(v.plan):''}<details class="details"><summary>内容摘要与档案增量</summary><p>${esc(v.summary)}</p>${renderFacts(v.facts)}${renderStateEntities(v.stateEntities)}${renderStateRelations(v.stateRelations,v.stateEntities)}</details>${comparison}</div>
  ${report?`<div class="review ${report.passed?'':'warning'}"><strong>${report.passed?'检查已返回':'检查发现问题'}${!reviewCurrent&&!clean(a)?' · 依据已变化，需要重查':''}</strong>${reviewHelp}${convergenceHelp}<ul>${report.issueDetails?.length?report.issueDetails.map(d=>`<li>${renderReviewDetail(d,a,v)}</li>`).join(''):report.issues.map(i=>`<li>${renderReviewIssue(i,a,v)}</li>`).join('')||'<li>未发现明确冲突；仍需你阅读确认。</li>'}</ul>${!report.passed&&!historical&&!clean(a)?'<label>如需接受当前问题并确认，请填写人工复核理由（必填，且不能绕过字数等硬规则）<input id="overrideReason" maxlength="2000" placeholder="说明你为什么接受当前问题，至少5个字符"></label>':''}</div>`:'<p class="hint">手动修改后，需要重新检查摘要、档案与正文一致性。</p>'}
  ${a.kind==='CHAPTER'&&styleReport?`<div class="review"><strong>文风检查${styleReviewCurrent?'':' · 规则已更新，可重新检查'}</strong>${styleHelp}<ul>${styleReport.issueDetails?.length?styleReport.issueDetails.map(d=>`<li>${renderReviewDetail(d,a,v)}</li>`).join(''):'<li>未发现明显的赘述、无意义精确时间或装饰性环境罗列。</li>'}</ul></div>`:''}
  ${stateHelp}
  <div class="controls"><button id="editButton" ${editable?'':'disabled'}>${historical?'以此版本建立新草稿':a.kind==='OUTLINE'&&v.outlineSpec?'编辑大纲结构':'手动编辑'}</button>${a.kind==='CHAPTER'&&!historical?`<button id="styleReviewButton" ${busy()?'disabled':''}>${styleReport&&styleReviewCurrent?'重新检查文风':'检查文风'}</button>`:''}${!historical&&!clean(a)?`${incomplete?(summaryMissing?`<button id="summaryButton" ${canOperate&&!a.needsRevision?'':'disabled'}>只补充摘要</button>`:`<button id="repairDraftButton" ${canRewrite?'':'disabled'}>补充缺失内容</button>`):(!report||!reviewCurrent?`<button id="reviewButton" ${canOperate&&!a.needsRevision?'':'disabled'}>重新检查</button>`:'')}${canRetryState?`<button id="stateExtractionButton" ${canOperate?'':'disabled'}>只重试状态提取</button>`:''}<button id="confirmButton" class="primary" ${canOperate&&!a.needsRevision&&!incomplete&&report&&reviewCurrent&&stateExtractionReady?'':'disabled'}>确认这个版本</button>`:''}</div>
  ${!historical?`<details class="details" id="rewriteDetails"><summary>让 Agent 按要求修改</summary><label>修改要求<textarea id="rewriteInstructions" rows="3" maxlength="20000" placeholder="说明需要改变什么；已确认内容被修改后，相关后续内容会进入待修订。"></textarea></label><button id="rewriteButton" ${canRewrite?'':'disabled'}>生成修订版本</button></details>`:''}</div></article>`;
}
function renderPlan(p) {
  return `<div class="plan-info"><strong>第 ${p.startChapter}—${p.endChapter} 章 ${p.finalBatch?'· 最终批次':''}</strong><p>${esc(p.triggerReason)}</p><p>${p.finalBatch?'本批负责主线收尾':`第 ${p.prepareNextAfterChapter} 章确认后，提前规划下一批。`}</p><p>衔接：${esc(p.handoff)}</p><p>未发生的假设：${esc(p.assumptions)}</p><ol start="${p.startChapter}">${p.chapters.map(c=>`<li><strong>${esc(c.title)}</strong> — ${esc(c.purpose)}</li>`).join('')}</ol></div>`;
}
function renderCompletion(n) {
  if(n.status==='COMPLETED') return '<section class="finish-panel"><h3>这部故事，已走到结尾。</h3><p>主线、结局和伏笔已由你确认。全部内容及历史版本保存在本机。</p></section>';
  const c=n.completionChecks.at(-1); if(!c) return '';
  const valid=c.revision===n.revision, r=c.review;
  const issues=r.issueDetails?.length?r.issueDetails.map(d=>renderReviewDetail(d,null,null)).join('<br><br>'):(r.issues||[]).map(i=>esc(humanizeIssue(i,null))).join('<br>');
  return `<section class="finish-panel"><h3>全书完结检查 ${valid?'':'· 已过期'}</h3><p>主线 ${r.mainlineResolved?'✓':'未完成'} · 明确结局 ${r.endingClear?'✓':'未完成'} · 伏笔回收 ${r.foreshadowingResolved?'✓':'未完成'}</p><p>${issues}</p>${valid&&r.passed&&r.mainlineResolved&&r.endingClear&&r.foreshadowingResolved?'<label class="check-label"><input type="checkbox" id="finishAcknowledge">我已核对故事主线、明确结局和重要伏笔，确认全书完成。</label><button id="finishButton" class="primary">确认完结</button>':''}</section>`;
}
const shadowCheckerName = checker => ({CONTINUITY:'连续性检查',HISTORICAL_CONTINUITY:'历史连续性（灰度）',PLOT_FORESHADOW:'情节与伏笔检查'}[checker]||'专业检查');
const shadowDecisionName = decision => ({USEFUL:'有帮助',PARTLY_USEFUL:'部分有帮助',NOT_USEFUL:'没有帮助'}[decision]||'未评价');
function durationText(milliseconds) {
  if(milliseconds===null||milliseconds===undefined) return '暂无';
  if(milliseconds<1000) return `${milliseconds} 毫秒`;
  return `${(milliseconds/1000).toFixed(milliseconds<10000?1:0)} 秒`;
}
function renderShadowExperiments(summary) {
  if(!summary?.totalReports) return `<section><h3>专业检查实验</h3><p class="muted">后续新生成或修订的内容会在后台接受连续性、情节与伏笔检查；旧内容不会追溯调用模型。</p></section>`;
  const allReports=summary.reports||[];
  const sampled=allReports.filter(report=>report.evaluationSample);
  const reports=(sampled.length?sampled:allReports.slice(0,8)).map(report=>{
    const issues=report.review?.issueDetails||[];
    const issueList=issues.length?`<ul>${issues.slice(0,5).map(issue=>`<li><strong>${esc(issue.severity||'建议')}</strong>：${esc(humanizeIssue(issue.problem,null))}</li>`).join('')}${issues.length>5?`<li>另有 ${issues.length-5} 条，详见原始报告。</li>`:''}</ul>`:'<p class="muted">没有提出具体问题。</p>';
    const buttons=report.status==='SUCCEEDED'?`<div class="feedback-buttons" aria-label="评价这份专业检查"><span>这份检查：</span>${[['USEFUL','有帮助'],['PARTLY_USEFUL','部分有帮助'],['NOT_USEFUL','没有帮助']].map(([value,label])=>`<button data-shadow-feedback="${report.id}" data-decision="${value}" class="${report.authorDecision===value?'active':''}">${label}</button>`).join('')}</div>`:'';
    const comparison=report.status==='SUCCEEDED'?`正式检查必改 ${report.formalBlockingFindings} 条 · 专业检查必改 ${report.blockingFindings} 条 · 重合 ${report.overlappingFindings} 条 · 新发现 ${report.uniqueFindings} 条`:esc(statuses[report.status]||report.status);
    const sampleLabel=report.evaluationSample?`<span class="badge">抽样验收</span>`:'';
    return `<details class="shadow-report"><summary><span>${esc(report.targetLabel)} · ${esc(shadowCheckerName(report.checker))}</span><span>${sampleLabel}<span class="badge ${report.status==='FAILED'||report.status==='INTERRUPTED'?'error':''}">${esc(statuses[report.status]||report.status)}</span></span></summary><p>${comparison}</p>${report.sampleGroup?`<p class="muted">样本分组：${esc(report.sampleGroup.replaceAll('|',' · '))}</p>`:''}<p class="muted">总耗时 ${durationText(report.turnaroundMillis)} · 模型响应 ${durationText(report.modelDurationMillis)} · 作者评价：${esc(shadowDecisionName(report.authorDecision))}</p>${report.error?`<p class="shadow-error">${esc(friendlyError(report.error))}</p>`:issueList}${buttons}</details>`;
  }).join('');
  const checkerRows=(summary.checkerSummaries||[]).map(item=>`<span>${esc(shadowCheckerName(item.checker))}：成功 ${item.succeeded}/${item.reports} · 重合 ${item.overlappingFindings} · 新发现 ${item.uniqueFindings} · 抽样已评 ${item.sampleReviewed}/${item.sampleSize}</span>`).join('');
  return `<section><h3>专业检查实验</h3><div class="shadow-summary"><strong>${summary.totalReports} 份报告</strong><span>成功 ${summary.succeeded} · 失败 ${summary.failed} · 进行中 ${summary.running} · 中断 ${summary.interrupted}</span><span>固定抽样 ${summary.sampleSize} 份 · 已评价 ${summary.sampleReviewed} · 待评价 ${summary.samplePending}</span>${checkerRows}<span>已开始模型调用 ${summary.modelCalls} 次 · 平均总耗时 ${durationText(summary.averageTurnaroundMillis)} · 平均模型响应 ${durationText(summary.averageModelDurationMillis)}</span><span>必改问题：语义重合 ${summary.overlappingFindings} · 专业检查新增 ${summary.uniqueShadowFindings}</span><span>全部作者评价：有帮助 ${summary.useful} · 部分有帮助 ${summary.partlyUseful} · 没有帮助 ${summary.notUseful} · 未评价 ${summary.unreviewed}</span></div><p class="muted">下面只展示覆盖大纲、规划、正文前中后段，以及“发现问题/未发现问题”的固定代表样本。语义重合是统计估算，不会参与正文修改或确认；当前模型接口没有可靠的 token 与价格数据，因此不显示金额。</p>${reports}</section>`;
}
const decisionTypeName=type=>({MUST_KEEP:'必须保留',MUST_CHANGE:'必须修改',FORBID:'禁止出现',PREFERENCE:'作者偏好',OPEN_QUESTION:'待决定',ASSUMPTION:'暂定假设'}[type]||'创作决定');
const decisionStatusName=status=>({PROPOSED:'待采纳',ACCEPTED:'已采纳',REJECTED:'已拒绝',WITHDRAWN:'已撤销'}[status]||status);
const projectFieldName=field=>({TITLE:'书名',SYNOPSIS:'简介',REQUIREMENTS:'补充要求',TARGET_WORDS:'目标字数'}[field]||field);
const projectUpdateStatusName=status=>({PROPOSED:'待选择',ACCEPTED:'待保存',REJECTED:'保留原内容',APPLIED:'已保存',STALE:'已过期'}[status]||status);
const sessionThreadId=session=>session?.threadId||session?.id;
function outlineThreads() {
  const byThread=new Map();
  state.conversations.filter(session=>session.scope==='OUTLINE').forEach(session=>byThread.set(sessionThreadId(session),session));
  return [...byThread.values()];
}
function syncConversationThread() {
  const threads=outlineThreads();
  if(!threads.some(session=>sessionThreadId(session)===state.conversationThreadId))
    state.conversationThreadId=sessionThreadId(threads.at(-1))||null;
}
function currentOutlineSession(n,threadId=state.conversationThreadId) {
  const outline=n.artifacts.filter(a=>a.kind==='OUTLINE'&&latest(a)).at(-1),version=latest(outline);
  return state.conversations.filter(s=>s.scope==='OUTLINE'&&s.baseRevision===n.revision
    &&sessionThreadId(s)===threadId
    &&(s.targetArtifactId||null)===(outline?.id||null)&&(s.baseVersionId||null)===(version?.id||null)).at(-1);
}
function latestOutlineSession(threadId=state.conversationThreadId) { return state.conversations.filter(s=>s.scope==='OUTLINE'&&sessionThreadId(s)===threadId).at(-1); }
function upsertConversation(session,select=true) {
  const index=state.conversations.findIndex(item=>item.id===session.id);
  if(index<0)state.conversations.push(session);else state.conversations[index]=session;
  if(select)state.conversationThreadId=sessionThreadId(session);
}
const taskStageName=stage=>({QUEUED:'等待开始',PREPARING:'正在整理上下文',DESIGNING:'正在设计人物与世界',GENERATING:'正在生成大纲',CHECKING_STRUCTURE:'正在检查大纲结构',CHECKING_CONTINUITY:'正在检查前后逻辑',CHECKING_PLOT:'正在检查情节与伏笔',CHECKING:'正在检查内容',EXTRACTING_STATE:'正在整理章节状态',READY:'生成和检查已完成',FAILED:'执行失败',CANCELLED:'已取消',STALE:'结果已过期',INTERRUPTED:'服务中断'}[stage]||'正在处理');
const conversationStageName=stage=>({UNDERSTANDING:'正在理解你的要求',CONNECTING:'正在连接创作助手',GENERATING:'正在回复',FINALIZING:'正在整理结果',COMPLETED:'已完成',FAILED:'回复失败',INTERRUPTED:'已停止'}[stage]||'正在处理');
function proposalTask(n,proposal) {
  const first=n.tasks.find(task=>task.id===proposal.taskId); if(!first)return null;
  const followups=n.tasks.filter(task=>task.automationKind==='OUTLINE_AUTO_REPAIR'&&first.resultArtifactId&&task.artifactId===first.resultArtifactId&&task.createdAt>=first.createdAt);
  return followups.at(-1)||first;
}
function renderAgentActivity(n,proposal) {
  const task=proposalTask(n,proposal);
  if(!task) return `<div class="agent-run-card error"><strong>任务记录暂不可用</strong></div>`;
  const stage=task.status==='SUCCEEDED'?'READY':['FAILED','CANCELLED','STALE','INTERRUPTED'].includes(task.status)?task.status:(task.progressStage||task.status);
  const checking=['CHECKING_STRUCTURE','CHECKING_CONTINUITY','CHECKING_PLOT','CHECKING','EXTRACTING_STATE'].includes(stage);
  const finished=task.status==='SUCCEEDED',failed=['FAILED','CANCELLED','STALE','INTERRUPTED'].includes(task.status);
  const steps=[['理解与设计',!['QUEUED','PREPARING'].includes(stage)],['生成候选',checking||finished],['检查与整理',finished]];
  const timeline=steps.map(([label,done],index)=>`<li class="${done?'done':!failed&&((index===0&&!done)||(index===1&&stage==='GENERATING')||(index===2&&checking))?'active':failed?'stopped':''}"><span></span>${label}</li>`).join('');
  if(!finished) return `<div class="agent-run-card ${failed?'error':''}"><div class="agent-run-heading"><strong>${esc(taskStageName(stage))}</strong><span>${failed?'已停止':'Agent 正在工作'}</span></div><ol>${timeline}</ol>${task.error?`<p>${esc(friendlyError(task.error))}</p>`:''}${active(task)?`<button data-cancel="${task.id}">停止</button>`:''}</div>`;
  const artifact=n.artifacts.find(item=>item.id===task.resultArtifactId),candidate=artifact?.versions.find(version=>version.id===task.resultVersionId)||latest(artifact);
  if(!artifact||!candidate) return `<div class="agent-run-card error"><strong>任务已完成，但没有找到候选内容</strong></div>`;
  const approved=artifact.approvedVersionId===candidate.id,dismissed=Boolean(candidate.dismissed);
  const reviewCurrent=candidate.review&&candidate.reviewRevision===n.revision;
  const stateExtractionReady=!candidate.stateExtractionRequired
    ||(candidate.stateExtractionStatus==='SUCCEEDED'&&candidate.stateExtractionPolicyVersion===state.view.stateExtractionPolicyVersion);
  const canConfirm=!busy()&&!dismissed&&!approved&&(candidate.draftIssues||[]).length===0&&reviewCurrent&&candidate.review.passed&&stateExtractionReady;
  const canDismiss=!busy()&&!dismissed&&!approved&&latest(artifact)?.id===candidate.id;
  const resultText=approved?'你已采用这个版本':dismissed?'你已放弃这个候选':candidate.review?.passed?'生成与检查已完成，可以阅读后决定':'候选已保留，请查看检查问题后继续修改';
  return `<div class="agent-run-card ready"><div class="agent-run-heading"><strong>${esc(resultText)}</strong><span>${approved?'已采用':dismissed?'已放弃':'等待你的决定'}</span></div><ol>${timeline}</ol><div class="agent-result-actions"><button data-chat-view="${artifact.id}">查看结果</button>${canConfirm?`<button class="primary" data-chat-confirm="${artifact.id}" data-version="${candidate.id}">同意并采用</button>`:''}${canDismiss?`<button class="danger" data-chat-dismiss="${artifact.id}" data-version="${candidate.id}">放弃本次候选</button>`:''}</div></div>`;
}
function renderConversation(v) {
  const n=v.novel,threads=outlineThreads(),current=currentOutlineSession(n),session=current||latestOutlineSession();
  const threadPicker=threads.length?`<select id="conversationThreadSelect" aria-label="选择对话">${threads.slice().reverse().map(item=>`<option value="${esc(sessionThreadId(item))}" ${sessionThreadId(item)===state.conversationThreadId?'selected':''}>${esc(item.threadTitle||'未命名对话')}</option>`).join('')}</select>`:'';
  const heading=`<div class="conversation-heading"><div><h3>大纲创作对话</h3>${session?`<small>${current?'已连接当前版本':'内容版本已变化，下一条消息会自动接续'} · 不同对话的聊天记忆互相隔离</small>`:'<small>每个新对话都有独立聊天记忆</small>'}</div><div class="conversation-tools">${threadPicker}<button type="button" id="newConversation" ${busy()?'disabled':''}>＋ 新建</button></div></div>`;
  if(!session) return `<section class="conversation-panel">${heading}<div class="conversation-empty"><p class="muted">先和 AI 讨论主线、人物变化、世界规则或结局，也可以直接要求它生成大纲。</p><button id="startConversation" ${busy()?'disabled':''}>开始第一个对话</button></div></section>`;
  const stale=!current;
  const turnForMessage=message=>(session.turns||[]).find(turn=>turn.userMessageId===message.id);
  const messages=(session.messages||[]).map(message=>{
    const turn=message.role==='USER'?turnForMessage(message):null;
    const turnState=['FAILED','INTERRUPTED'].includes(turn?.status)?`<div class="chat-turn-state failed"><span>${esc(friendlyError(turn.error||'本轮回复失败'))}</span><button type="button" data-turn-retry="${turn.id}">重试这条消息</button></div>`:'';
    return `<div class="chat-message ${message.role==='USER'?'from-user':'from-agent'}"><small>${message.role==='USER'?'你':'创作助手'}</small><p>${esc(message.content)}</p>${turnState}</div>`;
  }).join('');
  const runningTurn=(session.turns||[]).find(turn=>turn.status==='RUNNING');
  const stream=state.chatStream?.sessionId===session.id&&(!runningTurn||!state.chatStream.turnId||state.chatStream.turnId===runningTurn.id)?state.chatStream:null;
  const pendingTurnId=runningTurn?.id||stream?.turnId;
  const pendingStage=stream?.stage||runningTurn?.stage||'UNDERSTANDING';
  const pendingText=stream?.text||'';
  const pending=(runningTurn||state.chatPendingThreadId===state.conversationThreadId||stream&&!stream.finished)
    ?`<div class="chat-message from-agent thinking streaming"><small>创作助手</small>${pendingText?`<p class="streamed-reply">${esc(pendingText)}</p>`:'<p><span class="thinking-dot"></span>正在准备回复…</p>'}<div class="chat-stream-state"><span>${esc(conversationStageName(pendingStage))}</span>${pendingTurnId?`<button type="button" data-turn-cancel="${pendingTurnId}">停止</button>`:''}</div></div>`:'';
  const decisions=(session.decisions||[]).map(decision=>`<div class="decision-card ${decision.status.toLowerCase()}"><div><strong>${esc(decisionTypeName(decision.type))}</strong><span>${esc(decisionStatusName(decision.status))}</span></div><p>${esc(decision.text)}</p>${stale?'':`<div class="decision-actions">${decision.status!=='ACCEPTED'?`<button data-decision="${decision.id}" data-status="ACCEPTED">采纳</button>`:`<button data-decision="${decision.id}" data-status="WITHDRAWN">撤销</button>`}${decision.status==='PROPOSED'?`<button data-decision="${decision.id}" data-status="REJECTED">拒绝</button>`:''}</div>`}</div>`).join('');
  const accepted=(session.decisions||[]).filter(decision=>decision.status==='ACCEPTED').length;
  const projectUpdates=(session.projectUpdates||[]).map(update=>`<div class="project-update-card ${update.status.toLowerCase()}"><div class="project-update-heading"><strong>${esc(projectFieldName(update.field))}</strong><span>${esc(projectUpdateStatusName(update.status))}</span></div><div class="project-update-compare"><div><small>当前内容</small><p>${esc(update.previousValue||'（空）')}</p></div><div><small>建议改为</small><p>${esc(update.proposedValue)}</p></div></div>${update.reason?`<p class="project-update-reason">说明：${esc(update.reason)}</p>`:''}${stale?'':`<div class="decision-actions">${['PROPOSED','REJECTED'].includes(update.status)?`<button data-project-update="${update.id}" data-status="ACCEPTED">接受修改</button>`:''}${['PROPOSED','ACCEPTED'].includes(update.status)?`<button data-project-update="${update.id}" data-status="REJECTED">保留原内容</button>`:''}</div>`}</div>`).join('');
  const acceptedProjectUpdates=(session.projectUpdates||[]).filter(update=>update.status==='ACCEPTED').length;
  const projectUpdateSection=projectUpdates?`<div class="conversation-block"><h4>项目信息修改</h4><p class="muted">AI 只能提出修改。逐项选择后统一保存，保存时才会真正更新作品资料。</p>${projectUpdates}${acceptedProjectUpdates&&!stale?`<button class="primary apply-project-updates" data-apply-project-updates ${busy()?'disabled':''}>保存已接受的修改（${acceptedProjectUpdates} 项）</button><p class="muted">保存后旧的大纲操作提案会失效；已有大纲将进入待复核状态。</p>`:''}</div>`:'';
  const proposals=(session.proposals||[]).map(proposal=>proposal.status==='EXECUTED'?renderAgentActivity(n,proposal):`<div class="action-proposal"><strong>${proposal.executionError?'没有成功开始任务':session.targetArtifactId?'准备修订大纲':'准备生成大纲'}</strong><p>${esc(proposal.executionError?friendlyError(proposal.executionError):proposal.instructions||'调用正式大纲流水线')}</p>${proposal.status==='STALE'?'<span class="badge warn">已过期</span>':`<button class="primary" data-execute-proposal="${proposal.id}" ${busy()||acceptedProjectUpdates>0||(!proposal.automaticExecution&&accepted===0)?'disabled':''}>${proposal.executionError?'重新尝试':'开始生成'}</button>`}</div>`).join('');
  const decisionSection=decisions?`<details class="conversation-block decision-summary"><summary>本次对话整理了 ${session.decisions.length} 条创作要求</summary>${decisions}</details>`:'';
  const draft=chatDraft(n.id,state.conversationThreadId);
  return `<section class="conversation-panel">${heading}<div class="chat-scroll"><div class="chat-history">${messages||'<p class="muted">这是一个全新的对话，不会读取其他对话的聊天记录。说说你的想法，或直接要求生成大纲。</p>'}${pending}</div>${decisionSection}${projectUpdateSection}${proposals?`<div class="conversation-block"><h4>Agent 工作</h4>${proposals}</div>`:''}</div><form id="conversationForm"><textarea name="message" aria-label="给创作助手发送消息" rows="3" maxlength="10000" required placeholder="Enter 发送，Shift + Enter 换行">${esc(draft)}</textarea><button class="chat-send-button" aria-label="发送消息" title="发送" ${busy()?'disabled':''}><span aria-hidden="true">↑</span></button></form></section>`;
}
function renderTaskInspector(v) {
  const n=v.novel,tasks=n.tasks.slice(-6).reverse(),invalid=n.artifacts.filter(a=>a.needsRevision);
  return `<section><h3>任务进度</h3>${tasks.length?tasks.map(t=>`<div class="task"><div class="task-line"><span>${esc(taskName(t))}</span><span>${esc(statuses[t.status])}</span></div>${t.error?`<p>${esc(friendlyError(t.error))}</p>${technicalError(t.error)?`<details><summary class="muted">技术诊断（排查故障时使用）</summary><pre>${esc(technicalError(t.error))}</pre></details>`:''}`:''}${active(t)?`<button data-cancel="${t.id}">取消任务</button>`:['FAILED','CANCELLED','STALE','INTERRUPTED'].includes(t.status)?`<button data-retry="${t.id}" ${busy()?'disabled':''}>手动重试</button>`:''}</div>`).join(''):'<p class="muted">生成任务会显示在这里。</p>'}</section>${invalid.length?`<section><h3>关联修订 · ${invalid.length} 项</h3><p>已暂停受影响的续写，按内容顺序修订并重新确认。</p><ul>${invalid.map(a=>`<li>${esc(latest(a).title)}</li>`).join('')}</ul></section>`:''}${renderShadowExperiments(v.shadowReviewSummary)}`;
}
function renderCanonInspector(v) {
  return `<section><h3>小说档案</h3><p class="muted">只来自已确认设定与正文，不含对话和未来规划。</p>${v.canon.length?v.canon.map(e=>`<div class="fact"><strong>${esc(e.fact.detail)} ${e.fact.state==='OPEN'?'· 待回收':e.fact.state==='RESOLVED'?'· 已回收':''}</strong><small>类别：${esc(factTypeName(e.fact.type))} · ${e.chapter?'来源：第'+e.chapter+'章':'来源：人物设定'}</small></div>`).join(''):'<p class="muted">确认人物设定与正文后，档案将在这里积累。</p>'}</section>`;
}
function renderInspector(v) {
  const body=state.inspectorTab==='tasks'?renderTaskInspector(v):state.inspectorTab==='canon'?renderCanonInspector(v):renderConversation(v);
  return `<nav class="inspector-tabs" aria-label="右侧工具"><button data-inspector-tab="chat" class="${state.inspectorTab==='chat'?'active':''}">创作对话</button><button data-inspector-tab="tasks" class="${state.inspectorTab==='tasks'?'active':''}">任务进度</button><button data-inspector-tab="canon" class="${state.inspectorTab==='canon'?'active':''}">小说档案</button></nav><div class="inspector-content">${body}</div>`;
}
async function generate(action,artifactId=null,instructions='') {
  const n=state.view.novel;
  await api(`/novels/${n.id}/generation-tasks`,{action,artifactId,instructions,requestKey:crypto.randomUUID(),revision:n.revision});
  await refresh(true);
}
function wireWorkspace() {
  $('.inspector-tabs')?.addEventListener('click',event=>{
    const button=event.target.closest('[data-inspector-tab]'); if(!button)return;
    state.inspectorTab=button.dataset.inspectorTab; render();
  });
  $('#startConversation')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    const session=await api(`/novels/${n.id}/conversations/outline`,{revision:n.revision,newThread:true});upsertConversation(session);await ensureConversationEvents(session);
    toast('大纲对话已开始。聊天内容不会直接修改作品。');
  }));
  $('#newConversation')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    const session=await api(`/novels/${n.id}/conversations/outline`,{revision:n.revision,newThread:true});upsertConversation(session);await ensureConversationEvents(session);
    state.chatStickBottom=true;state.chatScrollTop=0;
    toast('已新建独立对话，不会继承其他对话的聊天记忆。');
  }));
  $('#conversationThreadSelect')?.addEventListener('change',event=>{
    state.conversationThreadId=event.target.value;state.chatStickBottom=true;state.chatScrollTop=0;render();ensureConversationEvents(currentOutlineSession(state.view.novel)||latestOutlineSession());
  });
  const conversationForm=$('#conversationForm'),conversationInput=conversationForm?.elements.message;
  conversationInput?.addEventListener('input',event=>saveChatDraft(event.target.value));
  conversationInput?.addEventListener('compositionstart',event=>event.target.dataset.composing='1');
  conversationInput?.addEventListener('compositionend',event=>event.target.dataset.composing='0');
  conversationInput?.addEventListener('keydown',event=>{
    if(event.key!=='Enter'||event.shiftKey||event.isComposing||event.target.dataset.composing==='1')return;
    event.preventDefault();conversationForm.requestSubmit();
  });
  conversationForm?.addEventListener('submit',event=>{event.preventDefault();const data=new FormData(event.target);perform(async()=>{
    const n=state.view.novel,novelId=n.id;let session=currentOutlineSession(n);const message=String(data.get('message')||'').trim();
    if(!message)throw new Error('请输入想讨论的内容。');
    if(!session){session=await api(`/novels/${n.id}/conversations/outline`,{revision:n.revision,threadId:state.conversationThreadId,newThread:false});upsertConversation(session);}
    await ensureConversationEvents(session);
    const submittedThreadId=sessionThreadId(session),requestKey=crypto.randomUUID(),optimisticId=`local-${requestKey}`;
    saveChatDraft('',novelId,submittedThreadId);
    session.messages=[...(session.messages||[]),{id:optimisticId,role:'USER',content:message,createdAt:new Date().toISOString()}];
    upsertConversation(session,false);state.chatPendingThreadId=submittedThreadId;state.chatStickBottom=true;render();
    try{
      const reply=await api(`/novels/${novelId}/conversations/${session.id}/messages`,{content:message,requestKey});
      if(state.view?.novel.id===novelId){upsertConversation(reply,state.conversationThreadId===submittedThreadId);await refresh(true);}
    }
    catch(problem){
      if(state.view?.novel.id===novelId){
        await refresh(false);
        const persistedTurn=state.conversations.flatMap(item=>sessionThreadId(item)===submittedThreadId?(item.turns||[]):[]).find(turn=>turn.requestKey===requestKey);
        if(persistedTurn?.status==='INTERRUPTED')return;
        const persisted=Boolean(persistedTurn);
        if(!persisted)saveChatDraft(message,novelId,submittedThreadId);
      } else saveChatDraft(message,novelId,submittedThreadId);
      throw problem;
    }
    finally{state.chatPendingThreadId=null;}
  });});
  $('#nextButton')?.addEventListener('click',()=>perform(async()=>{
    if(!canLeave()) return;
    if(state.view.nextAction==='CONFIRM') {state.selected=state.view.pendingArtifactId;state.version=null;render();return;}
    if(state.view.nextAction==='REPAIR') {await generate('REWRITE',state.view.pendingArtifactId,'根据已确认的新上游内容修订本项，同步摘要和档案，并保持大纲一致。');return;}
    await generate(state.view.nextAction);
  }));
  $('[aria-label="创作内容"]')?.addEventListener('click',e=>{const b=e.target.closest('[data-artifact]');if(b&&canLeave()){state.selected=b.dataset.artifact;state.version=null;render();}});
  $('#versionSelect')?.addEventListener('change',e=>{state.version=e.target.value;render();});
  $('#editButton')?.addEventListener('click',openEditor);
  $('#copyReviewButton')?.addEventListener('click',()=>{
    const a=state.view.novel.artifacts.find(a=>a.id===state.selected),v=latest(a),box=$('#rewriteInstructions'),details=$('#rewriteDetails');
    if(!box||!v?.review) return;
    const issues=reviewIssueText(v.review,v);
    box.value=rewriteReviewPreamble(a)+'\n'+issues.map((i,index)=>`${index+1}. ${i}`).join('\n');
    details.open=true;box.focus();toast('检查意见已填入；你可以补充要求后再生成，不会自动调用模型。');
  });
  $('#autoFixButton')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    await api(`/novels/${n.id}/artifacts/${state.selected}/content-fix-tasks`,{requestKey:crypto.randomUUID(),revision:n.revision});
    toast('已开始定点修订；只处理必须修正问题。');await refresh(true);
  }));
  $('#copyStyleReviewButton')?.addEventListener('click',()=>{
    const a=state.view.novel.artifacts.find(a=>a.id===state.selected),v=latest(a),box=$('#rewriteInstructions'),details=$('#rewriteDetails');
    if(!box||!v?.styleReview) return;
    const issues=styleReviewIssueText(v.styleReview,v);
    box.value=`请只对当前“${artifactName(a)}”做局部文字替换或删除，逐条处理下面的文风建议。不能返回整篇正文，不能改变剧情、事实、人物选择、伏笔、摘要、档案或章节规划。\n`+issues.map((i,index)=>`${index+1}. ${i}`).join('\n');
    details.open=true;box.focus();toast('文风建议已填入；你可以删掉不认可的建议，再生成局部修改。');
  });
  $('#reviewButton')?.addEventListener('click',()=>perform(()=>generate('REVIEW',state.selected)));
  $('#styleReviewButton')?.addEventListener('click',()=>perform(()=>generate('STYLE_REVIEW',state.selected)));
  $('#stylePolishButton')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    await api(`/novels/${n.id}/artifacts/${state.selected}/style-polish-tasks`,{requestKey:crypto.randomUUID(),revision:n.revision});
    toast('已开始唯一一轮安全文风修改；完成后会重新检查内容和文风。');await refresh(true);
  }));
  $('#summaryButton')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    await api(`/novels/${n.id}/artifacts/${state.selected}/summary-tasks`,{requestKey:crypto.randomUUID(),revision:n.revision});
    await refresh(true);
  }));
  $('#stateExtractionButton')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel;
    await api(`/novels/${n.id}/artifacts/${state.selected}/state-extraction-tasks`,{requestKey:crypto.randomUUID(),revision:n.revision});
    toast('已开始重新整理章节状态；正文和检查结论不会重做。');await refresh(true);
  }));
  $('#repairDraftButton')?.addEventListener('click',()=>{
    const a=state.view.novel.artifacts.find(a=>a.id===state.selected),v=latest(a);
    const box=$('#rewriteInstructions'),details=$('#rewriteDetails'); if(!box||!details) return;
    box.value='只补充当前版本缺失的档案增量：仅记录正文中已经发生的事实变化，不改标题、正文、摘要或章节规划。\n'+(v.draftIssues||[]).join('\n');
    details.open=true;box.focus();toast('缺失项已填入修改要求；确认后再生成，不会自动调用模型。');
  });
  $('#confirmButton')?.addEventListener('click',()=>perform(async()=>{
    const n=state.view.novel,a=n.artifacts.find(a=>a.id===state.selected);
    state.view=await api(`/novels/${n.id}/artifacts/${a.id}/versions/${latest(a).id}/confirm`,{revision:n.revision,overrideReason:$('#overrideReason')?.value||null});
    toast('版本已确认，进度与档案已保存。'); await refresh(true);
  }));
  $('#rewriteButton')?.addEventListener('click',()=>perform(async()=>{
    const instructions=$('#rewriteInstructions').value.trim(); if(!instructions) throw new Error('请填写修改要求。');
    await generate('REWRITE',state.selected,instructions);
  }));
  $('.inspector')?.addEventListener('click',e=>{
    const cancel=e.target.closest('[data-cancel]'),retry=e.target.closest('[data-retry]'),feedback=e.target.closest('[data-shadow-feedback]');
    const decision=e.target.closest('[data-decision]'),projectUpdate=e.target.closest('[data-project-update]');
    const applyProjectUpdates=e.target.closest('[data-apply-project-updates]'),proposal=e.target.closest('[data-execute-proposal]');
    const chatView=e.target.closest('[data-chat-view]'),chatConfirm=e.target.closest('[data-chat-confirm]'),chatDismiss=e.target.closest('[data-chat-dismiss]');
    const turnRetry=e.target.closest('[data-turn-retry]'),turnCancel=e.target.closest('[data-turn-cancel]');
    if(turnCancel){
      const n=state.view.novel,session=currentOutlineSession(n)||latestOutlineSession();if(!session)return;
      api(`/novels/${n.id}/conversations/${session.id}/turns/${turnCancel.dataset.turnCancel}/cancel`,{}).then(reply=>{
        if(state.view?.novel.id!==n.id)return;upsertConversation(reply,state.conversationThreadId===sessionThreadId(session));state.chatPendingThreadId=null;state.chatStream=null;render();schedulePoll();
      }).catch(error);return;
    }
    if(turnRetry) perform(async()=>{
      const n=state.view.novel,novelId=n.id,session=currentOutlineSession(n)||latestOutlineSession();
      if(!session)throw new Error('没有找到需要重试的对话。');
      const turn=(session.turns||[]).find(item=>item.id===turnRetry.dataset.turnRetry);
      if(!turn)throw new Error('没有找到需要重试的消息。');
      turn.status='RUNNING';turn.error=null;state.chatPendingThreadId=sessionThreadId(session);state.chatStickBottom=true;render();
      try{
        const reply=await api(`/novels/${novelId}/conversations/${session.id}/turns/${turn.id}/retry`,{});
        if(state.view?.novel.id===novelId){upsertConversation(reply,state.conversationThreadId===sessionThreadId(session));await refresh(true);}
      } catch(problem) {
        if(state.view?.novel.id===novelId)await refresh(false);
        throw problem;
      } finally {state.chatPendingThreadId=null;}
    });
    if(decision) perform(async()=>{
      const n=state.view.novel,session=currentOutlineSession(n); if(!session)throw new Error('当前对话已经过期，请重新开始。');
      upsertConversation(await api(`/novels/${n.id}/conversations/${session.id}/decisions/${decision.dataset.decision}`,{status:decision.dataset.status}));
    });
    if(projectUpdate) perform(async()=>{
      const n=state.view.novel,session=currentOutlineSession(n); if(!session)throw new Error('当前对话已经过期，请重新开始。');
      upsertConversation(await api(`/novels/${n.id}/conversations/${session.id}/project-updates/${projectUpdate.dataset.projectUpdate}`,{status:projectUpdate.dataset.status}));
    });
    if(applyProjectUpdates) perform(async()=>{
      const n=state.view.novel,session=currentOutlineSession(n); if(!session)throw new Error('当前对话已经过期，请重新开始。');
      await api(`/novels/${n.id}/conversations/${session.id}/project-updates/apply`,{revision:n.revision});
      toast('项目信息已保存，并已切换到基于新资料的对话。'); await refresh(true);
    });
    if(proposal) perform(async()=>{
      const n=state.view.novel,session=currentOutlineSession(n); if(!session)throw new Error('当前对话已经过期，请重新开始。');
      await api(`/novels/${n.id}/conversations/${session.id}/proposals/${proposal.dataset.executeProposal}/execute`,{requestKey:crypto.randomUUID(),revision:n.revision});
      toast('Agent 已开始生成；进度会继续显示在对话中。'); await refresh(true);
    });
    if(chatView){state.selected=chatView.dataset.chatView;state.version=null;render();setTimeout(()=>$('.writing')?.scrollIntoView({behavior:'smooth',block:'start'}),0);}
    if(chatConfirm) perform(async()=>{
      const n=state.view.novel;
      state.view=await api(`/novels/${n.id}/artifacts/${chatConfirm.dataset.chatConfirm}/versions/${chatConfirm.dataset.version}/confirm`,{revision:n.revision,overrideReason:null});
      state.selected=chatConfirm.dataset.chatConfirm;state.version=null;toast('已经采用这个版本。');await refresh(true);
    });
    if(chatDismiss) perform(async()=>{
      const n=state.view.novel;
      state.view=await api(`/novels/${n.id}/artifacts/${chatDismiss.dataset.chatDismiss}/versions/${chatDismiss.dataset.version}/dismiss`,{revision:n.revision});
      state.version=null;toast('已放弃本次候选；历史记录仍然保留。');await refresh(true);
    });
    if(cancel) perform(async()=>{await api(`/novels/${state.view.novel.id}/tasks/${cancel.dataset.cancel}/cancel`,{});await refresh();});
    if(retry) perform(async()=>{await api(`/novels/${state.view.novel.id}/tasks/${retry.dataset.retry}/retry`,{requestKey:crypto.randomUUID(),revision:state.view.novel.revision});await refresh();});
    if(feedback) perform(async()=>{
      const novelId=state.view.novel.id;
      state.view=await api(`/novels/${novelId}/shadow-reviews/${feedback.dataset.shadowFeedback}/feedback`,{decision:feedback.dataset.decision,note:''});
      toast('评价已记录，只用于专业检查实验统计。');
    });
  });
  $('#budgetForm')?.addEventListener('submit',e=>{e.preventDefault();const data=new FormData(e.target);perform(async()=>{
    state.view=await api(`/novels/${state.view.novel.id}/budget-adjustments`,{targetWords:Number(data.get('target')),approvedMaxWords:Number(data.get('max')),reason:data.get('reason'),revision:state.view.novel.revision});
    toast('目标字数和字数上限已保存。');await refresh();
  });});
  $('#autoStyleToggle')?.addEventListener('change',e=>perform(async()=>{
    const enabled=e.target.checked,n=state.view.novel;
    state.view=await api(`/novels/${n.id}/auto-style-settings`,{enabled,revision:n.revision});
    toast(enabled?'已开启自动文风：内容检查通过后最多安全处理一轮。':'已关闭自动文风；仍可在章节页面手动检查。');
    await refresh();
  }));
  $('#finishButton')?.addEventListener('click',()=>perform(async()=>{
    if(!$('#finishAcknowledge').checked) throw new Error('请先勾选全书完成确认。');
    const n=state.view.novel;state.view=await api(`/novels/${n.id}/completion-confirmations`,{checkId:n.completionChecks.at(-1).id,revision:n.revision,acknowledge:true});
    toast('全书已确认完结。');await refresh();
  }));
}
function selectOptions(options,current) {
  return options.map(([value,label])=>`<option value="${value}" ${value===current?'selected':''}>${label}</option>`).join('');
}
function factEditorRow(fact={}) {
  const key=fact.key||`user_${crypto.randomUUID().replaceAll('-','')}`;
  return `<div data-fact-row class="fact"><input type="hidden" data-fact="key" value="${esc(key)}"><label>类别<select data-fact="type">${selectOptions([['CHARACTER','人物'],['WORLD','世界设定'],['TIMELINE','时间线'],['EVENT','事件'],['FORESHADOW','伏笔']],fact.type||'CHARACTER')}</select></label><label>中文内容<textarea data-fact="detail" rows="2" maxlength="3000" placeholder="例如：王阿姨是住在邱天隔壁的邻居">${esc(fact.detail||'')}</textarea></label><label>状态<select data-fact="state">${selectOptions([['ACTIVE','有效'],['OPEN','待回收'],['RESOLVED','已回收']],fact.state||'ACTIVE')}</select></label><button type="button" data-remove-fact>删除这条档案</button></div>`;
}
const outlineId = prefix => `${prefix}_user_${crypto.randomUUID().replaceAll('-','')}`;
const outlineValue = (value='') => esc(value ?? '');
const outlineText = (label,name,value='',rows=2,required=true) => `<label>${label}<textarea data-outline-field="${name}" rows="${rows}" maxlength="10000" ${required?'required':''}>${outlineValue(value)}</textarea></label>`;
const outlineInput = (label,name,value='',type='text',required=true) => `<label>${label}<input data-outline-field="${name}" type="${type}" value="${outlineValue(value)}" ${type==='number'?'min="1" max="50000000"':''} ${required?'required':''}></label>`;
const outlineRemove = label => `<button type="button" class="danger-link" data-remove-outline>${label}</button>`;
function outlineRequirementRow(item={}) {
  return `<div class="outline-card" data-outline-requirement><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('requirement'))}">${outlineText('要求内容','text',item.text)}<div class="outline-grid"><label>来源<input data-outline-field="source" value="${outlineValue(item.source||'作者编辑')}"></label><label>类型<select data-outline-field="type">${selectOptions([['EXPLICIT','作者明确要求'],['ASSUMPTION','创作假设']],item.type||'EXPLICIT')}</select></label></div>${outlineRemove('删除这条要求')}</div>`;
}
function outlineWorldRuleRow(item={}) {
  return `<div class="outline-card" data-outline-world-rule><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('world'))}">${outlineText('规则','rule',item.rule)}<div class="outline-grid">${outlineText('适用范围','scope',item.scope)}${outlineText('限制','limit',item.limit)}${outlineText('代价','cost',item.cost||'',2,false)}${outlineText('例外','exceptionRule',item.exceptionRule||'无',2,false)}<label>约束等级<select data-outline-field="constraintLevel">${selectOptions([['HARD','硬性约束'],['DIRECTION','方向性约束'],['OPEN','开放创作空间']],item.constraintLevel||'HARD')}</select></label></div>${outlineRemove('删除这条世界规则')}</div>`;
}
function outlineCharacterRow(item={}) {
  return `<div class="outline-card" data-outline-character><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('character'))}"><div class="outline-grid">${outlineInput('人物姓名','name',item.name)}${outlineText('剧情功能','storyFunction',item.storyFunction)}${outlineText('核心目标','goal',item.goal)}${outlineText('深层动机','motivation',item.motivation)}${outlineText('性格缺陷','flaw',item.flaw||'',2,false)}${outlineText('关键选择','keyChoice',item.keyChoice||'',2,false)}${outlineText('变化方向','arcDirection',item.arcDirection||'',2,false)}${outlineText('最终状态','finalState',item.finalState)}</div>${outlineRemove('删除这个人物骨架')}</div>`;
}
function outlineEventRow(item={}) {
  return `<div class="outline-card compact" data-outline-event><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('event'))}">${outlineInput('事件名称','title',item.title||'新关键事件')}${outlineText('发生什么','event',item.event)}${outlineText('造成的后果','consequence',item.consequence)}<div class="outline-grid">${outlineText('人物变化','characterChange',item.characterChange||'',2,false)}${outlineText('不可逆转折','irreversibleTurn',item.irreversibleTurn||'',2,false)}</div>${outlineRemove('删除这个关键事件')}</div>`;
}
function outlineStageRow(item={}) {
  return `<details class="outline-card outline-stage" data-outline-stage open><summary>${esc(item.title||'新故事阶段')}</summary><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('stage'))}"><div class="outline-grid">${outlineInput('阶段标题','title',item.title||'新故事阶段')}${outlineInput('预计字数','estimatedWords',item.estimatedWords||1000,'number')}</div>${outlineText('阶段目标','goal',item.goal)}<label>前置阶段或事件<select data-outline-field="prerequisiteIds" data-outline-reference="all" data-current="${outlineValue(JSON.stringify(item.prerequisiteIds||[]))}" multiple size="5"></select><small>按住 Ctrl 可以多选；没有前置条件时留空。</small></label><div class="outline-subhead"><strong>关键事件</strong><button type="button" data-add-outline="event">＋ 添加事件</button></div><div class="outline-events">${(item.events||[]).map(outlineEventRow).join('')}</div>${outlineRemove('删除这个故事阶段')}</details>`;
}
function outlinePartRow(item={}) {
  return `<details class="outline-card outline-part" data-outline-part open><summary>${esc(item.title||'新篇章')}</summary><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('part'))}"><div class="outline-grid">${outlineInput('篇章标题','title',item.title||'新篇章')}${outlineInput('预计字数','estimatedWords',item.estimatedWords||1000,'number')}</div>${outlineText('篇章目标','goal',item.goal)}${outlineText('主要冲突','conflict',item.conflict)}${outlineText('篇章转折','turn',item.turn||'',2,false)}<div class="outline-subhead"><strong>故事阶段</strong><button type="button" data-add-outline="stage">＋ 添加阶段</button></div><div class="outline-stages">${(item.stages||[]).map(outlineStageRow).join('')}</div>${outlineRemove('删除这个篇章')}</details>`;
}
function outlineForeshadowRow(item={}) {
  return `<div class="outline-card" data-outline-foreshadow><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('clue'))}">${outlineText('伏笔内容','clue',item.clue)}<div class="outline-grid"><label>铺设阶段<select data-outline-field="setupStageId" data-outline-reference="stage" data-current="${outlineValue(item.setupStageId||'')}"></select></label><label>回收阶段<select data-outline-field="recoveryStageId" data-outline-reference="stage" data-current="${outlineValue(item.recoveryStageId||'')}"></select></label></div><label>发展阶段<select data-outline-field="developmentStageIds" data-outline-reference="stage" data-current="${outlineValue(JSON.stringify(item.developmentStageIds||[]))}" multiple size="5"></select><small>按住 Ctrl 可以多选。</small></label>${outlineText('服务目标','serviceGoal',item.serviceGoal||'',2,false)}${outlineRemove('删除这个伏笔')}</div>`;
}
function outlineThreadRow(item={}) {
  return `<div class="outline-card" data-outline-thread><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('thread'))}">${outlineInput('支线名称','title',item.title||'新支线')}${outlineText('服务主线的目标','serviceGoal',item.serviceGoal)}<label>收束阶段<select data-outline-field="resolutionStageId" data-outline-reference="stage" data-current="${outlineValue(item.resolutionStageId||'')}"></select></label>${outlineRemove('删除这条支线')}</div>`;
}
function outlineConstraintRow(item={}) {
  return `<div class="outline-card" data-outline-constraint><input type="hidden" data-outline-field="id" value="${outlineValue(item.id||outlineId('constraint'))}">${outlineText('约束内容','text',item.text)}<div class="outline-grid"><label>分类<select data-outline-field="category">${selectOptions([['HARD','硬性约束'],['DIRECTION','方向性约束'],['OPEN','开放创作空间']],item.category||'HARD')}</select></label><label>来自哪条要求<select data-outline-field="sourceRequirementId" data-outline-reference="requirement" data-current="${outlineValue(item.sourceRequirementId||'')}"></select></label></div>${outlineRemove('删除这条约束')}</div>`;
}
function outlineAssumptionRow(value='') {
  return `<div class="outline-card compact" data-outline-assumption>${outlineText('创作假设','value',value)}${outlineRemove('删除这条假设')}</div>`;
}
function outlineSection(title,description,type,items,renderer,addLabel,open=false) {
  return `<details class="outline-section" ${open?'open':''}><summary>${title}<small>${description}</small></summary><div data-outline-list="${type}">${(items||[]).map(renderer).join('')}</div><button type="button" data-add-outline="${type}">＋ ${addLabel}</button></details>`;
}
function referenceOptions(items,selected,multiple=false,optional=false) {
  const selectedValues=new Set(multiple?selected:[selected]);
  const none=optional&&!multiple?'<option value="">不关联具体要求</option>':'';
  return none+items.map(item=>`<option value="${esc(item.id)}" ${selectedValues.has(item.id)?'selected':''}>${esc(item.label)}</option>`).join('');
}
function refreshOutlineReferences() {
  const editor=$('#outlineEditForm'); if(!editor) return;
  const stages=[...editor.querySelectorAll('[data-outline-stage]')].map(row=>({id:row.querySelector('[data-outline-field="id"]').value,label:`阶段：${row.querySelector('[data-outline-field="title"]').value||'未命名阶段'}`}));
  const events=[...editor.querySelectorAll('[data-outline-event]')].map(row=>({id:row.querySelector('[data-outline-field="id"]').value,label:`事件：${row.querySelector('[data-outline-field="title"]').value||'未命名事件'}`}));
  const requirements=[...editor.querySelectorAll('[data-outline-requirement]')].map(row=>({id:row.querySelector('[data-outline-field="id"]').value,label:row.querySelector('[data-outline-field="text"]').value||'未命名要求'}));
  editor.querySelectorAll('select[data-outline-reference]').forEach(select=>{
    let selected;
    if(select.dataset.referenceReady==='1') selected=select.multiple?[...select.selectedOptions].map(o=>o.value):select.value;
    else { try{selected=select.multiple?JSON.parse(select.dataset.current||'[]'):(select.dataset.current||'');}catch{selected=select.multiple?[]:'';} }
    const options=select.dataset.outlineReference==='stage'?stages:select.dataset.outlineReference==='requirement'?requirements:[...stages,...events];
    select.innerHTML=referenceOptions(options,selected,select.multiple,select.dataset.outlineReference==='requirement');
    select.dataset.referenceReady='1';
  });
}
function field(row,name) { return row.querySelector(`[data-outline-field="${name}"]`)?.value?.trim()||''; }
function selectedValues(row,name) { return [...(row.querySelector(`[data-outline-field="${name}"]`)?.selectedOptions||[])].map(option=>option.value); }
function collectOutlineSpec(form,schemaVersion) {
  const requirements=[...form.querySelectorAll('[data-outline-requirement]')].map(row=>({id:field(row,'id'),text:field(row,'text'),source:field(row,'source'),type:field(row,'type')}));
  const worldRules=[...form.querySelectorAll('[data-outline-world-rule]')].map(row=>({id:field(row,'id'),rule:field(row,'rule'),scope:field(row,'scope'),limit:field(row,'limit'),cost:field(row,'cost'),exceptionRule:field(row,'exceptionRule'),constraintLevel:field(row,'constraintLevel'),authorityStatus:'CANDIDATE'}));
  const characters=[...form.querySelectorAll('[data-outline-character]')].map(row=>({id:field(row,'id'),name:field(row,'name'),goal:field(row,'goal'),motivation:field(row,'motivation'),flaw:field(row,'flaw'),storyFunction:field(row,'storyFunction'),keyChoice:field(row,'keyChoice'),arcDirection:field(row,'arcDirection'),finalState:field(row,'finalState')}));
  const parts=[...form.querySelectorAll('[data-outline-part]')].map(part=>({id:field(part,'id'),title:field(part,'title'),goal:field(part,'goal'),conflict:field(part,'conflict'),turn:field(part,'turn'),estimatedWords:Number(field(part,'estimatedWords')),stages:[...part.querySelector('.outline-stages').children].filter(row=>row.matches('[data-outline-stage]')).map(stage=>({id:field(stage,'id'),title:field(stage,'title'),goal:field(stage,'goal'),prerequisiteIds:selectedValues(stage,'prerequisiteIds'),estimatedWords:Number(field(stage,'estimatedWords')),events:[...stage.querySelector('.outline-events').children].filter(row=>row.matches('[data-outline-event]')).map(event=>({id:field(event,'id'),title:field(event,'title'),event:field(event,'event'),consequence:field(event,'consequence'),characterChange:field(event,'characterChange'),irreversibleTurn:field(event,'irreversibleTurn')}))}))}));
  const foreshadows=[...form.querySelectorAll('[data-outline-foreshadow]')].map(row=>({id:field(row,'id'),clue:field(row,'clue'),setupStageId:field(row,'setupStageId'),developmentStageIds:selectedValues(row,'developmentStageIds'),recoveryStageId:field(row,'recoveryStageId'),serviceGoal:field(row,'serviceGoal')}));
  const threads=[...form.querySelectorAll('[data-outline-thread]')].map(row=>({id:field(row,'id'),title:field(row,'title'),serviceGoal:field(row,'serviceGoal'),resolutionStageId:field(row,'resolutionStageId')}));
  const constraints=[...form.querySelectorAll('[data-outline-constraint]')].map(row=>({id:field(row,'id'),category:field(row,'category'),text:field(row,'text'),sourceRequirementId:field(row,'sourceRequirementId')}));
  const creativeAssumptions=[...form.querySelectorAll('[data-outline-assumption]')].map(row=>field(row,'value')).filter(Boolean);
  return {schemaVersion:schemaVersion||'1',storyCore:field(form,'storyCore'),genreTone:field(form,'genreTone'),protagonistGoal:field(form,'protagonistGoal'),coreConflict:field(form,'coreConflict'),endingContract:field(form,'endingContract'),requirements,worldRules,characters,parts,foreshadows,threads,constraints,creativeAssumptions};
}
function openOutlineEditor(a,v) {
  const spec=v.outlineSpec;
  state.editing=true;
  $('#contentArea').innerHTML=`<form id="outlineEditForm" class="editor outline-editor"><p class="editing-note">你正在编辑结构化大纲。保存会创建新版本，不覆盖历史内容；可读大纲由系统重新生成，旧检查立即失效。</p><label>大纲标题<input name="title" required maxlength="300" value="${esc(v.title)}"></label><label>内容摘要<textarea name="summary" required maxlength="5000" rows="4">${esc(v.summary)}</textarea></label><section class="outline-core"><h3>故事核心</h3>${outlineText('一句话故事核心','storyCore',spec.storyCore,3)}<div class="outline-grid">${outlineText('题材与叙事基调','genreTone',spec.genreTone)}${outlineText('主角目标','protagonistGoal',spec.protagonistGoal)}${outlineText('核心冲突','coreConflict',spec.coreConflict)}${outlineText('明确结局','endingContract',spec.endingContract,3)}</div></section>${outlineSection('作者要求','明确要求和模型补充假设的来源追踪','requirement',spec.requirements,outlineRequirementRow,'添加要求')}${outlineSection('世界规则','能力边界、社会规则、限制、代价与例外','world-rule',spec.worldRules,outlineWorldRuleRow,'添加世界规则')}${outlineSection('核心人物骨架','只编辑人物在全书中的功能、选择和变化方向','character',spec.characters,outlineCharacterRow,'添加人物骨架')}${outlineSection('篇章、阶段与关键事件','预计字数必须逐层汇总到小说目标字数','part',spec.parts,outlinePartRow,'添加篇章',true)}${outlineSection('重要伏笔','选择铺设、发展和回收阶段','foreshadow',spec.foreshadows,outlineForeshadowRow,'添加伏笔')}${outlineSection('主要支线','每条支线都应服务主线并有收束阶段','thread',spec.threads,outlineThreadRow,'添加支线')}${outlineSection('大纲约束','区分硬性约束、方向要求和开放空间','constraint',spec.constraints,outlineConstraintRow,'添加约束')}${outlineSection('创作假设','尚未经作者确认、但本版大纲采用的补充设定','assumption',spec.creativeAssumptions,outlineAssumptionRow,'添加创作假设')}<label>修改原因<input name="reason" required maxlength="2000" placeholder="例如：调整第二篇章的取证方式，并同步后续证据链"></label><div class="controls"><button class="primary" type="submit">保存并重新检查</button><button type="button" id="discardEdit">取消编辑</button></div></form>`;
  document.querySelectorAll('.sheet-body > .controls button, #nextButton, #rewriteButton, #versionSelect').forEach(button=>button.disabled=true);
  const form=$('#outlineEditForm'); refreshOutlineReferences();
  $('#discardEdit').onclick=()=>{state.editing=false;render();};
  form.addEventListener('input',event=>{if(event.target.matches('[data-outline-field="title"],[data-outline-field="text"]'))refreshOutlineReferences();});
  form.addEventListener('click',event=>{
    const remove=event.target.closest('[data-remove-outline]');
    if(remove) { if(confirm('删除后仍可从历史版本恢复。确定删除吗？')) { remove.closest('[data-outline-requirement],[data-outline-world-rule],[data-outline-character],[data-outline-part],[data-outline-stage],[data-outline-event],[data-outline-foreshadow],[data-outline-thread],[data-outline-constraint],[data-outline-assumption]').remove();refreshOutlineReferences(); } return; }
    const add=event.target.closest('[data-add-outline]'); if(!add)return;
    const type=add.dataset.addOutline; let html=''; let container;
    if(type==='event'){html=outlineEventRow();container=add.closest('[data-outline-stage]').querySelector('.outline-events');}
    else if(type==='stage'){html=outlineStageRow();container=add.closest('[data-outline-part]').querySelector('.outline-stages');}
    else {container=form.querySelector(`[data-outline-list="${type}"]`);html=({requirement:outlineRequirementRow,'world-rule':outlineWorldRuleRow,character:outlineCharacterRow,part:outlinePartRow,foreshadow:outlineForeshadowRow,thread:outlineThreadRow,constraint:outlineConstraintRow,assumption:outlineAssumptionRow}[type])();}
    container.insertAdjacentHTML('beforeend',html);refreshOutlineReferences();
  });
  form.onsubmit=event=>{event.preventDefault();perform(async()=>{
    const data=new FormData(form),outlineSpec=collectOutlineSpec(form,spec.schemaVersion);
    state.view=await api(`/novels/${state.view.novel.id}/artifacts/${a.id}/outline-versions`,{baseVersionId:latest(a).id,title:data.get('title'),summary:data.get('summary'),outlineSpec,reason:data.get('reason'),revision:state.view.novel.revision});
    state.editing=false;state.version=null;state.selected=a.id;
    const saved=state.view.novel.artifacts.find(item=>item.id===a.id),candidate=latest(saved);
    if(candidate.review?.passed) {
      await api(`/novels/${state.view.novel.id}/generation-tasks`,{action:'REVIEW',artifactId:a.id,instructions:'',requestKey:crypto.randomUUID(),revision:state.view.novel.revision});
      toast('大纲新版本已保存，正在按影响范围重新检查。');
    } else toast('大纲草稿已保存；请先修正页面指出的结构问题，不会调用模型。');
    await refresh(true);
  });};
}
function openEditor() {
  const a=state.view.novel.artifacts.find(a=>a.id===state.selected);
  const v=a.versions.find(v=>v.id===state.version)||latest(a);
  if(a.kind==='OUTLINE'&&v.outlineSpec) { openOutlineEditor(a,v); return; }
  state.editing=true;
  $('#contentArea').innerHTML=`<form id="editForm" class="editor"><p class="editing-note">保存为新版本，不覆盖历史内容。摘要与档案也应同步修改；没有实际变化时不会创建空版本。</p><label>标题<input name="title" required maxlength="300" value="${esc(v.title)}"></label><label>内容正文<textarea name="content" required maxlength="250000">${esc(v.content)}</textarea></label><label>内容摘要<textarea name="summary" required maxlength="5000" rows="4">${esc(v.summary)}</textarea></label><details><summary>小说档案（可选）</summary><p class="hint">这里只填写读者能够理解的中文事实，内部编号由系统保留。人物设定不要重复填写“第一次、随后、第几章”等剧情顺序。</p><div id="factEditor">${(v.facts||[]).map(factEditorRow).join('')}</div><button type="button" id="addFact">＋ 新增档案条目</button>${a.kind==='PLAN'?`<details><summary>高级章节规划数据</summary><p class="hint">逐章结构改动较复杂，通常建议使用 Agent 修改。</p><textarea name="plan" class="code" rows="15">${esc(JSON.stringify(v.plan,null,2))}</textarea></details>`:''}</details><label>修改原因<input name="reason" required maxlength="2000" placeholder="说明本次修改，方便准确判断哪些后续内容会受影响"></label><div class="controls"><button class="primary" type="submit">保存为新草稿</button><button type="button" id="discardEdit">取消编辑</button></div></form>`;
  document.querySelectorAll('.sheet-body > .controls button, #nextButton, #rewriteButton, #versionSelect').forEach(b=>b.disabled=true);
  $('#discardEdit').onclick=()=>{state.editing=false;render();};
  $('#addFact').onclick=()=>$('#factEditor').insertAdjacentHTML('beforeend',factEditorRow());
  $('#factEditor').onclick=e=>{const button=e.target.closest('[data-remove-fact]');if(button)button.closest('[data-fact-row]').remove();};
  $('#editForm').onsubmit=e=>{e.preventDefault();const f=new FormData(e.target);perform(async()=>{
    const facts=[...document.querySelectorAll('[data-fact-row]')].map(row=>({key:row.querySelector('[data-fact="key"]').value,type:row.querySelector('[data-fact="type"]').value,detail:row.querySelector('[data-fact="detail"]').value.trim(),state:row.querySelector('[data-fact="state"]').value})).filter(x=>x.detail);
    let plan=null;try{if(a.kind==='PLAN')plan=JSON.parse(f.get('plan'));}catch{throw new Error('章节规划数据格式不正确；建议取消编辑，改用 Agent 修改。');}
    state.view=await api(`/novels/${state.view.novel.id}/change-requests`,{artifactId:a.id,baseVersionId:latest(a).id,title:f.get('title'),content:f.get('content'),summary:f.get('summary'),facts,plan,reason:f.get('reason'),revision:state.view.novel.revision});
    state.editing=false;state.version=null;toast('草稿已保存，相关后续内容已标记待修订。');await refresh(true);
  });};
}
$('#newButton').onclick=openCreate; $('#firstBook').onclick=openCreate;
$('#closeCreate').onclick=()=>$('#createDialog').close();
$('#helpButton').onclick=()=>$('#helpDialog').showModal(); $('#closeHelp').onclick=()=>$('#helpDialog').close();
$('#bookList').onclick=e=>{const b=e.target.closest('[data-book]');if(b)perform(()=>selectBook(b.dataset.book));};
$('#createForm').onsubmit=e=>{e.preventDefault();const f=new FormData(e.target);perform(async()=>{
  state.view=await api('/novels',{title:f.get('title'),synopsis:f.get('synopsis'),targetWords:Number(f.get('targetWords')),requirements:f.get('requirements')});
  state.selected=null;state.version=null;$('#createDialog').close();await listBooks();render();
});};
window.addEventListener('beforeunload',e=>{if(state.editing){e.preventDefault();e.returnValue='';}});
try {const health=await api('/health');state.mode=health.mode;$('#modelMode').textContent=health.mode==='demo'?'离线演示 · 非真实创作':health.modelReady?'真实模型模式':'真实模型 · 待配置';await listBooks();if(state.books.length)await selectBook(state.books[0].id);}catch(e){$('#modelMode').textContent='服务未连接';error(e);}
