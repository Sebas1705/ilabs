import { render } from "@testing-library/react";
import { TokenBar } from "./TokenBar";

describe("TokenBar", () => {
  it("is decorative and sized relative to the largest cost", () => {
    const { container } = render(<TokenBar cost={5} max={20} />);
    const bar = container.querySelector(".tokenbar")!;
    expect(bar).toHaveAttribute("aria-hidden", "true");
    expect((bar.firstElementChild as HTMLElement).style.width).toBe("25%");
  });

  it("never divides by zero", () => {
    const { container } = render(<TokenBar cost={0} max={0} />);
    expect((container.querySelector(".tokenbar")!.firstElementChild as HTMLElement).style.width).toBe("0%");
  });
});
