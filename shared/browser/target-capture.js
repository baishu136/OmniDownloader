/* First-party target data only. No CAPTCHA interaction and no unsigned detail polling. */
(() => {
  'use strict';
  if (window.__omniReadTarget) return;
  const target=window.__omniTarget;
  if(!target || !target.id || !target.site)return;
  const roots=[];
  const hosts={'抖音':['douyin.com','iesdouyin.com'],'快手':['kuaishou.com','kwai.com','gifshow.com'],'小红书':['xiaohongshu.com','rednote.com'],
    'X (Twitter)':['x.com','twitter.com','syndication.twimg.com'],'TikTok':['tiktok.com'],'Instagram':['instagram.com'],
    'Facebook':['facebook.com'],'Pinterest':['pinterest.com'],'Pixiv':['pixiv.net'],'哔哩哔哩':['bilibili.com'],'小黑盒':['xiaoheihe.cn','heybox.cn']};
  const owns=(host,domain)=>host===domain||host.endsWith('.'+domain);
  const firstParty=url=>{try{return (hosts[target.site]||[]).some(h=>owns(new URL(url,location.href).hostname,h));}catch{return false;}};
  const safe=url=>{try{const u=new URL(url);return ['https:','http:'].includes(u.protocol)&&!u.username&&!u.password;}catch{return false;}};
  const list=x=>Array.isArray(x)?x:[];
  const number=x=>Number.isFinite(Number(x))?Math.max(0,Number(x)):0;
  function capture(data){try{if(!data||typeof data!=='object'||JSON.stringify(data).length>4194304)return;roots.push(data);if(roots.length>12)roots.shift();}catch{}}
  const oldFetch=window.fetch;
  if(oldFetch)window.fetch=function(...args){return oldFetch.apply(this,args).then(response=>{
    if(firstParty(response.url)&&/json/.test(response.headers.get('content-type')||'')&&number(response.headers.get('content-length'))<=4194304)
      response.clone().text().then(text=>{if(text.length<=4194304)capture(JSON.parse(text));}).catch(()=>{});
    return response;
  });};
  const oldOpen=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(...args){this.addEventListener('load',()=>{try{
    if(!firstParty(this.responseURL))return;
    if(this.responseType==='json')capture(this.response);
    else if((this.responseType===''||this.responseType==='text')&&this.responseText.length<=4194304)capture(JSON.parse(this.responseText));
  }catch{}});return oldOpen.apply(this,args);};
  function state(){
    const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0;};
    const nodes=Array.from(document.querySelectorAll('[class*="captcha"],[id*="captcha"],[role="dialog"],[class*="login-panel"]')).slice(0,32).filter(visible);
    const text=nodes.map(n=>n.innerText||'').join(' ');
    if(/请完成.{0,16}验证|拖动.{0,16}滑块|按住左边按钮|验证后继续|Verify you are human/i.test(text)||Array.from(document.querySelectorAll('iframe[src*="captcha"],iframe[src*="verifycenter"]')).some(visible))return 'challenge';
    if(/登录后.{0,12}(观看|高清)|请先登录|扫码登录/.test(text)||/\/(?:login|passport)\b/.test(location.pathname))return 'auth';
    return 'normal';
  }
  function pageID(){
    const u=new URL(location.href),p=u.pathname;
    const patterns={'抖音':/\/(?:video|note|share\/video|share\/note)\/(\d+)/,'快手':/\/(?:short-video|photo)\/([a-zA-Z0-9]+)/,
      '小红书':/\/(?:explore|discovery\/item)\/([a-zA-Z0-9]+)/,'X (Twitter)':/\/status\/(\d+)/,'TikTok':/\/video\/(\d+)/,
      'Instagram':/\/(?:p|reel)\/([a-zA-Z0-9_-]+)/,'Pinterest':/\/pin\/(\d+)/,'Pixiv':/\/artworks\/(\d+)/,'哔哩哔哩':/\/(?:opus|video)\/(BV[a-zA-Z0-9]+|\d+)/};
    if(target.site==='Facebook')return u.searchParams.get('v')||p.match(/\/(?:videos|reel)\/(\d+)/)?.[1]||'';
    if(target.site==='小黑盒')return u.searchParams.get('link_id')||'';
    return p.match(patterns[target.site]||/$a/)?.[1]||'';
  }
  function read(){
    if(!firstParty(location.href))return {status:'wrong_target'};
    if(/\/(?:login|passport)\b/.test(location.pathname))return {status:'auth'};
    if(pageID()!==String(target.id))return {status:'wrong_target'};
    const output={contentId:String(target.id),title:'',author:'',thumbnailUrl:'',media:[],images:[]};
    const media=new Map(),pictures=new Map();
    function addImage(url,w=0,h=0,original=true){if(safe(url)&&pictures.size<500)pictures.set(url,{url,width:number(w),height:number(h),original});}
    function addFormat(item,url,w=0,h=0,extra={}){if(!safe(url))return;item.formats.push({url,width:number(w),height:number(h),...extra});}
    function video(id,durationMs=0){const key=String(id||target.id);if(!media.has(key)&&media.size<200)media.set(key,{mediaId:key,formats:[],durationMs:number(durationMs)});return media.get(key);}
    function douyin(item){
      if(!item.video && !item.images && !item.image_post_info)return;
      output.title ||= item.desc||'';output.author ||= item.author?.nickname||'';
      const v=item.video||{},m=video(target.id,v.duration||item.duration);if(!m)return;
      m.referenceWidth=number(v.width);m.referenceHeight=number(v.height);output.thumbnailUrl ||= v.cover?.url_list?.[0]||'';
      const append=(parent,rate={})=>{for(const [key,address]of Object.entries(parent))if(/^(play_addr|playAddr)/.test(key)&&address&&typeof address==='object')
        for(const url of list(address.url_list||address.urlList))addFormat(m,url,address.width,address.height,{fps:number(rate.fps),vcodec:address.codec_type||address.codecType||rate.codec_type||rate.codecType||'',dynamicRange:address.dynamic_range||rate.dynamic_range||'',bitrateKbps:number(rate.bit_rate||rate.bitRate)/1000});};
      for(const rate of list(v.bit_rate||v.bitRateList))append(rate,rate);append(v);
      for(const image of list(item.images||item.image_post_info?.images)){
        const variants=[image.display_image,image.origin_image,image].filter(Boolean);const best=variants.find(i=>list(i.url_list||i.urlList).some(safe));
        if(best)addImage(list(best.url_list||best.urlList).find(safe),image.width,image.height,!!image.origin_image);
      }
    }
    function tweet(item){
      const node=item.legacy||item;if(!node.extended_entities?.media&&!node.mediaDetails&&!node.media_details)return;
      output.title ||= node.full_text||node.text||'';output.author ||= item.core?.user_results?.result?.legacy?.screen_name||item.user?.screen_name||'';
      for(const entry of list(node.extended_entities?.media||node.mediaDetails||node.media_details)){
        if(entry.type==='photo'){let url=entry.media_url_https||entry.media_url;if(url&&owns(new URL(url).hostname,'twimg.com')){const u=new URL(url);if(!u.searchParams.has('name'))u.searchParams.set('name','orig');url=u.href;}addImage(url,entry.original_info?.width,entry.original_info?.height);continue;}
        const info=entry.video_info||entry.videoInfo||{},m=video(entry.id_str||entry.id||entry.media_key,info.duration_millis);if(!m)continue;
        m.isGif=entry.type==='animated_gif';m.thumbnailUrl=entry.media_url_https||'';
        for(const variant of list(info.variants))if((variant.content_type||variant.type)==='video/mp4'){
          const size=(variant.url||'').match(/\/(\d+)x(\d+)\//);addFormat(m,variant.url,size?.[1],size?.[2],{bitrateKbps:number(variant.bitrate)/1000});}
      }
    }
    function xhs(note){
      output.title ||= note.title||note.desc||'';output.author ||= note.user?.nickname||'';
      for(const image of list(note.imageList||note.image_list)){const info=list(image.infoList||image.info_list);const original=info.find(i=>/ORIGIN|MASTER/.test(i.imageScene||''));addImage(original?.url||image.urlDefault||image.url||info[0]?.url,image.width,image.height,!!original);}
      const v=note.video;if(!v)return;const m=video(target.id,number(v.media?.duration||v.duration)*1000);if(!m)return;
      for(const variants of Object.values(v.media?.stream||{}))for(const variant of list(variants))for(const url of [variant.masterUrl,...list(variant.backupUrls)])addFormat(m,url,variant.width,variant.height,{fps:number(variant.fps),vcodec:variant.codec||'',dynamicRange:variant.hdrType||'',bitrateKbps:number(variant.avgBitrate)/1000});
      const original=v.consumer?.originVideoKey;if(original)addFormat(m,original.startsWith('http')?original:'https://sns-video-bd.xhscdn.com/'+original);
    }
    function generic(node){
      const site=target.site;
      if(site==='TikTok'){
        output.title ||= node.desc||'';output.author ||= node.author?.nickname||'';const v=node.video||{},m=video(target.id,number(v.duration)*1000);if(!m)return;
        for(const value of [v.playAddr,v.downloadAddr]){if(typeof value==='string')addFormat(m,value,v.width,v.height);else for(const url of list(value?.urlList||value?.url_list))addFormat(m,url,value.width,value.height);}
        for(const rate of list(v.bitrateInfo))for(const url of list(rate.PlayAddr?.UrlList))addFormat(m,url,rate.PlayAddr?.Width,rate.PlayAddr?.Height,{bitrateKbps:number(rate.Bitrate)/1000});
      }else if(site==='Instagram'){
        output.title ||= node.edge_media_to_caption?.edges?.[0]?.node?.text||node.caption?.text||'';output.author ||= node.owner?.username||node.user?.username||'';
        const entries=node.edge_sidecar_to_children?.edges?.map(e=>e.node)||node.carousel_media||[node];
        for(const entry of entries){const variants=list(entry.video_versions);if(entry.video_url)variants.push({url:entry.video_url,width:entry.dimensions?.width,height:entry.dimensions?.height});
          if(variants.length){const m=video(entry.id||entry.pk||target.id,number(entry.video_duration)*1000);if(m)for(const v of variants)addFormat(m,v.url,v.width,v.height);}
          else{const image=entry.display_url||entry.image_versions2?.candidates?.[0]?.url;addImage(image,entry.dimensions?.width,entry.dimensions?.height);}}
      }else if(site==='Pinterest'){
        output.title ||= node.title||node.description||'';const m=video(target.id);if(!m)return;
        for(const v of Object.values(node.videos?.video_list||{}))addFormat(m,v.url,v.width,v.height);
        const image=node.images?.orig||node.images?.original;addImage(image?.url,image?.width,image?.height);
      }else if(site==='Facebook'){
        const m=video(target.id,number(node.playable_duration_in_ms));if(!m)return;
        for(const url of [node.playable_url_quality_hd,node.playable_url,node.browser_native_hd_url,node.browser_native_sd_url])addFormat(m,url);
        output.title ||= node.name||node.title||'';
      }else if(site==='快手'){
        const p=node.photo||node;output.title ||= p.caption||p.title||'';const m=video(target.id,p.duration||p.durationMs);if(!m)return;
        for(const v of [p.mainMvUrls,p.videoResource?.h264?.adaptationSet?.[0]?.representation,p.videoResource?.hevc?.adaptationSet?.[0]?.representation].flatMap(list))addFormat(m,v.url,v.width,v.height,{bitrateKbps:number(v.avgBitrate)/1000});
        if(typeof p.photoUrl==='string')addFormat(m,p.photoUrl,p.width,p.height);
        for(const image of list(p.images||p.ext_params?.atlas?.list))addImage(typeof image==='string'?image:image.url,image.width,image.height);
      }
    }
    const visited=new Set();let steps=0;
    function walk(node,depth=0){
      if(!node||typeof node!=='object'||visited.has(node)||depth>25||steps++>60000)return;visited.add(node);
      const id=String(target.id),site=target.site;
      if(site==='抖音'&&String(node.aweme_id||node.awemeId||'')===id)douyin(node);
      if(site==='X (Twitter)'&&String(node.rest_id||node.id_str||node.id||'')===id)tweet(node);
      if(site==='小红书'){
        if(String(node.noteId||node.note_id||'')===id)xhs(node);
        if(node.noteDetailMap?.[id])xhs(node.noteDetailMap[id].note||node.noteDetailMap[id]);
      }
      if((site==='TikTok'&&String(node.id||node.aweme_id||'')===id)||(site==='Instagram'&&String(node.shortcode||node.code||'')===id)||
         (site==='Pinterest'&&String(node.id||'')===id)||(site==='Facebook'&&String(node.id||node.videoId||'')===id)||
         (site==='快手'&&String(node.photoId||node.photo_id||node.id||'')===id))generic(node);
      for(const child of Object.values(node))walk(child,depth+1);
    }
    const values=[...roots,window._ROUTER_DATA,window.__INITIAL_STATE__,window.SIGI_STATE,window.__NEXT_DATA__];
    for(const id of ['RENDER_DATA','SIGI_STATE','__UNIVERSAL_DATA_FOR_REHYDRATION__','__NEXT_DATA__'])try{const text=document.getElementById(id)?.textContent;if(text&&text.length<=4194304)values.push(JSON.parse(id==='RENDER_DATA'?decodeURIComponent(text):text));}catch{}
    values.forEach(value=>walk(value));
    // A playing placeholder/ad is not evidence. Only an already linked target URL may supplement details.
    if(target.site==='抖音')for(const element of document.querySelectorAll('video')){
      const source=element.currentSrc||element.src;if(!safe(source))continue;
      const linked=Array.from(media.values()).some(m=>m.formats.some(f=>f.url===source))||new URL(source).searchParams.get('__vid')===String(target.id);
      if(linked&&element.videoWidth>0){const m=video(target.id,number(element.duration)*1000);addFormat(m,source,element.videoWidth,element.videoHeight);}
    }
    output.media=Array.from(media.values()).filter(m=>m.formats.length).map(m=>({...m,formats:Array.from(new Map(m.formats.map(f=>[f.url,f])).values())}));
    output.images=Array.from(pictures.values());output.status=state();
    return output;
  }
  window.__omniReadTarget=read;
  function quiet(video){if(!target.quiet)return;video.muted=true;video.autoplay=false;video.preload='metadata';if(!video.paused)video.pause();}
  document.addEventListener('play',e=>{if(e.target instanceof HTMLVideoElement)quiet(e.target);},true);
  new MutationObserver(()=>document.querySelectorAll('video').forEach(quiet)).observe(document,{childList:true,subtree:true});
})();
