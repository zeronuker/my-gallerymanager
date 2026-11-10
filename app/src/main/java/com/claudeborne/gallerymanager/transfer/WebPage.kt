package com.claudeborne.gallerymanager.transfer

/** The page a computer's browser gets from the phone: drop files to send them to the phone, and download what the phone offers. */
object WebPage {
    fun html(deviceName: String, token: String): String = PAGE
        .replace("__NAME__", deviceName.replace("&", "&amp;").replace("<", "&lt;"))
        .replace("__TOKEN__", token)

    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Altus Cirrus transfer</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b1020;color:#e8ecf5;max-width:720px;margin:0 auto;padding:24px}
h1{font-size:18px;letter-spacing:.25em;color:#3ddc97}h2{font-size:13px;letter-spacing:.2em;color:#4c8dff;margin-top:32px}
.box{display:block;border:2px dashed #3a4a7a;border-radius:8px;padding:32px;text-align:center;cursor:pointer}
.box{transition:border-color .2s,background-color .2s}.box.over{border-color:#3ddc97;background:#101a33}
button{background:#1c2542;color:#e8ecf5;border:1px solid #3a4a7a;border-radius:6px;padding:8px 14px;margin:8px 8px 0 0;cursor:pointer;transition:background-color .15s,border-color .15s}
button:hover{background:#26335c;border-color:#4c8dff}
.row{margin:10px 0;font-size:14px;word-break:break-all;animation:rowin .25s ease-out}
@keyframes rowin{from{opacity:0;transform:translateY(-6px)}to{opacity:1;transform:none}}
.bar{height:5px;background:#1c2542;margin-top:4px;overflow:hidden;position:relative}.bar i{display:block;height:100%;width:0;background:#3ddc97;transition:width .25s linear}
.bar.sweep i{width:35%;position:absolute;animation:sweep 1.1s linear infinite}
@keyframes sweep{from{left:-35%}to{left:100%}}
a{color:#4c8dff}.dim{color:#8a94a8}#offers{transition:opacity .2s}[hidden]{display:none}
</style></head><body>
<h1>ALTUS CIRRUS · TRANSFER</h1>
<div class="dim">Connected to <b>__NAME__</b></div>
<h2>SEND TO PHONE</h2>
<label class="box" id="drop">Drop files here or click to choose<input id="pick" type="file" multiple hidden></label>
<button id="folder">Choose a folder</button><input id="pickdir" type="file" webkitdirectory hidden>
<div id="up"></div>
<h2>FROM PHONE</h2>
<div id="offers" class="dim">Nothing offered</div>
<button id="refresh">Refresh</button>
<h2>SPEED TEST</h2>
<div class="dim">Moves 64 MB each way between this computer and the phone, to compare Wi-Fi, a direct link and a USB cable.</div>
<button id="speed">Test speed</button><div id="speedout" class="row"></div><div id="speedbar" class="bar sweep" hidden><i></i></div>
<script>
const T="__TOKEN__";
const fmt=n=>n>1e9?(n/1e9).toFixed(1)+' GB':n>1e6?(n/1e6).toFixed(1)+' MB':Math.ceil(n/1e3)+' KB';
const API='/api/localsend/v2/';
function put(file,id,session,token,row){return new Promise(done=>{
  const x=new XMLHttpRequest();
  x.open('POST',API+'upload?sessionId='+session+'&fileId='+id+'&token='+token);
  x.upload.onprogress=e=>{row.bar.style.width=(e.loaded*100/e.total)+'%'};
  x.onload=()=>{row.bar.style.width='100%';row.txt.textContent=row.name+(x.status==200?' ✓':' ✗ '+x.status);done()};
  x.onerror=()=>{row.txt.textContent=row.name+' ✗ failed';done()};
  x.send(file);
})}
let last=Promise.resolve();
function run(files){const list=[...files];last=last.then(()=>batch(list))}
async function batch(q){
  const box=document.getElementById('up'),rows=q.map(f=>{
    const r=document.createElement('div');r.className='row';
    r.innerHTML='<span></span> <span class="dim">'+fmt(f.size)+'</span><div class="bar sweep"><i></i></div>';
    box.prepend(r);
    const row={name:f.webkitRelativePath||f.name,txt:r.children[0],bar:r.querySelector('i'),box:r.querySelector('.bar')};
    row.txt.textContent=row.name+' · waiting for the phone to accept';return row});
  const files={};q.forEach((f,i)=>{files[i]={id:String(i),fileName:rows[i].name,size:f.size,fileType:f.type||'application/octet-stream'}});
  const fail=m=>rows.forEach(r=>{r.box.classList.remove('sweep');r.txt.textContent=r.name+' ✗ '+m});
  let res;
  try{res=await fetch(API+'prepare-upload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({info:{alias:'Computer (browser)',version:'2.0',deviceType:'desktop',fingerprint:'browser'},files:files})})}catch(e){return fail('the phone did not answer')}
  if(res.status==403)return fail('declined');
  if(res.status==408)return fail('no answer');
  if(res.status==409)return fail('the phone is busy');
  if(res.status!=200)return fail('failed');
  const a=await res.json(),ids=q.map((_,i)=>i);
  rows.forEach(r=>{r.box.classList.remove('sweep');r.txt.textContent=r.name});
  async function worker(){while(ids.length){const i=ids.shift();await put(q[i],i,a.sessionId,a.files[i],rows[i])}}
  await Promise.all([worker(),worker(),worker(),worker()]);
}
const pick=document.getElementById('pick'),pickdir=document.getElementById('pickdir'),drop=document.getElementById('drop');
pick.onchange=()=>run(pick.files);pickdir.onchange=()=>run(pickdir.files);
document.getElementById('folder').onclick=()=>pickdir.click();
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');run(e.dataTransfer.files)};
async function offers(){
  const box=document.getElementById('offers');box.style.opacity=.4; // dims while the list is fetched
  const list=await (await fetch('/api/offers?t='+T)).json();box.style.opacity=1;
  box.innerHTML=list.length?'':'Nothing offered';
  for(const o of list){const d=document.createElement('div');d.className='row';
    d.innerHTML='<a download href="/api/file/'+o.id+'?t='+T+'"></a> <span class="dim">'+fmt(o.size)+'</span>';
    d.firstChild.textContent=o.name;box.append(d);}
}
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
