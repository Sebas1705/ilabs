import { ProviderError, type CompletionRequest, type CompletionResponse, type Provider } from "@ilabs/providers";
import { evaluateGrader } from "./graders.js";
import { buildJudgeRequest, parseVerdict } from "./judge.js";
import { InvalidSuiteError, validateSuite } from "./suite.js";
import { summarize } from "./summary.js";
import type { CaseResult, EvalCase, GraderResult, RunOptions, RunOutput, Status, Suite } from "./types.js";

/** The reserved target id: the key of the judge in `calls`. */
const JUDGE_ID = "judge";

function checkTargets(targets: Provider[]): void {
  const seen = new Set<string>();
  for (const t of targets) {
    if (typeof t.id !== "string" || t.id.length === 0) throw new InvalidSuiteError("target ids must be non-empty");
    if (t.id === JUDGE_ID) throw new InvalidSuiteError(`the target id "${JUDGE_ID}" is reserved`);
    if (seen.has(t.id)) throw new InvalidSuiteError(`duplicate target id ${JSON.stringify(t.id)}`);
    seen.add(t.id);
  }
}

async function judgeGrader(
  rubric: string,
  c: EvalCase,
  reply: string,
  call: ((req: CompletionRequest) => Promise<CompletionResponse>) | undefined,
): Promise<GraderResult> {
  if (call === undefined) return { kind: "judge", passed: false, error: "no_judge" };
  let text: string;
  try {
    text = (await call(buildJudgeRequest(rubric, c.messages, reply))).text;
  } catch (e) {
    if (e instanceof ProviderError) return { kind: "judge", passed: false, error: e.kind };
    throw e;
  }
  const verdict = parseVerdict(text);
  if (verdict === null) return { kind: "judge", passed: false, error: "bad_verdict" };
  return { kind: "judge", passed: verdict === "PASS" };
}

/**
 * Runs every case against every target (case-major, then target-minor), grades each reply and summarizes.
 * Validates the suite and the targets before any call (`invalid suite: <reason>`). A non-provider exception aborts the run.
 */
export async function runSuite(suiteInput: Suite, options: RunOptions): Promise<RunOutput> {
  const suite = validateSuite(suiteInput, false);
  const targets = [...options.targets];
  checkTargets(targets);
  const judge = options.judge;

  const counts = new Map<string, number>(targets.map((t) => [t.id, 0]));
  if (judge) counts.set(JUDGE_ID, 0);
  const counted = (p: Provider, id: string) => (req: CompletionRequest) => {
    counts.set(id, (counts.get(id) ?? 0) + 1);
    return p.complete(req);
  };
  const judgeCall = judge ? counted(judge, JUDGE_ID) : undefined;

  const results: CaseResult[] = [];
  for (const c of suite.cases) {
    for (const t of targets) {
      let reply: string;
      try {
        reply = (await counted(t, t.id)({ messages: c.messages.map((m) => ({ role: m.role, content: m.content })) })).text;
      } catch (e) {
        if (e instanceof ProviderError) {
          results.push({ caseId: c.id, targetId: t.id, status: "error", error: e.kind, graders: [] });
          continue;
        }
        throw e;
      }
      const graders: GraderResult[] = [];
      for (const g of c.graders) {
        graders.push(g.kind === "judge" ? await judgeGrader(g.rubric, c, reply, judgeCall) : evaluateGrader(g, reply));
      }
      const status: Status = graders.some((g) => g.error !== undefined) ? "error" : graders.some((g) => !g.passed) ? "fail" : "pass";
      results.push({ caseId: c.id, targetId: t.id, status, reply, graders });
    }
  }
  return { results, summary: summarize(results), calls: Object.fromEntries(counts) };
}
