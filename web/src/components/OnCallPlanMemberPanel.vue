<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,ref } from 'vue'
import { auth } from '@/stores/auth'
import { RequestError } from '@/services/api'
import { clearMemberIntent,listMemberPlans,listPlanMembers,memberIntentError,memberManager,memberPreflightError,readMemberIntent,restoreMemberIntentForManualAck,saveMemberIntent,submitMemberIntent,type MemberIntent,type MemberResult,type PlanMember,type PlanOption } from '@/services/onCallPlanMembers'
const emit=defineEmits<{changed:[]}>()
const plans=ref<PlanOption[]>([]),rows=ref<PlanMember[]|null>(null),selected=ref<number|null>(null),targetId=ref('')
const intent=ref<MemberIntent|null>(null),result=ref<MemberResult|null>(null),frozen=ref(false),broken=ref(false),busy=ref(false),error=ref(''),message=ref(''),confirmDiscard=ref(false)
const sessionActor=()=>{try{return JSON.parse(localStorage.getItem('opspilot_user')??'null')?.id as number|undefined}catch{return undefined}}
const qualified=computed(()=>Boolean(localStorage.getItem('opspilot_token'))&&sessionActor()===auth.state.user?.id&&memberManager(auth.state.user?.id,auth.state.user?.roleCode))
const managing=computed(()=>qualified.value&&rows.value!==null&&plans.value.some(p=>p.id===selected.value)&&(auth.state.user?.roleCode==='ADMIN'||rows.value.some(m=>m.userId===auth.state.user?.id&&m.effectiveManagement)))
const foreign=computed(()=>intent.value&&intent.value.scheduleId!==selected.value)
let epoch=0
const capture=()=>({epoch,actor:auth.state.user?.id,token:localStorage.getItem('opspilot_token')})
const current=(identity:ReturnType<typeof capture>)=>identity.epoch===epoch&&identity.actor===auth.state.user?.id&&identity.actor===sessionActor()&&identity.token===localStorage.getItem('opspilot_token')
async function refresh(keepError=false){
  if(busy.value||!auth.state.user||!localStorage.getItem('opspilot_token')||sessionActor()!==auth.state.user.id)return
  const identity=capture();busy.value=true;rows.value=null;plans.value=[];if(!keepError)error.value=''
  try{const list=await listMemberPlans();if(!current(identity))return;plans.value=list
    if(selected.value===null)selected.value=list[0]?.id??null
    if(selected.value!==null){const latest=await listPlanMembers(selected.value);if(current(identity))rows.value=latest}
  }catch(cause){if(current(identity)){rows.value=null;result.value=null;error.value+=`${cause instanceof Error?cause.message:'成员读取失败'}；旧事实已清空，不创建新操作`}}
  finally{if(current(identity))busy.value=false}
}
async function selectPlan(){epoch++;busy.value=false;rows.value=null;result.value=null;error.value='';message.value='';confirmDiscard.value=false;await refresh()}
function begin(userId:number){
  if(busy.value||intent.value||broken.value||!managing.value||!selected.value||!Number.isSafeInteger(userId)||userId<1)return
  const row=rows.value?.find(m=>m.userId===userId)
  intent.value={schema:1,actorId:auth.state.user!.id,scheduleId:selected.value,blocked:false,command:{userId,expectedVersion:row?.version??null,active:row?.active??true,canRespond:row?.canRespond??true,canManage:row?.canManage??false,operationKey:crypto.randomUUID(),reason:''}}
  frozen.value=false;result.value=null;message.value='';error.value=''
}
async function submit(){
  if(busy.value||broken.value||foreign.value||!qualified.value||!intent.value||intent.value.blocked||intent.value.actorId!==auth.state.user?.id)return
  const draft=intent.value,identity=capture();busy.value=true;error.value='';message.value='';result.value=null
  try{
    if(!frozen.value){
      draft.command.reason=draft.command.reason.trim();const invalid=memberIntentError(draft);if(invalid){error.value=invalid;return}
      rows.value=null;const latest=await listPlanMembers(draft.scheduleId);if(!current(identity))return;rows.value=latest
      const invalidLatest=memberPreflightError(draft,latest,auth.state.user?.roleCode);if(invalidLatest){error.value=`${invalidLatest}；未发送，请核对后明确放弃`;return}
      try{saveMemberIntent(sessionStorage,draft)}catch{error.value='原意图无法可靠保存，尚未发送；请检查浏览器存储权限';return}
      frozen.value=true
    }else{
      // A successful remove followed by denied readback may leave only this page's frozen command.
      // Manual recovery re-saves the exact original only when absent; a different intent is never overwritten.
      if(!current(identity))return
      restoreMemberIntentForManualAck(sessionStorage,draft)
    }
    if(!current(identity))return
    const response=await submitMemberIntent(sessionStorage,draft,identity.token);if(!current(identity))return
    result.value=response;message.value='本人原成员操作已回执；当前成员版本/权限与原回执分开显示，不取消已有责任'
    try{clearMemberIntent(sessionStorage,draft.actorId);intent.value=null;frozen.value=false}catch{message.value+='；本地原意图清除未能确认，当前页仍保留原命令；刷新可能丢失，请先手动求原回执或明确放弃'}
    emit('changed')
  }catch(cause){
    if(!current(identity))return
    if(!frozen.value){rows.value=null;error.value=`${cause instanceof Error?cause.message:'最新核对失败'}；未发送，不使用旧事实`;return}
    if(cause instanceof RequestError&&[403,409].includes(cause.status)){draft.blocked=true;try{saveMemberIntent(sessionStorage,draft)}catch{broken.value=true;error.value='锁定未能可靠保存，请核对后明确放弃；不能保证刷新后的存储状态。'}}
    error.value+=`${cause instanceof TypeError?'响应中断，服务器可能已提交':cause instanceof Error?cause.message:'结果未确认'}。原键/版本/权限/说明已冻结，刷新不自动POST${draft.blocked?'；原意图已锁定':'，仅手动求原回执'}`
  }finally{if(current(identity)){busy.value=false;if(frozen.value||message.value)await refresh(true)}}
}
async function discard(){
  if(busy.value||!auth.state.user||sessionActor()!==auth.state.user.id)return
  try{clearMemberIntent(sessionStorage,auth.state.user.id)}catch{error.value='无法可靠清除原意图，尚未放弃';return}
  intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;message.value='仅放弃本人本标签页草稿，不撤销服务器授权/回执或已有责任';await refresh()
}
function resetAccount(){
  epoch++;busy.value=false;plans.value=[];rows.value=null;selected.value=null;targetId.value='';intent.value=null;result.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;error.value='';message.value=''
  if(!auth.state.user)return
  if(!localStorage.getItem('opspilot_token')||sessionActor()!==auth.state.user.id){broken.value=true;error.value='浏览器会话账号与页面身份不一致，请重新登录；未发送';return}
  try{intent.value=readMemberIntent(sessionStorage,auth.state.user.id);frozen.value=Boolean(intent.value)
    if(intent.value){selected.value=intent.value.scheduleId;message.value='已恢复本人原键/版本/权限/说明，仅手动求原回执，不自动POST'}
  }catch(cause){broken.value=true;error.value=cause instanceof Error?cause.message:'原意图读取失败，未发送'}
  void refresh(true)
}
const storageChanged=(event:StorageEvent)=>{if(event.key===null||['opspilot_token','opspilot_user'].includes(event.key))resetAccount()}
onMounted(()=>{window.addEventListener('opspilot-auth-session-changed',resetAccount);window.addEventListener('storage',storageChanged);resetAccount()})
onBeforeUnmount(()=>{epoch++;window.removeEventListener('opspilot-auth-session-changed',resetAccount);window.removeEventListener('storage',storageChanged)})
defineExpose({refresh})
</script>
<template>
  <section class="content-panel member-panel" :aria-busy="busy">
    <div class="panel-heading"><div><h2>计划成员权限</h2><span>响应与管理独立 · 撤销成员不取消已提交责任</span></div><button class="secondary-button" :disabled="busy" @click="refresh()">刷新成员事实</button></div>
    <p class="member-boundary">现阶段保持认证后的全局历史读取，不是私有计划可见性隔离。旧计划一次回填来源标为 V38 迁移；新计划/账号不会自动加入。</p>
    <label class="member-plan">选择成员计划<select v-model="selected" aria-label="选择成员计划" @change="selectPlan"><option v-for="plan in plans" :key="plan.id" :value="plan.id">#{{ plan.id }} {{ plan.name }} · {{ plan.resourceName }}</option></select></label>
    <p v-if="error" class="member-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <p v-if="rows===null" class="empty-state">{{ busy ? '读取当前成员资格…' : '未取得当前成员事实' }}</p>
    <div v-else class="member-list">
      <article v-for="member in rows" :key="member.userId" class="member-card" :data-user-id="member.userId">
        <div class="member-person"><strong>{{ member.userName }} · #{{ member.userId }}</strong><span>{{ member.roleCode }} / {{ member.accountStatus }} · v{{ member.version }}</span></div>
        <div class="member-flags"><span :class="{allowed:member.effectiveResponse}">响应：{{ member.effectiveResponse ? '当前有效' : '无当前资格' }}</span><span :class="{allowed:member.effectiveManagement}">管理：{{ member.effectiveManagement ? '当前有效' : '无当前资格' }}</span></div>
        <small>成员{{ member.active ? '启用' : '已撤销' }} · 存储位 响应{{ member.canRespond ? '开' : '关' }}/管理{{ member.canManage ? '开' : '关' }} · {{ member.origin==='MIGRATED_GLOBAL_V38' ? 'V38 一次迁移回填' : '显式授权' }}</small>
        <button v-if="managing" class="secondary-button" :disabled="busy||Boolean(intent)||broken" @click="begin(member.userId)">调整 {{ member.userName }}</button>
      </article>
      <p v-if="!rows.length" class="empty-state">此计划没有成员关系；不使用全局用户清单冒充成员。</p>
    </div>
    <div v-if="managing" class="member-new"><label>明确目标账号 ID<input v-model="targetId" type="number" min="1" step="1" aria-label="明确目标账号 ID" :disabled="busy||Boolean(intent)||broken"></label><button class="secondary-button" :disabled="busy||Boolean(intent)||broken||!Number.isSafeInteger(Number(targetId))||Number(targetId)<1" @click="begin(Number(targetId))">捕获成员授权草稿</button><small>只捕获关系是否存在；账号当前资格由服务端确认，首次明确预期不存在。</small></div>
    <p v-else-if="rows!==null">当前只读；新变更须 ADMIN，或该计划具有当前管理权限的 OPS_MANAGER。原已提交操作的人工回执仍独立核验。</p>
    <div v-if="intent" class="member-intent">
      <h3>{{ frozen ? '冻结的原成员意图' : '成员授权草稿' }} · 计划 #{{ intent.scheduleId }} / 账号 #{{ intent.command.userId }}</h3>
      <p>捕获版本：{{ intent.command.expectedVersion===null ? 'null（明确预期不存在）' : `v${intent.command.expectedVersion}` }} · 原操作人 #{{ intent.actorId }}</p><code>{{ intent.command.operationKey }}</code>
      <fieldset :disabled="busy||frozen||broken"><label><input v-model="intent.command.active" type="checkbox" aria-label="启用成员关系">启用成员关系</label><label><input v-model="intent.command.canRespond" type="checkbox" aria-label="响应权限">响应权限（允许本人承担新责任）</label><label><input v-model="intent.command.canManage" type="checkbox" aria-label="管理权限">管理权限（不代表本人响应）</label><label class="member-reason">操作说明<textarea v-model="intent.command.reason" aria-label="成员操作说明" maxlength="500" rows="2" /></label></fieldset>
      <p v-if="foreign">当前选择不是原计划；请选择原计划后人工核对，不改变原键/版本。</p><p v-if="intent.blocked">原意图已锁定；恢复权限/刷新不解锁，不自动重基。</p>
      <button class="primary-button" :disabled="busy||broken||Boolean(foreign)||!qualified||intent.blocked" @click="submit">{{ frozen ? '手动求成员原回执' : '确认本人成员变更' }}</button>
    </div>
    <div v-if="intent||broken" class="member-discard"><button class="secondary-button" :disabled="busy" @click="confirmDiscard=true">明确放弃成员草稿</button><template v-if="confirmDiscard"><span>只清本标签页本人草稿，不撤销服务器结果。</span><button class="secondary-button" :disabled="busy" @click="discard">确认仅本地放弃成员草稿</button></template></div>
    <article v-if="result" class="member-receipt"><h3>原成员操作回执（不是当前权限）</h3><p>实际操作人 #{{ result.receipt.actorId }} · 计划 #{{ result.receipt.scheduleId }} / 目标 #{{ result.receipt.userId }} · 提交 v{{ result.receipt.resultVersion }}</p><code>{{ result.receipt.operationKey }}</code><p>原决定：成员{{ result.receipt.active ? '启用' : '撤销' }} / 响应{{ result.receipt.canRespond ? '开' : '关' }} / 管理{{ result.receipt.canManage ? '开' : '关' }} · {{ result.receipt.reason }}</p><p>回执返回时的成员行 v{{ result.current.version }}（可高于提交版本）；最新有效资格请查上方刷新结果。撤权不重写原回执、不取消已有班次。</p></article>
  </section>
</template>
<style scoped>
.member-panel,.member-intent,.member-receipt{scroll-margin-top:80px}
.member-panel{margin-bottom:20px}.member-boundary,.member-panel small,.member-person span{color:var(--text-muted);font-size:12px}.member-plan{display:flex;gap:12px;align-items:center;margin:16px 0}.member-plan select{max-width:100%;min-width:220px}.member-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.member-card,.member-intent,.member-receipt{border:1px solid var(--line);border-radius:8px;padding:14px}.member-person{display:flex;flex-direction:column;gap:6px}.member-flags{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0;font-size:12px}.member-flags span{padding:4px 8px;border-radius:5px;background:var(--surface-muted)}.member-flags .allowed{color:var(--success)}.member-card small{display:block;margin-bottom:12px}.member-new{display:flex;align-items:end;gap:12px;flex-wrap:wrap;margin-top:16px}.member-new label,.member-reason{display:flex;flex-direction:column;gap:6px}.member-new input{max-width:130px}.member-intent,.member-receipt{margin-top:16px;background:var(--surface-muted)}.member-panel code{display:block;overflow-wrap:anywhere;font-size:12px}.member-intent fieldset{border:0;padding:12px 0;display:flex;flex-wrap:wrap;gap:12px}.member-intent fieldset label{display:flex;align-items:center;gap:6px;font-size:13px}.member-intent fieldset .member-reason{align-items:stretch;flex-basis:100%}.member-discard{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:12px}.member-error{color:var(--danger)}.member-panel p{font-size:13px;line-height:1.6}
@media(max-width:760px){.member-list{grid-template-columns:1fr}.member-plan{align-items:stretch;flex-direction:column}.member-plan select{width:100%;min-width:0}.member-panel .panel-heading{align-items:start;gap:12px;flex-wrap:wrap}.member-panel button{white-space:normal}.member-intent h3,.member-receipt h3{font-size:14px}}
</style>
