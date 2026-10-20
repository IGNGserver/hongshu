import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

function runtime() {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, atob });
  vm.runInContext(readFileSync(new URL("../app.js", import.meta.url), "utf8").replace(/^export /gm, ""), ctx);
  vm.runInContext(`
    document={querySelector:()=>null,visibilityState:'visible'};
    setBusy=()=>{};renderDetail=()=>0;renderConversations=()=>{};
    renderGroupBar=()=>{};renderFilterMenu=()=>{};
    state.me={device:{id:'browser-A',admin:true,notify:true},cursor:200,epoch:1,analysis_revision:1,ai_enabled:false,unread_count:1,vapid_public_key:'BA'};
    state.cursor=200;state.epoch=1;state.epochSeen=true;state.loggedOut=false;
    state.conversationsQuery=inboxQueryKey();state.messagesQuery=historyQueryKey();
    calls=[];
    metadata=p=>p==='/devices'?{devices:[]}:p==='/sims'?{sims:[]}:p==='/contacts'?{contacts:{}}:{enabled:false};
  `, ctx);
  return ctx;
}
const run = (ctx, code) => vm.runInContext(code, ctx);

test("metadata failure cannot block new head and classification revalidation, and retries independently", async () => {
  const ctx = runtime();
  run(ctx, `
    failOnce=true;refreshes=[];
    api=async p=>{
      if(p==='/me')return {...state.me,cursor:201,analysis_revision:2};
      if(p==='/devices'&&failOnce){failOnce=false;throw Error('503');}
      return metadata(p);
    };
    refreshInbox=async options=>{refreshes.push(options);return true};
  `);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "refreshes.length"), 1, "content did not wait for failed metadata");
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "refreshes.length"), 1);
  assert.equal(run(ctx, "refreshes[0].revalidateHistory && refreshes[0].revalidateConversations"), true);
  assert.equal(run(ctx, "state.inboxDirty || state.historyDirty || state.conversationsDirty"), false);
});

test("cancelled refresh does not acknowledge pending invalidation", async () => {
  const ctx = runtime();
  run(ctx, `api=async p=>p==='/me'?{...state.me,cursor:201}:metadata(p);refreshInbox=async()=>false;`);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "state.inboxDirty"), true);
});

test("read-only count changes revalidate conversations, and arrivals cannot hide behind equal counts", async () => {
  const ctx = runtime();
  run(ctx, `
    nextHead={...state.me,unread_count:0};refreshes=[];
    api=async p=>p==='/me'?nextHead:metadata(p);
    refreshInbox=async options=>{refreshes.push(options);return true};
  `);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "refreshes[0].revalidateConversations"), true);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "refreshes.length"), 1, "idle polling must not reload history");
  run(ctx, "nextHead={...state.me,cursor:201};");
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "refreshes.length"), 2, "new message plus a read can leave the count unchanged");
  assert.equal(run(ctx, "refreshes[1].revalidateConversations"), true, "the read may have affected a tail row");
});

test("realtime refresh overtaking a SIM switch cannot retain the previous query's pages", async () => {
  const ctx = runtime();
  run(ctx, `
    state.sim='A';state.sender='s';
    state.conversations=[{sender:'old-A',timestamp:1,receiver:'A'}];
    state.messages=[{id:1,timestamp:1,sender:'s',receiver:'A'}];
    state.conversationsQuery=inboxQueryKey();state.messagesQuery=historyQueryKey();
    state.sim='B';
    reloadMetadata=()=>new Promise(r=>releaseMetadata=r);
    api=async p=>p.startsWith('/conversations')?{conversations:Array.from({length:100},(_,i)=>({sender:'B-'+i,timestamp:1000-i,receiver:'B'}))}:
      p.startsWith('/analysis/counts')?{counts:{}}:{messages:[{id:2,timestamp:2,sender:'s',receiver:'B'}]};
    filterRefresh=refreshInbox({metadata:true});
  `);
  await run(ctx, "refreshInbox({metadata:false,preserveHistory:true})");
  run(ctx, "releaseMetadata()");
  assert.equal(await run(ctx, "filterRefresh"), false);
  assert.equal(run(ctx, "state.conversations.every(m=>m.receiver==='B') && state.messages.every(m=>m.receiver==='B')"), true);
  assert.equal(run(ctx, "state.messages.length"), 1);
});

for (const field of ["sim", "device", "q", "group", "sender"]) {
  test(`late history response cannot cross a ${field} query change`, async () => {
    const ctx = runtime();
    run(ctx, `state.sender='s';api=()=>new Promise(r=>releaseHistory=r);pending=loadHistory(false);state.${field}='changed';releaseHistory({messages:[{id:10,timestamp:10,sender:'s'}]});`);
    await run(ctx, "pending");
    assert.equal(run(ctx, "state.messages.length"), 0);
  });
}

test("classification refresh revalidates the loaded conversation tail and its pagination end", async () => {
  const ctx = runtime();
  run(ctx, `
    state.conversations=Array.from({length:200},(_,i)=>({sender:'s'+i,timestamp:1000-i}));
    state.conversationsHasMore=true;
    head=state.conversations.slice(0,100);tail=state.conversations.slice(100).filter(m=>m.sender!=='s150');
    api=async p=>{calls.push(p);return p.startsWith('/analysis/counts')?{counts:{}}:
      {conversations:p.includes('offset=100')?tail:head}};
  `);
  await run(ctx, "refreshInbox({metadata:false,preserveHistory:true,revalidateConversations:true})");
  assert.equal(run(ctx, "state.conversations.some(m=>m.sender==='s150')"), false);
  assert.equal(run(ctx, "state.conversations.length"), 199);
  assert.equal(run(ctx, "state.conversationsHasMore"), false);
  assert.equal(run(ctx, "calls.filter(p=>p.startsWith('/conversations')).length"), 2);
});

test("revalidation cannot truncate a third page appended while its second page is in flight", async () => {
  const ctx = runtime();
  run(ctx, `
    allRows=Array.from({length:300},(_,i)=>({sender:'s'+i,timestamp:1000-i}));
    state.conversations=allRows.slice(0,200);state.conversationsHasMore=true;
    secondPageStarted=new Promise(r=>secondPageReady=r);
    api=async p=>{
      calls.push(p);
      if(p.startsWith('/analysis/counts'))return {counts:{}};
      const offset=Number(new URLSearchParams(p.split('?')[1]).get('offset'));
      if(offset===100){secondPageReady();return new Promise(r=>releaseSecondPage=()=>r({conversations:allRows.slice(100,200)}));}
      return {conversations:allRows.slice(offset,offset+100)};
    };
    refreshing=refreshInbox({metadata:false,preserveHistory:true,revalidateConversations:true});
  `);
  await run(ctx, "secondPageStarted");
  await run(ctx, "loadMoreConversations()");
  assert.equal(run(ctx, "state.conversations.length"), 300);
  run(ctx, "releaseSecondPage()");
  await run(ctx, "refreshing");
  assert.equal(run(ctx, "state.conversations.length"), 300, "revalidation must cover the newly loaded range");
  assert.equal(run(ctx, "new Set(state.conversations.map(conversationKey)).size"), 300);
  assert.equal(run(ctx, "state.conversations.at(-1).sender"), "s299");
});

test("a third page arriving after revalidation still extends the refreshed list", async () => {
  const ctx = runtime();
  run(ctx, `
    allRows=Array.from({length:300},(_,i)=>({sender:'s'+i,timestamp:1000-i}));
    state.conversations=allRows.slice(0,200);state.conversationsHasMore=true;
    secondPageStarted=new Promise(r=>secondPageReady=r);
    thirdPageStarted=new Promise(r=>thirdPageReady=r);
    api=async p=>{
      if(p.startsWith('/analysis/counts'))return {counts:{}};
      const offset=Number(new URLSearchParams(p.split('?')[1]).get('offset'));
      if(offset===100){secondPageReady();return new Promise(r=>releaseSecondPage=()=>r({conversations:allRows.slice(100,200)}));}
      if(offset===200){thirdPageReady();return new Promise(r=>releaseThirdPage=()=>r({conversations:allRows.slice(200)}));}
      return {conversations:allRows.slice(0,100)};
    };
    refreshing=refreshInbox({metadata:false,preserveHistory:true,revalidateConversations:true});
  `);
  await run(ctx, "secondPageStarted");
  run(ctx, "loading=loadMoreConversations()");
  await run(ctx, "thirdPageStarted");
  run(ctx, "releaseSecondPage()");
  await run(ctx, "refreshing");
  run(ctx, "releaseThirdPage()");
  await run(ctx, "loading");
  assert.equal(run(ctx, "state.conversations.length"), 300);
  assert.equal(run(ctx, "new Set(state.conversations.map(conversationKey)).size"), 300);
});

function backgroundHistory(ctx) {
  run(ctx, `
    state.sender='s';state.messagesQuery=historyQueryKey();
    document.visibilityState='hidden';
    stream={scrollTop:0,scrollHeight:100,clientHeight:100};
    document.querySelector=s=>s==='.messages'?stream:null;
    serverUnread=1;
    serverHead={...state.me,cursor:201};
    api=async(p,method,body)=>{
      calls.push({p,method,body});
      if(p==='/me')return {...serverHead,unread_count:serverUnread};
      if(p==='/messages/read'){serverUnread=0;return {ok:true,unread_count:0};}
      if(p.startsWith('/conversations'))return {conversations:[{sender:'s',timestamp:1,unread_count:serverUnread}]};
      if(p.startsWith('/analysis/counts'))return {counts:{}};
      if(p.startsWith('/messages?'))return {messages:[{id:201,timestamp:1,sender:'s'}]};
      return metadata(p);
    };
  `);
}

test("background catchUp defers displayed reads until a visible catchUp even with no new head or count", async () => {
  const ctx = runtime();
  backgroundHistory(ctx);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "state.inboxDirty"), false);
  assert.equal(run(ctx, "state.messages[0].id"), 201);
  assert.equal(run(ctx, "calls.some(c=>c.p==='/messages/read')"), false);
  run(ctx, "document.visibilityState='visible'");
  await run(ctx, "catchUp()");
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "calls.filter(c=>c.p==='/messages/read').length"), 1);
  assert.equal(run(ctx, "calls.find(c=>c.p==='/messages/read').body.message_id"), 201);
  assert.equal(run(ctx, "state.conversations[0].unread_count"), 0);
});

for (const change of ["state.sim='another-SIM'", "state.sender='another-sender'", "state.generation++", "state.messages=[]"]) {
  test(`deferred displayed read cannot survive a stale snapshot: ${change}`, async () => {
    const ctx = runtime();
    backgroundHistory(ctx);
    await run(ctx, "catchUp()");
    assert.equal(run(ctx, "state.pendingDisplayedRead.message_id"), 201);
    run(ctx, `${change};document.visibilityState='visible'`);
    await run(ctx, "catchUp()");
    assert.equal(run(ctx, "calls.some(c=>c.p==='/messages/read')"), false);
    assert.equal(run(ctx, "state.pendingDisplayedRead"), null);
  });
}

test("simultaneous visible polls submit a deferred read once, at its rendered boundary", async () => {
  const ctx = runtime();
  backgroundHistory(ctx);
  await run(ctx, "catchUp()");
  run(ctx, `
    originalAPI=api;readStarted=new Promise(r=>readReady=r);
    api=(p,m,b)=>{
      if(p!=='/messages/read')return originalAPI(p,m,b);
      calls.push({p,method:m,body:b});readReady();
      return new Promise(r=>releaseRead=()=>{serverUnread=0;r({ok:true,unread_count:0})});
    };
    document.visibilityState='visible';first=catchUp();
  `);
  await run(ctx, "readStarted");
  await run(ctx, "catchUp()");
  run(ctx, "releaseRead()");
  await run(ctx, "first");
  assert.equal(run(ctx, "calls.filter(c=>c.p==='/messages/read').length"), 1);
  assert.equal(run(ctx, "calls.find(c=>c.p==='/messages/read').body.message_id"), 201);
  assert.equal(run(ctx, "state.unreadCount"), 0);
});

function displayedHistory(ctx) {
  run(ctx, `
    state.sender='s';state.conversations=[{sender:'s',unread_count:2}];
    stream={scrollTop:0,scrollHeight:100,clientHeight:100};
    document.querySelector=s=>s==='.messages'?stream:null;
    renderDetail=()=>{displayed=state.messages.map(m=>m.id);return 0};
    catchUp=async()=>{};
    originalMarkRead=markDisplayedRead;
    markDisplayedRead=(...args)=>(readPending=originalMarkRead(...args));
    api=async(p,method,body)=>{
      calls.push({p,method,body});
      if(method==='POST')return {ok:true,unread_count:1};
      // ID order differs from timestamp order, as with historical imports.
      return {messages:[{id:10,timestamp:20,sender:'s'},{id:12,timestamp:10,sender:'s'}]};
    };
  `);
}

test("automatic read uses the greatest displayed ID, never the server's newer head", async () => {
  const ctx = runtime();
  displayedHistory(ctx);
  await run(ctx, "loadHistory(false)");
  await run(ctx, "readPending");
  assert.deepEqual(Array.from(run(ctx, "displayed")), [12, 10]);
  assert.equal(run(ctx, "calls.find(c=>c.method==='POST').body.message_id"), 12);
  assert.equal(run(ctx, "calls.find(c=>c.method==='POST').body.sender"), "s");
  assert.equal(run(ctx, "state.conversations[0].unread_count"), 2, "do not optimistically clear unseen arrivals");
});

for (const scenario of ["state.page='settings'", "document.visibilityState='hidden'"]) {
  test(`hidden history must not mark read: ${scenario}`, async () => {
    const ctx = runtime();
    displayedHistory(ctx);
    run(ctx, scenario);
    await run(ctx, "loadHistory(false)");
    assert.equal(run(ctx, "calls.some(c=>c.method==='POST')"), false);
  });
}

test("a failed displayed-read write keeps counts and schedules a later retry", async () => {
  const ctx = runtime();
  displayedHistory(ctx);
  run(ctx, "api=async()=>{throw Error('503')};");
  await run(ctx, "markDisplayedRead('s',12)");
  assert.equal(run(ctx, "state.readRetry"), true);
  assert.equal(run(ctx, "state.conversations[0].unread_count"), 2);
});

function pushRuntime() {
  const ctx = runtime();
  run(ctx, `
    window={isSecureContext:true,PushManager:function(){}};
    sub={toJSON:()=>({endpoint:'https://push.example.test/synthetic',keys:{}}),unsubscribe:async()=>{calls.push('unsubscribe')}};
    reg={pushManager:{getSubscription:async()=>sub,subscribe:async()=>sub}};
    navigator={serviceWorker:{getRegistration:async()=>reg,ready:Promise.resolve(reg)}};
    Notification={permission:'granted',requestPermission:async()=>'granted'};
    api=async(p,m,b)=>{calls.push(p);return {ok:true}};
    replaceSession=()=>{disconnect();state.me={device:{id:'browser-B',notify:false},vapid_public_key:'BA'}};
  `);
  return ctx;
}

test("existing browser subscription is bound to the newly logged-in server identity before reporting enabled", async () => {
  const ctx = pushRuntime();
  assert.equal(await run(ctx, "browserPushEnabled()"), true);
  assert.deepEqual(Array.from(run(ctx, "calls")), ["/push"]);
  run(ctx, "api=async()=>{throw Error('503')}");
  await assert.rejects(run(ctx, "browserPushEnabled()"), /503/);
});

for (const boundary of ["permission", "ready", "subscription", "subscribe", "binding"]) {
  test(`enable Push cannot cross logout at ${boundary}`, async () => {
    const ctx = pushRuntime();
    run(ctx, `gate=new Promise(r=>release=r);`);
    const setup = {
      permission: "Notification.requestPermission=()=>gate",
      ready: "navigator.serviceWorker.ready=gate",
      subscription: "reg.pushManager.getSubscription=()=>gate",
      subscribe: "reg.pushManager.getSubscription=async()=>null;reg.pushManager.subscribe=()=>gate",
      binding: "api=async p=>{calls.push(p);return gate}",
    };
    run(ctx, `${setup[boundary]};pending=enablePush();`);
    // Let the chain reach the selected external await without any wall-clock timers.
    for (let i = 0; i < 8; i++) await Promise.resolve();
    run(ctx, "replaceSession();release(" + (boundary === "permission" ? "'granted'" : boundary === "ready" ? "reg" : "sub") + ")");
    await assert.rejects(run(ctx, "pending"), e => e.name === "AbortError");
    assert.equal(run(ctx, "calls.some(p=>p.startsWith('/devices/'))"), false);
    assert.equal(run(ctx, "state.me.device.notify"), false);
    if (boundary !== "binding") assert.equal(run(ctx, "calls.length"), 0);
  });
}

test("disable Push cannot unsubscribe or change a new identity after a delayed registration lookup", async () => {
  const ctx = pushRuntime();
  run(ctx, "navigator.serviceWorker.getRegistration=()=>new Promise(r=>release=r);pending=disablePush();");
  for (let i = 0; i < 8; i++) await Promise.resolve();
  run(ctx, "replaceSession();release(reg)");
  await assert.rejects(run(ctx, "pending"), e => e.name === "AbortError");
  assert.deepEqual(Array.from(run(ctx, "calls")), ["/push"]);
});

test("automatic Push rebind cannot attach an old subscription lookup to a new login", async () => {
  const ctx = pushRuntime();
  run(ctx, "reg.pushManager.getSubscription=()=>new Promise(r=>release=r);pending=browserPushEnabled();");
  for (let i = 0; i < 8; i++) await Promise.resolve();
  run(ctx, "replaceSession();release(sub)");
  await assert.rejects(run(ctx, "pending"), e => e.name === "AbortError");
  assert.equal(run(ctx, "calls.length"), 0);
});

test("device revocation uses the selected identity and current-device revocation clears the session", async () => {
  const ctx = runtime();
  run(ctx, "api=async(p,m)=>{calls.push([p,m]);return {ok:true}};reloadMetadata=async()=>{};shell=()=>{};authView=()=>{authShown=true};");
  await run(ctx, "revokeDevice('other-browser')");
  assert.equal(run(ctx, "state.me.device.id"), "browser-A");
  assert.equal(run(ctx, "calls[0][0]"), "/devices/other-browser");
  assert.equal(run(ctx, "calls[0][1]"), "DELETE");
  await run(ctx, "revokeDevice('browser-A')");
  assert.equal(run(ctx, "state.me"), null);
  assert.equal(run(ctx, "authShown"), true);
});

test("device settings expose Web identities and only active devices have administrator revoke actions", async () => {
  const ctx = runtime();
  run(ctx, `
    el=(tag,cls,text)=>({tag,cls,text,children:[],append(...items){this.children.push(...items)}});
    settingsRow=options=>options;button=(text,click)=>({text,click});
    state.devices=[{id:'browser-A',name:'current',kind:'web'},
      {id:'browser-B',name:'other',kind:'web'},
      {id:'phone',name:'phone',kind:'android'},
      {id:'old',name:'revoked',kind:'web',revoked:true}];
    revoked=[];revokeDevice=async id=>{revoked.push(id)};
    rows=deviceRows();
  `);
  assert.equal(run(ctx, "rows.length"), 4);
  assert.equal(run(ctx, "rows[0].trailing.children[0].text"), "本浏览器");
  await run(ctx, "rows[1].trailing.children[1].click()");
  assert.equal(run(ctx, "revoked[0]"), "browser-B");
  assert.equal(run(ctx, "rows[3].trailing.children.length"), 1);
  run(ctx, "state.me.device.admin=false;rows=deviceRows()");
  assert.equal(run(ctx, "rows.every(row=>row.trailing.children.length===1)"), true);
});
