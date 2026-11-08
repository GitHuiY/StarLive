(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const now = new Date();
  const today = (now.getDay() + 6) % 7;
  const monday = new Date(now); monday.setDate(monday.getDate() - today); monday.setHours(12,0,0,0);
  const dateKey = d => `${d.getFullYear()}-${d.getMonth()+1}-${d.getDate()}`;
  const weekKey = dateKey(monday);
  const dateAt = (day, offset=0) => {const d=new Date(monday);d.setDate(d.getDate()+day+offset*7);return d;};
  const keyAt = offset => dateKey(dateAt(0,offset));
  const weekLabel = offset => `${dateAt(0,offset).getMonth()+1}.${String(dateAt(0,offset).getDate()).padStart(2,'0')} — ${dateAt(6,offset).getMonth()+1}.${String(dateAt(6,offset).getDate()).padStart(2,'0')}`;
  const days = [
    {name:'天枢',english:'DUBHE',x:770,y:180,weekday:'一',title:'轻轻开始，新的一周。',intro:'整理一点身边的空间，也给心情留一个新起点。'},
    {name:'天璇',english:'MERAK',x:810,y:350,weekday:'二',title:'动一动，让自己舒展。',intro:'不追赶任何目标，只感受身体慢慢醒来。'},
    {name:'天玑',english:'PHECDA',x:615,y:365,weekday:'三',title:'给好奇心，一点时间。',intro:'翻开一页书，学一点新东西。慢慢来也很好。'},
    {name:'天权',english:'MEGREZ',x:575,y:220,weekday:'四',title:'和喜欢的人，靠近一点。',intro:'一句问候，一次散步。让连接发生在日常里。'},
    {name:'玉衡',english:'ALIOTH',x:445,y:240,weekday:'五',title:'把平常，过得有趣一点。',intro:'为这一周留一点小小的期待，不需要特别的理由。'},
    {name:'开阳',english:'MIZAR',x:315,y:320,weekday:'六',title:'去看看，新的风景。',intro:'走出熟悉的路线，跟着今天的好奇心走。'},
    {name:'摇光',english:'ALKAID',x:165,y:360,weekday:'日',title:'给自己，一点留白。',intro:'不用把休息变成任务。今天，舒服地度过就好。'}
  ];
  const icons = {
    '家务':'<path d="M4 10 12 3l8 7M6 9v12h12V9M10 21v-7h4v7"/>',
    '运动':'<path d="m5 10 2-5m10 14 2-5M4 8l4 2m8 4 4 2M8 10l8 4M3 12l3-9m12 18 3-9"/>',
    '学习':'<path d="m3 9 9-5 9 5-9 5-9-5Zm4 3v5c3 3 7 3 10 0v-5M21 9v8"/>',
    '阅读':'<path d="M12 6c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V5c-3-1-6-1-9 1Zm0 0v15"/>',
    '社交':'<path d="M20 11c0 4-4 7-8 7H5l-3 3 1-6c-1-1-1-3-1-4 0-4 4-7 9-7s9 3 9 7Z"/><path d="M7 10h8M7 13h5"/>',
    '探索':'<circle cx="12" cy="12" r="9"/><path d="m16 8-2.5 5.5L8 16l2.5-5.5L16 8Z"/>',
    '休息':'<path d="M20 15A8 8 0 0 1 9 4a8.5 8.5 0 1 0 11 11Z"/>',
    '喜欢':'<path d="M12 20 4 12C-1 6 7 1 12 7c5-6 13-1 8 5l-8 8Z"/>'
  };
  const colors = {'家务':'#bba987','运动':'#9fb5a4','学习':'#a4adc3','阅读':'#c0b196','社交':'#b9a3b7','探索':'#91b5bc','休息':'#c5b692','喜欢':'#c3a3a0'};
  const ideas = [
    ['给房间一个小小整理','家务'],['认真做一顿喜欢的饭','家务'],['散步，看看路边的树','运动'],['让身体舒展 15 分钟','运动'],['学一点一直好奇的东西','学习'],['练习一个新技能','学习'],['读几页想读的书','阅读'],['去书店随手翻一本书','阅读'],['和朋友好好聊聊天','社交'],['给在意的人打个电话','社交'],['探索一条没走过的路','探索'],['去附近的小店坐坐','探索'],['听一张喜欢的专辑','休息'],['发会儿呆，什么也不做','休息']
  ];
  const initial=[[0,3,4],[2,3],[6,4,12],[8,9,2],[1,12],[10,11,7],[6,2,13]];
  let nextId=Date.now(),selected=today,overview=true,page='stars',animation=0,introTimer,toastTimer,category='全部',editWeek=0;
  const blank=()=>Array.from({length:7},()=>[]);
  const clone=v=>JSON.parse(JSON.stringify(v));
  const freshDemo=()=>initial.map((items,d)=>items.map((n,j)=>({id:`demo-${d}-${j}`,name:ideas[n][0],category:ideas[n][1],done:false})));
  const validPlans=p=>Array.isArray(p)&&p.length===7&&p.every(d=>Array.isArray(d)&&d.every(i=>i&&typeof i.id==='string'&&typeof i.name==='string'&&typeof i.category==='string'&&typeof i.done==='boolean'));
  const esc=v=>String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=c=>`<span class="activity-icon" style="--icon-color:${colors[c]||colors['喜欢']}"><svg viewBox="0 0 24 24" aria-hidden="true">${icons[c]||icons['喜欢']}</svg></span>`;
  let store={weeks:{},drafts:{},demo:{},settings:{apps:['douyin','red'],continuous:45,cumulative:180,continuousEnabled:true,cumulativeEnabled:true}};
  try{const v=JSON.parse(localStorage.getItem('seven-stars-v2'));if(v&&v.weeks&&v.drafts&&v.demo){for(const type of ['weeks','drafts','demo'])for(const [key,value]of Object.entries(v[type]))if(validPlans(value))store[type][key]=value;const s=v.settings;if(s&&Array.isArray(s.apps)&&s.apps.every(x=>typeof x==='string')){store.settings.apps=s.apps;if(Number.isFinite(s.continuous))store.settings.continuous=Math.min(90,Math.max(15,s.continuous));if(Number.isFinite(s.cumulative))store.settings.cumulative=Math.min(360,Math.max(30,s.cumulative));for(const k of ['continuousEnabled','cumulativeEnabled'])if(typeof s[k]==='boolean')store.settings[k]=s[k];}}else{const old=JSON.parse(localStorage.getItem('seven-stars-demo-v1'));if(old?.week===weekKey&&validPlans(old.plans)){const own=old.plans.map(d=>d.filter(p=>!p.id.startsWith('seed-')));if(own.some(d=>d.length)){store.weeks[weekKey]=own;store.drafts[weekKey]=clone(own);}}}}catch(_){/* A malformed prior save never prevents the prototype from opening. */}
  if(!validPlans(store.demo[weekKey]))store.demo[weekKey]=freshDemo();
  let mode=store.weeks[weekKey]?'personal':'demo';
  const plans=()=>mode==='demo'?store.demo[weekKey]:(store.weeks[weekKey]||blank());
  function save(){try{localStorage.setItem('seven-stars-v2',JSON.stringify(store));return true;}catch(_){toast('浏览器未允许本地保存，本次仍可体验。');return false;}}
  function toast(message){$('toast').textContent=message;$('toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('show'),2900);}
  let seed=73;const rand=()=>{seed=(seed*9301+49297)%233280;return seed/233280;};
  $('backgroundStars').innerHTML=Array.from({length:165},()=>`<circle cx="${(rand()*1250-100).toFixed(1)}" cy="${(rand()*800-60).toFixed(1)}" r="${(.35+rand()).toFixed(2)}" fill="#c7d8e7" stroke="none" opacity="${(.1+rand()*.5).toFixed(2)}"/>`).join('');
  $('starNodes').innerHTML=days.map((d,i)=>{const left=i<4?20:-20,anchor=i<4?'start':'end';return `<g class="star-node ${i===today?'current':''}" data-day="${i}" role="button" tabindex="0" aria-label="周${d.weekday}，${d.name}${i===today?'，今天':''}" transform="translate(${d.x} ${d.y})"><circle class="star-hit" r="58"/><circle class="star-halo" r="80"/><circle class="star-glow" r="7"/><circle class="star-ring" r="18"/><path class="star-cross" d="M-12 0H12M0-12V12"/><circle class="star-core" r="4.2"/><text class="star-label" x="${left}" y="${i===3?-26:-18}" text-anchor="${anchor}">周${d.weekday}<tspan x="${left}" dy="26">${d.name}</tspan>${i===today?`<tspan class="today-marker" x="${left}" dy="23">今天</tspan>`:''}</text></g>`;}).join('');
  $('miniNodes').innerHTML=days.map((d,i)=>`<circle cx="${d.x}" cy="${d.y}" r="11" data-mini="${i}"/>`).join('');
  $('weekRange').textContent=weekLabel(0);
  $('targetDay').innerHTML=days.map((d,i)=>`<option value="${i}">周${d.weekday}</option>`).join('');$('targetDay').value=String(today);
  function render(){
    const d=days[selected],date=dateAt(selected),all=plans(),items=all[selected],done=items.filter(p=>p.done).length,ratio=items.length?done/items.length:0;
    $('weekStrip').innerHTML=days.map((day,i)=>`<button class="day-tab ${i===selected?'active':''} ${i===today?'today':''}" data-day="${i}" aria-label="周${day.weekday}${i===today?'，今天':''}" aria-pressed="${i===selected}"><span>${day.weekday}</span><span class="date">${dateAt(i).getDate()}</span></button>`).join('');
    $('dayDate').textContent=`${selected===today?'今天 · ':''}${date.getMonth()+1} 月 ${date.getDate()} 日 / 周${d.weekday}`;
    $('dayTitle').textContent=d.title;$('dayIntro').textContent=d.intro;$('planProgress').textContent=`${done} / ${items.length}`;
    $('focusNumber').textContent=`0${selected+1} / 周${d.weekday}`;$('focusStarName').textContent=d.name;$('focusEnglish').textContent=d.english;
    $('lightBar').style.width=`${ratio*100}%`;$('lightNote').textContent=done===0?'从一点微光开始':done===items.length?'今天的期待，都亮起来了':`已点亮 ${done} / ${items.length} 份期待`;
    $('planKind').textContent=mode==='demo'?'示例：提前安排好的一天':'这一周，亲手留好的期待';
    $('dataModeLabel').textContent=mode==='demo'?'示例周计划 · 非真实日程':'我的周计划 · 此浏览器保存';
    $('modeToggle').textContent=mode==='demo'?'查看我的计划':'体验示例';
    $('editPlanLink').textContent=store.weeks[weekKey]?'调整我的周计划 ↗':'制定我的周计划 ↗';
    $('planList').innerHTML=items.length?items.map(p=>`<div class="plan-card ${p.done?'completed':''}">${icon(p.category)}<div class="plan-text"><div class="plan-name">${esc(p.name)}</div><div class="plan-category">${esc(p.category)} · ${mode==='demo'?'示例安排':'已安排'}</div></div><button class="complete-button" data-complete="${esc(p.id)}" aria-label="${p.done?'取消完成':'完成'}：${esc(p.name)}" aria-pressed="${p.done}"><span class="check-ring">${p.done?'✓':''}</span></button></div>`).join(''):`<div class="empty-plan">${store.weeks[weekKey]?'这一天，给自己一点留白。<br>按自己的节奏度过就好。':'还没有制定本周计划。<br>先为一周留一点期待吧。'}</div>`;
    document.querySelectorAll('.star-node').forEach((node,i)=>{const completed=all[i].filter(p=>p.done).length,r=all[i].length?completed/all[i].length:0;node.classList.toggle('selected',i===selected&&!overview);node.style.setProperty('--halo',(.16+.84*r).toFixed(3));node.style.setProperty('--core',(.38+.62*r).toFixed(3));node.style.setProperty('--glow',(.12+.88*r).toFixed(3));node.style.setProperty('--cross',(.2+.7*r).toFixed(3));node.querySelector('.star-core').setAttribute('r',(3.6+2.4*r).toFixed(2));node.querySelector('.star-glow').setAttribute('r',(6+7*r).toFixed(2));node.setAttribute('aria-label',`周${days[i].weekday}，${days[i].name}${i===today?'，今天':''}，已完成 ${completed} 项，共 ${all[i].length} 项`);node.setAttribute('tabindex',overview||i===selected?'0':'-1');});
    document.querySelectorAll('[data-mini]').forEach((n,i)=>n.classList.toggle('selected',i===selected));
    $('dayPanel').inert=overview;
  }
  function animateView(target,duration=1200){cancelAnimationFrame(animation);const v=$('starMap').viewBox.baseVal,from=[v.x,v.y,v.width,v.height],start=performance.now(),reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;function frame(now){const t=reduced||duration===0?1:Math.min((now-start)/duration,1),e=t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;$('starMap').setAttribute('viewBox',target.map((n,i)=>(from[i]+(n-from[i])*e).toFixed(3)).join(' '));if(t<1)animation=requestAnimationFrame(frame);}animation=requestAnimationFrame(frame);}
  function selectDay(index){clearTimeout(introTimer);selected=Math.max(0,Math.min(6,index));overview=false;document.body.classList.remove('is-overview');document.body.classList.add('is-focused');const d=days[selected];render();animateView([d.x-215,d.y-100,430,284]);}
  function showOverview(auto=false){clearTimeout(introTimer);overview=true;document.body.classList.add('is-overview');document.body.classList.remove('is-focused');render();animateView([0,0,1000,660],900);$('autoHint').textContent=auto?'停留片刻，自动走进今天':'轻触一颗星，看看那一天的期待';if(auto)introTimer=setTimeout(()=>{if(page==='stars')selectDay(today);},2500);}
  $('starNodes').addEventListener('click',e=>{const n=e.target.closest('[data-day]');if(n)selectDay(Number(n.dataset.day));});
  $('starNodes').addEventListener('keydown',e=>{const n=e.target.closest('[data-day]');if(n&&(e.key==='Enter'||e.key===' ')){e.preventDefault();selectDay(Number(n.dataset.day));}});
  $('weekStrip').addEventListener('click',e=>{const n=e.target.closest('[data-day]');if(n)selectDay(Number(n.dataset.day));});
  $('miniMap').onclick=()=>{showOverview(false);window.scrollTo({top:0,behavior:'smooth'});};
  $('modeToggle').onclick=()=>{mode=mode==='demo'?'personal':'demo';render();};
  $('planList').addEventListener('click',e=>{const b=e.target.closest('[data-complete]');if(!b)return;const p=plans()[selected].find(p=>p.id===b.dataset.complete);if(!p)return;p.done=!p.done;save();render();const replacement=$('planList').querySelector(`[data-complete="${CSS.escape(p.id)}"]`);replacement?.focus({preventScroll:true});if(p.done)toast('又为这一天，点亮了一点光。');});
  function draft(offset=editWeek){const key=keyAt(offset);if(!store.drafts[key])store.drafts[key]=clone(store.weeks[key]||blank());return store.drafts[key];}
  function renderIdeas(){const cats=['全部','家务','运动','学习','阅读','社交','探索','休息'];$('categoryFilter').innerHTML=cats.map(c=>`<button class="filter-button ${category===c?'active':''}" data-category="${c}" aria-pressed="${category===c}">${c}</button>`).join('');const added=draft(Number($('ideaWeek').value))[Number($('targetDay').value)];$('ideaGrid').innerHTML=ideas.map((idea,i)=>({idea,i})).filter(({idea})=>category==='全部'||idea[1]===category).map(({idea,i})=>{const exists=added.some(p=>p.name===idea[0]);return `<button class="idea-card ${exists?'added':''}" data-idea="${i}" aria-label="${exists?'已加入':'加入周计划草稿'}：${idea[0]}">${icon(idea[1])}<div><div class="idea-name">${idea[0]}</div><div class="idea-category">${idea[1]}</div></div><span class="plus" aria-hidden="true">${exists?'✓':'＋'}</span></button>`;}).join('');}
  $('categoryFilter').onclick=e=>{const b=e.target.closest('[data-category]');if(b){category=b.dataset.category;renderIdeas();}};
  function addIdea(name,category){const offset=Number($('ideaWeek').value),day=Number($('targetDay').value),items=draft(offset)[day];if(items.some(p=>p.name===name)){toast('这件小事，已经在那天的草稿里。');return false;}items.push({id:`plan-${nextId++}`,name,category,done:false});editWeek=offset;save();renderIdeas();toast(`已加入${offset?'下周':'本周'}周${days[day].weekday}草稿，确认周计划后显示在首页。`);return true;}
  $('ideaGrid').onclick=e=>{const b=e.target.closest('[data-idea]');if(b)addIdea(...ideas[Number(b.dataset.idea)]);};
  $('customForm').onsubmit=e=>{e.preventDefault();const name=$('customIdea').value.trim();if(name&&addIdea(name,'喜欢'))$('customIdea').value='';};
  $('ideaWeek').onchange=()=>{editWeek=Number($('ideaWeek').value);renderIdeas();};$('targetDay').onchange=renderIdeas;
  function renderPlanner(){const items=draft();$('plannerRange').textContent=weekLabel(editWeek);document.querySelectorAll('[data-edit-week]').forEach(b=>{b.classList.toggle('active',Number(b.dataset.editWeek)===editWeek);b.setAttribute('aria-pressed',String(Number(b.dataset.editWeek)===editWeek));});$('plannerGrid').innerHTML=days.map((d,i)=>`<section class="planner-day"><div class="planner-day-head"><div>周${d.weekday}<small>${dateAt(i,editWeek).getMonth()+1}.${dateAt(i,editWeek).getDate()}</small></div><span>${d.name}</span></div>${items[i].map(p=>`<div class="draft-row"><input data-draft="${esc(p.id)}" data-draft-day="${i}" value="${esc(p.name)}" maxlength="60" aria-label="周${d.weekday}计划" placeholder="想做的一件小事"><button class="delete-draft" data-remove="${esc(p.id)}" data-draft-day="${i}" aria-label="删除周${d.weekday}计划：${esc(p.name)}">×</button></div>`).join('')}<button class="add-draft" data-add-day="${i}">＋ 给周${d.weekday}留一点期待</button></section>`).join('');$('draftStatus').textContent='草稿自动保存在此浏览器，确认后才更新首页。';}
  document.querySelectorAll('[data-edit-week]').forEach(b=>b.onclick=()=>{editWeek=Number(b.dataset.editWeek);$('ideaWeek').value=String(editWeek);renderPlanner();});
  $('plannerGrid').oninput=e=>{const input=e.target.closest('[data-draft]');if(input){const p=draft()[Number(input.dataset.draftDay)].find(p=>p.id===input.dataset.draft);if(p){if(p.name!==input.value)p.done=false;p.name=input.value;save();$('draftStatus').textContent='草稿已更新。确认后，会替换这一周的首页计划。';}}};
  $('plannerGrid').onclick=e=>{const add=e.target.closest('[data-add-day]'),remove=e.target.closest('[data-remove]');if(add){const day=Number(add.dataset.addDay),id=`plan-${nextId++}`;draft()[day].push({id,name:'',category:'喜欢',done:false});save();renderPlanner();$('plannerGrid').querySelector(`[data-draft="${id}"]`).focus();}else if(remove){const day=Number(remove.dataset.draftDay);store.drafts[keyAt(editWeek)][day]=draft()[day].filter(p=>p.id!==remove.dataset.remove);save();renderPlanner();}};
  $('useExample').onclick=()=>{let count=0;const target=draft();freshDemo().forEach((items,i)=>items.forEach(p=>{if(!target[i].some(x=>x.name===p.name)){target[i].push({...p,id:`plan-${nextId++}`});count++;}}));save();renderPlanner();toast(count?'示例已加入草稿，请按自己的想法修改后再确认。':'示例内容已在草稿中。');};
  $('saveWeek').onclick=()=>{const offset=editWeek,key=keyAt(offset),existing=store.weeks[key]||blank();store.weeks[key]=draft().map((items,i)=>items.filter(p=>p.name.trim()).map(p=>{const previous=existing[i].find(x=>x.id===p.id&&x.name===p.name.trim());return {...p,name:p.name.trim(),done:previous?previous.done:p.done};}));store.drafts[key]=clone(store.weeks[key]);const persisted=save();if(offset===0){mode='personal';location.hash='stars';}else renderPlanner();if(persisted)toast(offset===0?'本周计划已确认。每天回来，慢慢点亮。':'下周计划已确认，下周会自动显示在首页。');};
  const apps=[{id:'douyin',name:'抖音',mark:'抖',minutes:52,continuous:35,color:'#c4a9b4',bg:'#b58fa01c'},{id:'red',name:'小红书',mark:'红',minutes:36,continuous:0,color:'#9db6ac',bg:'#81b3a51c'},{id:'wechat',name:'微信',mark:'微',minutes:24,continuous:0,color:'#c2ae88',bg:'#b9a0711c'},{id:'bili',name:'哔哩哔哩',mark:'哔',minutes:16,continuous:0,color:'#92adc3',bg:'#81a2c01c'}];
  function renderApps(){const s=store.settings;$('appList').innerHTML=apps.map(a=>`<div class="app-row"><span class="app-icon" style="--app-color:${a.color};--app-bg:${a.bg}">${a.mark}</span><div class="app-info"><strong>${a.name}</strong><small>${a.continuous?`本次连续 ${a.continuous} 分钟`:'当前未使用'} · 模拟</small></div><span class="app-minutes">${a.minutes}<small>分钟</small></span><label class="switch"><input type="checkbox" data-app="${a.id}" aria-label="将${a.name}纳入监测" ${s.apps.includes(a.id)?'checked':''}><span></span></label></div>`).join('');$('continuous').value=s.continuous;$('cumulative').value=s.cumulative;$('continuousEnabled').checked=s.continuousEnabled;$('cumulativeEnabled').checked=s.cumulativeEnabled;renderSettings();}
  function renderSettings(){const s=store.settings,chosen=apps.filter(a=>s.apps.includes(a.id)),total=chosen.reduce((n,a)=>n+a.minutes,0);$('monitorSummary').textContent=chosen.length?`已选择 ${chosen.length} 个应用 · 今日累计 ${total} 分钟（模拟）`:'尚未选择应用。打开左侧开关，选择想留意的应用。';$('continuousValue').textContent=`${s.continuous} 分钟`;$('cumulativeValue').textContent=s.cumulative>=60?`${Math.floor(s.cumulative/60)} 小时${s.cumulative%60?` ${s.cumulative%60} 分钟`:''}`:`${s.cumulative} 分钟`;$('continuous').disabled=!s.continuousEnabled;$('cumulative').disabled=!s.cumulativeEnabled;$('previewReminder').disabled=!chosen.length||(!s.continuousEnabled&&!s.cumulativeEnabled);}
  $('appList').onchange=e=>{const input=e.target.closest('[data-app]');if(input){store.settings.apps=input.checked?[...new Set([...store.settings.apps,input.dataset.app])]:store.settings.apps.filter(x=>x!==input.dataset.app);save();renderSettings();}};
  for(const id of ['continuous','cumulative'])$(id).oninput=()=>{store.settings[id]=Number($(id).value);save();renderSettings();};
  for(const id of ['continuousEnabled','cumulativeEnabled'])$(id).onchange=()=>{store.settings[id]=$(id).checked;save();renderSettings();};
  $('previewReminder').onclick=()=>{const chosen=apps.filter(a=>store.settings.apps.includes(a.id));$('reminderContext').textContent=`你选择的 ${chosen.length} 个应用，今天已使用 ${chosen.reduce((n,a)=>n+a.minutes,0)} 分钟（模拟）。这是一条手动触发的提醒演示。`;$('reminderDialog').showModal();};
  $('closeReminder').onclick=()=>{$('reminderDialog').close();};$('laterReminder').onclick=()=>{$('reminderDialog').close();toast('没关系，按你的节奏来。');};
  $('returnToPlan').onclick=()=>{$('reminderDialog').close();location.hash='stars';};
  function route(initial=false){const requested=location.hash.slice(1);page=['stars','ideas','space','planner'].includes(requested)?requested:'stars';clearTimeout(introTimer);cancelAnimationFrame(animation);document.body.dataset.page=page;for(const p of ['stars','ideas','space','planner'])$(p+'Page').hidden=p!==page;document.querySelectorAll('.nav-item').forEach(a=>{const active=a.hash===`#${page}`;a.classList.toggle('active',active);active?a.setAttribute('aria-current','page'):a.removeAttribute('aria-current');});if(page==='stars')showOverview(true);if(page==='ideas'){$('ideaWeek').value=String(editWeek);$('targetDay').value=String(selected);renderIdeas();}if(page==='planner')renderPlanner();if(page==='space')renderApps();window.scrollTo({top:0,behavior:'instant'});if(!initial){const title=$(page==='stars'?'starsPage':page+'Title');if(title){title.tabIndex=-1;title.focus({preventScroll:true});}}}
  window.addEventListener('hashchange',()=>route());
  document.querySelector('.brand').addEventListener('click',()=>{if(page==='stars')showOverview(true);});
  render();route(true);
})();
