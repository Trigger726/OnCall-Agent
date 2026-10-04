<script setup lang="ts">
import { computed,onMounted,onBeforeUnmount,ref } from 'vue'
import { auth } from '@/stores/auth'
import { RequestError } from '@/services/api'
import { swapClock } from '@/services/onCallSwaps'
import { canManageNotification,canRetryNotification,clearNotificationRetry,getSwapNotifications,notificationRetryError,notificationState,notificationPayloadLabel,notificationClock,
  readNotificationRetry,retrySwapNotification,saveNotificationRetry,type NotificationRetry,type SwapNotification,type SwapNotifications } from '@/services/onCallSwapNotifications'
const props=defineProps<{swapId:number;requester:number;target:number}>()
const info=ref<SwapNotifications|null>(null),intent=ref<NotificationRetry|null>(null),busy=ref(false),frozen=ref(false),broken=ref(false),confirmDiscard=ref(false),error=ref(''),message=ref('')
const other= computed(()=>intent.value&&intent.value.swapId!==props.swapId)
const canWrite=computed(()=>canManageNotification(auth.state.user?.id,auth.state.user?.roleCode,props.requester,props.target))
const canRetry=(row:SwapNotification)=>canRetryNotification(row,info.value?.enabled??false,auth.state.user?.id,auth.state.user?.roleCode,props.requester,props.target,info.value)
const newRetryAllowed=computed(()=>{const row=info.value?.deliveries.find(n=>n.id===intent.value?.id);return Boolean(row&&row.version===intent.value?.version&&canRetry(row))})
let epoch=0
const capture=()=>({epoch,actor:auth.state.user?.id,token:localStorage.getItem('opspilot_token')})
const current=(value:ReturnType<typeof capture>)=>value.epoch===epoch&&value.actor===auth.state.user?.id&&value.token===localStorage.getItem('opspilot_token')
async function refresh(keepError=false){if(busy.value)return;const identity=capture();busy.value=true;info.value=null;if(!keepError)error.value=''
  try{const rows=await getSwapNotifications(props.swapId);if(current(identity))info.value=rows}
  catch(cause){if(current(identity))error.value=cause instanceof Error?cause.message:'通知读取失败，旧投递状态已清空'}
  finally{if(current(identity))busy.value=false}}
function choose(row:SwapNotification){if(busy.value||intent.value||broken.value||!canRetry(row)||!auth.state.user)return
  intent.value={schema:1,actorId:auth.state.user.id,swapId:props.swapId,id:row.id,version:row.version,reason:'',blocked:false};frozen.value=false;error.value='';message.value=''}
async function submit(){if(busy.value||broken.value||other.value||!intent.value||intent.value.blocked||!canWrite.value||intent.value.actorId!==auth.state.user?.id)return
  error.value='';message.value='';const captured=intent.value
  const identity=capture()
  if(!frozen.value){captured.reason=captured.reason.trim();const invalid=notificationRetryError(captured);if(invalid){error.value=invalid;return}
    busy.value=true;info.value=null
    try{const rows=await getSwapNotifications(props.swapId);if(!current(identity))return;info.value=rows
      if(!newRetryAllowed.value){error.value='通知已到期、已变化或保留事实异常，未发送新重试；未发送草稿保留，请核对后明确放弃';return}}
    catch(cause){if(current(identity))error.value=`${cause instanceof Error?cause.message:'通知核对失败'}，未发送新重试；不使用旧保留事实`;return}
    finally{if(current(identity))busy.value=false}
    try{saveNotificationRetry(sessionStorage,captured)}catch{error.value='无法保存通知重试意图，尚未发送；请检查浏览器存储权限';return}frozen.value=true}
  if(!current(identity))return;busy.value=true;info.value=null
  try{const row=await retrySwapNotification(captured);if(!current(identity))return
    message.value=`通知 #${row.id} 原重试已回执；${notificationState(row)}。不会修改换班决定或当前覆盖`
    try{clearNotificationRetry(sessionStorage,captured.actorId);intent.value=null;frozen.value=false}catch{message.value+='；本地意图未清除，核对后可明确放弃'}
  }catch(cause){if(!current(identity))return
    if(cause instanceof RequestError&&[403,409].includes(cause.status)){captured.blocked=true;try{saveNotificationRetry(sessionStorage,captured)}catch{error.value='锁定未能保存；刷新后仍须核对原意图。'}}
    error.value+=`${cause instanceof TypeError?'响应中断，服务器可能已提交':cause instanceof Error?cause.message:'结果未确认'}。原通知版本/说明已冻结，刷新不自动提交${captured.blocked?'；原意图已锁定，请核对后明确放弃':'，仅手动回执原重试'}`
  }finally{if(current(identity)){busy.value=false;await refresh(true)}}}
async function discard(){try{if(auth.state.user)clearNotificationRetry(sessionStorage,auth.state.user.id)}catch{error.value='无法清除草稿，尚未放弃';return}
  intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;message.value='仅放弃本标签页通知草稿，不撤回已发送通知或换班决定';await refresh()}
function resetAccount(){epoch++;busy.value=false;info.value=null;intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;error.value='';message.value=''
  if(!auth.state.user)return
  try{intent.value=readNotificationRetry(sessionStorage,auth.state.user.id);frozen.value=Boolean(intent.value);if(intent.value)message.value='已恢复原通知版本/说明，不自动提交'}catch(cause){broken.value=true;error.value=cause instanceof Error?cause.message:'通知草稿无法读取'}
  void refresh()}
onMounted(()=>{window.addEventListener('opspilot-auth-session-changed',resetAccount);resetAccount()})
onBeforeUnmount(()=>{epoch++;window.removeEventListener('opspilot-auth-session-changed',resetAccount)})
</script>
<template>
  <section class="swap-notification-panel" :aria-busy="busy">
    <div class="notification-heading"><h3>换班通知 · 技术投递</h3><button class="secondary-button" :disabled="busy" @click="refresh()">刷新通知状态</button></div>
    <p class="notification-note">2xx仅代表接收端技术回执，不代表本人已读、接受换班或当前责任。双方决定与两段coverage事实仍独立核对；失效事件/收件人可能跳过，出站存在至少一次投递，请接收端按稳定键去重。</p>
    <p v-if="error" class="notification-error" role="alert">{{error}}</p><p v-if="message" role="status">{{message}}</p>
    <p v-if="info&&!info.enabled" class="notification-note">通知未启用；默认旧Demo不发外部请求，不回填历史事件。已有投递事实仍保留。</p>
    <p v-if="info?.enabled" class="notification-note">通知已启用 · 数据库快照 {{notificationClock(info.databaseNow)}} · 当前配置启用不等于所有历史申请曾入队。</p>
    <p v-if="info" class="notification-note">载荷保留按数据库快照核对，不使用浏览器时钟；清理不删除换班决定、审计或技术回执，也不能撤回已发出的外部请求。</p>
    <div v-if="info" class="notification-list"><div v-for="row in info.deliveries" :key="row.id" class="notification-row" :data-notification-id="row.id">
      <strong>{{row.recipientName}} · 事件 {{row.eventStatus}} / v{{row.eventVersion}}</strong><p>{{notificationState(row)}} · 通知版本 v{{row.version}}</p>
      <p>本轮尝试 {{row.attempts}} · 累计 {{row.totalAttempts}} · HTTP {{row.lastHttpStatus??'尚无回执'}}<span v-if="row.lastErrorCode"> · {{row.lastErrorCode}}</span></p>
      <p v-if="row.deliveredAt">技术回执 {{swapClock(row.deliveredAt)}}</p><p v-else-if="row.status==='PENDING'">下次可尝试 {{swapClock(row.nextAttemptAt)}}</p>
      <p class="notification-retention">{{notificationPayloadLabel(row,info)}}<span v-if="row.payloadExpiresAt"> · 冻结期限 {{notificationClock(row.payloadExpiresAt)}}</span><span v-if="row.payloadErasedAt"> · 清理记录 {{notificationClock(row.payloadErasedAt)}}</span></p>
      <button v-if="canRetry(row)" class="secondary-button" :disabled="busy||Boolean(intent)||broken" @click="choose(row)">核对后重试通知 #{{row.id}}</button>
    </div><p v-if="!info.deliveries.length" class="notification-note">没有本次换班的通知入队事实，不把空列表称为送达。</p></div>
    <p v-else class="notification-note">通知事实尚未取得或读取失败，不显示旧回执。</p>
    <p v-if="other" class="notification-error">原通知重试草稿属于换班 #{{intent?.swapId}}，请打开原换班详情核对；不会改用当前通知。</p>
    <form v-if="intent&&!other" class="notification-retry" @submit.prevent="submit"><h4>仅重试通知 #{{intent.id}} · 捕获v{{intent.version}}</h4>
      <label>通知重试说明<textarea v-model="intent.reason" required maxlength="500" rows="2" :disabled="busy||frozen||!canWrite"/></label>
      <p class="notification-note">首次发送前按账号保存原通知版本/说明；丢响应后只回执原意图，不重发新事件。人工重试不会接受换班。</p>
      <p v-if="frozen" class="notification-note">到期或已清理后仍只手动核对原回执；服务器若已提交会保留原结果，未提交或失效则拒绝，不自动换版本重新投递。</p>
      <p v-else-if="!newRetryAllowed" class="notification-error">不能新重试：先刷新核对当前版本与保留事实；不会自动更新未发送草稿。</p>
      <div class="notification-heading"><button class="primary-button" :disabled="busy||intent.blocked||!canWrite||(!frozen&&!newRetryAllowed)">{{frozen?'回执原通知重试':'确认重试通知'}}</button><button type="button" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">放弃通知重试草稿</button></div>
      <p v-if="intent.blocked" class="notification-error">原通知重试已锁定，不自动换版本。</p>
    </form>
    <button v-if="broken||other" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">核对后放弃原通知草稿</button>
    <div v-if="confirmDiscard" class="notification-retry"><p>仅清本账号本标签页草稿，不撤回服务器已入队通知、送达或换班决定。</p><button class="secondary-button" :disabled="busy" @click="discard">确认仅放弃通知草稿</button></div>
    <p v-if="!canWrite" class="notification-note">当前账号只读通知，不能代替参与者确认或重试。</p>
  </section>
</template>
<style scoped>
.swap-notification-panel {border-top:1px solid var(--line);margin-top:16px;padding-top:16px;scroll-margin-top:84px;}
.notification-heading {display:flex;gap:8px;align-items:center;flex-wrap:wrap;}
h3,h4 {font-size:14px;margin:0 0 8px;}
.notification-note,.notification-row p {font-size:12px;color:var(--text-muted);line-height:1.7;overflow-wrap:anywhere;}
.notification-error {color:#b42318;overflow-wrap:anywhere;}
.notification-list {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:12px 0;}
.notification-row,.notification-retry {border:1px solid var(--line);border-radius:8px;padding:12px;min-width:0;font-size:12px;overflow-wrap:anywhere;}
label {display:flex;flex-direction:column;gap:6px;}
textarea {width:100%;box-sizing:border-box;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:var(--surface);color:inherit;font:inherit;}
@media(max-width:640px){.notification-list {grid-template-columns:1fr;}}
</style>
