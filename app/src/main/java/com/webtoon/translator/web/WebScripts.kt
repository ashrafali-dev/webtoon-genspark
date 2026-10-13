package com.webtoon.translator.web

/**
 * All JavaScript injected into WebViews lives here as Kotlin string templates.
 * Every payload is passed through JSON.stringify on the JS side, never interpolated raw.
 */
object WebScripts {

    /**
     * Extracts chapter candidate images from the CURRENT reader page.
     * Returns JSON: {url, x, y, w, h, area, natural:{w,h}, complete}[]
     * Filters obvious non-panel images (ads, avatars, icons, tiny images).
     */
    const val EXTRACT_IMAGES = """
(function(){
  function n(t){return typeof t==='number'&&isFinite(t)?t:0;}
  var out=[], seen=Object.create(null);
  function push(u,el){
    if(!u||u.length>2000000)return;
    if(u.indexOf('blob:')!==0&&u.indexOf('data:')!==0&&u.indexOf('http')!==0)return;
    if(/(logo|icon|avatar|sprite|banner|favicon|placeholder|spinner|loading|ads?|doubleclick|tracker)/i.test(u))return;
    var r=el?el.getBoundingClientRect():{width:0,height:0};
    var w=n(r.width),h=n(r.height);
    var nw=el&&el.naturalWidth?el.naturalWidth:w, nh=el&&el.naturalHeight?el.naturalHeight:h;
    if(w<80||h<60) return;               // icons/avatars
    if(w*2<h&&h>1500) {/* tall strip ok */}
    var key=u; if(seen[key])return; seen[key]=1;
    out.push({url:u,x:n(r.left),y:n(r.top),w:w,h:h,nw:nw,nh:nh,complete:el?!!el.complete:true});
  }
  var imgs=document.querySelectorAll('img');
  for(var i=0;i<imgs.length;i++){
    var el=imgs[i];
    var u=el.currentSrc||el.src||el.getAttribute('data-src')||el.getAttribute('data-original')||el.getAttribute('data-lazy-src')||el.getAttribute('data-cfsrc')||'';
    push(u,el);
  }
  // CSS background panels inside common reader containers
  var containers=document.querySelectorAll('#reader,#comic,iM AGE,#page,div[class*="reading" i],div[class*="reading-content"],div[class*="readingwrap"],div[class*="container-chapter-reader"]');
  for(var c=0;c<containers.length;c++){
    var kids=containers[c].querySelectorAll('img,div,section');
    for(var k=0;k<kids.length;k++){
      var el2=kids[k];
      if(el2.tagName==='IMG'){push(el2.currentSrc||el2.src||'',el2);}
      else{
        try{var bg=getComputedStyle(el2).backgroundImage;
          var m=bg&&bg.match(/url\(["']?([^"')]+)["']?\)/);
          if(m&&m[1]&&m[1].indexOf('data:image')===0)push(m[1],el2);
        }catch(e){}
      }
    }
  }
  // sort top-to-bottom as the page displays them
  out.sort(function(a,b){return (a.y-b.y)||(a.x-b.x);});
  return JSON.stringify(out);
})();
"""

    /**
     * Finds chapter images inside a container by selector text.
     */
    const val EXTRACT_IMAGES_IN = """
(function(sel){
  var root=document.querySelector(sel)||document;
  var out=[],seen=Object.create(null);
  var imgs=root.querySelectorAll('img');
  for(var i=0;i<imgs.length;i++){
    var el=imgs[i];var u=el.currentSrc||el.src||el.getAttribute('data-src')||'';
    if(!u||seen[u])continue;seen[u]=1;
    var r=el.getBoundingClientRect();
    if(r.width<80||r.height<60)continue;
    out.push({url:u,x:r.left,y:r.top,w:r.width,h:r.height,nw:el.naturalWidth,nh:el.naturalHeight,complete:!!el.complete});
  }
  out.sort(function(a,b){return (a.y-b.y)||(a.x-b.x);});
  return JSON.stringify(out);
})
"""

    /**
     * Extracts novel paragraphs from the best-scoring content container.
     * Returns JSON: [{idx, text}] — stable idx maps 1:1 to DOM element position.
     */
    const val EXTRACT_NOVEL = """
(function(){
  function txt(e){return (e.textContent||'').replace(/\s+/g,' ').trim();}
  var candidates=Array.prototype.slice.call(document.querySelectorAll(
    'div[class*="chapter" i],div[class*="content" i],article,#content,.entry-content,.chapter-content,.reading-content,main'));
  var best=null,bestScore=0;
  for(var i=0;i<candidates.length;i++){
    var ps=candidates[i].querySelectorAll('p');
    var score=0;
    for(var p=0;p<ps.length;p++){var t=txt(ps[p]);if(t.length>60)score+=t.length;}
    if(score>bestScore){bestScore=score;best=candidates[i];}
  }
  if(!best)return JSON.stringify({ok:false,reason:'no content container'});
  var paras=best.querySelectorAll('p');
  var out=[];
  for(var q=0;q<paras.length;q++){
    var t=txt(paras[q]);
    if(t.length<2)continue;
    out.push({idx:q,text:t});
  }
  if(out.length<2)return JSON.stringify({ok:false,reason:'too few paragraphs ('+out.length+')'});
  return JSON.stringify({ok:true,count:out.length,items:out});
})();
"""

    /** Replaces paragraph idx text with the translation, preserving the node. */
    const val APPLY_NOVEL = """
(function(payload){
  try{
    var p=JSON.parse(payload);
    var best=null,bestScore=0;
    function txt(e){return (e.textContent||'').replace(/\s+/g,' ').trim();}
    var candidates=Array.prototype.slice.call(document.querySelectorAll(
      'div[class*="chapter" i],div[class*="content" i],article,#content,.entry-content,.chapter-content,.reading-content,main'));
    for(var i=0;i<candidates.length;i++){
      var ps=candidates[i].querySelectorAll('p');
      var score=0;
      for(var q=0;q<ps.length;q++){var t=txt(ps[q]);if(t.length>60)score+=t.length;}
      if(score>bestScore){bestScore=score;best=candidates[i];}
    }
    if(!best)return JSON.stringify({ok:false,reason:'container not found'});
    var paras=best.querySelectorAll('p');
    var applied=0,missing=[];
    for(var r=0;r<p.updates.length;r++){
      var u=p.updates[r];
      var el=paras[u.idx];
      if(!el){missing.push(u.idx);continue;}
      if(u.original&&txt(el)!==u.original.replace(/\s+/g,' ').trim()){missing.push(u.idx);continue;}
      el.textContent=u.text;
      applied++;
    }
    return JSON.stringify({ok:applied>0,applied:applied,missing:missing});
  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
})
"""

    /**
     * Scroll one step for lazy loading. Returns {scrolled, y, pageHeight, done}.
     */
    const val SCROLL_STEP = """
(function(step){
  var max=document.documentElement.scrollHeight||document.body.scrollHeight;
  var before=window.scrollY;
    scrolled:window.scrollY>before,
    y:window.scrollY,
    pageHeight:max,
    done:(window.scrollY+window.innerHeight)>=max-40
  });
})
"""

    /**
     * Downloads one image INSIDE the page context (same origin/cookies/referer).
     * Handles blob: and data:. Returns {ok, mime, base64} or {ok:false, reason}.
     * This is the only reliable way to get blob: URLs into the native layer.
     */
    const val FETCH_IMAGE = """
(function(url){
  return new Promise(function(resolve){
    function b64(buf){var u=new Uint8Array(buf),s='',chunk=0x8000;for(var i=0;i<u.length;i+=chunk){s+=String.fromCharCode.apply(null,u.subarray(i,i+chunk));}return btoa(s);}
    if(url.indexOf('data:')===0){
      var m=url.match(/^data:([^;]+);base64,(.*)$/);
      if(m)resolve(JSON.stringify({ok:true,mime:m[1],base64:m[2]}));
      else resolve(JSON.stringify({ok:false,reason:'unsupported data url'}));
      return;
    }
    if(url.indexOf('blob:')===0){
      fetch(url).then(function(r){return r.blob();}).then(function(b){
        var fr=new FileReader();
        fr.onload=function(){var m2=fr.result.match(/^data:([^;]+);base64,(.*)$/);
          if(m2)resolve(JSON.stringify({ok:true,mime:m2[1],base64:m2[2]}));
          else resolve(JSON.stringify({ok:false,reason:'blob read failed'}));};
        fr.onerror=function(){resolve(JSON.stringify({ok:false,reason:'blob read error'}));};
        fr.readAsDataURL(b);
      }).catch(function(e){resolve(JSON.stringify({ok:false,reason:'blob fetch: '+String(e)}));});
      return;
    }
    fetch(url,{credentials:'include',mode:'cors'}).then(function(r){
      if(!r.ok)throw new Error('http '+r.status);
      return r.arrayBuffer();
    }).then(function(buf){
      resolve(JSON.stringify({ok:true,mime:'image/*',base64:b64(buf)}));
    }).catch(function(e){resolve(JSON.stringify({ok:false,reason:'fetch: '+String(e)}));});
  });
})
"""

    /** Injects the Bengali overlay layer once; idempotent. */
    const val INJECT_OVERLAY_LAYER = """
(function(){
  if(document.getElementById('wt-overlay-layer'))return 'exists';
  var st=document.createElement('style');
  st.id='wt-overlay-style';
  st.textContent=''
    +'.wt-ov{position:absolute;box-sizing:border-box;margin:0;overflow:hidden;'
    +'  display:flex;align-items:center;justify-content:center;text-align:center;'
    +'  color:#111;font-family:sans-serif;line-height:1.25;pointer-events:none;'
    +'  text-shadow:0 0 2px rgba(255,255,255,.7);z-index:2147483000;}'
    +'.wt-ovimg{position:relative !important;box-sizing:border-box;}'
    +'#wt-overlay-layer{position:absolute;top:0;left:0;width:100%;height:0;pointer-events:none;z-index:2147483000;}';
  document.documentElement.appendChild(st);
  var layer=document.createElement('div');
  layer.id='wt-overlay-layer';
  document.body.appendChild(layer);
  return 'ok';
})();
"""

    /**
     * Places/refreshes overlays for one image. Idempotent per (imageId, regionId).
     * Payload: {imageId, src, opacity, fontScale, regions:[{id, l, t, w, h, text}]}
     * where l/t/w/h are FRACTIONS of the image element's box.
     */
    const val APPLY_OVERLAYS = """
(function(payload){
  try{
    var p=JSON.parse(payload);
    var img=null;
    var imgs=document.querySelectorAll('img');
    for(var i=0;i<imgs.length;i++){
      var u=imgs[i].currentSrc||imgs[i].src||'';
      if(u===p.src||(u&&p.src&&u.indexOf(p.src)===0)||(p.src&&p.src.indexOf(u)===0&&u.length>4)){img=imgs[i];break;}
    }
    if(!img)return JSON.stringify({ok:false,reason:'image element not found for '+p.imageId});
    var r=img.getBoundingClientRect();
    if(r.width<10||r.height<10)return JSON.stringify({ok:false,reason:'image too small'});
    img.classList.add('wt-ovimg');
    // clean previous overlays for this image only
    var olds=document.querySelectorAll('.wt-ov[data-img="'+p.imageId+'"]');
    for(var d=0;d<olds.length;d++)olds[d].remove();
    var layer=document.getElementById('wt-overlay-layer');
    var created=0;
    for(var k=0;k<p.regions.length;k++){
      var g=p.regions[k];
      var box=document.createElement('div');
      box.className='wt-ov';
      box.setAttribute('data-img',p.imageId);
      box.setAttribute('data-region',g.id);
      box.style.left=(g.l*100)+'%';
      box.style.top=(g.t*100)+'%';
      box.style.width=(g.w*100)+'%';
      box.style.height=(g.h*100)+'%';
      var fs=Math.max(8,Math.min(26,g.h*r.height*0.62))*p.fontScale;
      box.style.fontSize=fs.toFixed(1)+'px';
      box.style.background='rgba(255,255,255,'+p.opacity+')';
      box.textContent=g.text;
      // attach to the image's offsetParent so it moves with the image
      var host=img.parentElement||layer;
      var cs=window.getComputedStyle(host);
      if(cs.position==='static')host.style.position='relative';
      host.appendChild(box);
      created++;
    }
    return JSON.stringify({ok:true,created:created});
  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
})
"""

    const val CLEAR_OVERLAYS = """
(function(){
  var a=document.querySelectorAll('.wt-ov');
  for(var i=0;i<a.length;i++)a[i].remove();
  return JSON.stringify({ok:true,removed:a.length});
})();
"""

    /** Reading-order repair: groups vertical Japanese lines into columns. */
    const val IMAGE_RENDER_SIZE = """
(function(src){
  var imgs=document.querySelectorAll('img');
  for(var i=0;i<imgs.length;i++){
    var u=imgs[i].currentSrc||imgs[i].src||'';
    if(u===src){var r=imgs[i].getBoundingClientRect();return JSON.stringify({w:r.width,h:r.height,nw:imgs[i].naturalWidth,nh:imgs[i].naturalHeight});}
  }
  return JSON.stringify({ok:false});
})
"""
}
