import { promises as dns } from "node:dns";
import { createHash, timingSafeEqual } from "node:crypto";
import type { LookupAddress } from "node:dns";
import ipaddr from "ipaddr.js";
import nodemailer from "nodemailer";
import type { VercelRequest, VercelResponse } from "@vercel/node";

const MAX_BODY_BYTES = 16 * 1024;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 10;
const MAX_RATE_LIMIT_ENTRIES = 5_000;

type JsonObject = Record<string, unknown>;

interface ForwardRequest {
  smtp: {
    host: string;
    port: number;
    secure: boolean;
    user: string;
    pass: string;
  };
  mail: {
    from: string;
    fromName?: string;
    to: string;
    subject: string;
    text?: string;
    html?: string;
  };
}

interface RateLimitEntry {
  count: number;
  resetAt: number;
}

class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

const rateLimits = new Map<string, RateLimitEntry>();

function sendError(
  response: VercelResponse,
  status: number,
  code: string,
  message: string,
): void {
  response.status(status).json({ error: { code, message } });
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(
  value: JsonObject,
  required: readonly string[],
  optional: readonly string[] = [],
): boolean {
  const keys = Object.keys(value);
  const allowed = new Set([...required, ...optional]);
  return required.every((key) => Object.hasOwn(value, key))
    && keys.every((key) => allowed.has(key));
}

function hasHeaderInjection(value: string): boolean {
  return /[\r\n]/u.test(value);
}

function isEmail(value: string): boolean {
  return value.length <= 254
    && /^[^\s<>@,;]+@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$/u.test(value);
}

function isValidHostname(value: string): boolean {
  if (value.length < 1 || value.length > 253 || value.toLowerCase() === "localhost") {
    return false;
  }

  if (ipaddr.isValid(value)) {
    return true;
  }

  return value.split(".").every(
    (label) =>
      label.length >= 1
      && label.length <= 63
      && /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/u.test(label),
  );
}

function parseRequestBody(request: VercelRequest): unknown {
  const contentLength = request.headers["content-length"];
  const normalizedLength = Array.isArray(contentLength) ? contentLength[0] : contentLength;
  if (normalizedLength !== undefined) {
    const length = Number(normalizedLength);
    if (!Number.isSafeInteger(length) || length < 0) {
      throw new ApiError(400, "INVALID_REQUEST", "Content-Length 无效");
    }
    if (length > MAX_BODY_BYTES) {
      throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
    }
  }

  let body: unknown = request.body;
  if (Buffer.isBuffer(body)) {
    if (body.byteLength > MAX_BODY_BYTES) {
      throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
    }
    body = body.toString("utf8");
  }

  if (typeof body === "string") {
    if (Buffer.byteLength(body, "utf8") > MAX_BODY_BYTES) {
      throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
    }
    try {
      return JSON.parse(body) as unknown;
    } catch {
      throw new ApiError(400, "INVALID_REQUEST", "请求体必须是有效 JSON");
    }
  }

  let serialized: string;
  try {
    serialized = JSON.stringify(body);
  } catch {
    throw new ApiError(400, "INVALID_REQUEST", "请求体无法解析");
  }
  if (Buffer.byteLength(serialized ?? "", "utf8") > MAX_BODY_BYTES) {
    throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
  }
  return body;
}

function validateRequest(value: unknown): ForwardRequest {
  if (!isObject(value) || !hasExactKeys(value, ["smtp", "mail"])) {
    throw new ApiError(400, "INVALID_REQUEST", "请求字段不符合 schema");
  }

  const smtp = value.smtp;
  const mail = value.mail;
  if (
    !isObject(smtp)
    || !hasExactKeys(smtp, ["host", "port", "secure", "user", "pass"])
    || typeof smtp.host !== "string"
    || typeof smtp.port !== "number"
    || typeof smtp.secure !== "boolean"
    || typeof smtp.user !== "string"
    || typeof smtp.pass !== "string"
  ) {
    throw new ApiError(400, "INVALID_REQUEST", "smtp 字段不符合 schema");
  }

  if (
    !isValidHostname(smtp.host)
    || !Number.isInteger(smtp.port)
    || smtp.port < 1
    || smtp.port > 65_535
    || smtp.port === 25
    || smtp.user.length < 1
    || smtp.user.length > 320
    || smtp.pass.length < 1
    || smtp.pass.length > 1_024
  ) {
    throw new ApiError(400, "INVALID_REQUEST", "SMTP 配置无效，且不允许使用 25 端口");
  }

  if (
    !isObject(mail)
    || !hasExactKeys(mail, ["from", "to", "subject"], ["fromName", "text", "html"])
    || typeof mail.from !== "string"
    || (mail.fromName !== undefined && typeof mail.fromName !== "string")
    || typeof mail.to !== "string"
    || typeof mail.subject !== "string"
    || (mail.text !== undefined && typeof mail.text !== "string")
    || (mail.html !== undefined && typeof mail.html !== "string")
  ) {
    throw new ApiError(400, "INVALID_REQUEST", "mail 字段不符合 schema");
  }

  if (
    !isEmail(mail.from)
    || !isEmail(mail.to)
    || hasHeaderInjection(mail.from)
    || (mail.fromName !== undefined && hasHeaderInjection(mail.fromName))
    || hasHeaderInjection(mail.to)
    || hasHeaderInjection(mail.subject)
    || mail.subject.length < 1
    || mail.subject.length > 200
    || (mail.fromName?.length ?? 0) > 200
    || (mail.text === undefined && mail.html === undefined)
    || (mail.text?.length ?? 0) > 12_000
    || (mail.html?.length ?? 0) > 12_000
  ) {
    throw new ApiError(400, "INVALID_REQUEST", "邮件字段无效或包含非法换行");
  }

  return {
    smtp: {
      host: smtp.host,
      port: smtp.port,
      secure: smtp.secure,
      user: smtp.user,
      pass: smtp.pass,
    },
    mail: {
      from: mail.from,
      ...(mail.fromName === undefined ? {} : { fromName: mail.fromName }),
      to: mail.to,
      subject: mail.subject,
      ...(mail.text === undefined ? {} : { text: mail.text }),
      ...(mail.html === undefined ? {} : { html: mail.html }),
    },
  };
}

function isPublicAddress(address: string): boolean {
  try {
    const parsed = ipaddr.process(address);
    return parsed.range() === "unicast";
  } catch {
    return false;
  }
}

async function resolvePublicSmtpHost(host: string): Promise<string> {
  let addresses: LookupAddress[];
  try {
    addresses = await dns.lookup(host, { all: true, verbatim: true });
  } catch {
    throw new ApiError(400, "SMTP_HOST_BLOCKED", "SMTP 主机无法解析");
  }

  if (addresses.length === 0 || addresses.some(({ address }) => !isPublicAddress(address))) {
    throw new ApiError(400, "SMTP_HOST_BLOCKED", "SMTP 主机解析到了非公网地址");
  }
  return addresses[0]!.address;
}

function isAuthorized(header: string | string[] | undefined, expected: string): boolean {
  const value = Array.isArray(header) ? header[0] : header;
  if (typeof value !== "string" || !value.startsWith("Bearer ")) {
    return false;
  }
  const suppliedHash = createHash("sha256").update(value.slice(7)).digest();
  const expectedHash = createHash("sha256").update(expected).digest();
  return timingSafeEqual(suppliedHash, expectedHash);
}

function applyRateLimit(request: VercelRequest, response: VercelResponse): void {
  const forwardedFor = request.headers["x-forwarded-for"];
  const rawIp = (Array.isArray(forwardedFor) ? forwardedFor[0] : forwardedFor)?.split(",")[0]?.trim();
  const key = rawIp || request.socket.remoteAddress || "unknown";
  const now = Date.now();

  if (rateLimits.size >= MAX_RATE_LIMIT_ENTRIES) {
    for (const [entryKey, entry] of rateLimits) {
      if (entry.resetAt <= now) {
        rateLimits.delete(entryKey);
      }
    }
  }
  if (rateLimits.size >= MAX_RATE_LIMIT_ENTRIES && !rateLimits.has(key)) {
    const oldestKey = rateLimits.keys().next().value as string | undefined;
    if (oldestKey !== undefined) {
      rateLimits.delete(oldestKey);
    }
  }

  const existing = rateLimits.get(key);
  const entry = !existing || existing.resetAt <= now
    ? { count: 1, resetAt: now + RATE_LIMIT_WINDOW_MS }
    : { count: existing.count + 1, resetAt: existing.resetAt };
  rateLimits.set(key, entry);

  response.setHeader("X-RateLimit-Limit", String(RATE_LIMIT_MAX_REQUESTS));
  response.setHeader("X-RateLimit-Remaining", String(Math.max(0, RATE_LIMIT_MAX_REQUESTS - entry.count)));
  response.setHeader("X-RateLimit-Reset", String(Math.ceil(entry.resetAt / 1_000)));
  if (entry.count > RATE_LIMIT_MAX_REQUESTS) {
    response.setHeader("Retry-After", String(Math.ceil((entry.resetAt - now) / 1_000)));
    throw new ApiError(429, "RATE_LIMITED", "请求过于频繁，请稍后重试");
  }
}

export function resetRateLimitForTests(): void {
  rateLimits.clear();
}

export default async function handler(
  request: VercelRequest,
  response: VercelResponse,
): Promise<void> {
  response.setHeader("Cache-Control", "no-store");

  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    sendError(response, 405, "METHOD_NOT_ALLOWED", "仅支持 POST");
    return;
  }

  const apiKey = process.env.FORWARDER_API_KEY;
  if (!apiKey) {
    sendError(response, 500, "CONFIGURATION_ERROR", "服务端未配置 API Key");
    return;
  }
  if (!isAuthorized(request.headers.authorization, apiKey)) {
    sendError(response, 401, "UNAUTHORIZED", "Authorization Bearer 无效");
    return;
  }

  const contentType = request.headers["content-type"];
  if (typeof contentType !== "string" || !contentType.toLowerCase().startsWith("application/json")) {
    sendError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type 必须为 application/json");
    return;
  }

  try {
    applyRateLimit(request, response);
    const input = validateRequest(parseRequestBody(request));
    const smtpAddress = await resolvePublicSmtpHost(input.smtp.host);
    const transport = nodemailer.createTransport({
      host: smtpAddress,
      port: input.smtp.port,
      secure: input.smtp.secure,
      requireTLS: !input.smtp.secure,
      auth: {
        user: input.smtp.user,
        pass: input.smtp.pass,
      },
      connectionTimeout: 10_000,
      greetingTimeout: 10_000,
      socketTimeout: 15_000,
      tls: {
        servername: input.smtp.host,
        rejectUnauthorized: true,
      },
    });

    try {
      const { fromName, ...mail } = input.mail;
      const result = await transport.sendMail({
        ...mail,
        from: fromName ? { name: fromName, address: mail.from } : mail.from,
      });
      response.status(200).json({
        ok: true,
        messageId: typeof result.messageId === "string" ? result.messageId : null,
      });
    } catch {
      throw new ApiError(502, "SMTP_DELIVERY_FAILED", "SMTP 投递失败");
    } finally {
      transport.close();
    }
  } catch (error) {
    if (error instanceof ApiError) {
      sendError(response, error.status, error.code, error.message);
      return;
    }
    sendError(response, 500, "INTERNAL_ERROR", "服务器内部错误");
  }
}
