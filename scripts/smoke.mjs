// Against a running DEMO server. Creates a clearly labelled validation novel and leaves it for inspection.
import assert from 'node:assert/strict';
const base = process.env.NOVELFORGE_TEST_URL || 'http://127.0.0.1:8080';
async function api(path, body, expected = 200) {
  const response = await fetch(base + '/api' + path, { ...(body === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/json','X-NovelForge-Request':'1'},body:JSON.stringify(body)}) });
  const data = await response.json();
  assert.equal(response.status, expected, JSON.stringify(data));
  return data;
}
const health = await api('/health');
assert.equal(health.mode,'demo','Smoke test requires demo mode to avoid real model charges.');
let view = await api('/novels',{title:'【闭环验证】雾港来信',synopsis:'修船师寻找父亲，揭开灯塔的秘密。',targetWords:1000},201);
const id = view.novel.id;
async function refresh() { view=await api('/novels/'+id); }
async function generate(action) {
  const task=await api(`/novels/${id}/generation-tasks`,{action,requestKey:crypto.randomUUID(),revision:view.novel.revision},202);
  const deadline=Date.now()+15000;
  while(Date.now()<deadline) {
    const current=await api(`/novels/${id}/tasks/${task.id}`);
    if(current.status==='SUCCEEDED') {await refresh();return;}
    assert.ok(['QUEUED','RUNNING'].includes(current.status),JSON.stringify(current));
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('Task timeout');
}
async function approve() {
  const a=view.novel.artifacts.find(a=>a.id===view.pendingArtifactId);
  view=await api(`/novels/${id}/artifacts/${a.id}/versions/${a.versions.at(-1).id}/confirm`,{revision:view.novel.revision});
}
await generate('OUTLINE');
await api(`/novels/${id}/generation-tasks`,{action:'CHARACTERS',requestKey:crypto.randomUUID(),revision:view.novel.revision},409);
await approve();
for(const action of ['CHARACTERS','PLAN','CHAPTER','PLAN']) {await generate(action);await approve();}
assert.equal(view.novel.artifacts.filter(a=>a.kind==='CHAPTER').length,1);
assert.equal(view.novel.artifacts.filter(a=>a.kind==='PLAN').length,2);
console.log('PASS: next batch planned and approved before first batch finishes');
for(let chapter=2;chapter<=5;chapter++) {await generate('CHAPTER');await approve();}
assert.equal(view.wordCount,1050);
await generate('COMPLETE');
assert.equal(view.novel.status,'WRITING');
view=await api(`/novels/${id}/completion-confirmations`,{checkId:view.novel.completionChecks.at(-1).id,revision:view.novel.revision,acknowledge:true});
assert.equal(view.novel.status,'COMPLETED');
const blocked=await fetch(base+'/api/novels',{method:'POST',headers:{'Content-Type':'application/json','Origin':'https://evil.example'},body:'{}'});
assert.equal(blocked.status,403);
console.log('PASS: title → outline → characters → rolling plans → five confirmed chapters → manual completion');
console.log('PASS: 1050 words / target 1000 / upper bound 1100; cross-origin writes rejected');
console.log(JSON.stringify({novelId:id,status:view.novel.status,wordCount:view.wordCount,url:base},null,2));
