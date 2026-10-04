import { api } from './api'
export interface SwapNotification { id:number; swapId:number; eventVersion:number; eventStatus:'PENDING'|'ACCEPTED'|'REJECTED'|'WITHDRAWN'; recipientId:number; recipientName:string; deliveryKey:string; status:'PENDING'|'CLAIMED'|'DELIVERED'|'FAILED'|'SKIPPED'; version:number; attempts:number; totalAttempts:number; nextAttemptAt:string; leaseUntil:string|null; lastHttpStatus:number|null; lastErrorCode:string|null; deliveredAt:string|null; payloadExpiresAt:string; payloadErasedAt:string|null }
export interface SwapNotifications { enabled:boolean; retentionEnabled:boolean; databaseNow:string; deliveries:SwapNotification[] }
export interface NotificationRetry { schema:1; actorId:number; swapId:number; id:number; version:number; reason:string; blocked:boolean }
const validId=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>0
const validVersion=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>=0
const key=(actor:number)=>`opspilot_swap_notification_retry:v1:${actor}`
export const notificationState=(value:SwapNotification)=>({PENDING:'待技术投递',CLAIMED:'投递中 · 尚无回执',DELIVERED:'技术送达 · 不代表人工已读',FAILED:'技术投递失败',SKIPPED:'已跳过 · 不再投递'}[value.status])
export const canManageNotification=(actor:number|undefined,role:string|undefined,requester:number,target:number)=>
  ['ADMIN','OPS_MANAGER'].includes(role??'') || role==='ON_CALL'&&(actor===requester||actor===target)
// Compare database-session local timestamps without interpreting them in a browser timezone.
export function databaseTime(value:unknown){
  if(typeof value!=='string')return null
  const m=value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/);if(!m)return null
  const year=Number(m[1]),month=Number(m[2]),day=Number(m[3]),leap=year%4===0&&(year%100!==0||year%400===0)
  if(year<1||month<1||month>12||day<1||day>[31,leap?29:28,31,30,31,30,31,31,30,31,30,31][month-1]!
    ||Number(m[4])>23||Number(m[5])>59||Number(m[6]??0)>59)return null
  return value.slice(0,16)+':'+(m[6]??'00')+'.'+(m[7]??'').padEnd(9,'0')
}
export function notificationPayloadState(row:SwapNotification,info:Pick<SwapNotifications,'retentionEnabled'|'databaseNow'>|null){
  if(row.payloadErasedAt!==null)return databaseTime(row.payloadErasedAt)?'ERASED':'UNKNOWN'
  const expires=databaseTime(row.payloadExpiresAt),now=databaseTime(info?.databaseNow)
  if(row.payloadErasedAt!==null||!expires||!now||typeof info?.retentionEnabled!=='boolean')return 'UNKNOWN'
  if(!info.retentionEnabled)return 'DISABLED'
  return expires<=now?'EXPIRED':'RETAINED'
}
export const notificationClock=(value:unknown)=>databaseTime(value)?String(value).replace('T',' '):'未取得有效数据库时间'
export const notificationPayloadLabel=(row:SwapNotification,info:Pick<SwapNotifications,'retentionEnabled'|'databaseNow'>|null)=>({
  ERASED:'冻结载荷已清理 · 不可新投递；技术回执与原重试指纹保留',
  EXPIRED:'已到保留期限 · 禁止新重试；有效在途租约可能暂缓清理',
  DISABLED:'载荷清理未启用 · 冻结期限仅记录，不按此停止投递',
  RETAINED:'数据库快照时仍在保留期 · 不保证后续操作时未到期',
  UNKNOWN:'保留事实未取得或异常 · 先刷新核对，不能新重试'
}[notificationPayloadState(row,info)])
export const canRetryNotification=(row:SwapNotification,enabled:boolean,actor:number|undefined,role:string|undefined,requester:number,target:number,info:Pick<SwapNotifications,'retentionEnabled'|'databaseNow'>|null)=>
  enabled&&row.status==='FAILED'&&['RETAINED','DISABLED'].includes(notificationPayloadState(row,info))&&canManageNotification(actor,role,requester,target)
export function notificationRetryError(value:NotificationRetry){return !validId(value.actorId)||!validId(value.swapId)||!validId(value.id)||!validVersion(value.version)
  ||typeof value.reason!=='string'||!value.reason.trim()||value.reason.length>500?'请保留有效原通知版本与1–500字说明':''}
export const saveNotificationRetry=(storage:Pick<Storage,'setItem'>,value:NotificationRetry)=>storage.setItem(key(value.actorId),JSON.stringify(value))
export function readNotificationRetry(storage:Pick<Storage,'getItem'>,actor:number):NotificationRetry|null {
  const raw=storage.getItem(key(actor));if(!raw)return null
  const value=JSON.parse(raw) as NotificationRetry
  if(!value||value.schema!==1||value.actorId!==actor||typeof value.blocked!=='boolean'||notificationRetryError(value))throw new Error('通知重试草稿损坏，未发送；请核对投递状态后明确放弃')
  return value
}
export const clearNotificationRetry=(storage:Pick<Storage,'removeItem'>,actor:number)=>storage.removeItem(key(actor))
export const getSwapNotifications=(id:number)=>api<SwapNotifications>(`/on-call/swaps/${id}/notifications`)
export const retrySwapNotification=(value:NotificationRetry)=>api<SwapNotification>(`/on-call/swaps/${value.swapId}/notifications/${value.id}/retry`,{
  method:'POST',body:JSON.stringify({version:value.version,reason:value.reason})})
