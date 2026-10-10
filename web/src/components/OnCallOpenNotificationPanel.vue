<script setup lang="ts">
import { onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { auth } from '@/stores/auth'
import { readOpenNotifications,openDeliveryLabel,openDeliveryMeaning,openNotificationClock,type OpenNotificationFacts } from '@/services/onCallOpenNotifications'
const props=defineProps<{handoffId:number}>()
const facts=ref<OpenNotificationFacts|null>(null),busy=ref(false),error=ref('')
let epoch=0,controller:AbortController|undefined
const sessionActor=()=>{try{return JSON.parse(localStorage.getItem('opspilot_user')??'null')?.id as number|undefined}catch{return undefined}}
const sessionToken=()=>{try{return localStorage.getItem('opspilot_token')}catch{return null}}
const capture=()=>({epoch,handoffId:props.handoffId,actorId:auth.state.user?.id,token:sessionToken()})
const current=(identity:ReturnType<typeof capture>)=>identity.epoch===epoch&&identity.handoffId===props.handoffId&&identity.actorId===auth.state.user?.id&&identity.actorId===sessionActor()&&identity.token===sessionToken()
async function refresh(){
  epoch++;controller?.abort();facts.value=null;error.value='';busy.value=false
  const identity=capture()
  if(!identity.token||!identity.actorId||identity.actorId!==sessionActor()){error.value='会话身份不一致，请重新登录；未读取通知';return}
  controller=new AbortController();busy.value=true
  try{const received=await readOpenNotifications(identity.handoffId,{actorId:identity.actorId,token:identity.token},controller.signal);if(current(identity))facts.value=received}
  catch(cause){if(current(identity))error.value=`${cause instanceof Error?cause.message:'通知读取失败'}；失败事实不沿用旧快照`}
  finally{if(identity.epoch===epoch){busy.value=false;if(!current(identity)){facts.value=null;error.value='会话在读取期间改变，未显示旧通知；请重新核对登录身份'}}}
}
const storageChanged=(event:StorageEvent)=>{if(event.key===null||['opspilot_token','opspilot_user'].includes(event.key))void refresh()}
watch(()=>[props.handoffId,auth.state.user?.id],()=>{void refresh()},{immediate:true})
onMounted(()=>{window.addEventListener('opspilot-auth-session-changed',refresh);window.addEventListener('storage',storageChanged)})
onBeforeUnmount(()=>{epoch++;controller?.abort();window.removeEventListener('opspilot-auth-session-changed',refresh);window.removeEventListener('storage',storageChanged)})
</script>
<template>
  <section class="open-notification-panel" :aria-busy="busy" :data-notification-handoff="handoffId">
    <div class="notification-heading"><h4>开放通知 · 技术回执</h4><button type="button" class="secondary-button" :disabled="busy" @click="refresh">刷新通知状态</button></div>
    <p class="notification-note">只读技术状态，不执行认领或重投。渠道成功不等于对方已读、同意或责任已转移；仍须本人明确认领并独立核对 coverage。</p>
    <p v-if="error" class="notification-error" role="alert">{{error}}</p>
    <p v-if="busy" role="status">正在读取当前请求的通知事实；旧回执已清除。</p>
    <template v-if="facts">
      <p class="notification-config" role="status">{{facts.adapterImplemented?'适配器已实现':'适配器未实现'}} · {{facts.enabled?'当前投递已启用':'当前投递未启用 · 已有回执仍保留'}}<br>数据库事实时间 {{openNotificationClock(facts.databaseNow)}}</p>
      <p v-if="!facts.deliveries.length" class="notification-empty">没有本请求的通知事件。空原候选或旧发布不因后续启用、授权而自动补发；此处不推断具体原因。</p>
      <article v-for="row in facts.deliveries" :key="row.id" class="notification-row" :data-delivery-id="row.id" :data-delivery-state="row.status">
        <div class="notification-row-heading"><strong>原收件人 #{{row.recipientId}}</strong><span class="notification-state" :data-state="row.status">{{openDeliveryLabel(row)}}</span></div>
        <p>{{openDeliveryMeaning(row)}}</p>
        <dl><div><dt>事件 / 技术版本</dt><dd>v{{row.eventVersion}} / v{{row.version}}</dd></div><div><dt>租约尝试次数</dt><dd>{{row.attempts}}（不等于收到请求数）</dd></div>
          <div v-if="row.lastHttpStatus!==null"><dt>最近 HTTP</dt><dd>{{row.lastHttpStatus}}</dd></div><div v-if="row.lastErrorCode"><dt>技术代码</dt><dd>{{row.lastErrorCode}}</dd></div>
          <div v-if="row.status==='PENDING'"><dt>可再次尝试时间</dt><dd>{{openNotificationClock(row.nextAttemptAt)}} · 非送达承诺</dd></div>
          <div v-if="row.leaseUntil"><dt>服务端租约记录</dt><dd>{{openNotificationClock(row.leaseUntil)}}</dd></div><div v-if="row.deliveredAt"><dt>技术成功时间</dt><dd>{{openNotificationClock(row.deliveredAt)}}</dd></div></dl>
        <details><summary>原投递标识</summary><p class="notification-key">{{row.deliveryKey}}</p></details>
      </article>
    </template>
  </section>
</template>
<style scoped>
.open-notification-panel {border-top:1px solid var(--line);margin:16px 0;padding-top:14px;scroll-margin-top:84px;}
.notification-heading,.notification-row-heading {display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;}
h4 {margin:0;font-size:14px;}.notification-note,.notification-empty {color:var(--text-muted);line-height:1.7;}
.notification-error {color:#b42318;overflow-wrap:anywhere;}.notification-config {line-height:1.7;}
.notification-row {border:1px solid var(--line);border-radius:8px;padding:12px;margin-top:12px;overflow-wrap:anywhere;}
.notification-row p {line-height:1.7;color:var(--text-muted);}.notification-state {border-radius:5px;padding:4px 8px;background:var(--surface);}
.notification-state[data-state="DELIVERED"] {color:#146c43;}.notification-state[data-state="FAILED"] {color:#b42318;}
dl {margin:10px 0;display:grid;gap:8px;}dl div {display:grid;grid-template-columns:140px minmax(0,1fr);gap:10px;}dt {color:var(--text-muted);}dd {margin:0;}
summary {cursor:pointer;color:var(--text-muted);}.notification-key {font-family:monospace;}
@media(max-width:640px){dl div {grid-template-columns:1fr;gap:3px;}.notification-heading {align-items:flex-start;}.notification-heading button {width:100%;}}
</style>
