package com.webtoon.translator.web

/** JavaScript programs injected into the reader WebView. */
object WebScripts {
    const val EXTRACT_IMAGES = """
(function(){
  var out=[], seen=Object.create(null);
  function add(el,u){
    if(!u||seen[u]||!/^(https?:|blob:|data:image)/i.test(u))return;
    if(/(logo|icon|avatar|sprite|banner|favicon|placeholder|spinner|doubleclick|tracker)/i.test(u))return;
    var r=el.getBoundingClientRect(), nw=el.naturalWidth||r.width, nh=el.naturalHeight||r.height;
    if(r.width<80||r.height<60||nw<80||nh<60)return;
    seen[u]=1;out.push({url:u,x:r.left,y:r.top,w:r.width,h:r.height,nw:nw,nh:nh,complete:!!el.complete});
  }
  document.querySelectorAll('img').forEach(function(el){
    add(el,el.currentSrc||el.src||el.getAttribute('data-src')||el.getAttribute('data-original')||el.getAttribute('data-lazy-src')||'');
  });
  out.sort(function(a,b){return a.y-b.y||a.x-b.x;});
  return JSON.stringify(out);
})();
"""

    const val EXTRACT_NOVEL = """
(function(){
  function txt(e){return (e.textContent||'').replace(/\\s+/g,' ').trim();}
  var sels='article,main,#content,.entry-content,.chapter-content,.reading-content,div[class*="chapter" i],div[class*="content" i]';
  var candidates=Array.prototype.slice.call(document.querySelectorAll(sels));
  var best=null,bestScore=0;
  for(var i=0;i<candidates.length;i++){
    var ps=candidates[i].querySelectorAll('p'),score=0;
    for(var j=0;j<ps.length;j++){var t=txt(ps[j]);if(t.length>40)score+=t.length;}
    if(score>bestScore){bestScore=score;best=candidates[i];}
  }
  if(!best)return JSON.stringify({ok:false,reason:'no chapter text container'});
  var ps=best.querySelectorAll('p'),out=[];
  for(var p=0;p<ps.length;p++){var t=txt(ps[p]);if(t.length>=2)out.push({idx:p,text:t});}
  if(out.length<2)return JSON.stringify({ok:false,reason:'too few paragraphs'});
  return JSON.stringify({ok:true,count:out.length,items:out});
})();
"""

    /** Find a real next-episode link; never fabricate or increment episode IDs. */
    const val FIND_NEXT_CHAPTER_URL = """
(function(){
  try{
    var current=new URL(location.href), best='', bestScore=-1;
    var links=Array.prototype.slice.call(document.querySelectorAll('a[href],link[rel="next"][href]'));
    var now=Number(current.searchParams.get('episode_no')||current.searchParams.get('episode')||'0');
    var title=current.searchParams.get('title_no')||'';
    for(var i=0;i<links.length;i++){
      var a=links[i], raw=a.href||a.getAttribute('href'); if(!raw)continue;
      var u; try{u=new URL(raw,location.href);}catch(e){continue;}
      if((u.protocol!=='http:'&&u.protocol!=='https:')||u.href.split('#')[0]===current.href.split('#')[0])continue;
      var rel=(a.getAttribute('rel')||'').toLowerCase();
      var label=((a.innerText||a.textContent||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||'')+' '+(typeof a.className==='string'?a.className:'')).toLowerCase();
      var score=-1;
      if(rel.split(' ').indexOf('next')>=0)score=120;
      if(label.indexOf('next')>=0||label.indexOf('continue reading')>=0||
         label.indexOf('下一话')>=0||label.indexOf('下一章')>=0||label.indexOf('下話')>=0||
         label.indexOf('次の話')>=0||label.indexOf('다음화')>=0||label.indexOf('다음 회차')>=0)score=Math.max(score,90);
      var ep=Number(u.searchParams.get('episode_no')||u.searchParams.get('episode')||'0');
      var title2=u.searchParams.get('title_no')||'';
      if(now>0&&ep>now&&(!title||!title2||title===title2)){
        score=Math.max(score,(u.pathname===current.pathname?80:65)-Math.min(ep-now,30));
      }
      if(score>bestScore){bestScore=score;best=u.href;}
    }
    return JSON.stringify({url:bestScore>=0?best:''});
  }catch(e){return JSON.stringify({url:'',reason:String(e)});}
})();
"""

    const val SCROLL_STEP = """
(function(step){
  var max=document.documentElement.scrollHeight||document.body.scrollHeight;
  var before=window.scrollY;
  window.scrollBy(0,Number(step)||700);
  return JSON.stringify({scrolled:window.scrollY>before,y:window.scrollY,pageHeight:max,done:(window.scrollY+window.innerHeight)>=max-40});
})
"""

    const val FETCH_IMAGE = """
(function(url){
  return new Promise(function(resolve){
    function b64(buf){var u=new Uint8Array(buf),s='',chunk=0x8000;for(var i=0;i<u.length;i+=chunk)s+=String.fromCharCode.apply(null,u.subarray(i,i+chunk));return btoa(s);}
    if(url.indexOf('data:')===0){
      var m=url.match(/^data:([^;]+);base64,(.*)$/);
      resolve(m?JSON.stringify({ok:true,mime:m[1],base64:m[2]}):JSON.stringify({ok:false,reason:'unsupported data URL'}));return;
    }
    fetch(url,{credentials:'include'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.arrayBuffer();})
      .then(function(buf){resolve(JSON.stringify({ok:true,mime:'image/*',base64:b64(buf)}));})
      .catch(function(e){resolve(JSON.stringify({ok:false,reason:String(e)}));});
  });
})
"""

    const val APPLY_NOVEL = """
(function(payload){
  try{
    var p=(typeof payload==='string')?JSON.parse(payload):payload;
    function txt(e){return (e.textContent||'').replace(/\\s+/g,' ').trim();}
    var sels='article,main,#content,.entry-content,.chapter-content,.reading-content,div[class*="chapter" i],div[class*="content" i]';
    var cs=Array.prototype.slice.call(document.querySelectorAll(sels)),best=null,scoreBest=0;
    for(var i=0;i<cs.length;i++){var ps=cs[i].querySelectorAll('p'),score=0;for(var j=0;j<ps.length;j++){var t=txt(ps[j]);if(t.length>40)score+=t.length;}if(score>scoreBest){scoreBest=score;best=cs[i];}}
    if(!best)return JSON.stringify({ok:false,reason:'container not found'});
    var paras=best.querySelectorAll('p'),applied=0,missing=[];
    for(var i=0;i<p.updates.length;i++){var u=p.updates[i],el=paras[u.idx];if(!el){missing.push(u.idx);continue;}
      if(u.original&&txt(el)!==u.original.replace(/\\s+/g,' ').trim()){missing.push(u.idx);continue;}
      el.textContent=u.text;applied++;}
    return JSON.stringify({ok:applied>0,applied:applied,missing:missing});
  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
})
"""

    const val INJECT_OVERLAY_LAYER = """
(function(){
  if(!document.getElementById('wt-overlay-style')){
    var st=document.createElement('style');st.id='wt-overlay-style';
    st.textContent='.wt-ovwrap{position:absolute;left:0;top:0;pointer-events:none;z-index:2147483000}.wt-ov{position:absolute;box-sizing:border-box;overflow:hidden;display:flex;align-items:center;justify-content:center;text-align:center;font-family:inherit;font-weight:inherit;font-style:inherit;line-height:1.15;color:var(--wt-text-color,#111);text-shadow:none;border-radius:1px;pointer-events:none;}';
    document.documentElement.appendChild(st);
  }
  return 'ok';
})();
"""

    const val APPLY_OVERLAYS = """
(function(payload){
  try{
    var p=(typeof payload==='string')?JSON.parse(payload):payload,imgs=document.querySelectorAll('img'),img=null;
    for(var i=0;i<imgs.length;i++){var u=imgs[i].currentSrc||imgs[i].src||'';if(u===p.src){img=imgs[i];break;}}
    if(!img)return JSON.stringify({ok:false,reason:'image not found'});
    var rect=img.getBoundingClientRect();
    if(rect.width<5||rect.height<5)return JSON.stringify({ok:false,reason:'image not visible'});
    // Use document coordinates on a body-level layer. Parent-relative offsets
    // drift on Webtoon pages with transformed/scaled image containers.
    var id='wt-wrap-'+p.imageId.replace(/[^a-zA-Z0-9_-]/g,'_');
    var old=document.getElementById(id);if(old)old.remove();
    var wrap=document.createElement('div');wrap.id=id;wrap.className='wt-ovwrap';
    wrap.style.left=(rect.left+window.scrollX)+'px';
    wrap.style.top=(rect.top+window.scrollY)+'px';
    wrap.style.width=rect.width+'px';wrap.style.height=rect.height+'px';
    for(var k=0;k<p.regions.length;k++){
      var g=p.regions[k],box=document.createElement('div');box.className='wt-ov';
      box.style.left=(g.l*100)+'%';box.style.top=(g.t*100)+'%';box.style.width=(g.w*100)+'%';box.style.height=(g.h*100)+'%';
      box.style.fontSize=Math.max(8,Math.min(26,g.h*rect.height*.62))*p.fontScale+'px';
      box.style.background='rgba(255,255,255,'+p.opacity+')';box.style.color='#111';box.textContent=g.text;wrap.appendChild(box);
    }
    (document.body||document.documentElement).appendChild(wrap);return JSON.stringify({ok:true,created:p.regions.length});
  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
})
"""

    const val CLEAR_OVERLAYS = """
(function(){var a=document.querySelectorAll('.wt-ovwrap');var n=a.length;for(var i=0;i<n;i++)a[i].remove();return JSON.stringify({ok:true,removed:n});})();
"""
}
