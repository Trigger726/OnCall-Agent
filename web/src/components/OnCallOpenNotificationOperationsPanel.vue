<script setup lang="ts">
import {onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {auth} from '@/stores/auth'
import {readOpenOperations,openOperationsClock,type OpenOperationsFacts} from '@/services/onCallOpenNotificationOperations'
const facts=ref<OpenOperationsFacts|null>(null),busy=ref(false),error=ref('')
let epoch=0,controller:AbortController|undefined
const stored=()=>{try{return {token:localStorage.getItem('opspilot_token'),user:JSON.parse(localStorage.getItem('opspilot_user')??'null')}}catch{return {token:null,user:null}}}
const capture=()=>({epoch,actorId:auth.state.user?.id,roleCode:auth.state.user?.roleCode,token:stored().token})
const current=(i:ReturnType<typeof capture>)=>{const s=stored();return i.epoch===epoch&&i.actorId===auth.state.user?.id&&i.roleCode===auth.state.user?.roleCode&&i.roleCode==='ADMIN'&&s.user?.id===i.actorId&&s.user?.roleCode==='ADMIN'&&s.token===i.token}
function clear(message=''){epoch++;controller?.abort();facts.value=null;busy.value=false;error.value=message}
async function read(){clear();const identity=capture();if(!identity.actorId||!identity.token||!current(identity)){error.value='当前管理员身份与存储会话不一致；未读取全局事实';return}
  controller=new AbortController();busy.value=true
  try{const value=await readOpenOperations({actorId:identity.actorId,token:identity.token,roleCode:'ADMIN'},controller.signal);if(current(identity))facts.value=value}
  catch(cause){if(current(identity))error.value=`${cause instanceof Error?cause.message:'运行事实读取失败'}；不沿用旧事实，不显示零积压`}
  finally{if(identity.epoch===epoch){busy.value=false;if(!current(identity)){facts.value=null;error.value='会话在读取期间改变；旧运行事实已清除，请重新核对身份'}}}
}
const sessionChanged=()=>clear('会话已变化；旧运行事实已清除，须重新核对身份并手动读取')
const storageChanged=(e:StorageEvent)=>{if(e.key===null||['opspilot_token','opspilot_user'].includes(e.key))sessionChanged()}
watch(()=>[auth.state.user?.id,auth.state.user?.roleCode],()=>clear())
onMounted(()=>{window.addEventListener('storage',storageChanged);window.addEventListener('opspilot-auth-session-changed',sessionChanged)})
onBeforeUnmount(()=>{clear();window.removeEventListener('storage',storageChanged);window.removeEventListener('opspilot-auth-session-changed',sessionChanged)})
</script>
<template>
  <section class="content-panel open-operations-panel" :aria-busy="busy">
    <div class="panel-heading"><div><h2>开放通知运行观察</h2><span>仅管理员 · 全局数据库队列与本实例状态分别读取</span></div><button type="button" class="secondary-button" :disabled="busy" @click="read">读取 / 刷新运行状态</button></div>
    <p class="operations-note">人工只读，不自动轮询、发送、重投或认领。技术成功不等于本人已读或接班；零本地队列不代表数据库无积压。</p>
    <p v-if="error" role="alert" class="operations-error">{{error}}</p>
    <p v-else-if="busy" role="status">正在读取；旧运行事实已清除。</p>
    <p v-else-if="!facts" class="operations-empty">尚未读取运行事实；不以零值替代。点击上方按钮读取当前管理员允许的全局统计。</p>
    <div v-if="facts" class="operations-facts">
      <article class="operations-queue"><h3>持久数据库事实</h3><p>当前投递{{facts.queue.enabled?'已启用':'未启用'}}；配置关闭不清空历史事件。数据库时间 {{openOperationsClock(facts.queue.databaseNow)}}</p>
        <dl class="operations-counts"><div><dt>持久事件总数</dt><dd data-queue="total">{{facts.queue.total}}</dd></div><div><dt>等待尝试（全部）</dt><dd data-queue="pending">{{facts.queue.pending}}</dd></div><div><dt>已到尝试时间</dt><dd data-queue="pendingDue">{{facts.queue.pendingDue}}</dd></div><div><dt>发送租约 / 其中过期</dt><dd data-queue="claimed">{{facts.queue.claimed}} / {{facts.queue.expiredLeases}}</dd></div><div><dt>渠道技术成功 · 非接班</dt><dd data-queue="delivered">{{facts.queue.delivered}}</dd></div><div><dt>失败终态</dt><dd data-queue="failed">{{facts.queue.failed}}</dd></div><div><dt>跳过终态</dt><dd data-queue="skipped">{{facts.queue.skipped}}</dd></div></dl>
        <p>最早排队创建时间：{{facts.queue.oldestPendingCreatedAt?openOperationsClock(facts.queue.oldestPendingCreatedAt):'无排队事件'}}。到尝试时间不是送达承诺；过期租约也不是人已接班。</p>
      </article>
      <article class="operations-worker"><h3>本实例启动以来 · 非集群汇总</h3><dl class="operations-counts"><div><dt>活跃 worker（近似）</dt><dd data-worker="activeWorkers">{{facts.worker.activeWorkers}}</dd></div><div><dt>本地等待 tick</dt><dd data-worker="queuedTicks">{{facts.worker.queuedTicks}}</dd></div><div><dt>忙拒绝 tick（累计）</dt><dd data-worker="busyRejectedTicks">{{facts.worker.busyRejectedTicks}}</dd></div><div><dt>批次异常（累计）</dt><dd data-worker="failedBatches">{{facts.worker.failedBatches}}</dd></div></dl>
        <p>{{facts.worker.enabled?(facts.worker.shutdown?'executor已请求关闭':'executor已配置'):'executor未启用'}}；本地计数重启清零。忙拒绝不是丢失事件，累计次数不证明当前饱和；数据库与本地状态不是原子快照。</p>
      </article>
    </div>
  </section>
</template>
<style scoped>
.open-operations-panel {scroll-margin-top:84px;}.panel-heading {display:flex;justify-content:space-between;gap:16px;align-items:center;flex-wrap:wrap;}.panel-heading h2 {font-size:16px;margin:0 0 5px;}.panel-heading span,.operations-note,.operations-empty {color:var(--text-muted);font-size:13px;line-height:1.7;}
.operations-error {color:#b42318;overflow-wrap:anywhere;}.operations-facts article {border:1px solid var(--line);border-radius:8px;padding:14px;margin-top:12px;}.operations-facts h3 {font-size:14px;margin:0;}.operations-facts p {font-size:13px;color:var(--text-muted);line-height:1.7;overflow-wrap:anywhere;}
.operations-counts {display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:16px 0;}.operations-counts div {padding:10px;border-radius:6px;background:var(--surface);}.operations-counts dt {font-size:12px;color:var(--text-muted);line-height:1.6;}.operations-counts dd {margin:6px 0 0;font-size:22px;font-weight:600;overflow-wrap:anywhere;}
@media(max-width:640px){.panel-heading button {width:100%;}.operations-counts {grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;}.operations-counts div {padding:8px;}.operations-facts article {padding:12px;}}
</style>
