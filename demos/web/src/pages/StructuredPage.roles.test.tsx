import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LangContext, type Lang } from "../i18n";
import { StructuredPage } from "./StructuredPage";

async function replay(lang: Lang, fixtureLabel: string, resultLabel: string, name: string) {
  render(
    <LangContext.Provider value={lang}>
      <StructuredPage />
    </LangContext.Provider>,
  );
  await userEvent.setup().selectOptions(screen.getByLabelText(fixtureLabel), name);
  await screen.findByRole("region", { name: resultLabel });
}

describe("repair roles of a replayed run", () => {
  it("shows the translated roles of the appended messages in Spanish", async () => {
    await replay("es", "Escenario", "Resultado", "loop-repair-second");
    const attempts = screen.getByRole("list", { name: "Intentos" });
    expect(attempts.querySelector(".repair")).toHaveTextContent("asistente, usuario");
    expect(screen.getByRole("region", { name: "Resultado" })).toHaveTextContent("asistente, usuario");
  });

  it("shows one divider per repair, each with the roles appended at that step", async () => {
    await replay("en", "Fixture", "Result", "loop-repair-second");
    const dividers = screen.getByRole("list", { name: "Attempts" }).querySelectorAll(".repair");
    expect(dividers).toHaveLength(1);
    expect(dividers[0]).toHaveTextContent("assistant, user");
  });
});
