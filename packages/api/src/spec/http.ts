import { asyncFunction } from '../async.ts'
import type { LuaClass } from '../types.ts'

/** `nf.http`: requests to web services. */
export const nfHttp: LuaClass = {
  name: 'nf.http',
  doc: 'Requests to web services (a Discord webhook, a status API). A package declares the hosts it talks to in `netherforge.json`\'s `requires` (`"http": ["discord.com"]`, or `"*.discord.com"` for every name under it), and the server owner sees every host the project and its packages declare when it starts (`/nf requires`); a request to any other host is an error. Every request is held to the package whose code makes it. The server looks a name up once, checks the addresses it leads to and connects to exactly the address it checked: a loopback, private, link-local, shared (`100.64.0.0/10`), unique-local or IPv4-mapped address is refused even when its name is declared, unless the server owner turned on `http.allow-private-addresses` in `config.yml` (for a service on the same machine or network). Redirects (at most 5) are followed, and each one is held to the same rules, host declaration included. The server\'s proxy settings are never used. Only `http` and `https` URLs work. The server owner also limits requests in `config.yml`\'s `http:` section: how large a request and a response may be, how long a request may take, and how many a package may make a minute.',
  methods: false,
  fields: [],
  functions: [
    asyncFunction({
      name: 'request',
      doc: "Sends an HTTP request and gives the response. Any response is a result, whatever its status: a `404` or `500` is `response.status`, not an error, so check it. `nil, err` is for a request that couldn't be made or finished: a name that doesn't resolve, an address that isn't allowed, a refused connection, a timeout, a response over the size limit, too many requests this minute, a redirect to a host the package didn't declare, or more than 5 redirects. A mistake in the call itself (a URL that isn't `http` or `https`, a host the package didn't declare, a body over the limit, a header that can't be sent) is an error at the script's line.",
      requires: 'http',
      params: [
        {
          name: 'options',
          type: 'HttpRequestOptions',
          doc: 'The request: its `url`, and optionally its `method`, `headers` and `body`.',
        },
      ],
      value: {
        name: 'response',
        type: 'HttpResponse',
        doc: 'the response: its status, headers and body',
      },
      example:
        'nf.task(function()\n  local response, err = nf.http.request({\n    method = "POST",\n    url = "https://discord.com/api/webhooks/123/token",\n    headers = { ["content-type"] = "application/json" },\n    body = nf.json.encode({ content = "someone joined" }),\n  })\n  if not response then\n    log("webhook failed: " .. err)\n  elseif response.status >= 400 then\n    log("webhook said " .. response.status)\n  end\nend)',
    }),
  ],
}

/** The plain tables `nf.http.request` takes and gives. */
export const httpShapes: LuaClass[] = [
  {
    name: 'HttpRequestOptions',
    doc: 'What `nf.http.request` takes.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'url',
        type: 'string',
        doc: 'An `http://` or `https://` URL, without a user name or password in it (send credentials in a header). Its host must be one the calling package declares in `requires`.',
      },
      {
        name: 'method',
        type: '"GET"|"POST"|"PUT"|"PATCH"|"DELETE"|"HEAD"|"OPTIONS"?',
        doc: 'The HTTP method; `"GET"` when left out.',
      },
      {
        name: 'headers',
        type: 'table<string, string>?',
        doc: 'Request headers by name. `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade` and the like are set by the server and refused here. A `User-Agent` of `NetherForge` is sent unless you give one. `Authorization`, `Cookie` and `Proxy-Authorization` are dropped when a redirect leads to another host.',
      },
      {
        name: 'body',
        type: 'string?',
        doc: "The request body, as text (UTF-8). Its size is limited by `http.max-request-bytes` in the server's `config.yml`. A `GET`, `HEAD` or `OPTIONS` can't have one.",
      },
    ],
  },
  {
    name: 'HttpResponse',
    doc: 'What `nf.http.request` gives.',
    methods: false,
    functions: [],
    fields: [
      { name: 'status', type: 'integer', doc: 'The status code: `200`, `404`.' },
      {
        name: 'headers',
        type: 'table<string, string>',
        doc: 'The response headers by name in lowercase (`response.headers["content-type"]`); a header sent more than once is its values joined with `, `.',
      },
      {
        name: 'body',
        type: 'string',
        doc: "The body as text, decoded by the charset the response names (UTF-8 when it names none), so a response that isn't text (an image) doesn't survive. Its size is limited by `http.max-response-bytes` in the server's `config.yml`.",
      },
      {
        name: 'url',
        type: 'string',
        doc: "The URL the response came from: the request's own, or where its redirects ended.",
      },
    ],
  },
]
