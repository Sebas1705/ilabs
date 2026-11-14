import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LangContext, type Lang } from "../i18n";
import { GraderPlayground } from "./GraderPlayground";

function mount(lang: Lang = "en") {
  return render(
    <LangContext.Provider value={lang}>
      <GraderPlayground />
    </LangContext.Provider>,
  );
}

const verdict = () => screen.getByRole("status");
const reply = () => screen.getByLabelText("Reply to grade");

async function choose(kind: string) {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText("Grader"), kind);
  return user;
}

describe("GraderPlayground", () => {
  it("starts on equals with a verdict", () => {
    mount();
    expect(screen.getByLabelText("Grader")).toHaveValue("equals");
    expect(verdict()).toHaveTextContent(/Pass|Fail/);
  });

  it("offers every pure grader and not judge", () => {
    mount();
    const options = within(screen.getByLabelText("Grader")).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["equals", "contains", "notContains", "startsWith", "endsWith", "oneOf", "length", "json", "jsonEquals"]);
    expect(screen.getByText(/judge grader needs a provider/i)).toBeInTheDocument();
  });

  it("updates the verdict live as the reply is typed (equals with trim)", async () => {
    mount();
    const user = userEvent.setup();
    const value = screen.getByLabelText("Value");
    await user.clear(value);
    await user.type(value, "hello");
    await user.click(screen.getByRole("checkbox", { name: "Trim whitespace" }));
    await user.clear(reply());
    await user.type(reply(), "hello");
    expect(verdict()).toHaveTextContent("Pass");
    await user.type(reply(), "   ");
    expect(verdict()).toHaveTextContent("Pass");
    await user.click(screen.getByRole("checkbox", { name: "Trim whitespace" }));
    expect(verdict()).toHaveTextContent("Fail");
    await user.click(screen.getByRole("checkbox", { name: "Trim whitespace" }));
    expect(verdict()).toHaveTextContent("Pass");
  });

  it("shows only the controls of the chosen grader", async () => {
    mount();
    expect(screen.queryByLabelText("Minimum length")).toBeNull();
    await choose("length");
    expect(screen.getByLabelText("Minimum length")).toBeInTheDocument();
    expect(screen.getByLabelText("Maximum length")).toBeInTheDocument();
    expect(screen.queryByLabelText("Value")).toBeNull();
    expect(screen.queryByRole("checkbox", { name: "Trim whitespace" })).toBeNull();
    await choose("json");
    expect(screen.getByLabelText("Schema (JSON)")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Ignore case" })).toBeNull();
    await choose("jsonEquals");
    expect(screen.getByLabelText("Expected value (JSON)")).toBeInTheDocument();
    await choose("oneOf");
    expect(screen.getByLabelText("Values (one per line)")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Ignore case" })).toBeInTheDocument();
  });

  it("reads oneOf as one value per line", async () => {
    mount();
    await choose("oneOf");
    fireEvent.change(screen.getByLabelText("Values (one per line)"), { target: { value: "yes\nno\nmaybe" } });
    fireEvent.change(reply(), { target: { value: "no" } });
    expect(verdict()).toHaveTextContent("Pass");
    fireEvent.change(reply(), { target: { value: "yes\nno" } });
    expect(verdict()).toHaveTextContent("Fail");
  });

  it("treats empty oneOf text as the empty-string value, with no rule of its own", async () => {
    mount();
    await choose("oneOf");
    fireEvent.change(screen.getByLabelText("Values (one per line)"), { target: { value: "" } });
    fireEvent.change(reply(), { target: { value: "" } });
    expect(verdict()).toHaveTextContent("Pass");
    fireEvent.change(reply(), { target: { value: "x" } });
    expect(verdict()).toHaveTextContent("Fail");
  });

  it("checks length in code points with inclusive bounds", async () => {
    mount();
    await choose("length");
    fireEvent.change(screen.getByLabelText("Minimum length"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("Maximum length"), { target: { value: "2" } });
    fireEvent.change(reply(), { target: { value: "😀😀" } });
    expect(verdict()).toHaveTextContent("Pass");
    fireEvent.change(reply(), { target: { value: "😀" } });
    expect(verdict()).toHaveTextContent("Fail");
  });

  it("shows a translated parameter error instead of a verdict for invalid schema JSON", async () => {
    mount();
    await choose("json");
    const schema = screen.getByLabelText("Schema (JSON)");
    fireEvent.change(schema, { target: { value: "{nope" } });
    expect(verdict()).toHaveTextContent("This text is not valid JSON.");
    expect(verdict()).not.toHaveTextContent(/^Pass|^Fail/);
    expect(verdict().querySelector("[data-status]")).toBeNull();
    expect(schema).toHaveAttribute("aria-invalid", "true");
    expect(schema).toHaveAccessibleDescription(/not valid JSON/);
    fireEvent.change(schema, { target: { value: '{"type":"object","required":["a"]}' } });
    fireEvent.change(reply(), { target: { value: '{"a":1}' } });
    expect(schema).not.toHaveAttribute("aria-invalid", "true");
    expect(verdict()).toHaveTextContent("Pass");
  });

  it("shows grader issues for a failing json grader", async () => {
    mount();
    await choose("json");
    fireEvent.change(screen.getByLabelText("Schema (JSON)"), { target: { value: '{"type":"object","required":["name"]}' } });
    fireEvent.change(reply(), { target: { value: "{}" } });
    expect(verdict()).toHaveTextContent("Fail");
    const issues = screen.getByRole("list", { name: "Issues" });
    expect(issues).toHaveTextContent("/name");
    expect(issues).toHaveTextContent("required");
    fireEvent.change(reply(), { target: { value: "no json here" } });
    expect(screen.getByRole("list", { name: "Issues" })).toHaveTextContent("no_json");
  });

  it("shows a parameter error for a schema that parses but is not a schema", async () => {
    mount();
    await choose("json");
    fireEvent.change(screen.getByLabelText("Schema (JSON)"), { target: { value: '{"type":"nope"}' } });
    expect(verdict()).toHaveTextContent("The schema is not valid.");
  });

  it("accepts null for jsonEquals and flags an empty value", async () => {
    mount();
    await choose("jsonEquals");
    const value = screen.getByLabelText("Expected value (JSON)");
    fireEvent.change(value, { target: { value: "" } });
    expect(verdict()).toHaveTextContent("This text is not valid JSON.");
    fireEvent.change(value, { target: { value: "null" } });
    fireEvent.change(reply(), { target: { value: "null" } });
    expect(verdict()).toHaveTextContent("Pass");
  });

  it("flags bad length bounds", async () => {
    mount();
    await choose("length");
    expect(verdict()).toHaveTextContent("These parameters are not valid.");
    expect(verdict()).toHaveTextContent("length needs min or max");
    fireEvent.change(screen.getByLabelText("Minimum length"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText("Maximum length"), { target: { value: "1" } });
    expect(verdict()).toHaveTextContent("min must be <= max");
    fireEvent.change(screen.getByLabelText("Maximum length"), { target: { value: "x" } });
    expect(screen.getByLabelText("Maximum length")).toHaveAttribute("aria-invalid", "true");
  });

  it("keeps the verdict text stable while typing within the same outcome", async () => {
    mount();
    const user = userEvent.setup();
    await choose("contains");
    await user.clear(screen.getByLabelText("Value"));
    await user.type(screen.getByLabelText("Value"), "a");
    fireEvent.change(reply(), { target: { value: "a" } });
    const before = verdict().textContent;
    await user.type(reply(), "bcd");
    expect(verdict().textContent).toBe(before);
  });

  it("renders in Spanish", async () => {
    mount("es");
    expect(screen.getByLabelText("Calificador")).toBeInTheDocument();
    expect(screen.getByLabelText("Respuesta a calificar")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Recortar espacios" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(/Pasa|Falla/);
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Calificador"), "json");
    fireEvent.change(screen.getByLabelText("Esquema (JSON)"), { target: { value: "{" } });
    expect(screen.getByRole("status")).toHaveTextContent("Este texto no es JSON válido.");
  });
});
