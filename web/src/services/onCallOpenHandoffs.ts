import { api } from './api'
import type { HandoffSource } from './onCallHandoffs'
import { databaseTime, notificationClock } from './onCallSwapNotifications'

export interface OpenHandoff {
  id:number; scheduleId:number; sourceShiftId:number; sourceVersion:number; requesterId:number; requestKey:string
  startsAt:string; endsAt:string; reason:string; status:'OPEN'|'CLAIMED'|'WITHDRAWN'; version:number
  createdAt:string; closedAt:string|null; claimedBy:number|null; replacementShiftId:number|null
}
export interface OpenCoverage {
  databaseNow:string; request:OpenHandoff
  replacement:{id:number;scheduleId:number;userId:number;version:number;startsAt:string;endsAt:string;cancelledAt:string|null;cancellationReason:string|null}|null
  operation:{handoffId:number;actorId:number;operationKey:string;operation:'CLAIM'|'WITHDRAW';capturedVersion:number;reason:string;committedAt:string}|null
}
export interface OpenRoster { databaseNow:string; shifts:HandoffSource[]; schedules:{id:number}[]; users:{id:number;roleCode:string}[]; truncated:boolean }
export interface PublishCommand { sourceShiftId:number; sourceVersion:number; requestKey:string; startsAt:string; endsAt:string; reason:string }
export interface OpenOperation { version:number; operationKey:string; reason:string }
export type OpenIntent = {schema:1;actorId:number;blocked:boolean} & (
  {action:'PUBLISH';source:HandoffSource;command:PublishCommand} |
  {action:'CLAIM'|'WITHDRAW';row:OpenHandoff;command:OpenOperation})
const id=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>0
const version=(v:unknown)=>Number.isInteger(v)&&Number(v)>=0&&Number(v)<=2147483647
const roles=['ADMIN','OPS_MANAGER','ON_CALL']
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const reason=(v:unknown)=>typeof v==='string'&&Boolean(v.trim())&&v.length<=500
const storageKey=(actor:number)=>`opspilot_open_handoff_intent:v1:${actor}`
export const openClock=notificationClock
export const openQualified=(actor:number|undefined,role:string|undefined)=>id(actor)&&roles.includes(role??'')
// Inputs and database-session timestamps are not converted to the browser timezone.
export function openTime(value:string){const time=databaseTime(value);return time?.endsWith('.000000000')?time.slice(0,19):''}
function validSource(s:HandoffSource){return s&&[s.id,s.scheduleId,s.userId].every(id)&&version(s.version)&&s.override===false&&s.cancelledAt===null
  &&Boolean(databaseTime(s.startsAt)&&databaseTime(s.endsAt)&&databaseTime(s.startsAt)!<databaseTime(s.endsAt)!)}
function validRow(r:OpenHandoff){return r&&[r.id,r.scheduleId,r.sourceShiftId,r.requesterId].every(id)&&version(r.version)&&version(r.sourceVersion)
  &&['OPEN','CLAIMED','WITHDRAWN'].includes(r.status)&&Boolean(openTime(r.startsAt)&&openTime(r.endsAt)&&openTime(r.startsAt)<openTime(r.endsAt))}
export function canPublishOpen(source:HandoffSource,actor:number|undefined,role:string|undefined,now:string){return openQualified(actor,role)&&validSource(source)
  &&source.userId===actor&&Boolean(databaseTime(now)&&databaseTime(source.endsAt)!>databaseTime(now)!)}
export function openActionError(info:OpenCoverage|null,action:'CLAIM'|'WITHDRAW',actor:number|undefined,role:string|undefined){
  if(!openQualified(actor,role))return '当前账号只读；发布/认领/撤回须当前活跃运维资格'
  const row=info?.request,now=databaseTime(info?.databaseNow)
  if(!row||!validRow(row)||!now)return '未取得有效请求与数据库时间，不能创建新责任'
  if(row.status!=='OPEN'||row.version===2147483647||info!.operation!==null||info!.replacement!==null)return '请求已关闭或事实异常；历史认领不是当前责任'
  if(action==='WITHDRAW')return row.requesterId===actor?'':'只有发布本人可以撤回'
  if(row.requesterId===actor)return '不能认领自己发布的请求，管理角色也不能代他人同意'
  return databaseTime(row.endsAt)!<=now?'请求时段已结束，不能新认领':''
}
export function openIntentError(value:OpenIntent){
  if(!value||value.schema!==1||!id(value.actorId)||typeof value.blocked!=='boolean'||!value.command||!reason(value.command.reason))return '须保留本人身份与1–500字原说明'
  if(value.action==='PUBLISH'){
    const c=value.command,s=value.source
    if(!validSource(s)||s.userId!==value.actorId||c.sourceShiftId!==s.id||!version(c.sourceVersion)||c.sourceVersion!==s.version)return '源班次身份/原版本无效'
    if(!uuid.test(c.requestKey??'')||!openTime(c.startsAt)||!openTime(c.endsAt)||c.startsAt!==openTime(c.startsAt)||c.endsAt!==openTime(c.endsAt)
      ||c.startsAt>=c.endsAt||databaseTime(c.startsAt)!<databaseTime(s.startsAt)!||databaseTime(c.endsAt)!>databaseTime(s.endsAt)!)return '须为本人班次内整秒子时段和规范小写UUID'
  }else if(value.action==='CLAIM'||value.action==='WITHDRAW'){
    const r=value.row,c=value.command
    if(!validRow(r)||r.status!=='OPEN'||!version(c.version)||c.version!==r.version||!uuid.test(c.operationKey??'')
      ||(value.action==='CLAIM'?r.requesterId===value.actorId:r.requesterId!==value.actorId))return '须保留原请求、明确版本、本人决定与规范小写UUID'
  }else return '未知开放接班操作'
  return ''
}
export function openPreflightError(intent:OpenIntent,info:OpenCoverage|null,roster:OpenRoster|null,role:string|undefined){
  if(openIntentError(intent))return openIntentError(intent)
  if(!openQualified(intent.actorId,role))return '当前账号只读'
  if(intent.action!=='PUBLISH'){
    const invalid=openActionError(info,intent.action,intent.actorId,role);if(invalid)return invalid
    if(info!.request.id!==intent.row.id||info!.request.version!==intent.command.version)return '请求身份或版本已变化；不自动重基'
    if(intent.action==='WITHDRAW')return '' // Owner may close an expired or inactive request.
  }
  const captured=intent.action==='PUBLISH'?intent.source:intent.row
  const sourceId=intent.action==='PUBLISH'?intent.command.sourceShiftId:intent.row.sourceShiftId
  const sourceVersion=intent.action==='PUBLISH'?intent.command.sourceVersion:intent.row.sourceVersion
  const owner=intent.action==='PUBLISH'?intent.actorId:intent.row.requesterId
  const from=databaseTime(intent.action==='PUBLISH'?intent.command.startsAt:intent.row.startsAt)
  const end=databaseTime(intent.action==='PUBLISH'?intent.command.endsAt:intent.row.endsAt)
  const now=databaseTime(roster?.databaseNow),s=roster?.shifts.find(s=>s.id===sourceId)
  if(!now||!s||!validSource(s)||s.scheduleId!==captured.scheduleId||s.userId!==owner||s.version!==sourceVersion)return '最新源班次未取得、已取消或原版本改变；不使用旧事实'
  if(!roster!.schedules.some(s=>s.id===captured.scheduleId)||![owner,intent.actorId].every(actor=>roster!.users.some(u=>u.id===actor&&roles.includes(u.roleCode))))return '计划停用或当前人员资格已变化'
  if(!from||!end||from<databaseTime(s.startsAt)!||end>databaseTime(s.endsAt)!||end<=now)return '原时段已结束或不在最新源班次内'
  if(roster!.truncated)return '核对窗口被截断，请缩小窗口；不假定缺失班次没有冲突'
  const start=intent.action==='CLAIM'&&now>from?now:from
  if(roster!.shifts.some(other=>other.id!==s.id&&other.cancelledAt===null&&databaseTime(other.startsAt)!<end&&databaseTime(other.endsAt)!>start))return '核对窗口存在其他班次或临时覆盖冲突'
  return ''
}
export function saveOpenIntent(storage:Pick<Storage,'setItem'|'getItem'>,value:OpenIntent){
  const invalid=openIntentError(value);if(invalid)throw Error(invalid)
  const raw=JSON.stringify(value);storage.setItem(storageKey(value.actorId),raw)
  if(storage.getItem(storageKey(value.actorId))!==raw)throw Error('原意图未可靠保存，尚未发送')
}
export function readOpenIntent(storage:Pick<Storage,'getItem'>,actor:number):OpenIntent|null{
  const raw=storage.getItem(storageKey(actor));if(raw===null)return null
  const value=JSON.parse(raw) as OpenIntent
  if(value?.actorId!==actor||openIntentError(value))throw Error('开放接班原意图损坏，未发送；核对后明确放弃')
  return value
}
export const clearOpenIntent=(storage:Pick<Storage,'removeItem'>,actor:number)=>storage.removeItem(storageKey(actor))
export const listOpenHandoffs=(scope:'ALL'|'MINE'|'AVAILABLE',status:OpenHandoff['status']|'',scheduleId:string)=>{
  const query=new URLSearchParams({scope});if(status)query.set('status',status);if(scheduleId)query.set('scheduleId',scheduleId)
  return api<{databaseNow:string;requests:OpenHandoff[];truncated:boolean}>(`/on-call/open-handoffs?${query}`)
}
export async function getOpenCoverage(id:number){const info=await api<OpenCoverage>(`/on-call/open-handoffs/${id}/coverage`)
  if(info?.request?.id!==id||!validRow(info.request)||!databaseTime(info.databaseNow))throw Error('请求快照身份或时间异常，旧事实已清空');return info}
export const getOpenRoster=(intent:OpenIntent)=>{const value=intent.action==='PUBLISH'?intent.source:intent.row
  return api<OpenRoster>('/on-call/roster?'+new URLSearchParams({scheduleId:String(value.scheduleId),from:openTime(value.startsAt),to:openTime(value.endsAt)}))}
export async function submitOpenIntent(intent:OpenIntent){
  if(intent.action==='PUBLISH'){
    const row=await api<OpenHandoff>('/on-call/open-handoffs',{method:'POST',body:JSON.stringify(intent.command)}),c=intent.command
    if(!validRow(row)||row.requesterId!==intent.actorId||row.requestKey!==c.requestKey||row.sourceShiftId!==c.sourceShiftId||row.sourceVersion!==c.sourceVersion
      ||openTime(row.startsAt)!==c.startsAt||openTime(row.endsAt)!==c.endsAt||row.reason!==c.reason)throw Error('发布回执与原意图不符，仍保留原键')
    return {row,coverage:null}
  }
  const info=await api<OpenCoverage>(`/on-call/open-handoffs/${intent.row.id}/${intent.action==='CLAIM'?'claims':'withdrawals'}`,{method:'POST',body:JSON.stringify(intent.command)})
  const operation=info?.operation,c=intent.command
  if(info?.request?.id!==intent.row.id||!validRow(info.request)||!operation||operation.handoffId!==intent.row.id||operation.actorId!==intent.actorId
    ||operation.operation!==intent.action||operation.operationKey!==c.operationKey||operation.capturedVersion!==c.version||operation.reason!==c.reason)throw Error('操作回执与本人原意图不符，仍保留原键')
  return {row:info.request,coverage:info}
}
