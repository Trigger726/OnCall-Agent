<script setup lang="ts">
import { computed,onMounted,onBeforeUnmount,ref } from 'vue'
import { auth } from '@/stores/auth'
import { RequestError } from '@/services/api'
import { canManagePair,clearPairIntent,getSwapPair,pairClock,pairCommandError,pairRevocationEligibility,readPairIntent,revokeSwapPair,sameCapturedPair,savePairIntent,type PairRevocationIntent,type SwapPair } from '@/services/onCallSwapRevocations'
const props=defineProps<{swapId:number}>(),emit=defineEmits<{changed:[]}>()
const info=ref<SwapPair|null>(null),intent=ref<PairRevocationIntent|null>(null),busy=ref(false),frozen=ref(false),broken=ref(false),confirmDiscard=ref(false),error=ref(''),message=ref('')
const manager=computed(()=>canManagePair(auth.state.user?.id,auth.state.user?.roleCode))
const eligibility=computed(()=>pairRevocationEligibility(info.value,props.swapId,auth.state.user?.id,auth.state.user?.roleCode))
const other=computed(()=>intent.value&&intent.value.swapId!==props.swapId)
const newAllowed=computed(()=>Boolean(intent.value&&!eligibility.value&&sameCapturedPair(info.value,intent.value)))
let epoch=0
const capture=()=>({epoch,actor:auth.state.user?.id,token:localStorage.getItem('opspilot_token')})
const current=(identity:ReturnType<typeof capture>)=>identity.epoch===epoch&&identity.actor===auth.state.user?.id&&identity.token===localStorage.getItem('opspilot_token')
async function refresh(keepError=false){if(busy.value)return;const identity=capture();busy.value=true;info.value=null;if(!keepError)error.value=''
  try{const facts=await getSwapPair(props.swapId);if(current(identity))info.value=facts}
  catch(cause){if(current(identity))error.value=cause instanceof Error?cause.message:'成对事实读取失败，旧事实已清空'}
  finally{if(current(identity))busy.value=false}}
function choose(){if(busy.value||intent.value||broken.value||eligibility.value||!auth.state.user)return
  const facts=info.value!;intent.value={schema:1,actorId:auth.state.user.id,swapId:props.swapId,firstReplacementId:facts.firstReplacement!.id,secondReplacementId:facts.secondReplacement!.id,
    command:{swapVersion:facts.accepted.version,firstReplacementVersion:facts.firstReplacement!.version,secondReplacementVersion:facts.secondReplacement!.version,operationKey:crypto.randomUUID(),reason:''},blocked:false}
  frozen.value=false;error.value='';message.value=''}
async function submit(){if(busy.value||broken.value||other.value||!intent.value||intent.value.blocked||!manager.value||intent.value.actorId!==auth.state.user?.id)return
  const captured=intent.value,identity=capture();error.value='';message.value=''
  if(!frozen.value){const invalid=pairCommandError(captured.command);if(invalid){error.value=invalid;return}captured.command.reason=captured.command.reason.trim();busy.value=true;info.value=null
    try{const facts=await getSwapPair(props.swapId);if(!current(identity))return;info.value=facts
      if(!newAllowed.value){error.value='覆盖已变化、已结束或事实异常，未发送新撤销；不更新原ID/三个版本，请核对后明确放弃';return}}
    catch(cause){if(current(identity))error.value=`${cause instanceof Error?cause.message:'成对核对失败'}，未发送新撤销；不使用旧事实`;return}
    finally{if(current(identity))busy.value=false}
    if(!current(identity))return
    try{savePairIntent(sessionStorage,captured)}catch{error.value='无法保存成对撤销意图，尚未发送；请检查浏览器存储权限';return}frozen.value=true}
  if(!current(identity))return;busy.value=true;info.value=null
  try{const facts=await revokeSwapPair(captured);if(!current(identity))return
    info.value=facts;message.value='原成对撤销已回执；历史接受不变，两条取消和独立回执一起提交。实际责任请另查区间coverage'
    try{clearPairIntent(sessionStorage,captured.actorId);intent.value=null;frozen.value=false}catch{message.value+='；本地意图未清除，可核对后明确放弃'}
    emit('changed')
  }catch(cause){if(!current(identity))return
    if(cause instanceof RequestError&&[403,409].includes(cause.status)){captured.blocked=true;try{savePairIntent(sessionStorage,captured)}catch{error.value='原意图锁定未能保存；刷新后仍须核对，不能保证浏览器持久保存。'}}
    error.value+=`${cause instanceof TypeError?'响应中断，服务器可能已提交':cause instanceof Error?cause.message:'结果未确认'}。原键/三个版本/说明已冻结，刷新不自动提交${captured.blocked?'；原意图已锁定，请核对后明确放弃':'，仅手动求原回执'}`
  }finally{if(current(identity)){busy.value=false;await refresh(true)}}}
async function discard(){if(busy.value)return
  try{if(auth.state.user)clearPairIntent(sessionStorage,auth.state.user.id)}catch{error.value='无法清除成对草稿，尚未放弃';return}
  intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;message.value='仅放弃本账号本标签页草稿，不撤销服务器结果、不恢复覆盖';await refresh()}
function resetAccount(){epoch++;busy.value=false;info.value=null;intent.value=null;frozen.value=false;broken.value=false;confirmDiscard.value=false;error.value='';message.value=''
  if(!auth.state.user)return
  try{intent.value=readPairIntent(sessionStorage,auth.state.user.id);frozen.value=Boolean(intent.value);if(intent.value)message.value='已恢复原成对撤销键/三个版本/说明，刷新不自动发送'}catch(cause){broken.value=true;error.value=cause instanceof Error?cause.message:'成对草稿读取失败'}
  void refresh()}
onMounted(()=>{window.addEventListener('opspilot-auth-session-changed',resetAccount);resetAccount()})
onBeforeUnmount(()=>{epoch++;window.removeEventListener('opspilot-auth-session-changed',resetAccount)})
</script>
<template>
  <section class="swap-revocation-panel" :aria-busy="busy" :data-pair-swap-id="swapId">
    <div class="pair-heading"><h3>成对覆盖与撤销回执 #{{swapId}}</h3><button class="secondary-button" :disabled="busy" @click="refresh()">刷新成对事实</button></div>
    <p class="pair-note">单SQL快照核对两覆盖与独立回执；历史接受、当前覆盖、管理撤销三层分开。撤销不修改过去责任、不撤回已发通知，也不保证原负责人仍可用。</p>
    <p v-if="error" class="pair-error" role="alert">{{error}}</p><p v-if="message" role="status">{{message}}</p>
    <template v-if="info"><p class="pair-history">历史决定：{{info.accepted.status}} · 原换班v{{info.accepted.version}} · 数据库快照 {{pairClock(info.databaseNow)}}</p>
      <div class="pair-coverages"><article v-for="(row,index) in [info.firstReplacement,info.secondReplacement]" :key="index" :data-pair-coverage="index+1"><h4>{{index?'第二段当前覆盖':'第一段当前覆盖'}}</h4><template v-if="row"><strong>#{{row.id}} · v{{row.version}} · {{row.cancelledAt===null?'未取消覆盖 · 不等于此刻生效':'已取消或取消事实异常'}}</strong><p>计划 #{{row.scheduleId}} · 当前覆盖负责人 #{{row.userId}}</p><p>{{pairClock(row.startsAt)}} → {{pairClock(row.endsAt)}}</p><p v-if="row.cancelledAt">取消记录 {{pairClock(row.cancelledAt)}} · {{row.cancellationReason}}</p></template><p v-else>尚未取得本次接受生成的覆盖，不假定责任已交换</p></article></div>
      <article v-if="info.revocation" class="pair-receipt"><h4>独立成对撤销回执</h4><p>管理账号 #{{info.revocation.actorId}} · {{pairClock(info.revocation.revokedAt)}}</p><p>原键 {{info.revocation.operationKey}} · 原三版本 {{info.revocation.swapVersion}} / {{info.revocation.firstReplacementVersion}} / {{info.revocation.secondReplacementVersion}}</p><p>{{info.revocation.reason}}</p><p class="pair-note">此回执不重写历史ACCEPTED，不承诺原普通班次和人员仍可承担责任；上方区间coverage独立查询可能显示空档。</p></article>
      <p v-else class="pair-note">尚无独立成对撤销回执；任一单段取消不伪造成成对操作。</p>
    </template><p v-else class="pair-note">成对事实尚未取得或读取失败，不显示旧覆盖/撤销回执。</p>
    <p v-if="eligibility" class="pair-note">{{eligibility}}</p>
    <button v-if="manager&&!eligibility" class="secondary-button" :disabled="busy||Boolean(intent)||broken" @click="choose">核对后成对撤销覆盖</button>
    <p v-if="other" class="pair-error">原成对草稿属于换班 #{{intent?.swapId}}，请打开原详情核对；不改用当前覆盖。</p>
    <form v-if="intent&&!other" class="pair-intent" @submit.prevent="submit"><h4>确认管理成对撤销 · 两条一起提交</h4><p>捕获覆盖 #{{intent.firstReplacementId}} / #{{intent.secondReplacementId}} · 原三版本 {{intent.command.swapVersion}} / {{intent.command.firstReplacementVersion}} / {{intent.command.secondReplacementVersion}}</p>
      <label>成对撤销说明<textarea v-model="intent.command.reason" required maxlength="500" rows="2" :disabled="busy||frozen||!manager"/></label><p class="pair-note">原撤销键 {{intent.command.operationKey}}。首次提交前重新GET并保存原意图；任一覆盖改变则不撤销另一条。</p>
      <p v-if="frozen" class="pair-note">原内容已冻结：当前取消或结束也只手动求原回执，不换新版本/键，不自动POST。</p><p v-else-if="!newAllowed" class="pair-error">未发送草稿与当前成对事实不符；请核对后明确放弃，不自动重基。</p>
      <div class="pair-heading"><button class="primary-button" :disabled="busy||intent.blocked||!manager||(!frozen&&!newAllowed)">{{frozen?'回执原成对撤销':'确认成对撤销'}}</button><button type="button" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">放弃成对撤销草稿</button></div>
      <p v-if="intent.blocked" class="pair-error">原意图已锁定，不自动换版本；先核对服务器事实。</p>
    </form>
    <button v-if="broken||other" class="secondary-button" :disabled="busy" @click="confirmDiscard=true">核对后放弃原成对草稿</button>
    <div v-if="confirmDiscard" class="pair-intent" role="group" aria-label="明确放弃成对草稿"><p>仅清本账号本标签页意图，不恢复已取消覆盖、不撤回服务器成对结果。</p><div class="pair-heading"><button class="secondary-button" :disabled="busy" @click="discard">确认仅放弃成对草稿</button><button class="secondary-button" @click="confirmDiscard=false">保留成对原意图</button></div></div>
  </section>
</template>
<style scoped>
.swap-revocation-panel {border-top:1px solid var(--line);margin-top:16px;padding-top:16px;scroll-margin-top:84px;}
.pair-heading {display:flex;gap:8px;align-items:center;flex-wrap:wrap;}
h3,h4 {font-size:14px;margin:0 0 8px;}
.pair-note,.pair-coverages p {font-size:12px;color:var(--text-muted);line-height:1.7;overflow-wrap:anywhere;}
.pair-error {color:#b42318;overflow-wrap:anywhere;}
.pair-history,.pair-receipt p,.pair-intent p {font-size:12px;line-height:1.7;overflow-wrap:anywhere;}
.pair-coverages {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:12px 0;}
.pair-coverages article,.pair-intent,.pair-receipt {border:1px solid var(--line);border-radius:8px;padding:12px;min-width:0;font-size:12px;overflow-wrap:anywhere;}
.pair-intent,.pair-receipt {margin-top:12px;scroll-margin-top:84px;}
label {display:flex;flex-direction:column;gap:6px;}
textarea {width:100%;box-sizing:border-box;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:var(--surface);color:inherit;font:inherit;}
@media(max-width:640px){.pair-coverages {grid-template-columns:1fr;}}
</style>
