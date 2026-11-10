package com.claudeborne.gallerymanager.transfer

/** The page a computer's browser gets from the phone: drop files to send them to the phone, and download what the phone offers. */
object WebPage {
    fun html(deviceName: String, token: String): String = PAGE
        .replace("__NAME__", deviceName.replace("&", "&amp;").replace("<", "&lt;"))
        .replace("__TOKEN__", token)

    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Gallery Manager transfer</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b1020;color:#e8ecf5;max-width:720px;margin:0 auto;padding:24px}
h1{font-size:18px;letter-spacing:.25em;color:#3ddc97}h2{font-size:13px;letter-spacing:.2em;color:#4c8dff;margin-top:32px}
.box{display:block;border:2px dashed #3a4a7a;border-radius:8px;padding:32px;text-align:center;cursor:pointer}
.box.over{border-color:#3ddc97}button{background:#1c2542;color:#e8ecf5;border:1px solid #3a4a7a;border-radius:6px;padding:8px 14px;margin:8px 8px 0 0;cursor:pointer}
.row{margin:10px 0;font-size:14px;word-break:break-all}.bar{height:5px;background:#1c2542;margin-top:4px}.bar i{display:block;height:100%;width:0;background:#3ddc97}
a{color:#4c8dff}.dim{color:#8a94a8}
</style></head><body>
<h1>GALLERY MANAGER · TRANSFER</h1>
<div class="dim">Connected to <b>__NAME__</b></div>
<h2>SEND TO PHONE</h2>
<label class="box" id="drop">Drop files here or click to choose<input id="pick" type="file" multiple hidden></label>
<button id="folder">Choose a folder</button><input id="pickdir" type="file" webkitdirectory hidden>
<div id="up"></div>
<h2>FROM PHONE</h2>
<div id="offers" class="dim">Nothing offered</div>
<button id="refresh">Refresh</button>
<script>
const T="__TOKEN__";
const fmt=n=>n>1e9?(n/1e9).toFixed(1)+' GB':n>1e6?(n/1e6).toFixed(1)+' MB':Math.ceil(n/1e3)+' KB';
function put(file,row){return new Promise(done=>{
  const x=new XMLHttpRequest(),name=file.webkitRelativePath||file.name;
  x.open('PUT','/api/upload?t='+T+'&name='+encodeURIComponent(name)+'&mtime='+file.lastModified);
  x.upload.onprogress=e=>{row.bar.style.width=(e.loaded*100/e.total)+'%'};
  x.onload=()=>{row.bar.style.width='100%';row.txt.textContent=name+(x.status==200?' ✓':' ✗ '+x.status);done()};
  x.onerror=()=>{row.txt.textContent=name+' ✗ failed';done()};
  x.send(file);
})}
async function run(files){
  const q=[...files],box=document.getElementById('up');
  async function worker(){
    while(q.length){
      const f=q.shift(),r=document.createElement('div');r.className='row';
      r.innerHTML='<span></span> <span class="dim">'+fmt(f.size)+'</span><div class="bar"><i></i></div>';
      box.prepend(r);
      const row={txt:r.children[0],bar:r.querySelector('i')};row.txt.textContent=f.webkitRelativePath||f.name;
      await put(f,row);
    }
  }
  await Promise.all([worker(),worker(),worker(),worker()]);
}
const pick=document.getElementById('pick'),pickdir=document.getElementById('pickdir'),drop=document.getElementById('drop');
pick.onchange=()=>run(pick.files);pickdir.onchange=()=>run(pickdir.files);
document.getElementById('folder').onclick=()=>pickdir.click();
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');run(e.dataTransfer.files)};
async function offers(){
  const list=await (await fetch('/api/offers?t='+T)).json(),box=document.getElementById('offers');
  box.innerHTML=list.length?'':'Nothing offered';
  for(const o of list){const d=document.createElement('div');d.className='row';
    d.innerHTML='<a download href="/api/file/'+o.id+'?t='+T+'"></a> <span class="dim">'+fmt(o.size)+'</span>';
    d.firstChild.textContent=o.name;box.append(d);}
}
document.getElementById('refresh').onclick=offers;offers();
</script></body></html>"""
}
