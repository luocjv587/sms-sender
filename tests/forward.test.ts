import { promises as dns } from "node:dns";
import nodemailer from "nodemailer";
import type { LookupAddress } from "node:dns";
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { beforeEach, describe, expect, it, vi } from "vitest";
import handler, { resetRateLimitForTests } from "../api/forward.js";

vi.mock("node:dns", () => ({
  promises: {
    lookup: vi.fn(),
  },
}));

vi.mock("nodemailer", () => ({
  default: {
    createTransport: vi.fn(),
  },
}));

const lookupMock = vi.mocked(dns.lookup) as unknown as {
  mockResolvedValue(value: LookupAddress[]): void;
};

interface ResponseState {
  statusCode: number;
  body: unknown;
  headers: Record<string, string>;
  status(code: number): ResponseState;
  json(body: unknown): ResponseState;
  send(body: unknown): ResponseState;
  setHeader(name: string, value: number | string | readonly string[]): ResponseState;
  getHeader(name: string): string | undefined;
}

type MockResponse = VercelResponse & ResponseState;

const validBody = {
  smtp: {
    host: "smtp.example.com",
    port: 587,
    secure: false,
    user: "mailer@example.com",
    pass: "secret",
  },
  mail: {
    from: "mailer@example.com",
    fromName: "+86 13800138000",
    to: "receiver@example.com",
    subject: "测试消息",
    text: "hello",
  },
};

function createResponse(): MockResponse {
  const response: ResponseState = {
    statusCode: 200,
    body: undefined,
    headers: {},
    status(code: number) {
      this.statusCode = code;
      return this;
    },
    json(body: unknown) {
      this.body = body;
      return this;
    },
    send(body: unknown) {
      this.body = body;
      return this;
    },
    setHeader(name: string, value: number | string | readonly string[]) {
      this.headers[name.toLowerCase()] = Array.isArray(value) ? value.join(", ") : String(value);
      return this;
    },
    getHeader(name: string) {
      return this.headers[name.toLowerCase()];
    },
  };
  return response as unknown as MockResponse;
}

function createRequest(overrides: Partial<VercelRequest> = {}): VercelRequest {
  return {
    method: "POST",
    headers: {
      authorization: "Bearer test-api-key",
      "content-type": "application/json",
    },
    body: structuredClone(validBody),
    socket: { remoteAddress: "203.0.113.10" },
    ...overrides,
  } as unknown as VercelRequest;
}

async function invoke(overrides: Partial<VercelRequest> = {}): Promise<MockResponse> {
  const response = createResponse();
  await handler(createRequest(overrides), response);
  return response;
}

describe("POST /api/forward", () => {
  const sendMail = vi.fn();
  const close = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    resetRateLimitForTests();
    process.env.FORWARDER_API_KEY = "test-api-key";
    lookupMock.mockResolvedValue([
      { address: "8.8.8.8", family: 4 },
    ]);
    sendMail.mockResolvedValue({ messageId: "message-123" });
    vi.mocked(nodemailer.createTransport).mockReturnValue({
      sendMail,
      close,
    } as never);
  });

  it("只允许 POST", async () => {
    const response = await invoke({ method: "GET" });

    expect(response.statusCode).toBe(405);
    expect(response.headers.allow).toBe("POST");
    expect(response.body).toEqual({
      error: { code: "METHOD_NOT_ALLOWED", message: "仅支持 POST" },
    });
  });

  it("拒绝缺失或错误的 Bearer 凭据", async () => {
    const response = await invoke({
      headers: { "content-type": "application/json" },
    });

    expect(response.statusCode).toBe(401);
    expect(response.body).toMatchObject({ error: { code: "UNAUTHORIZED" } });
    expect(dns.lookup).not.toHaveBeenCalled();
  });

  it("拒绝额外字段和不完整 schema", async () => {
    const response = await invoke({
      body: { ...validBody, unexpected: true },
    });

    expect(response.statusCode).toBe(400);
    expect(response.body).toMatchObject({ error: { code: "INVALID_REQUEST" } });
  });

  it("拒绝超过大小上限的请求", async () => {
    const response = await invoke({
      headers: {
        authorization: "Bearer test-api-key",
        "content-type": "application/json",
        "content-length": String(16 * 1024 + 1),
      },
    });

    expect(response.statusCode).toBe(413);
    expect(response.body).toMatchObject({ error: { code: "PAYLOAD_TOO_LARGE" } });
  });

  it("拒绝邮件头注入", async () => {
    const response = await invoke({
      body: {
        ...validBody,
        mail: { ...validBody.mail, subject: "正常\r\nBcc: victim@example.com" },
      },
    });

    expect(response.statusCode).toBe(400);
    expect(response.body).toMatchObject({ error: { code: "INVALID_REQUEST" } });
    expect(nodemailer.createTransport).not.toHaveBeenCalled();
  });

  it("拒绝 SMTP 25 端口", async () => {
    const response = await invoke({
      body: {
        ...validBody,
        smtp: { ...validBody.smtp, port: 25 },
      },
    });

    expect(response.statusCode).toBe(400);
    expect(nodemailer.createTransport).not.toHaveBeenCalled();
  });

  it("拒绝解析到私网或保留地址的 SMTP 主机", async () => {
    lookupMock.mockResolvedValue([
      { address: "127.0.0.1", family: 4 },
    ]);

    const response = await invoke();

    expect(response.statusCode).toBe(400);
    expect(response.body).toMatchObject({ error: { code: "SMTP_HOST_BLOCKED" } });
    expect(nodemailer.createTransport).not.toHaveBeenCalled();
  });

  it("使用已校验公网 IP、安全超时并成功发送", async () => {
    lookupMock.mockResolvedValue([
      { address: "8.8.8.8", family: 4 },
    ]);

    const response = await invoke();

    expect(response.statusCode).toBe(200);
    expect(response.body).toEqual({ ok: true, messageId: "message-123" });
    expect(nodemailer.createTransport).toHaveBeenCalledWith(
      expect.objectContaining({
        host: "8.8.8.8",
        port: 587,
        requireTLS: true,
        connectionTimeout: 10_000,
        greetingTimeout: 10_000,
        socketTimeout: 15_000,
        tls: expect.objectContaining({ servername: "smtp.example.com" }),
      }),
    );
    expect(sendMail).toHaveBeenCalledWith({
      from: {
        name: "+86 13800138000",
        address: "mailer@example.com",
      },
      to: "receiver@example.com",
      subject: "测试消息",
      text: "hello",
    });
    expect(close).toHaveBeenCalledOnce();
    expect(JSON.stringify(response.body)).not.toContain("secret");
  });

  it("以一致错误结构隐藏 SMTP 失败细节", async () => {
    sendMail.mockRejectedValue(new Error("535 password secret rejected"));

    const response = await invoke();

    expect(response.statusCode).toBe(502);
    expect(response.body).toEqual({
      error: { code: "SMTP_DELIVERY_FAILED", message: "SMTP 投递失败" },
    });
    expect(JSON.stringify(response.body)).not.toContain("secret");
  });

  it("执行单实例 best-effort 限流", async () => {
    for (let index = 0; index < 10; index += 1) {
      expect((await invoke()).statusCode).toBe(200);
    }

    const response = await invoke();
    expect(response.statusCode).toBe(429);
    expect(response.body).toMatchObject({ error: { code: "RATE_LIMITED" } });
    expect(response.headers["retry-after"]).toBeDefined();
  });
});
