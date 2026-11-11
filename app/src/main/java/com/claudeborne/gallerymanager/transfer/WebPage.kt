package com.claudeborne.gallerymanager.transfer

/**
 * The page a computer's browser gets from the phone: drop files to send them to the phone, and download what the phone offers, one by one,
 * all at once or as a zip. It uses no outside fonts or pictures: the computer may be on a direct link or a cable, with no internet.
 * (This is a raw string: no dollar signs, so the script uses plain string joining.)
 */
object WebPage {
    fun html(deviceName: String, token: String): String = PAGE
        .replace("__NAME__", deviceName.replace("&", "&amp;").replace("<", "&lt;"))
        .replace("__TOKEN__", token)

    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Altus Cirrus transfer</title>
<style>
:root{--mint:#3FE0C5;--blue:#3B8DFF;--ink:#E8ECF6;--dim:#8791AD;--line:#26304d;--mono:ui-monospace,SFMono-Regular,Consolas,Menlo,monospace}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink);background:#0a1020;background-image:linear-gradient(#ffffff0a 1px,transparent 1px),linear-gradient(90deg,#ffffff0a 1px,transparent 1px);background-size:32px 32px}
main{max-width:980px;margin:0 auto;padding:28px 20px 48px;display:flex;flex-direction:column;gap:18px}
header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;animation:rise .4s ease-out}
.brand{display:flex;align-items:center;gap:14px}
.logo{width:48px;height:48px;border:2px solid var(--mint);border-radius:8px;display:flex;align-items:center;justify-content:center;font-family:var(--mono);font-size:24px;color:var(--mint)}
.wm b{display:block;font-family:var(--mono);font-size:16px;letter-spacing:.16em;color:var(--mint);font-weight:500}
.wm span{font-family:var(--mono);font-size:12px;letter-spacing:.3em;color:var(--blue)}
.status{display:flex;align-items:center;gap:9px;font-family:var(--mono);font-size:12px;letter-spacing:.1em;color:var(--dim)}
.status b{color:var(--ink);font-weight:500}
.status u{width:8px;height:8px;border-radius:50%;background:var(--mint);animation:pulse 1.6s ease-in-out infinite}
@keyframes pulse{0%,100%{box-shadow:0 0 0 2px #3fe0c533}50%{box-shadow:0 0 0 6px #3fe0c522}}
@keyframes rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
.cols{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}
@media(max-width:760px){.cols{grid-template-columns:1fr}}
.card{--c:14px;padding:1.5px;background:linear-gradient(135deg,#3FE0C5,#3B8DFF,#8B6BFF);animation:rise .4s ease-out;clip-path:polygon(var(--c) 0,calc(100% - var(--c)) 0,100% var(--c),100% calc(100% - var(--c)),calc(100% - var(--c)) 100%,var(--c) 100%,0 calc(100% - var(--c)),0 var(--c))}
.card>.in{height:100%;background:#0f1730;padding:20px;display:flex;flex-direction:column;gap:14px;clip-path:polygon(calc(var(--c) - 1px) 0,calc(100% - var(--c) + 1px) 0,100% calc(var(--c) - 1px),100% calc(100% - var(--c) + 1px),calc(100% - var(--c) + 1px) 100%,calc(var(--c) - 1px) 100%,0 calc(100% - var(--c) + 1px),0 calc(var(--c) - 1px))}
h2{margin:0;font-family:var(--mono);font-size:12px;letter-spacing:.22em;color:var(--mint);font-weight:500}
.top{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.count{font-family:var(--mono);font-size:12px;color:var(--dim);letter-spacing:.06em}
.box{display:flex;flex-direction:column;align-items:center;gap:6px;border:1.5px dashed #3a4a7a;padding:34px 12px;text-align:center;font-size:14px;color:var(--dim);cursor:pointer;transition:border-color .2s,background-color .2s,color .2s}
.box b{font-size:26px;color:var(--mint);font-weight:400}
.box.over{border-color:var(--mint);background:#101e34;color:var(--ink)}
.btns{display:flex;gap:8px;flex-wrap:wrap}
button{font-family:var(--mono);font-size:12px;letter-spacing:.14em;padding:11px 16px;color:var(--ink);background:transparent;border:1px solid #3a4a7a;cursor:pointer;transition:background-color .15s,border-color .15s,color .15s,opacity .15s}
button:hover{background:#17224a;border-color:var(--mint)}
button.p{background:linear-gradient(90deg,#3FE0C5,#5B6BFF);border:none;color:#0a1020;font-weight:700}
button.p:hover{filter:brightness(1.08)}
button:disabled{opacity:.4;cursor:default;pointer-events:none}
.row{margin:2px 0;font-size:14px;word-break:break-all;animation:rise .25s ease-out}
.bar{height:4px;background:#ffffff1f;margin-top:5px;overflow:hidden;position:relative}.bar i{display:block;height:100%;width:0;background:var(--mint);transition:width .3s linear}
.bar.sweep i{width:35%;position:absolute;animation:sweep 1.1s linear infinite}
@keyframes sweep{from{left:-35%}to{left:100%}}
.dim{color:var(--dim)}.note{font-size:12px;color:var(--dim);line-height:1.5}
.files{display:flex;flex-direction:column}
.file{display:flex;align-items:center;gap:12px;padding:9px 0;border-top:1px solid var(--line);font-size:14px;animation:rise .25s ease-out}
.file .ext{transition:background-color .25s,color .25s;flex:none;width:44px;text-align:center;font-family:var(--mono);font-size:11px;letter-spacing:.06em;color:var(--mint);border:1px solid #2a3a66;padding:5px 0}
.file .nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.file .sz{flex:none;font-family:var(--mono);font-size:12px;color:var(--dim)}
.file a{flex:none;width:34px;height:30px;display:flex;align-items:center;justify-content:center;border:1px solid #3a4a7a;color:var(--mint);text-decoration:none;font-size:16px;transition:background-color .15s,border-color .15s}
.file a:hover{background:#17224a;border-color:var(--mint)}
.file.got .ext{background:var(--mint);color:#0a1020}
.mk{flex:none;width:22px;height:22px;display:inline-flex;align-items:center;justify-content:center}
.mk svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;animation:pop .25s ease-out}
.mk.ok{color:var(--mint)}.mk.fail{color:#ff6b6b}.mk.warn{color:#e0a030}.mk.run{color:var(--blue)}.mk.wait{color:var(--dim)}
.mk.run svg{animation:spin .9s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pop{from{opacity:0;transform:scale(.6)}to{opacity:1;transform:none}}
.grp{border-top:1px solid var(--line);padding:6px 0;animation:rise .25s ease-out}
.gh{display:flex;align-items:center;gap:12px;padding:6px 0;cursor:pointer;user-select:none}
.gh .fi{flex:none;width:24px;height:24px;fill:none;stroke:var(--mint);stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.gh .nm{flex:1;min-width:0;font-size:14px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.gh small{display:block;font-family:var(--mono);font-size:11px;font-weight:400;letter-spacing:.06em;color:var(--dim);transition:color .25s}
.gh .chev{flex:none;width:20px;height:20px;fill:none;stroke:var(--dim);stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;transform:rotate(180deg);transition:transform .25s}
.grp.shut .chev{transform:none}
.grp.bad small{color:#e0a030}.grp.bad>.bar i{background:#e0a030}
.gb{display:grid;grid-template-rows:1fr;transition:grid-template-rows .25s ease}
.gb>div{overflow:hidden;min-height:0;padding-left:34px}
.grp.shut .gb{grid-template-rows:0fr}
.grp .file{border-top:0;padding:6px 0}
.fr{display:flex;align-items:flex-start;gap:10px;margin:5px 0;font-size:14px;animation:rise .25s ease-out}
.mid{flex:1;min-width:0}.mid .nm{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.fr .nm{word-break:break-all;white-space:normal}
.st{font-family:var(--mono);font-size:11px;letter-spacing:.06em;color:var(--dim);margin-top:3px}
.st.bad{color:#ff6b6b}
[hidden]{display:none!important}
</style></head><body><main>
<header><div class="brand"><div class="logo">C</div><div class="wm"><b>CLAUDEBORNE</b><span>ALTUS CIRRUS &middot; TRANSFER</span></div></div>
<div class="status"><u></u><span>CONNECTED TO <b>__NAME__</b></span></div></header>
<div class="cols">
<section class="card"><div class="in">
<h2>SEND TO PHONE</h2>
<label class="box" id="drop"><b>&uarr;</b>Drop files here or click to choose<input id="pick" type="file" multiple hidden></label>
<div class="btns"><button id="files">CHOOSE FILES</button><button id="folder">CHOOSE A FOLDER</button></div><input id="pickdir" type="file" webkitdirectory hidden>
<div id="up"></div>
</div></section>
<section class="card"><div class="in">
<div class="top"><h2>FROM PHONE</h2><div class="count" id="count"></div></div>
<div class="btns"><button class="p" id="all" disabled>RECEIVE ALL</button><button id="zip" disabled>AS ZIP</button><button id="refresh">REFRESH</button></div>
<div id="progress" hidden><div class="note" id="ptxt"></div><div class="bar"><i id="pbar"></i></div></div>
<div class="note" id="hint" hidden>Your browser may ask once to allow multiple downloads. Say yes, and the files arrive in your Downloads folder. A zip keeps folders as they were.</div>
<div class="files" id="offers"><div class="dim">Nothing offered</div></div>
</div></section>
</div>
<section class="card"><div class="in">
<div class="top"><h2>SPEED TEST</h2><button id="speed">TEST SPEED</button></div>
<div class="note">Moves 64 MB each way between this computer and the phone, to compare Wi-Fi, a direct link and a USB cable.</div>
<div id="speedout" class="row"></div><div id="speedbar" class="bar sweep" hidden><i></i></div>
</div></section>
</main>
<script>
const T="__TOKEN__";
const fmt=n=>n>1e9?(n/1e9).toFixed(1)+' GB':n>1e6?(n/1e6).toFixed(1)+' MB':Math.ceil(n/1e3)+' KB';
const API='/api/localsend/v2/';
const SV={ok:'<circle cx="12" cy="12" r="9"/><path d="M8 12.5l3 3 5-6"/>',fail:'<circle cx="12" cy="12" r="9"/><path d="M9 9l6 6M15 9l-6 6"/>',warn:'<circle cx="12" cy="12" r="9"/><path d="M12 7v6"/><path d="M12 16.5v.5"/>',wait:'<circle cx="12" cy="12" r="9" stroke-dasharray="2 3"/>',run:'<path d="M12 4a8 8 0 1 1-8 8"/>'};
function mk(el,st){if(el.dataset.s===st)return;el.dataset.s=st;el.className='mk '+st;el.innerHTML=st?'<svg viewBox="0 0 24 24">'+SV[st]+'</svg>':''}
// a folder: its name, how many files are through, one bar for all of them, and the files inside it (tap the header to fold them away)
function grp(parent,title,verb,top){
  const e=document.createElement('div');e.className='grp';
  e.innerHTML='<div class="gh"><svg class="fi" viewBox="0 0 24 24"><path d="M3 7v12h18V9h-9l-2-3H4z"/></svg><div class="nm"><span></span><small></small></div><span class="mk"></span><svg class="chev" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></div><div class="bar"><i></i></div><div class="gb"><div></div></div>';
  if(top)parent.prepend(e);else parent.append(e);
  const q=s=>e.querySelector(s),g={body:q('.gb>div'),rows:[]};
  q('.nm span').textContent=title;q('.gh').onclick=()=>e.classList.toggle('shut');
  g.sync=()=>{const n=g.rows.length;let ok=0,bad=0,go=false,any=false,pw=0,w=0;
    for(const r of g.rows){if(r.st=='ok')ok++;else if(r.st=='fail')bad++;if(r.st)any=true;if(r.st&&r.st!='wait')go=true;pw+=(r.st=='ok'?1:r.p)*r.w;w+=r.w}
    const done=ok+bad==n;
    mk(q('.gh .mk'),!any?'':done?(bad?(ok?'warn':'fail'):'ok'):go?'run':'wait');
    q('small').textContent=ok+' OF '+n+' '+verb+(bad?' · '+bad+' FAILED':'');
    e.classList.toggle('bad',bad>0);
    q('.bar i').style.width=((w?pw/w:(done?1:0))*100)+'%'};
  return g}
// one file's state: wait (not started), run, ok or fail; p is how far it got (0 to 1)
function setR(r,st,p,text){
  r.st=st;if(p!=null)r.p=p;mk(r.mkEl,st);r.i.style.width=(r.p*100)+'%';
  if(r.dl)r.barBox.hidden=st!='run';
  if(text!=null){r.txt.textContent=text;r.txt.classList.toggle('bad',st=='fail')}
  if(r.g)r.g.sync()}
function frow(parent,name,shown,size,g,top){
  const d=document.createElement('div');d.className='fr';
  d.innerHTML='<span class="mk"></span><div class="mid"><span class="nm"></span><div class="bar sweep"><i></i></div><div class="st"></div></div>';
  if(top)parent.prepend(d);else parent.append(d);
  d.querySelector('.nm').textContent=shown;
  const r={name,mkEl:d.firstChild,i:d.querySelector('i'),barBox:d.querySelector('.bar'),txt:d.querySelector('.st'),st:'',p:0,w:size,g};
  if(g)g.rows.push(r);
  setR(r,'wait',0,'WAITING FOR THE PHONE TO ACCEPT '+fmt(size));return r}
function put(file,id,session,token,row){return new Promise(done=>{
  const x=new XMLHttpRequest();
  x.open('POST',API+'upload?sessionId='+session+'&fileId='+id+'&token='+token);
  setR(row,'run',0,'SENDING');
  x.upload.onprogress=e=>setR(row,'run',e.loaded/e.total,fmt(e.loaded)+' OF '+fmt(e.total));
  x.onload=()=>{setR(row,x.status==200?'ok':'fail',1,x.status==200?'DONE '+fmt(file.size):'FAILED · '+x.status);done()};
  x.onerror=()=>{setR(row,'fail',null,'FAILED · NO ANSWER');done()};
  x.send(file);
})}
let last=Promise.resolve();
function run(files){const list=[...files];last=last.then(()=>batch(list))}
async function batch(q){
  const box=document.getElementById('up'),groups={},rows=q.map(f=>{
    const name=f.webkitRelativePath||f.name,top=name.indexOf('/')>0?name.split('/')[0]:'';
    if(!top)return frow(box,name,name,f.size,null,true);
    const g=groups[top]||(groups[top]=grp(box,top,'SENT',true));
    return frow(g.body,name,name.slice(top.length+1),f.size,g,false)});
  const files={};q.forEach((f,i)=>{files[i]={id:String(i),fileName:rows[i].name,size:f.size,fileType:f.type||'application/octet-stream'}});
  const fail=m=>rows.forEach(r=>{r.barBox.classList.remove('sweep');setR(r,'fail',0,'FAILED · '+m.toUpperCase())});
  let res;
  try{res=await fetch(API+'prepare-upload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({info:{alias:'Computer (browser)',version:'2.0',deviceType:'desktop',fingerprint:'browser'},files:files})})}catch(e){return fail('the phone did not answer')}
  if(res.status==403)return fail('declined');
  if(res.status==408)return fail('no answer');
  if(res.status==409)return fail('the phone is busy');
  if(res.status!=200)return fail('failed');
  const a=await res.json(),ids=q.map((_,i)=>i);
  rows.forEach(r=>{r.barBox.classList.remove('sweep');setR(r,'wait',0,'WAITING '+fmt(r.w))});
  async function worker(){while(ids.length){const i=ids.shift();await put(q[i],i,a.sessionId,a.files[i],rows[i])}}
  await Promise.all([worker(),worker(),worker(),worker()]);
}
const pick=document.getElementById('pick'),pickdir=document.getElementById('pickdir'),drop=document.getElementById('drop');
pick.onchange=()=>run(pick.files);pickdir.onchange=()=>run(pickdir.files);
document.getElementById('files').onclick=()=>pick.click();
document.getElementById('folder').onclick=()=>pickdir.click();
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');run(e.dataTransfer.files)};

let list=[],busy=false,orows={};
const box=document.getElementById('offers'),allBtn=document.getElementById('all'),zipBtn=document.getElementById('zip');
const ext=n=>{const i=n.lastIndexOf('.');return i>0&&i>n.lastIndexOf('/')?n.slice(i+1,i+5).toUpperCase():'FILE'};
const url=id=>'/api/file/'+id+'?t='+T;
async function offers(){
  box.style.opacity=.4; // dims while the list is fetched
  list=await (await fetch('/api/offers?t='+T)).json();box.style.opacity=1;
  box.innerHTML=list.length?'':'<div class="dim">Nothing offered</div>';orows={};
  const groups={};
  for(const o of list){const top=o.name.indexOf('/')>0?o.name.split('/')[0]:'',d=document.createElement('div');d.className='file';d.id='f'+o.id;
    d.innerHTML='<span class="mk"></span><span class="ext"></span><div class="mid"><span class="nm"></span><div class="bar" hidden><i></i></div></div><span class="sz"></span><a download title="Download">&darr;</a>';
    const r={name:o.name,mkEl:d.children[0],i:d.querySelector('i'),barBox:d.querySelector('.bar'),st:'',p:0,w:o.size,g:null,dl:true};
    d.children[1].textContent=ext(o.name);const n=d.querySelector('.nm');n.textContent=top?o.name.slice(top.length+1):o.name;n.title=o.name;
    d.children[3].textContent=fmt(o.size);const a=d.children[4];a.href=url(o.id);a.onclick=()=>{d.classList.add('got');if(!r.st)setR(r,'wait',0)};
    if(top){const g=groups[top]||(groups[top]=grp(box,top,'RECEIVED',false));r.g=g;g.rows.push(r);g.body.append(d)}else box.append(d);
    orows[o.name]=r;}
  for(const k in groups)groups[k].sync();
  poll();
  const total=list.reduce((s,o)=>s+o.size,0);
  document.getElementById('count').textContent=list.length?list.length+(list.length==1?' FILE · ':' FILES · ')+fmt(total):'';
  allBtn.disabled=zipBtn.disabled=!list.length;
  document.getElementById('hint').hidden=!list.length;
}
// what the phone has sent of each file so far (it counts the bytes it has handed over), so the list can show moving, done and failed
async function poll(){
  if(document.hidden)return;
  let s;try{s=await (await fetch('/api/status?t='+T)).json()}catch(e){return}
  const m={};s.forEach(x=>{m[x.name]=x}); // a file fetched twice: the last one counts
  for(const k in orows){const x=m[k],r=orows[k];if(!x)continue;
    setR(r,x.error?'fail':x.finished?'ok':x.done>0?'run':'wait',x.finished?1:x.size?x.done/x.size:0);
    r.mkEl.title=x.error||''}
}
setInterval(poll,800);
// RECEIVE ALL: starts each download in turn; the browser saves them to its Downloads folder
allBtn.onclick=async()=>{
  if(!list.length||busy)return;busy=true;allBtn.disabled=true;
  const p=document.getElementById('progress'),txt=document.getElementById('ptxt'),bar=document.getElementById('pbar');p.hidden=false;
  for(let i=0;i<list.length;i++){
    txt.textContent='RECEIVING '+(i+1)+' OF '+list.length;bar.style.width=(i*100/list.length)+'%';
    const a=document.createElement('a');a.href=url(list[i].id);a.download='';a.hidden=true;document.body.append(a);a.click();a.remove();
    const row=document.getElementById('f'+list[i].id);if(row)row.classList.add('got');const rr=orows[list[i].name];if(rr&&!rr.st)setR(rr,'wait',0);
    await new Promise(r=>setTimeout(r,600));
  }
  bar.style.width='100%';txt.textContent='ALL '+list.length+' DOWNLOADS STARTED. SEE YOUR BROWSER\'S DOWNLOADS.';
  busy=false;allBtn.disabled=false;
};
zipBtn.onclick=()=>{location.href='/api/zip?t='+T};
document.getElementById('refresh').onclick=offers;offers();
const fmtSpeed=mbs=>mbs.toFixed(1)+' MB/s ('+(mbs*8).toFixed(0)+' Mbps)';
document.getElementById('speed').onclick=async()=>{
  const out=document.getElementById('speedout'),sb=document.getElementById('speedbar');out.textContent='Testing download…';sb.hidden=false;
  let t=performance.now(),n=0;
  const reader=(await fetch('/api/speed/down?mb=64&t='+T)).body.getReader();
  for(;;){const r=await reader.read();if(r.done)break;n+=r.value.length}
  const down=n/1048576/((performance.now()-t)/1000);
  out.textContent='Download '+fmtSpeed(down)+' · testing upload…';
  const blob=new Blob([new Uint8Array(64*1048576)]);t=performance.now();
  await fetch('/api/speed/up?t='+T,{method:'PUT',body:blob});
  out.textContent='Download '+fmtSpeed(down)+' · Upload '+fmtSpeed(64/((performance.now()-t)/1000));
  sb.hidden=true;
};
</script></body></html>"""
}
