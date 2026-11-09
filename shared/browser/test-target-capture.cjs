const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
const script=fs.readFileSync(__dirname+'/target-capture.js','utf8');
function page(site,id,href,initial){
  const document={getElementById:()=>null,querySelectorAll:()=>[],addEventListener:()=>{}};
  function XHR(){}XHR.prototype.open=function(){};
  const context={URL,console,document,location:new URL(href),XMLHttpRequest:XHR,MutationObserver:class{observe(){}},HTMLVideoElement:class{},getComputedStyle:()=>({}),__omniTarget:{site,id,quiet:true},__INITIAL_STATE__:initial};context.window=context;
  let requests=0;context.fetch=()=>{requests++;throw Error('unexpected request')};
  vm.createContext(context);vm.runInContext(script,context);return {context,read:()=>JSON.parse(JSON.stringify(context.__omniReadTarget())),requests:()=>requests};
}
const id='7699370307513319141';
let p=page('抖音',id,'https://www.douyin.com/video/'+id,{aweme_id:id,video:{width:3840,height:2160,play_addr:{url_list:['https://example.com/720.mp4'],width:1280,height:720},bit_rate:[{play_addr:{url_list:['https://example.com/1080.mp4'],width:1920,height:1080},fps:60}]}});
assert.equal(p.read().media[0].formats.length,2);assert.equal(p.read().media[0].referenceWidth,3840);assert.equal(p.requests(),0);
p.context.__INITIAL_STATE__={aweme_id:'999',video:{play_addr:{url_list:['https://example.com/ad.mp4'],width:1920,height:1080}}};assert.equal(p.read().media.length,0);
p=page('X (Twitter)','123','https://x.com/example/status/123',{id_str:'123',extended_entities:{media:[{type:'video',video_info:{variants:[{content_type:'video/mp4',url:'https://video.twimg.com/ext_tw_video/111/vid/1920x1080/v.mp4'}]}},{type:'video',video_info:{variants:[{content_type:'application/x-mpegURL',url:'https://video.twimg.com/ext_tw_video/222/master.m3u8'}]}}]}});
assert.deepEqual(p.read().media.map(m=>m.mediaId),['111','222']);assert.equal(p.read().media[1].formats[0].width,0);
p.context.location=new URL('https://x.com/example/status/999');assert.equal(p.read().status,'wrong_target');
p=page('小红书','abc123','https://www.xiaohongshu.com/explore/abc123',{noteDetailMap:{abc123:{note:{title:'Original',imageList:[{urlDefault:'https://example.com/image.jpg',width:4000,height:3000}]}}}});
assert.equal(p.read().images.length,1);assert.equal(p.read().media.length,0);
p=page('小黑盒','123456','https://www.xiaoheihe.cn/app/bbs/link/123456',{link_id:'123456',content:'<video src="https://example.com/target.mp4?a=1&amp;b=2"></video>'});
assert.equal(p.read().media[0].formats[0].url,'https://example.com/target.mp4?a=1&b=2');assert.equal(p.requests(),0);
console.log('6 shared target-capture fixtures passed; no unsolicited network requests');
