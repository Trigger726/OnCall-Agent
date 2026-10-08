<script setup lang="ts">
import { computed,nextTick,onBeforeUnmount,onMounted,ref } from 'vue'
import { auth } from '@/stores/auth'
import { RequestError } from '@/services/api'
import type { HandoffSource } from '@/services/onCallHandoffs'
import { canPublishOpen,clearOpenIntent,getOpenCoverage,getOpenRoster,listOpenHandoffs,openActionError,openClock,openIntentError,openPreflightError,openQualified,openTime,readOpenIntent,saveOpenIntent,submitOpenIntent,type OpenCoverage,type OpenHandoff,type OpenIntent } from '@/services/onCallOpenHandoffs'
const emit=defineEmits<{changed:[]}>()
const rows=ref<OpenHandoff[]>([]),databaseNow=ref(''),truncated=ref(false),info=ref<OpenCoverage|null>(null),selected=ref<number|null>(null)
const scope=ref<'ALL'|'MINE'|'AVAILABLE'>('AVAILABLE'),status=ref<OpenHandoff['status']|''>(''),scheduleId=ref('')
const intent=ref<OpenIntent|null>(null),frozen=ref(false),broken=ref(false),busy=ref(false),error=ref(''),message=ref(''),confirmDiscard=ref(false)
const sessionActor=()=>{try{return JSON.parse(localStorage.getItem('opspilot_user')??'null')?.id as number|undefined}catch{return undefined}}
const qualified=computed(()=>Boolean(localStorage.getItem('opspilot_token'))&&sessionActor()===auth.state.user?.id&&openQualified(auth.state.user?.id,auth.state.user?.roleCode))
const foreign=computed(()=>intent.value&&intent.value.action!=='PUBLISH'&&intent.value.row.id!==selected.value)
let epoch=0
const capture=()=>({epoch,actor:auth.state.user?.id,token:localStorage.getItem('opspilot_token')})
const current=(identity:ReturnType<typeof capture>)=>identity.epoch===epoch&&identity.actor===auth.state.user?.id&&identity.actor===sessionActor()&&identity.token===localStorage.getItem('opspilot_token')
const commandKey=computed(()=>!intent.value?'':intent.value.action==='PUBLISH'?intent.value.command.requestKey:intent.value.command.operationKey)
const actionName=computed(()=>intent.value?.action==='PUBLISH'?'发布':intent.value?.action==='WITHDRAW'?'撤回':'认领')
const newOperationBlocked=computed(()=>{const draft=intent.value;if(!draft||frozen.value||draft.action==='PUBLISH')return false
  return Boolean(openActionError(info.value,draft.action,auth.state.user?.id,auth.state.user?.roleCode)||info.value?.request.id!==draft.row.id||info.value?.request.version!==draft.command.version)})
async function refresh(keepError=false){
  if(busy.value||!auth.state.user||!localStorage.getItem('opspilot_token')||sessionActor()!==auth.state.user.id)return
  const identity=capture();busy.value=true;rows.value=[];info.value=null;databaseNow.value='';truncated.value=false;if(!keepError)error.value=''
  try{
    const list=await listOpenHandoffs(scope.value,status.value,scheduleId.value);if(!current(identity))return
    rows.value=list.requests;databaseNow.value=list.databaseNow;truncated.value=list.truncated
    if(selected.value){const facts=await getOpenCoverage(selected.value);if(current(identity))info.value=facts}
  }catch(cause){if(current(identity))error.value+=`${cause instanceof Error?cause.message:'开放接班读取失败'}；失败事实不沿用旧快照`}
  finally{if(current(identity))busy.value=false}
}
async function select(id:number){epoch++;busy.value=false;selected.value=id;info.value=null;error.value='';message.value='';confirmDiscard.value=false;await refresh()}
function open(source:HandoffSource){
  if(busy.value||intent.value||broken.value||!qualified.value||!canPublishOpen(source,auth.state.user?.id,auth.state.user?.roleCode,databaseNow.value))return
  epoch++;info.value=null;selected.value=null;error.value='';message.value=''
  intent.value={schema:1,actorId:auth.state.user!.id,blocked:false,action:'PUBLISH',source:{...source},command:{sourceShiftId:source.id,sourceVersion:source.version,requestKey:crypto.randomUUID(),startsAt:openTime(source.startsAt),endsAt:openTime(source.endsAt),reason:''}}
  frozen.value=false
  void nextTick(()=>document.querySelector('.open-intent')?.scrollIntoView({block:'start'}))
}
function choose(action:'CLAIM'|'WITHDRAW'){
  if(busy.value||intent.value||broken.value||!qualified.value||openActionError(info.value,action,auth.state.user?.id,auth.state.user?.roleCode))return
  intent.value={schema:1,actorId:auth.state.user!.id,blocked:false,action,row:{...info.value!.request},command:{version:info.value!.request.version,operationKey:crypto.randomUUID(),reason:''}}
  frozen.value=false;error.value='';message.value=''
  void nextTick(()=>document.querySelector('.open-intent')?.scrollIntoView({block:'start'}))
}
async function submit(){
  if(busy.value||broken.value||foreign.value||!qualified.value||!intent.value||intent.value.blocked||intent.value.actorId!==auth.state.user?.id)return
  const draft=intent.value,identity=capture();error.value='';message.value='';busy.value=true;info.value=null
  try{
    if(!frozen.value){
      draft.command.reason=draft.command.reason.trim()
      if(draft.action==='PUBLISH'){draft.command.startsAt=openTime(draft.command.startsAt);draft.command.endsAt=openTime(draft.command.endsAt)}
      const invalid=openIntentError(draft);if(invalid){error.value=invalid;return}
      const facts=draft.action==='PUBLISH'?null:await getOpenCoverage(draft.row.id);if(!current(identity))return
      info.value=facts
      const roster=draft.action==='WITHDRAW'?null:await getOpenRoster(draft);if(!current(identity))return
      const invalidLatest=openPreflightError(draft,facts,roster,auth.state.user?.roleCode)
      if(invalidLatest){error.value=`${invalidLatest}；未发送，不换原版本/键，请核对后明确放弃`;return}
      try{saveOpenIntent(sessionStorage,draft)}catch{error.value='原意图无法可靠保存，尚未发送；请检查浏览器存储权限';return}
      frozen.value=true
    }
    if(!current(identity))return
    const result=await submitOpenIntent(draft);if(!current(identity))return
    info.value=result.coverage;selected.value=result.row.id
    message.value='本人原意图已回执；历史请求保留，实际责任仍须独立查询区间coverage'
    try{clearOpenIntent(sessionStorage,draft.actorId);intent.value=null;frozen.value=false}catch{message.value+='；本地原意图未清除，只能核对后手动求原回执或明确放弃'}
    emit('changed')
  }catch(cause){
    if(!current(identity))return
    if(!frozen.value){info.value=null;error.value=`${cause instanceof Error?cause.message:'最新核对失败'}；未发送，不使用旧事实`;return}
    if(cause instanceof RequestError&&[403,409].includes(cause.status)){
      draft.blocked=true;try{saveOpenIntent(sessionStorage,draft)}catch{broken.value=true;error.value='锁定未能可靠保存，请核对后明确放弃；不能保证刷新后的存储状态。'}
    }
    error.value+=`${cause instanceof TypeError?'响应中断，服务器可能已提交':cause instanceof Error?cause.message:'结果未确认'}。原键/原版本/说明已冻结，刷新不自动提交${draft.blocked?'；原意图已锁定':'，仅手动求原回执'}`
  }finally{if(current(identity)){busy.value=false;if(frozen.value||message.value)await refresh(true)}}
}
async function discard(){
  if(busy.value||!auth.state.user)return
  try{clearOpenIntent(sessionStorage,auth.state.user.id)}catch{error.value='无法清除原意图，尚未放弃';return}
  intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;message.value='仅放弃本账号本标签页草稿，不撤销服务器结果、不恢复覆盖';await refresh()
}
function resetAccount(){
  epoch++;busy.value=false;rows.value=[];databaseNow.value='';info.value=null;selected.value=null;intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;error.value='';message.value=''
  if(!auth.state.user)return
  if(!localStorage.getItem('opspilot_token')||sessionActor()!==auth.state.user.id){broken.value=true;error.value='浏览器会话账号与页面身份不一致，请重新登录；未发送任何操作';return}
  try{intent.value=readOpenIntent(sessionStorage,auth.state.user.id);frozen.value=Boolean(intent.value)
    if(intent.value){if(intent.value.action!=='PUBLISH')selected.value=intent.value.row.id;message.value='已恢复本人原键/原版本/原说明，仅手动求回执，不自动POST'}
  }catch(cause){broken.value=true;error.value=cause instanceof Error?cause.message:'原意图读取失败，未发送'}
  void refresh(true)
}
const storageChanged=(event:StorageEvent)=>{if(event.key===null||['opspilot_token','opspilot_user'].includes(event.key))resetAccount()}
onMounted(()=>{window.addEventListener('opspilot-auth-session-changed',resetAccount);window.addEventListener('storage',storageChanged);resetAccount()})
onBeforeUnmount(()=>{epoch++;window.removeEventListener('opspilot-auth-session-changed',resetAccount);window.removeEventListener('storage',storageChanged)})
defineExpose({refresh,open})
</script>
<template>
  <section class="content-panel open-handoff-panel" :aria-busy="busy" :data-open-id="selected">
    <div class="panel-heading"><div><h2>开放接班 · 自愿认领</h2><span>本人发布已有普通班次，合格同事自行承担；不代他人同意</span></div><button class="secondary-button" :disabled="busy" @click="refresh()">刷新开放接班</button></div>
    <div class="open-body">
      <p class="open-note">从下方班次维护点击“发布开放接班”。保留指定接班和双向换班；现阶段使用全局运维资格，尚无计划成员ACL、广播或到期提醒。</p>
      <p class="open-note">数据库快照：{{openClock(databaseNow)}}。输入使用数据库会话时间，不转换浏览器时区；可发布子时段，进行中认领仅覆盖数据库时间后的剩余部分。</p>
      <p v-if="error" class="open-error" role="alert">{{error}}</p><p v-if="message" role="status">{{message}}</p>
      <form class="open-filters" @submit.prevent="refresh()">
        <label>开放接班范围<select v-model="scope" aria-label="开放接班范围"><option value="AVAILABLE">可认领候选</option><option value="MINE">我发布或认领</option><option value="ALL">全部历史</option></select></label>
        <label>开放接班状态<select v-model="status" aria-label="开放接班状态"><option value="">全部状态</option><option value="OPEN">OPEN · 未关闭</option><option value="CLAIMED">CLAIMED · 历史认领</option><option value="WITHDRAWN">WITHDRAWN · 本人撤回</option></select></label>
        <label>开放接班计划ID<input v-model="scheduleId" inputmode="numeric" pattern="[1-9][0-9]*" placeholder="留空为全部"></label>
        <button class="secondary-button" :disabled="busy">查询开放接班</button>
      </form>
      <p v-if="truncated" role="status">只显示前200条，筛选在服务端先执行；请缩小计划/状态范围。</p>
      <div class="open-list"><article v-for="row in rows" :key="row.id" class="open-row" :data-open-row="row.id"><div><strong>#{{row.id}} · {{row.status}} · v{{row.version}}</strong><p>计划 #{{row.scheduleId}} · 发布人 #{{row.requesterId}}<br>{{openClock(row.startsAt)}} → {{openClock(row.endsAt)}}</p><p>{{row.reason}}</p></div><button class="secondary-button" @click="select(row.id)">核对开放接班</button></article></div>
      <p v-if="!busy&&!rows.length" class="open-note">当前筛选没有请求；候选不等于后续锁定时仍有资格，不使用浏览器时钟判断过期。</p>
      <article v-if="info" class="open-detail">
        <h3>历史请求 #{{info.request.id}} · {{info.request.status}} · v{{info.request.version}}</h3>
        <p>数据库事实时间 {{openClock(info.databaseNow)}} · 原班次 #{{info.request.sourceShiftId}} / v{{info.request.sourceVersion}}</p>
        <p v-if="info.replacement" class="open-replacement">覆盖 #{{info.replacement.id}} · 负责人 #{{info.replacement.userId}} · v{{info.replacement.version}}<br>{{openClock(info.replacement.startsAt)}} → {{openClock(info.replacement.endsAt)}}<br>{{info.replacement.cancelledAt===null?'覆盖未取消，不等于此刻胜出':'覆盖已取消；原CLAIMED不改写、不重新创建'}}<span v-if="info.replacement.cancelledAt"><br>{{openClock(info.replacement.cancelledAt)}} · {{info.replacement.cancellationReason}}</span></p>
        <p v-else>没有本次认领生成的覆盖，不假定已转移责任。</p>
        <p v-if="info.operation" class="open-receipt">{{info.operation.actorId===auth.state.user?.id?'本人操作回执':'历史操作回执 · 非本人'}}：{{info.operation.operation}} · 账号 #{{info.operation.actorId}} · {{openClock(info.operation.committedAt)}}<br>原键 {{info.operation.operationKey}} · 捕获v{{info.operation.capturedVersion}}<br>{{info.operation.reason}}</p>
        <p class="open-note">历史认领与当前责任分开。请在区间coverage选择计划 #{{info.request.scheduleId}}、原请求时段独立查询；取消后也不承诺原负责人仍可用。</p>
        <div class="open-actions"><button v-for="action in (['CLAIM','WITHDRAW'] as const)" v-show="qualified&&!openActionError(info,action,auth.state.user?.id,auth.state.user?.roleCode)" :key="action" class="secondary-button" :disabled="busy||Boolean(intent)||broken" @click="choose(action)">{{action==='CLAIM'?'我自愿认领':'本人撤回请求'}}</button></div>
        <p v-if="info.request.status==='OPEN'" class="open-note">{{openActionError(info,info.request.requesterId===auth.state.user?.id?'WITHDRAW':'CLAIM',auth.state.user?.id,auth.state.user?.roleCode)}}</p>
      </article>
      <p v-else-if="selected" class="open-note">请求详情尚未取得或读取失败，不沿用旧覆盖和旧回执。</p>
      <p v-if="foreign" class="open-error">原意图属于请求 #{{intent?.action!=='PUBLISH'&&intent?.row.id}}，不改用当前请求。<button class="secondary-button" @click="intent&&intent.action!=='PUBLISH'&&select(intent.row.id)">打开原请求</button></p>
      <form v-if="intent&&!foreign" class="open-intent" @submit.prevent="submit">
        <h3>{{frozen?'冻结的本人原意图':'确认本人操作'}} · {{actionName}}</h3>
        <template v-if="intent.action==='PUBLISH'"><p>原普通班次 #{{intent.source.id}} / v{{intent.command.sourceVersion}} · {{intent.source.userName}}</p><div class="open-times"><label>开放时段开始<input v-model="intent.command.startsAt" type="datetime-local" step="1" required :disabled="busy||frozen||!qualified"></label><label>开放时段结束<input v-model="intent.command.endsAt" type="datetime-local" step="1" required :disabled="busy||frozen||!qualified"></label></div></template>
        <p v-else>原请求 #{{intent.row.id}} / v{{intent.command.version}} · 本人账号 #{{intent.actorId}}</p>
        <label>开放接班操作说明<textarea v-model="intent.command.reason" rows="2" required maxlength="500" :disabled="busy||frozen||!qualified"/></label>
        <p class="open-note">原键 {{commandKey}}。首次提交先核对最新GET并可靠保存原意图；失败不自动换版本/键。放弃仅清本地，不删除服务器结果。</p>
        <p v-if="frozen" class="open-note">原内容已冻结。结束或覆盖取消后也只手动求原回执；不重新创建责任。</p>
        <p v-if="intent.blocked" class="open-error">403/409后的原意图已锁定，请核对服务器事实后明确放弃。</p>
        <div class="open-actions"><button class="primary-button" :disabled="busy||broken||intent.blocked||!qualified||newOperationBlocked">{{frozen?'手动求原回执':`确认本人${actionName}`}}</button><button type="button" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">放弃开放接班草稿</button></div>
      </form>
      <button v-if="broken||foreign" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">核对后放弃开放原意图</button>
      <div v-if="confirmDiscard" class="open-intent" role="group" aria-label="明确放弃开放草稿"><p>仅清本账号本标签页原意图，不撤回服务器请求、不恢复已取消覆盖。</p><div class="open-actions"><button class="secondary-button" :disabled="busy" @click="discard">确认仅放弃开放草稿</button><button class="secondary-button" @click="confirmDiscard=false">保留开放原意图</button></div></div>
    </div>
  </section>
</template>
<style scoped>
.open-handoff-panel {margin-bottom:18px;scroll-margin-top:84px;}
.open-body {padding:0 20px 20px;}
.open-note,.open-row p {font-size:12px;color:var(--text-muted);line-height:1.7;overflow-wrap:anywhere;}
.open-error {color:#b42318;overflow-wrap:anywhere;}
.open-filters {display:grid;grid-template-columns:1fr 1fr 1fr auto;gap:12px;align-items:end;margin:16px 0;}
.open-times {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-bottom:12px;}
label {display:flex;flex-direction:column;gap:6px;min-width:0;font-size:12px;}
input,select,textarea {width:100%;min-width:0;box-sizing:border-box;padding:9px;border:1px solid var(--line);border-radius:6px;background:var(--surface);color:inherit;font:inherit;}
.open-row {display:grid;grid-template-columns:minmax(0,1fr) auto;gap:12px;border-top:1px solid var(--line);padding:12px 0;align-items:center;}
.open-row strong,h3 {font-size:14px;}
.open-detail,.open-intent {border:1px solid var(--line);border-radius:8px;padding:14px;margin:14px 0;scroll-margin-top:84px;font-size:12px;overflow-wrap:anywhere;line-height:1.7;}
h3 {margin:0 0 8px;}
.open-actions {display:flex;gap:8px;flex-wrap:wrap;}
@media(max-width:900px){.open-filters {grid-template-columns:repeat(2,minmax(0,1fr));}}
@media(max-width:640px){.open-filters,.open-times,.open-row {grid-template-columns:1fr;}.open-body {padding:0 14px 14px;}}
</style>
