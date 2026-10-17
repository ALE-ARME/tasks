# Tasks.org HTTP API

A local HTTP server, in the same app, over the same data as the
[content provider API](CONTENT_PROVIDER.md). It is off by default and is started and stopped
with a broadcast, so it only listens while something needs it.

> **`v0` is unstable.** Collections, columns and parameters follow the content provider API and
> may change between releases.

## Starting and stopping

```bash
adb shell am broadcast -a org.tasks.intent.action.START_HTTP_SERVER
adb shell am broadcast -a org.tasks.intent.action.STOP_HTTP_SERVER
```

The start broadcast reports the endpoint as its result data:

```
Broadcast completed: result=0, data="http://127.0.0.1:8275/v0"
```

| Extra | Type | Meaning |
| --- | --- | --- |
| `port` | int | Port to listen on. Defaults to 8275, and to the last port used. |
| `token` | string | Bearer token to require. Defaults to the stored token, and is generated on the first start. |

`START` binds `127.0.0.1` only: nothing on the network can reach the server, and a client on the
phone itself (`curl` in Termux, an automation app) or a host reaching the device over
`adb forward tcp:8275 tcp:8275` is all that can talk to it. Every request except `GET /health`
needs `Authorization: Bearer <token>`.

Passing `--es token` replaces the stored token, which is the way to set one you know without
reading it back from the app:

```bash
adb shell am broadcast -a org.tasks.intent.action.START_HTTP_SERVER --es token "$TOKEN"
```

On Android 13 and up the notification permission is not required for this, but the app is kept out
of the cached and frozen states by a foreground service, so a notification is shown while the API
is running. If the platform refuses the foreground start (it does not count the broadcast as a user
action), the API keeps serving from the process handling the broadcast, and may be stopped by the
system sooner.

## Requests

`GET` lists, `POST` creates, `PATCH` (or `PUT`) changes and `DELETE` removes. Parameters, writable
columns and failures are exactly those of the [content provider API](CONTENT_PROVIDER.md).

| Endpoint | Methods |
| --- | --- |
| `/health` | `GET` - no token needed |
| `/v0` | `GET` - collections, parameters, columns and methods |
| `/v0/tasks`, `/v0/reminders`, `/v0/task_tags`, `/v0/lists`, `/v0/tags`, `/v0/places` | `GET`, `POST` |
| `/v0/tasks/{id}` and the same for the other collections | `GET`, `PATCH`, `DELETE` |
| `/v0/accounts`, `/v0/accounts/{id}` | `GET` |
| `/v0/task_tags?task_id={id}&tag_id={id}` | `DELETE` - takes one tag off one task |

A collection read returns a page:

```json
{
  "rows": [{"_id": 12, "title": "Buy milk", "due_date": 1757635200000}],
  "total": 1,
  "offset": 0,
  "has_more": false
}
```

A write takes a JSON object of the writable columns and nothing else:

```bash
curl -s -X POST http://127.0.0.1:8275/v0/tasks \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title": "Buy milk", "notes": "Oat", "due_date": "2026-09-12"}'
# {"id": 12}
```

Dates are epoch milliseconds, a local date (`2026-09-12`, stored as all day) or a local date and
time (`2026-09-12T19:00:00`). `created` in a create response means the row already existed and was
reused, which only happens for tags and places.

Status codes follow the same three outcomes as the provider API:

| Status | Meaning |
| --- | --- |
| `400` | The request is malformed: an unknown parameter, a body that is not a JSON object, a value the column cannot take. |
| `401` | No token, or the wrong one. |
| `403` | The target is read-only, such as a task on a shared list. |
| `404` | Nothing at that path or no row with that id. |
| `405` | The collection does not take that method; `Allow` lists the ones it does. |
| `502` | A remote operation, such as a sync, failed. |
| `500` | The app failed in a way the request cannot fix. |

Errors carry the reason in the body:

```json
{"error": "The request was malformed: Unknown parameter 'nope'. Supported: ..."}
```
