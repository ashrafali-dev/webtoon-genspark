package com.webtoon.translator.web

import android.webkit.WebView
import com.webtoon.translator.core.BotAdapter
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Drives a chatbot website inside a WebView using the selected adapter:
 * find editor → insert (adapter-specific) → verify → submit → wait for the
 * NEW assistant response with bounded polling (never fixed sleeps).
 */
class ChatbotHelper(private val eval: suspend (WebView, String, String?) -> String) {

    data class InsertResult(val ok: Boolean, val reason: String)

    /**
     * Inserts the prompt into the chatbot editor and VERIFIES the text is
     * actually present in the editor before reporting success.
     */
    suspend fun insert(wv: WebView, adapter: BotAdapter, text: String): InsertResult {
        val payload = JSONObject()
            .put("text", text)
            .put("editors", org.json.JSONArray(adapter.editorSelectors))
            .put("strategy", adapter.insertStrategy)
            .toString()
        val res = eval(wv, INSERT_JS, payload)
        return try {
            val o = JSONObject(res)
            if (o.optBoolean("ok")) InsertResult(true, "len=${o.optInt("len")}")
            else InsertResult(false, o.optString("reason", "insert failed"))
        } catch (_: Exception) { InsertResult(false, "bad insert response") }
    }

    /**
     * Clicks send (button first, Enter fallback) and polls for a NEW assistant
     * response that is stable across two polls. Returns the response text or
     * null on timeout / not-logged-in / layout-unsupported.
     */
    suspend fun sendAndWait(wv: WebView, adapter: BotAdapter, timeoutMs: Long): String? {
        val payload = JSONObject()
            .put("sends", org.json.JSONArray(adapter.sendSelectors))
            .put("stops", org.json.JSONArray(adapter.stopSelectors))
            .put("assistants", org.json.JSONArray(adapter.assistantSelectors))
            .toString()
        val before = eval(wv, COUNT_JS, payload)
        val sent = eval(wv, SEND_JS, payload)
        if (!sent.contains("clicked") && !sent.contains("enter")) return null

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLen = -1L
        var stable = 0
        var sawNew = false
        val beforeCount = before.substringAfter("count:").substringBefore(":").toIntOrNull() ?: 0
        val beforeLen = before.substringAfterLast(":").toLongOrNull() ?: 0L

        while (System.currentTimeMillis() < deadline) {
            delay(900)
            val snap = eval(wv, POLL_JS, payload)
            // format: count:<n>:len:<l>:busy:<0|1>
            val count = snap.substringAfter("count:").substringBefore(":").toIntOrNull() ?: continue
            val len = snap.substringAfter("len:").substringBefore(":").toLongOrNull() ?: 0L
            val busy = snap.substringAfter("busy:").trim().toIntOrNull() ?: 0
            if (count > beforeCount || (count == beforeCount && len > beforeLen + 20)) sawNew = true
            if (!sawNew) continue
            if (busy == 1) { stable = 0; lastLen = -1; continue }
            if (len == lastLen && len > 0) {
                stable++
                if (stable >= 2) {
                    // read the actual text of the last assistant message
                    return eval(wv, READ_JS, payload).takeIf { it.length > 2 }?.let { r ->
                        try { org.json.JSONArray(r).optString(0, "") } catch (_: Exception) { "" }
                    }?.takeIf { it.isNotBlank() }
                }
            } else { stable = 0; lastLen = len }
        }
        return null
    }

    // ---------------------------------------------------------------- JS

    private val INSERT_JS = """
(function(p){
  try{
    function visible(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
    function q(sel){var a=document.querySelectorAll(sel);for(var i=0;i<a.length;i++)if(visible(a[i]))return a[i];return null;}
    var box=null;
    for(var i=0;i<p.editors.length&&!box;i++){box=q(p.editors[i]);}
    if(!box)return JSON.stringify({ok:false,reason:'editor not found (login page or unsupported layout?)'});
    var text=p.text;
    function nativeSet(e,v){var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto,'value').set.call(e,v);
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
    if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){nativeSet(box,text);}
    else{
      box.focus();
      var sel=window.getSelection(),range=document.createRange();
      range.selectNodeContents(box);sel.removeAllRanges();sel.addRange(range);
      var ok=document.execCommand('insertText',false,text);
      if(!ok||!((box.innerText||box.textContent||'').trim())){
        box.textContent=text; // last-resort fallback for exotic editors
      }
      try{box.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}
      try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){}
      box.dispatchEvent(new Event('change',{bubbles:true}));
    }
    var len=((box.value!==undefined&&box.value!==null)?box.value:(box.innerText||box.textContent||'')).trim().length;
    if(len<10)return JSON.stringify({ok:false,reason:'editor did not accept the text'});
    return JSON.stringify({ok:true,len:len});
  }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
})
"""

    private val SEND_JS = """
(function(p){
  function visible(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  function enabled(b){return b&&!b.disabled&&b.getAttribute('aria-disabled')!=='true';}
  for(var i=0;i<p.sends.length;i++){
    try{
      var a=document.querySelectorAll(p.sends[i]);
      for(var j=0;j<a.length;j++){if(visible(a[j])&&enabled(a[j])){a[j].click();return 'clicked:'+p.sends[i];}}
    }catch(e){}
  }
  try{
    var box=document.querySelector('[contenteditable=\"true\"],textarea');
    if(box){box.focus();
      box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      box.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
      return 'enter';}
  }catch(e){}
  return 'nosend';
})
"""

    private val COUNT_JS = """
(function(p){
  function visible(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  var best=-1,bestLen=0;
  for(var i=0;i<p.assistants.length;i++){
    try{var a=document.querySelectorAll(p.assistants[i]);
      var nodes=[];for(var j=0;j<a.length;j++)if(visible(a[j]))nodes.push(a[j]);
      if(nodes.length>best){best=nodes.length;
        var last=nodes[nodes.length-1];bestLen=((last.innerText||'').trim()).length;}
    }catch(e){}
  }
  return 'count:'+best+':len:'+bestLen;
})
"""

    private val POLL_JS = """
(function(p){
  function visible(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  var best=-1,bestLen=0,busy=0;
  for(var i=0;i<p.assistants.length&&!busy;i++){
    try{
      var a=document.querySelectorAll(p.assistants[i]);
      var nodes=[];for(var j=0;j<a.length;j++)if(visible(a[j]))nodes.push(a[j]);
      if(nodes.length>best){best=nodes.length;
        var last=nodes[nodes.length-1];bestLen=((last.innerText||'').trim()).length;}
    }catch(e){}
  }
  for(var s=0;s<p.stops.length;s++){
    try{var st=document.querySelectorAll(p.stops[s]);
      for(var t=0;t<st.length;t++)if(visible(st[t])){busy=1;break;}
    }catch(e){}
    if(busy)break;
  }
  return 'count:'+best+':len:'+bestLen+':busy:'+busy;
})
"""

    private val READ_JS = """
(function(p){
  function visible(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  for(var i=0;i<p.assistants.length;i++){
    try{
      var a=document.querySelectorAll(p.assistants[i]);
      var nodes=[];for(var j=0;j<a.length;j++)if(visible(a[j]))nodes.push(a[j]);
      if(nodes.length)return JSON.stringify([(nodes[nodes.length-1].innerText||'').trim()]);
    }catch(e){}
  }
  return JSON.stringify(['']);
})
"""
}
