import { api } from './api'
import { databaseTime } from './onCallSwapNotifications'

export interface PlanOption { id:number; name:string; resourceName:string }
export interface PlanMember {
  scheduleId:number; userId:number; userName:string; active:boolean; canRespond:boolean; canManage:boolean; version:number
  origin:'EXPLICIT'|'MIGRATED_GLOBAL_V38'; accountStatus:string; roleCode:string; createdAt:string; updatedAt:string
  effectiveResponse:boolean; effectiveManagement:boolean
}
export interface MemberCommand { userId:number; expectedVersion:number|null; active:boolean; canRespond:boolean; canManage:boolean; operationKey:string; reason:string }
export interface MemberReceipt extends MemberCommand { scheduleId:number; actorId:number; resultVersion:number; committedAt:string }
export interface MemberResult { current:PlanMember; receipt:MemberReceipt }
export interface MemberIntent { schema:1; actorId:number; scheduleId:number; blocked:boolean; command:MemberCommand }
const id=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>0
const version=(v:unknown)=>Number.isInteger(v)&&Number(v)>=0&&Number(v)<=2147483647
const bool=(v:unknown)=>typeof v==='boolean'
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const managerRoles=['ADMIN','OPS_MANAGER'],responseRoles=[...managerRoles,'ON_CALL']
const key=(actor:number)=>`opspilot_plan_member_intent:v1:${actor}`
export const memberManager=(actor:number|undefined,role:string|undefined)=>id(actor)&&managerRoles.includes(role??'')
function validMember(m:PlanMember){return Boolean(m&&id(m.scheduleId)&&id(m.userId)&&typeof m.userName==='string'&&m.userName.trim()
  &&[m.active,m.canRespond,m.canManage,m.effectiveResponse,m.effectiveManagement].every(bool)&&version(m.version)
  &&['EXPLICIT','MIGRATED_GLOBAL_V38'].includes(m.origin)&&typeof m.accountStatus==='string'&&m.accountStatus&&typeof m.roleCode==='string'&&m.roleCode
  &&databaseTime(m.createdAt)&&databaseTime(m.updatedAt)&&databaseTime(m.createdAt)!<=databaseTime(m.updatedAt)!
  &&m.effectiveResponse===(m.active&&m.canRespond&&m.accountStatus==='ACTIVE'&&responseRoles.includes(m.roleCode))
  &&m.effectiveManagement===(m.active&&m.canManage&&m.accountStatus==='ACTIVE'&&managerRoles.includes(m.roleCode)))}
export function memberIntentError(i:MemberIntent){
  const c=i?.command
  if(!i||i.schema!==1||!id(i.actorId)||!id(i.scheduleId)||!bool(i.blocked)||!c||!id(c.userId))return '须保留本人、计划与目标账号'
  if(!Object.prototype.hasOwnProperty.call(c,'expectedVersion')||(c.expectedVersion!==null&&(!version(c.expectedVersion)||c.expectedVersion===2147483647)))return '须捕获实际版本；首次授权显式null，不能遗漏或自动重基'
  if(![c.active,c.canRespond,c.canManage].every(bool)||(c.active&&!c.canRespond&&!c.canManage))return '响应与管理独立；有效成员须至少一种权限'
  if(!uuid.test(c.operationKey??'')||typeof c.reason!=='string'||!c.reason.trim()||c.reason!==c.reason.trim()||c.reason.length>500)return '须规范小写UUID与1–500字原说明'
  return ''
}
export function memberPreflightError(i:MemberIntent,rows:PlanMember[],role:string|undefined){
  const invalid=memberIntentError(i);if(invalid)return invalid
  if(i.blocked||!memberManager(i.actorId,role))return '当前账号只读或原意图已锁定'
  if(!Array.isArray(rows)||rows.some(m=>!validMember(m)||m.scheduleId!==i.scheduleId)||new Set(rows.map(m=>m.userId)).size!==rows.length)return '最新成员事实异常，不能沿用旧快照'
  if(role!=='ADMIN'&&!rows.some(m=>m.userId===i.actorId&&m.effectiveManagement))return '本人没有该计划的当前管理权限'
  const target=rows.find(m=>m.userId===i.command.userId)
  if((target?.version??null)!==i.command.expectedVersion)return '成员版本已变化；不自动重基或换键'
  if(i.command.active&&target&&(target.accountStatus!=='ACTIVE'||!responseRoles.includes(target.roleCode)||(i.command.canManage&&!managerRoles.includes(target.roleCode))))return '目标当前账号/角色不允许该授权'
  return '' // Missing target account is checked by the authoritative server, not a global-user-as-member list.
}
export function saveMemberIntent(storage:Pick<Storage,'setItem'|'getItem'>,i:MemberIntent){
  const invalid=memberIntentError(i);if(invalid)throw Error(invalid)
  const raw=JSON.stringify(i);storage.setItem(key(i.actorId),raw);if(storage.getItem(key(i.actorId))!==raw)throw Error('原意图未可靠保存，尚未发送')
}
export function readMemberIntent(storage:Pick<Storage,'getItem'>,actor:number):MemberIntent|null{
  if(!id(actor))throw Error('账号无效')
  const raw=storage.getItem(key(actor));if(raw===null)return null
  let i:MemberIntent;try{i=JSON.parse(raw) as MemberIntent}catch{throw Error('原意图损坏，须明确放弃')}
  if(memberIntentError(i)||i.actorId!==actor)throw Error('原意图身份/内容异常，须明确放弃')
  return i
}
export function clearMemberIntent(storage:Pick<Storage,'removeItem'|'getItem'>,actor:number){
  if(!id(actor))throw Error('账号无效');storage.removeItem(key(actor));if(storage.getItem(key(actor))!==null)throw Error('原意图未可靠清除，仍须保留原回执')
}
// Only on an explicit manual acknowledgement, never on mount/refresh or first POST.
export function restoreMemberIntentForManualAck(storage:Pick<Storage,'getItem'|'setItem'>,i:MemberIntent){
  const invalid=memberIntentError(i);if(invalid||i.blocked)throw Error(invalid||'原意图已锁定')
  const raw=storage.getItem(key(i.actorId)),captured=JSON.stringify(i)
  if(raw!==null&&raw!==captured)throw Error('本地已有不同原意图，不能覆盖或换键')
  if(raw===null)saveMemberIntent(storage,i)
}
export async function listMemberPlans(){
  const data=await api<{schedules:PlanOption[]}>('/on-call/roster'),plans=data?.schedules
  if(!Array.isArray(plans)||plans.some(p=>!id(p.id)||typeof p.name!=='string'||!p.name.trim()||typeof p.resourceName!=='string')||new Set(plans.map(p=>p.id)).size!==plans.length)throw Error('计划事实异常')
  return plans
}
export async function listPlanMembers(schedule:number){
  if(!id(schedule))throw Error('须明确计划')
  const rows=await api<PlanMember[]>(`/on-call/schedules/${schedule}/members`)
  if(!Array.isArray(rows)||rows.some(m=>!validMember(m)||m.scheduleId!==schedule)||new Set(rows.map(m=>m.userId)).size!==rows.length)throw Error('成员身份/当前资格事实异常，旧事实已清空')
  return rows
}
export async function submitMemberIntent(storage:Pick<Storage,'getItem'>,i:MemberIntent,token:string|null=localStorage.getItem('opspilot_token')){
  const invalid=memberIntentError(i);if(invalid||i.blocked)throw Error(invalid||'原意图已锁定')
  if(storage.getItem(key(i.actorId))!==JSON.stringify(i))throw Error('原意图存储与捕获内容不一致，尚未发送')
  const result=await api<MemberResult>(`/on-call/schedules/${i.scheduleId}/members`,{method:'POST',body:JSON.stringify(i.command)},{token,actorId:i.actorId})
  const m=result?.current,r=result?.receipt,c=i.command,committedVersion=c.expectedVersion===null?0:c.expectedVersion+1
  if(!validMember(m)||m.scheduleId!==i.scheduleId||m.userId!==c.userId||!r||r.scheduleId!==i.scheduleId||r.actorId!==i.actorId||r.resultVersion!==committedVersion
    ||!(['userId','expectedVersion','active','canRespond','canManage','operationKey','reason'] as const).every(field=>Object.prototype.hasOwnProperty.call(r,field)&&r[field]===c[field])
    ||!databaseTime(r.committedAt)||m.version<r.resultVersion||databaseTime(m.updatedAt)!<databaseTime(r.committedAt)!
    ||(m.version===r.resultVersion&&([m.active,m.canRespond,m.canManage].some((flag,n)=>flag!==[r.active,r.canRespond,r.canManage][n])||databaseTime(m.updatedAt)!==databaseTime(r.committedAt))))throw Error('原回执与本人捕获命令/当前行不符，仍保留原键')
  return result // Current permissions may differ at a later version; never overwrite the immutable receipt.
}
