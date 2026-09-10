import { describe, it, expect } from "vitest";
import { sanitizeLimitPriceInput } from "../limit-price-input";

describe("sanitizeLimitPriceInput — KRW", () => {
  it("숫자만 남기고 소수점은 전부 제거한다(정수 단위만 허용)", () => {
    expect(sanitizeLimitPriceInput("72000", "KRW")).toBe("72000");
    expect(sanitizeLimitPriceInput("72,000", "KRW")).toBe("72000");
    expect(sanitizeLimitPriceInput("72000.5", "KRW")).toBe("720005");
    expect(sanitizeLimitPriceInput("7.2.0.0.0", "KRW")).toBe("72000");
  });
});

describe("sanitizeLimitPriceInput — USD", () => {
  it("소수점 둘째 자리까지만 허용한다(센트 단위)", () => {
    expect(sanitizeLimitPriceInput("95.5", "USD")).toBe("95.5");
    expect(sanitizeLimitPriceInput("95.567", "USD")).toBe("95.56");
    expect(sanitizeLimitPriceInput("95.5006", "USD")).toBe("95.50");
  });

  it("소수점이 여러 번 입력돼도 하나만 남긴다", () => {
    expect(sanitizeLimitPriceInput("9.5.6", "USD")).toBe("9.56");
    expect(sanitizeLimitPriceInput("1..2..3", "USD")).toBe("1.23");
  });

  it("소수점 이후 자릿수가 없어도 그대로 둔다(입력 중간 상태)", () => {
    expect(sanitizeLimitPriceInput("95.", "USD")).toBe("95.");
  });

  it("정수만 입력하면 그대로 반환한다", () => {
    expect(sanitizeLimitPriceInput("95", "USD")).toBe("95");
  });
});
