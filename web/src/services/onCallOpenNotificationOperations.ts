import {api,RequestError,type CapturedSession} from './api'
import {databaseTime,notificationClock} from './onCallSwapNotifications'
export interface OpenQueueFacts {scope:'SHARED_DATABASE_AT_STATEMENT';enabled:boolean;databaseNow:string;total:number;pending:number;pendingDue:number;claimed:number;expiredLeases:number;delivered:number;failed:number;skipped:number;oldestPendingCreatedAt:string|null}
export interface OpenWorkerFacts {scope:'THIS_PROCESS_SINCE_START';enabled:boolean;activeWorkers:number;queuedTicks:0;busyRejectedTicks:number;failedBatches:number;shutdown:boolean}
export interface OpenOperationsFacts {queue:OpenQueueFacts;worker:OpenWorkerFacts}
const count=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0
const clock=(v:unknown)=>typeof v==='string'&&Boolean(databaseTime(v))
const queueFields=['scope','enabled','databaseNow','total','pending','pendingDue','claimed','expiredLeases','delivered','failed','skipped','oldestPendingCreatedAt'] as const
const workerFields=['scope','enabled','activeWorkers','queuedTicks','busyRejectedTicks','failedBatches','shutdown'] as const
export function parseOpenOperationsFacts(value:unknown):OpenOperationsFacts {
  const invalid=()=>new RequestError('通知运行事实异常；不显示旧事实或零积压','INVALID_OPEN_NOTIFICATION_OPERATIONS',502)
  if(!value||typeof value!=='object')throw invalid()
  const {queue:q,worker:w}=value as OpenOperationsFacts
  if(!q||!w||q.scope!=='SHARED_DATABASE_AT_STATEMENT'||w.scope!=='THIS_PROCESS_SINCE_START'||typeof q.enabled!=='boolean'||typeof w.enabled!=='boolean'||typeof w.shutdown!=='boolean'||q.enabled!==w.enabled
    ||![q.total,q.pending,q.pendingDue,q.claimed,q.expiredLeases,q.delivered,q.failed,q.skipped,w.activeWorkers,w.queuedTicks,w.busyRejectedTicks,w.failedBatches].every(count)
    ||q.pending+q.claimed+q.delivered+q.failed+q.skipped!==q.total||q.pendingDue>q.pending||q.expiredLeases>q.claimed||w.activeWorkers>1||w.queuedTicks!==0
    ||!clock(q.databaseNow)||(q.pending===0?q.oldestPendingCreatedAt!==null:!clock(q.oldestPendingCreatedAt))
    ||(!w.enabled&&(w.activeWorkers!==0||w.busyRejectedTicks!==0||w.failedBatches!==0||w.shutdown)))throw invalid()
  return {queue:Object.fromEntries(queueFields.map(f=>[f,q[f]])) as unknown as OpenQueueFacts,worker:Object.fromEntries(workerFields.map(f=>[f,w[f]])) as unknown as OpenWorkerFacts}
}
export async function readOpenOperations(session:CapturedSession&{roleCode:string},signal?:AbortSignal){
  if(session.roleCode!=='ADMIN'||!Number.isSafeInteger(session.actorId)||session.actorId<1||!session.token)throw new RequestError('须核对当前管理员身份；未读取全局运行状态','INVALID_OPEN_OPERATIONS_SESSION',403)
  return parseOpenOperationsFacts(await api<unknown>('/on-call/open-notification-operations',{signal},session))
}
export const openOperationsClock=notificationClock
