const internalFactId = /\b(?:character|foreshadow|event|relationship|world|location|item)_[a-z0-9_]+\b/gi;
const fieldNames = [
  ['candidate.facts','当前版本的“档案增量”'],['candidate.summary','当前版本的“内容摘要”'],
  ['candidate.content','当前版本的“内容正文”'],['candidate.plan','当前版本的“章节安排”'],
  ['candidate.title','当前版本的“标题”'],['context.acceptedReferences','已确认内容'],
  ['acceptedReferences','已确认内容'],['context.canonBeforeChapter','已确认小说档案'],
  ['canonBeforeChapter','已确认小说档案'],['candidate','当前待确认内容']
];

const short = (value,max=34) => {
  const text=String(value??'').replace(/\s+/g,' ').trim();
  return text.length>max?text.slice(0,max)+'…':text;
};
const escapeRegExp = value => value.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');

export function factReference(fact) {
  const detail=String(fact?.detail??'').replace(internalFactId,'相关人物').trim();
  const name=detail.match(/^([\p{Script=Han}]{2,10})(?=[，、：:是为])/u)?.[1];
  return name ? `档案条目“${name}”` : `以“${short(detail,18)||'中文内容'}”开头的档案条目`;
}

export function humanizeReviewText(value,facts=[]) {
  let text=String(value??'');
  fieldNames.forEach(([internal,readable])=>text=text.split(internal).join(readable));

  // One replacement pass is intentional: replacement labels must never be scanned again.
  const byKey=new Map((facts||[]).filter(f=>f?.key).map(f=>[String(f.key).toLowerCase(),f]));
  const keys=[...byKey.keys()].sort((a,b)=>b.length-a.length);
  if(keys.length) {
    const pattern=new RegExp(keys.map(escapeRegExp).join('|'),'gi');
    text=text.replace(pattern,key=>factReference(byKey.get(key.toLowerCase())));
  }
  text=text.replace(internalFactId,'一个内部档案条目');
  [['facts','档案增量'],['detail','内容描述'],['summary','内容摘要'],['content','内容正文'],['plan','章节安排'],['key','内部编号'],['type','类别'],['state','状态']]
    .forEach(([internal,readable])=>text=text.replace(new RegExp(`\\b${internal}\\b`,'gi'),readable));
  return text;
}

export function compactLegacyIssue(value,facts=[]) {
  const text=humanizeReviewText(value,facts);
  if(text.length<=360) return text;
  const target=text.match(/档案条目“([^”]+)”/)?.[1];
  let offending=text.match(/仍写有[‘']([^’']{1,300})[’']/)?.[1];
  if(offending) {
    offending=offending
      .replace(/档案条目“[^”]+”|以“[^”]+”开头的档案条目/g,'相关人物')
      .replace(/\s+/g,' ').trim();
    const focused=offending.match(/.{0,16}(?:第一次|初次|首次|随后|后来|第[一二三四五六七八九十百0-9]+章).{0,40}/)?.[0];
    if(focused) offending=focused;
  }
  const action=text.match(/应(?:删除|改为|调整|修改)[^。]{1,180}[。]?/)?.[0];
  if(target&&offending) return `${target}的档案描述仍包含“${short(offending,90)}”，与已确认内容冲突。${action||'请删除剧情顺序表述，只保留静态人物设定。'}`;
  return `${short(text,300)}（这是旧版检查意见的简化显示，请结合已确认内容复核。）`;
}
