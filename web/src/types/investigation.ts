export interface AgentStep {
  id: number
  sequence: number
  phase: 'PLAN' | 'EXECUTE' | 'REPLAN' | 'FINISH'
  toolName: string | null
  status: 'SUCCEEDED' | 'NO_DATA' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
  title: string
  inputJson: string | null
  outputSummary: string
  evidenceJson: string
  errorMessage: string | null
  durationMs: number
  startedAt: string
  completedAt: string
}

export interface AgentRun {
  id: number
  incidentId: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
    | 'CANCELLED' | 'TIMED_OUT' | 'QUEUE_REJECTED'
  triggerSource: string
  planSummary: string
  conclusion: string | null
  reportId: number | null
  createdBy: string | null
  idempotencyKey: string | null
  deadlineAt: string | null
  terminationKind: 'CANCEL' | 'TIMEOUT' | 'QUEUE' | null
  terminationRequestedAt: string | null
  terminationRequestedBy: string | null
  terminationReason: string | null
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  steps: AgentStep[]
}

export interface AgentRunEvent {
  id: number
  runId: number
  sequence: number
  eventType: 'RUN_QUEUED' | 'RUN_STARTED' | 'PLAN_COMPLETED' | 'STEP_STARTED' | 'STEP_COMPLETED'
    | 'EVIDENCE_COLLECTED' | 'STEP_FAILED' | 'REPLAN_COMPLETED'
    | 'STEP_CANCELLED' | 'STEP_TIMED_OUT' | 'ACTION_PROPOSED'
    | 'RUN_CANCEL_REQUESTED' | 'RUN_TIMEOUT_REQUESTED'
    | 'RUN_COMPLETED' | 'RUN_FAILED' | 'RUN_CANCELLED' | 'RUN_TIMED_OUT' | 'RUN_REJECTED'
  phase: string | null
  toolName: string | null
  status: string | null
  payloadJson: string
  createdAt: string
}

export interface RemediationProposal {
  id: number
  incidentId: number
  runId: number
  changeId: number | null
  changeCode: string | null
  targetResourceId: number
  resourceCode: string
  resourceName: string
  actionType: string
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW'
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'
  title: string
  description: string
  evidenceRef: string
  requestedById: number
  requestedByName: string
  reviewedById: number | null
  reviewedByName: string | null
  reviewComment: string | null
  version: number
  reviewedAt: string | null
  createdAt: string
  updatedAt: string
}
