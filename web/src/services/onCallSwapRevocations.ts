import { api } from './api'
import type { Swap } from './onCallSwaps'
import { databaseTime, notificationClock } from './onCallSwapNotifications'

export interface SwapReplacement { id:number; scheduleId:number; userId:number; version:number; startsAt:string; endsAt:string; cancelledAt:string|null; cancellationReason:string|null }
export interface SwapRevocation { swapId:number; actorId:number; operationKey:string; swapVersion:number; firstReplacementVersion:number; secondReplacementVersion:number; reason:string; revokedAt:string }
export interface SwapPair { databaseNow:string; accepted:Swap; firstReplacement:SwapReplacement|null; secondReplacement:SwapReplacement|null; revocation:SwapRevocation|null }
export interface PairRevocationCommand { swapVersion:number; firstReplacementVersion:number; secondReplacementVersion:number; operationKey:string; reason:string }
export interface PairRevocationIntent { schema:1; actorId:number; swapId:number; firstReplacementId:number; secondReplacementId:number; command:PairRevocationCommand; blocked:boolean }
const id=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>0
const version=(n:unknown)=>Number.isInteger(n)&&Number(n)>=0&&Number(n)<=2147483647
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const key=(actor:number)=>`opspilot_swap_pair_revocation:v1:${actor}`
export const pairClock=notificationClock
export const canManagePair=(actor:number|undefined,role:string|undefined)=>id(actor)&&['ADMIN','OPS_MANAGER'].includes(role??'')
export function pairRevocationEligibility(info:SwapPair|null,swapId:number,actor:number|undefined,role:string|undefined){
  if(!canManagePair(actor,role))return '当前账号只读；成对撤销仅限当前活跃管理账号'
  const row=info?.accepted,a=info?.firstReplacement,b=info?.secondReplacement,now=databaseTime(info?.databaseNow)
  if(!row||!id(swapId)||row.id!==swapId||!version(row.version)||!now)return '成对事实或有效数据库时间未取得，不能新撤销'
  if(row.status!=='ACCEPTED')return '历史请求尚未接受，没有本次接受生成的成对覆盖'
  if(info!.revocation!==null)return '已有成对撤销或回执事实异常，不能创建新撤销意图'
  if(!a||!b||![a.id,b.id,a.scheduleId,b.scheduleId,a.userId,b.userId,row.requesterId,row.targetUserId].every(id)
    ||a.id===b.id||row.requesterId===row.targetUserId||a.id!==row.firstReplacementShiftId||b.id!==row.secondReplacementShiftId
    ||a.scheduleId!==row.firstScheduleId||b.scheduleId!==row.secondScheduleId||a.userId!==row.targetUserId||b.userId!==row.requesterId)return '两条覆盖身份与原接受快照不符或未取得'
  if(!version(a.version)||!version(b.version)||a.version===2147483647||b.version===2147483647)return '覆盖版本无效或已到上限，不能新撤销'
  if(a.cancelledAt!==null||b.cancelledAt!==null)return '任一覆盖已独立取消或取消事实未知，不能顺手撤销另一段'
  for(const [replacement,start,end] of [[a,row.firstStartsAt,row.firstEndsAt],[b,row.secondStartsAt,row.secondEndsAt]] as const){
    const from=databaseTime(replacement.startsAt),to=databaseTime(replacement.endsAt)
    if(!from||!to||from>=to||from!==databaseTime(start)||to!==databaseTime(end))return '覆盖时段与原接受快照不符或无效'
    if(to<=now)return '任一覆盖已结束，不能新撤销过去责任'
  }
  return ''
}
export function pairCommandError(command:PairRevocationCommand){
  if(!command||![command.swapVersion,command.firstReplacementVersion,command.secondReplacementVersion].every(version))return '三个捕获版本都须显式保留为有效整数'
  if(typeof command.operationKey!=='string'||!uuid.test(command.operationKey))return '撤销键须为规范小写 UUID'
  if(typeof command.reason!=='string'||!command.reason.trim()||command.reason.length>500)return '撤销说明须为1–500字'
  return ''
}
export function sameCapturedPair(info:SwapPair|null,intent:PairRevocationIntent){return Boolean(info?.accepted?.id===intent.swapId&&info.accepted.version===intent.command.swapVersion
  &&info.firstReplacement?.id===intent.firstReplacementId&&info.secondReplacement?.id===intent.secondReplacementId
  &&info.firstReplacement.version===intent.command.firstReplacementVersion&&info.secondReplacement.version===intent.command.secondReplacementVersion)}
export function savePairIntent(storage:Pick<Storage,'setItem'>,intent:PairRevocationIntent){storage.setItem(key(intent.actorId),JSON.stringify(intent))}
export function readPairIntent(storage:Pick<Storage,'getItem'>,actor:number):PairRevocationIntent|null{
  const raw=storage.getItem(key(actor));if(!raw)return null
  const value=JSON.parse(raw) as PairRevocationIntent
  if(!value||value.schema!==1||!id(actor)||value.actorId!==actor||![value.swapId,value.firstReplacementId,value.secondReplacementId].every(id)
    ||value.firstReplacementId===value.secondReplacementId||typeof value.blocked!=='boolean'||pairCommandError(value.command))throw new Error('成对撤销草稿损坏，未发送；核对事实后明确放弃')
  return value
}
export const clearPairIntent=(storage:Pick<Storage,'removeItem'>,actor:number)=>storage.removeItem(key(actor))
export async function getSwapPair(id:number){const info=await api<SwapPair>(`/on-call/swaps/${id}/coverage`);if(info?.accepted?.id!==id)throw new Error('成对事实身份异常，旧事实已清空');return info}
export const revokeSwapPair=(value:PairRevocationIntent)=>api<SwapPair>(`/on-call/swaps/${value.swapId}/coverage/revoke`,{method:'POST',body:JSON.stringify(value.command)})
