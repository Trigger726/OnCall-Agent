import { api } from './api'
export interface SwapNotification { id:number; swapId:number; eventVersion:number; eventStatus:'PENDING'|'ACCEPTED'|'REJECTED'|'WITHDRAWN'; recipientId:number; recipientName:string; deliveryKey:string; status:'PENDING'|'CLAIMED'|'DELIVERED'|'FAILED'|'SKIPPED'; version:number; attempts:number; totalAttempts:number; nextAttemptAt:string; leaseUntil:string|null; lastHttpStatus:number|null; lastErrorCode:string|null; deliveredAt:string|null }
export interface SwapNotifications { enabled:boolean; databaseNow:string; deliveries:SwapNotification[] }
export interface NotificationRetry { schema:1; actorId:number; swapId:number; id:number; version:number; reason:string; blocked:boolean }
const validId=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>0
const validVersion=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>=0
const key=(actor:number)=>`opspilot_swap_notification_retry:v1:${actor}`
export const notificationState=(value:SwapNotification)=>({PENDING:'待技术投递',CLAIMED:'投递中 · 尚无回执',DELIVERED:'技术送达 · 不代表人工已读',FAILED:'技术投递失败',SKIPPED:'已跳过 · 不再投递'}[value.status])
export const canManageNotification=(actor:number|undefined,role:string|undefined,requester:number,target:number)=>
  ['ADMIN','OPS_MANAGER'].includes(role??'') || role==='ON_CALL'&&(actor===requester||actor===target)
export const canRetryNotification=(row:SwapNotification,enabled:boolean,actor:number|undefined,role:string|undefined,requester:number,target:number)=>
  enabled&&row.status==='FAILED'&&canManageNotification(actor,role,requester,target)
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
