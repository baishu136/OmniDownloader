/* Android's page hierarchy and controls, shared by the packaged EXE and Web UI. */
(() => {
  'use strict';
  const VERSION = '1.7.19';
  const $ = id => document.getElementById(id);
  const platforms = [['哔哩哔哩','#fb7299'],['抖音','#60636c'],['快手','#ff4d16'],['小红书','#ff2442'],['YouTube','#f00'],['TikTok','#28bac5'],['X (Twitter)','#8c9099'],['Instagram','#ed2990'],['Facebook','#1683ff'],['Pinterest','#e60023'],['Pixiv','#0096fa'],['小黑盒','#f6b92c']];
  function navigation() {
    const nav = $('nav-home')?.parentElement;
    if (!nav) return;
    nav.classList.add('omni-nav');
    [['home','首页','house'],['images','图文','images'],['tasks','任务','arrow-down'],['settings','设置','gear']].forEach(([key,label,icon]) => {
      const button = $('nav-'+key);
      const badge = key === 'tasks' ? $('badge-running-count') : null;
      button.replaceChildren();
      const glyph = document.createElement('i'); glyph.className='fa-solid fa-'+icon; glyph.setAttribute('aria-hidden','true');
      const text = document.createElement('span'); text.textContent=label;
      button.append(glyph,text); if(badge) button.append(badge);
      button.setAttribute('aria-label',label);
    });
    const oldSwitch = window.switchTab;
    window.switchTab = function(tab) {
      oldSwitch(tab);
      for(const key of ['home','images','tasks','settings']) {
        if(key===tab) $('nav-'+key).setAttribute('aria-current','page'); else $('nav-'+key).removeAttribute('aria-current');
      }
      if(tab!=='settings' && window.omniSettingsPage) window.omniSettingsPage('root',false);
    };
    $('nav-home').setAttribute('aria-current','page');
    const title = document.querySelector('header h1');
    if(title) {
      title.parentElement.querySelector('p')?.remove();
      const version=document.createElement('span');version.className='omni-version';version.textContent='v'+VERSION;
      title.append(version);
    }
  }
  function home() {
    const input=$('input-url'); if(!input)return;
    const card=$('tab-home').firstElementChild;
    card.classList.add('omni-home-card');
    const body=input.closest('.text-center');
    if(!body)return;
    body.querySelector('h2')?.remove();body.querySelector(':scope > p')?.remove();
    const oldPlatforms=Array.from(body.children).find(e=>e.classList.contains('flex-wrap'));
    const chips=document.createElement('div');chips.className='omni-platforms';chips.setAttribute('aria-label','支持的平台');
    for(const [name,color] of platforms){const item=document.createElement('div');item.className='omni-platform';item.style.setProperty('--dot',color);const dot=document.createElement('span');dot.setAttribute('aria-hidden','true');item.append(dot,document.createTextNode(name));chips.append(item);}
    oldPlatforms?.remove();body.prepend(chips);
    const row=input.parentElement;
    const paste=row.querySelector('button[onclick="pasteClipboard()"]');
    const analyze=$('btn-analyze');
    const helpers=Array.from(row.querySelectorAll('button')).filter(b=>b!==paste&&b!==analyze);
    const field=document.createElement('div');field.className='omni-input';
    const icon=document.createElement('i');icon.className='fa-solid fa-link';icon.setAttribute('aria-hidden','true');
    const inlinePaste=document.createElement('button');inlinePaste.className='omni-inline-paste';inlinePaste.title='粘贴剪贴板';inlinePaste.setAttribute('aria-label','粘贴剪贴板');inlinePaste.innerHTML='<i class="fa-regular fa-clipboard" aria-hidden="true"></i>';inlinePaste.onclick=()=>window.pasteClipboard();
    field.append(icon,input,inlinePaste);input.placeholder='粘贴视频链接或分享文本…';input.setAttribute('aria-label','视频链接或分享文本');
    const actions=document.createElement('div');actions.className='omni-actions';paste.textContent='粘贴剪贴板';analyze.innerHTML='<span>立即解析</span>';actions.append(paste,analyze);
    row.replaceWith(field);field.after(actions);
    const assist=document.createElement('details');assist.className='omni-assist';const summary=document.createElement('summary');summary.textContent='浏览器辅助';assist.append(summary,...helpers);actions.after(assist);
    const relay=$('relay-sites-section');
    if(relay){const heading=relay.querySelector('h3');heading?.closest('.justify-between')?.remove();}
  }
  function settings() {
    const section=$('tab-settings'), root=section?.firstElementChild;
    if(!root)return;
    root.classList.add('omni-settings');
    const children=Array.from(root.children);
    const take=id=>children.find(c=>c.contains($(id)));
    const groups=[
      ['appearance','外观与语言','主题模式、界面语言','palette',['setting-theme']],
      ['downloads','下载与存储','保存目录、并发与数据清理','folder',['setting-download-dir','setting-concurrent','setting-auto-browser-download','setting-autostart']],
      ['proxy','网络代理','HTTP 与 SOCKS 代理连接','globe',['setting-proxy']],
      ['relay','中转站','添加、管理和删除解析站点','server',['settings-relay-sites-container']],
      ['accounts','账号','Cookie 与登录管理','circle-user',['youtube-cookie-file','session-platform','setting-bili-cookie']],
      ['engine','下载引擎','运行状态与组件更新','cloud-arrow-down',[]],
      ['about','关于','应用版本与支持平台','circle-info',[]]
    ];
    const menu=document.createElement('div');menu.className='omni-settings-menu';
    const heading=document.createElement('h2');heading.textContent='偏好设置';
    const pages=new Map();
    for(const [key,title,summary,icon,ids] of groups){
      const row=document.createElement('button');row.className='omni-settings-row';row.type='button';row.dataset.settingsCategory=key;
      row.innerHTML='<i class="fa-solid fa-'+icon+'" aria-hidden="true"></i><span><strong>'+title+'</strong><small>'+summary+'</small></span><i class="fa-solid fa-chevron-right" aria-hidden="true"></i>';
      row.onclick=()=>window.omniSettingsPage(key);menu.append(row);
      const page=document.createElement('div');page.className='omni-child hidden';page.dataset.settingsPage=key;
      const head=document.createElement('div');head.className='omni-child-head';
      const back=document.createElement('button');back.setAttribute('aria-label','返回偏好设置');back.innerHTML='<i class="fa-solid fa-arrow-left" aria-hidden="true"></i>';back.onclick=()=>history.back();
      const label=document.createElement('h2');label.textContent=title;head.append(back,label);
      const content=document.createElement('div');content.className='omni-child-content';
      for(const id of ids){const block=take(id);if(block&&!content.contains(block))content.append(block);}
      page.append(head,content);pages.set(key,page);
    }
    const engine=pages.get('engine').lastElementChild;
    const appearance=pages.get('appearance').lastElementChild;
    const languageBox=document.createElement('label');languageBox.className='omni-language';languageBox.append(document.createTextNode('界面语言'));
    const language=document.createElement('select');language.id='omni-language';language.setAttribute('aria-label','界面语言');
    for(const [value,label] of [['system','跟随系统'],['zh-CN','简体中文'],['zh-TW','繁體中文'],['en','English'],['ja','日本語']]){const option=document.createElement('option');option.value=value;option.textContent=label;language.append(option);}
    language.value=localStorage.getItem('omni.language')||'zh-CN';languageBox.append(language);appearance.append(languageBox);
    language.onchange=()=>{localStorage.setItem('omni.language',language.value);window.omniTranslate?.();};
    const existingEngine=pages.get('accounts').querySelector('[onclick="showEngineStatus()"]');
    if(existingEngine)engine.append(existingEngine);
    for(const title of pages.get('accounts').querySelectorAll('h3'))if(title.textContent.includes('平台会话'))title.textContent='Cookie 与登录管理';
    const engineText=document.createElement('p');engineText.textContent='解析、下载与格式转换组件在本机运行，组件随应用版本更新。';engineText.className='omni-engine-status';engine.append(engineText);
    const updates=document.createElement('a');updates.textContent='检查应用更新';updates.href='https://github.com/baishu136/OmniDownloader/releases';updates.target='_blank';updates.rel='noopener noreferrer';engine.append(updates);
    const about=children.find(c=>c.textContent.includes('v1.5.3 稳定版'));
    if(about){about.innerHTML=about.innerHTML.replace('v1.5.3 稳定版','v'+VERSION);pages.get('about').lastElementChild.append(about);}
    const save=children.find(c=>c.querySelector('[onclick="saveSettings()"]'));
    if(save)save.className='omni-settings-save';
    root.replaceChildren(heading,menu,...pages.values());if(save)root.append(save);
    window.omniSettingsPage=function(key,push=true){
      if(!pages.has(key))key='root';
      heading.classList.toggle('hidden',key!=='root');menu.classList.toggle('hidden',key!=='root');
      for(const [name,page] of pages)page.classList.toggle('hidden',key!==name);
      if(save)save.classList.toggle('hidden',key==='root'||key==='about'||key==='engine'||key==='relay');
      if(push)history.pushState({omniSettings:key},'',key==='root'?'#settings':'#settings/'+key);
      section.dataset.currentPage=key;
    };
    window.addEventListener('popstate',()=>window.omniSettingsPage(location.hash.startsWith('#settings/')?location.hash.slice(10):'root',false));
    window.omniSettingsPage('root',false);
  }
  function qualityEvidence(){
    const render=window.renderAnalyzeResult;
    if(!render)return;
    window.renderAnalyzeResult=function(data){
      render(data);
      const anchor=$('meta-access');if(!anchor)return;
      $('omni-quality-evidence')?.remove();
      const note=document.createElement('p');note.id='omni-quality-evidence';note.className='omni-engine-status';
      note.textContent=[data.qualityEvidence,data.qualityWarning].filter(Boolean).join(' · ');
      if(data.qualityWarning)note.style.color='var(--omni-error,#ba1a1a)';
      anchor.after(note);
    };
  }
  async function languages(){
    const dictionaries=await (await fetch('/static/strings.json')).json();
    const originals=new Map();for(const values of Object.values(dictionaries))for(const [source,value] of Object.entries(values)){originals.set(source,source);if(!originals.has(value))originals.set(value,source);}
    const apply=()=>{
      const selected=localStorage.getItem('omni.language')||'zh-CN';const locale=selected==='system'?navigator.language:selected;
      const key=locale.startsWith('zh')?(/TW|HK|Hant/.test(locale)?'zh-TW':'zh-CN'):locale.startsWith('ja')?'ja':'en';
      document.documentElement.lang=key;
      const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
      while(walker.nextNode()){const node=walker.currentNode;if(node.parentElement?.closest('script,style,textarea,pre,code'))continue;const trim=node.textContent.trim(),source=originals.get(trim);if(source){const next=dictionaries[key]?.[source]||source;if(next!==trim)node.textContent=node.textContent.replace(trim,next);}}
    };
    window.omniTranslate=apply;apply();
    let queued=false;new MutationObserver(()=>{if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;apply();});}).observe(document.body,{childList:true,subtree:true,characterData:true});
  }
  function start(){navigation();home();settings();qualityEvidence();languages().catch(()=>{});document.documentElement.dataset.omniVersion=VERSION;}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();
