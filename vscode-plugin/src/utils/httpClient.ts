import * as https from 'https';
import * as http from 'http';
import { URL } from 'url';

export interface HttpOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  /** Timeout in milliseconds (default: 30000) */
  timeout?: number;
}

export interface HttpResponse {
  statusCode: number;
  body: string;
  headers: Record<string, string | string[] | undefined>;
}

/**
 * Simple Promise-based HTTP client using Node.js built-in modules.
 * Used by all API clients in the extension to avoid external dependencies.
 * Automatically follows up to 10 redirects (3xx responses).
 */
export async function httpRequest(
  urlStr: string,
  options: HttpOptions = {},
  _redirectsLeft = 10
): Promise<HttpResponse> {
  const resp = await httpRequestOnce(urlStr, options);

  const status = resp.statusCode;
  if ((status === 301 || status === 302 || status === 303 || status === 307 || status === 308)) {
    const location = resp.headers['location'];
    const redirectUrl = Array.isArray(location) ? location[0] : location;
    if (!redirectUrl) {
      return resp; // no Location header, return as-is
    }
    if (_redirectsLeft <= 0) {
      throw new Error(`Too many redirects for ${urlStr}`);
    }
    // For 303 always use GET; for 307/308 preserve method; for 301/302 use GET
    const redirectMethod = (status === 307 || status === 308) ? (options.method ?? 'GET') : 'GET';
    const redirectBody = redirectMethod === 'GET' ? undefined : options.body;
    // Resolve relative redirects
    const resolvedUrl = redirectUrl.startsWith('http') ? redirectUrl : new URL(redirectUrl, urlStr).toString();
    return httpRequest(resolvedUrl, { ...options, method: redirectMethod, body: redirectBody }, _redirectsLeft - 1);
  }

  return resp;
}

function httpRequestOnce(
  urlStr: string,
  options: HttpOptions = {}
): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    let parsed: URL;
    try {
      parsed = new URL(urlStr);
    } catch (e) {
      reject(new Error(`Invalid URL: ${urlStr}`));
      return;
    }

    const isHttps = parsed.protocol === 'https:';
    const lib = isHttps ? https : http;
    const timeout = options.timeout ?? 30_000;

    const reqOptions: https.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port ? parseInt(parsed.port) : (isHttps ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method: options.method ?? 'GET',
      headers: options.headers ?? {},
    };

    const req = lib.request(reqOptions, (res) => {
      let data = '';
      res.on('data', (chunk: Buffer) => { data += chunk.toString(); });
      res.on('end', () => {
        resolve({
          statusCode: res.statusCode ?? 0,
          body: data,
          headers: res.headers as Record<string, string | string[] | undefined>,
        });
      });
      res.on('error', reject);
    });

    let timedOut = false;
    req.on('error', (err) => {
      if (timedOut) { return; } // already rejected with the timeout message
      reject(err);
    });

    const timer = setTimeout(() => {
      timedOut = true;
      req.destroy();
      reject(new Error(`Request timeout after ${timeout}ms: ${urlStr}`));
    }, timeout);

    req.on('close', () => clearTimeout(timer));

    if (options.body) {
      req.write(options.body, 'utf8');
    }
    req.end();
  });
}

/**
 * Performs an HTTP request and returns the response body as a string.
 * Throws an error if the response status is not 2xx.
 */
export async function httpGet(
  url: string,
  headers: Record<string, string> = {},
  timeout?: number
): Promise<string> {
  const resp = await httpRequest(url, { method: 'GET', headers, timeout });
  if (resp.statusCode < 200 || resp.statusCode >= 300) {
    throw new Error(`HTTP ${resp.statusCode}: ${resp.body.slice(0, 500)}`);
  }
  return resp.body;
}

/**
 * Performs an HTTP POST and returns the response body.
 * Throws on non-2xx unless allowedCodes is specified.
 */
export async function httpPost(
  url: string,
  body: string,
  headers: Record<string, string> = {},
  timeout?: number
): Promise<string> {
  const resp = await httpRequest(url, { method: 'POST', headers, body, timeout });
  if (resp.statusCode < 200 || resp.statusCode >= 300) {
    throw new Error(`HTTP ${resp.statusCode}: ${resp.body.slice(0, 500)}`);
  }
  return resp.body;
}

export async function httpPut(
  url: string,
  body: string,
  headers: Record<string, string> = {},
  timeout?: number
): Promise<string> {
  const resp = await httpRequest(url, { method: 'PUT', headers, body, timeout });
  if (resp.statusCode < 200 || resp.statusCode >= 300) {
    throw new Error(`HTTP ${resp.statusCode}: ${resp.body.slice(0, 500)}`);
  }
  return resp.body;
}

export async function httpPatch(
  url: string,
  body: string,
  headers: Record<string, string> = {},
  timeout?: number
): Promise<string> {
  const resp = await httpRequest(url, { method: 'PATCH', headers, body, timeout });
  if (resp.statusCode < 200 || resp.statusCode >= 300) {
    throw new Error(`HTTP ${resp.statusCode}: ${resp.body.slice(0, 500)}`);
  }
  return resp.body;
}
