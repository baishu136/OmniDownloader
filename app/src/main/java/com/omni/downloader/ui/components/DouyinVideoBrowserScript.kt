package com.omni.downloader.ui.components

internal object DouyinVideoBrowserScript {
    fun extract(id: String, requestDetail: Boolean = true): String {
        require(Regex("\\d{10,24}").matches(id))
        return """
        (() => {
          window.__omniVideoResult = null;
          const target = '$id';
          const items = [], seen = new WeakSet(); let visited = 0;
          const addresses = value => Object.entries(value || {}).filter(([key])=>/^(play_addr|playAddr)/.test(key));
          const variants = video => [video, ...(video?.bit_rate || video?.bitRateList || [])];
          const urls = address => address?.url_list || address?.urlList || [];
          const http = value => {try {const u=new URL(value);return /^https?:$/.test(u.protocol) && !u.username && !u.password;}catch(_){return false;}};
          const find = (value, depth=0) => {
            if(!value || typeof value !== 'object' || depth>25 || ++visited>8192 || seen.has(value)) return;
            seen.add(value);
            if(String(value.aweme_id || value.awemeId || '')===target && value.video &&
              variants(value.video).some(v=>addresses(v).some(([,a])=>urls(a).some(http)))) {items.push(value);return;}
            for(const child of Object.values(value)) find(child,depth+1);
          };
          const playback = () => {
            const current = location.pathname.match(/\/(?:video|note)\/(\d+)/)?.[1] || new URL(location.href).searchParams.get('modal_id');
            if(current!==target) return;
            const players=Array.from(document.querySelectorAll('video')).filter(v=>{
              const box=v.getBoundingClientRect();return box.width>0 && box.height>0 && v.readyState>=1 && v.videoWidth>0 && v.videoHeight>0 && http(v.currentSrc || v.src);
            });
            players.sort((a,b)=>b.getBoundingClientRect().width*b.getBoundingClientRect().height-a.getBoundingClientRect().width*a.getBoundingClientRect().height);
            // A verification/loading animation can be a full-size <video> on
            // the target page. Page location alone does not bind its media to
            // this work. Require the player's work ID or a known target URL.
            const key=value=>{const u=new URL(value);return u.origin+u.pathname+'|'+(u.searchParams.get('video_id') || '');};
            const v=players.find(player=>{
              const src=player.currentSrc || player.src, bound=new URL(src).searchParams.get('__vid');
              const linked=items.some(entry=>variants(entry.video).some(variant=>addresses(variant).some(([,address])=>
                urls(address).filter(http).some(url=>key(url)===key(src)))));
              return bound ? bound===target : linked;
            });
            if(!v)return;
            const src=v.currentSrc || v.src;
            items.push({aweme_id:target,desc:document.querySelector('h1')?.innerText || document.title,
              video:{duration:Number.isFinite(v.duration)?Math.round(v.duration*1000):0,
                play_addr:{url_list:[src],width:v.videoWidth,height:v.videoHeight}}});
          };
          const finish = () => {
            playback();
            const item=items[0];
            const rates=items.flatMap(entry=>variants(entry.video)).slice(0,128).map(variant=>{
              const rate={bit_rate:variant.bit_rate || variant.bitRate || 0,gear_name:variant.gear_name || variant.gearName || '',fps:variant.fps || 0,
                codec_type:variant.codec_type || variant.codecType || '',dynamic_range:variant.dynamic_range || variant.dynamicRange || ''};
              for(const [key,address] of addresses(variant)){
                const normal=key.replace(/^playAddr/,'play_addr').replace('H264','_h264').replace('H265','_h265');
                rate[normal]={...address,url_list:urls(address).filter(http).slice(0,8)};
              }
              return rate;
            });
            window.__omniVideoResult = JSON.stringify(item ? {ok:true,item:{aweme_id:target,desc:item.desc,
              author:{nickname:item.author?.nickname || ''},video:{...item.video,bit_rate:rates},duration:item.duration}} : {ok:false});
          };
          let rendered=null;
          try {rendered=JSON.parse(decodeURIComponent(document.getElementById('RENDER_DATA')?.textContent || '{}'));}catch(_){}
          find(window.__omniCapturedVideos); find(window.__omniCapturedVideo); find(window._ROUTER_DATA); find(rendered);
          playback();
          if(items.length)finish();
          else if (${if (requestDetail) "true" else "false"}) fetch('/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id='+target,{credentials:'same-origin'})
            .then(r=>r.ok?r.json():null).then(data=>{find(data);finish();}).catch(()=>finish());
          else finish();
          return null;
        })()
        """.trimIndent()
    }
}
