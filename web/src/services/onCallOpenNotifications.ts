import { api,RequestError,type CapturedSession } from './api'
import { databaseTime,notificationClock } from './onCallSwapNotifications'

export interface OpenDelivery {
  id:number;handoffId:number;eventVersion:0;recipientId:number;deliveryKey:string
  status:'PENDING'|'CLAIMED'|'DELIVERED'|'FAILED'|'SKIPPED';version:number;attempts:number
  nextAttemptAt:string;leaseUntil:string|null;lastHttpStatus:number|null;lastErrorCode:string|null;deliveredAt:string|null
}
export interface OpenNotificationFacts {adapterImplemented:boolean;enabled:boolean;databaseNow:string;deliveries:OpenDelivery[]}
const id=(value:unknown)=>Number.isSafeInteger(value)&&Number(value)>0
const integer=(value:unknown)=>Number.isInteger(value)&&Number(value)>=0&&Number(value)<=2147483647
const uuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const clock=(value:unknown)=>typeof value==='string'&&Boolean(databaseTime(value))
const nullableClock=(value:unknown)=>value===null||clock(value)
const fields=['id','handoffId','eventVersion','recipientId','deliveryKey','status','version','attempts','nextAttemptAt','leaseUntil','lastHttpStatus','lastErrorCode','deliveredAt'] as const
export function parseOpenNotificationFacts(value:unknown,handoffId:number):OpenNotificationFacts {
  const invalid=()=>new RequestError('通知技术事实异常；不使用旧回执','INVALID_OPEN_NOTIFICATION_FACTS',502)
  if(!id(handoffId)||!value||typeof value!=='object')throw invalid()
  const facts=value as OpenNotificationFacts
  if(typeof facts.adapterImplemented!=='boolean'||typeof facts.enabled!=='boolean'||!facts.adapterImplemented&&facts.enabled||!clock(facts.databaseNow)||!Array.isArray(facts.deliveries)||facts.deliveries.length>500)throw invalid()
  const ids=new Set<number>(),recipients=new Set<number>(),keys=new Set<string>()
  const deliveries=facts.deliveries.map(row=>{
    if(!row||![row.id,row.recipientId].every(id)||row.handoffId!==handoffId||row.eventVersion!==0||!uuid.test(row.deliveryKey??'')||!integer(row.version)||!integer(row.attempts)||row.attempts>10
      ||!['PENDING','CLAIMED','DELIVERED','FAILED','SKIPPED'].includes(row.status)||!clock(row.nextAttemptAt)||!nullableClock(row.leaseUntil)||!nullableClock(row.deliveredAt)
      ||!(row.lastHttpStatus===null||Number.isInteger(row.lastHttpStatus)&&row.lastHttpStatus>=100&&row.lastHttpStatus<=999)
      ||!(row.lastErrorCode===null||typeof row.lastErrorCode==='string'&&/^[A-Z][A-Z0-9_]{0,63}$/.test(row.lastErrorCode))
      ||ids.has(row.id)||recipients.has(row.recipientId)||keys.has(row.deliveryKey))throw invalid()
    if(row.status==='CLAIMED'?(row.leaseUntil===null||row.attempts===0):row.leaseUntil!==null)throw invalid()
    if(row.status==='DELIVERED'?(row.deliveredAt===null||row.attempts===0||row.lastHttpStatus===null||row.lastHttpStatus<200||row.lastHttpStatus>=300||row.lastErrorCode!==null):row.deliveredAt!==null)throw invalid()
    ids.add(row.id);recipients.add(row.recipientId);keys.add(row.deliveryKey)
    // Only technical fields survive parsing; provider payload, URLs and lease owners are never retained.
    return Object.fromEntries(fields.map(field=>[field,row[field]])) as unknown as OpenDelivery
  })
  return {adapterImplemented:facts.adapterImplemented,enabled:facts.enabled,databaseNow:facts.databaseNow,deliveries}
}
export async function readOpenNotifications(handoffId:number,session:CapturedSession,signal?:AbortSignal){
  if(!id(handoffId)||!id(session.actorId)||!session.token)throw new RequestError('须核对当前请求与登录身份','INVALID_OPEN_NOTIFICATION_READ',400)
  return parseOpenNotificationFacts(await api<unknown>(`/on-call/open-handoffs/${handoffId}/notifications`,{signal},session),handoffId)
}
export const openNotificationClock=notificationClock
export function openDeliveryLabel(row:OpenDelivery){return ({PENDING:row.attempts===0?'排队 · 尚未尝试':'等待自动重试',CLAIMED:'已领取发送租约',DELIVERED:'渠道技术成功 · 非接班',FAILED:'失败 · 自动重试已停止',SKIPPED:'发送条件不满足 · 已跳过'})[row.status]}
export function openDeliveryMeaning(row:OpenDelivery){return ({PENDING:'仅为数据库排队状态，未证明渠道收到。再次尝试时间不是送达承诺。',CLAIMED:'租约已领取，技术结果尚未确认；页面不按浏览器时钟判断租约是否过期。',DELIVERED:'HTTP 2xx 仅是网关技术回执，不证明本人已读或自愿接班。',FAILED:'已停止自动重试；当前没有手工重投入口，不换新键再次发送。',SKIPPED:'原候选当前不满足发送条件；不改投后来授权的新人。'})[row.status]}
