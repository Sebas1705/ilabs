import type { Message, Provider } from "@ilabs/providers";
import type { Issue, Schema } from "@ilabs/structured";

export type StringGraderKind = "equals" | "contains" | "notContains" | "startsWith" | "endsWith";

/** A grader that is evaluated on the reply text alone (everything except `judge`). */
export type PureGrader =
  | { kind: StringGraderKind; value: string; trim?: boolean; ignoreCase?: boolean }
  | { kind: "oneOf"; values: string[]; trim?: boolean; ignoreCase?: boolean }
  | { kind: "length"; min?: number; max?: number }
  | { kind: "json"; schema: Schema }
  | { kind: "jsonEquals"; value: unknown };

export type Grader = PureGrader | { kind: "judge"; rubric: string };

export interface EvalCase {
  id: string;
  messages: Message[];
  graders: Grader[];
}

export interface Suite {
  cases: EvalCase[];
}

export interface GraderResult {
  kind: string;
  passed: boolean;
  issues?: Issue[];
  error?: string;
}

export type Status = "pass" | "fail" | "error";

export interface CaseResult {
  caseId: string;
  targetId: string;
  status: Status;
  reply?: string;
  error?: string;
  graders: GraderResult[];
}

export interface TargetSummary {
  targetId: string;
  total: number;
  passed: number;
  failed: number;
  errored: number;
  passRate: number;
}

export interface RunOptions {
  targets: Provider[];
  judge?: Provider;
}

export interface RunOutput {
  results: CaseResult[];
  summary: TargetSummary[];
  calls: Record<string, number>;
}
