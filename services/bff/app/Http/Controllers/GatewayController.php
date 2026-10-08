<?php

namespace App\Http\Controllers;

use App\Bff\Contract;
use App\Bff\Mitigations;
use App\Bff\Problem;
use App\Bff\ShapeException;
use App\Bff\Upstream;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Http\Client\Response as UpstreamResponse;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\StreamedResponse;

/** One controller for every contract endpoint: authenticate (middleware), forward what the contract allows, shape what comes back. */
final class GatewayController extends Controller
{
    /** Upstream client errors the public API passes on (with a sanitised body). Anything else from a service is the BFF's 502. */
    private const PASS_STATUS = [400, 403, 404, 405, 409, 410, 413, 415, 422, 429];

    public function __construct(private Contract $contract, private Upstream $upstream, private Mitigations $mitigations) {}

    public function __invoke(Request $request): Response
    {
        $e = $this->contract->endpoint($request->route()->defaults['_endpoint']);
        $gateway = $request->route()->defaults['_gateway'];
        $params = $request->route()->parameters();
        $path = Upstream::path($e['upstream'], $gateway, $params);

        try {
            if ($e['kind'] === 'stream') {
                return $this->stream($request, $e, $path);
            }
            if ($e['kind'] === 'download') {
                return $this->download($request, $e, $path);
            }
            $client = $this->upstream->client($request, $e['service'], isset($e['multipart']) ? (float) config('bff.timeouts.upload') : null, $e['headers'] ?? [], $e['scope'] !== 'public');
            $method = $request->getMethod();
            $query = Upstream::query($request, $e['query'] ?? []);

            if (isset($e['multipart'])) {
                $up = $this->multipart($request, $client, $e, $path, $query);
            } elseif (in_array($method, ['POST', 'PUT', 'PATCH'], true)) {
                $body = isset($e['input']) ? $this->boundBody($request, $e['input']) : $this->jsonBody($request, $e['body'] ?? []);
                if ($body instanceof JsonResponse) {
                    return $body;
                }
                $up = $client->send($method, $path, ['query' => $query, 'json' => $body === [] ? new \stdClass : $body]);
            } else {
                $up = $client->send($method, $path, ['query' => $query]);
            }
        } catch (ConnectionException $ex) {
            Log::warning('bff upstream unreachable', ['endpoint' => $e['name'], 'service' => $e['service'], 'error' => class_basename($ex)]);

            return Problem::response(504, 'The service did not answer in time', 'Try again shortly.');
        }

        return $this->deliver($up, $e, $gateway);
    }

    /** Only the keys the contract lists, only scalars: a nested object a client smuggles in never travels on. */
    private function jsonBody(Request $request, array $allowed): array|JsonResponse
    {
        if ($allowed === []) {
            return [];
        }
        $raw = $request->getContent();
        if (strlen($raw) > config('bff.limits.max_json_body_bytes')) {
            return Problem::response(413, 'The request body is too large');
        }
        if ($raw === '') {
            return [];
        }
        if (! str_contains((string) $request->header('Content-Type'), 'json')) {
            return Problem::response(415, 'Send the body as application/json');
        }
        $data = json_decode($raw, true);
        if (! is_array($data) || array_is_list($data) && $data !== []) {
            return Problem::response(400, 'The body must be a JSON object');
        }
        $out = [];
        foreach ($allowed as $key) {
            if (! array_key_exists($key, $data)) {
                continue;
            }
            if (! is_scalar($data[$key]) && $data[$key] !== null) {
                return Problem::response(422, 'Invalid request', null, ['errors' => [$key => ['must be a plain value']]]);
            }
            $out[$key] = $data[$key];
        }

        return $out;
    }

    /** Validates and renames the JSON body against the contract's input schema (App\Bff\Binder); a bad request never reaches a service. */
    private function boundBody(Request $request, string $input): array|JsonResponse
    {
        $raw = $request->getContent();
        if (strlen($raw) > config('bff.limits.max_json_body_bytes')) {
            return Problem::response(413, 'The request body is too large');
        }
        if ($raw !== '' && ! str_contains((string) $request->header('Content-Type'), 'json')) {
            return Problem::response(415, 'Send the body as application/json');
        }
        $data = $raw === '' ? [] : json_decode($raw, true);
        if (! is_array($data)) {
            return Problem::response(400, 'The body must be a JSON object');
        }
        [$bound, $errors] = $this->contract->binder()->bind($data, $input);
        if ($errors) {
            return Problem::response(422, 'Invalid request', null, ['errors' => array_map(fn ($m) => array_values(array_unique($m)), array_slice($errors, 0, 50, true))]);
        }

        return $bound;
    }

    private function multipart(Request $request, $client, array $e, string $path, array $query): UpstreamResponse|Response
    {
        $spec = $e['multipart'];
        $file = $request->file($spec['file']);
        if (! $file || ! $file->isValid()) {
            return Problem::response(422, 'Invalid request', null, ['errors' => [$spec['file'] => ['a file is required']]]);
        }
        if ($file->getSize() > config('bff.limits.max_upload_bytes')) {
            return Problem::response(413, 'The file is too large');
        }
        $name = preg_replace('/[^A-Za-z0-9._ -]/', '_', basename($file->getClientOriginalName())) ?: 'upload';
        $fields = [];
        foreach ($spec['fields'] as $f) {
            $v = $request->input($f);
            if ($v !== null && is_scalar($v)) {
                $fields[] = ['name' => $f, 'contents' => (string) $v];
            }
        }
        $stream = fopen($file->getRealPath(), 'r');

        return $client->attach($spec['file'], $stream, $name)->post($path.($query ? '?'.http_build_query($query) : ''), $fields);
    }

    private function deliver(UpstreamResponse|Response $up, array $e, string $gateway): Response
    {
        if ($up instanceof Response) {                         // a request the BFF refused before calling the service
            return $up;
        }
        $status = $up->status();
        if ($status >= 200 && $status < 300) {
            if ($status === 204 || $e['kind'] === 'empty') {
                return response()->noContent();
            }
            $data = $up->json();
            if ($data === null) {
                Log::error('bff upstream answered with a non-JSON body', ['endpoint' => $e['name'], 'status' => $status]);

                return Problem::response(502, 'The service gave an unexpected answer');
            }
            try {
                $shaped = $this->contract->shaper()->shape($data, $e['schema'], $e['kind'] === 'list');
            } catch (ShapeException $ex) {
                Log::error('bff upstream answer does not match the contract', ['endpoint' => $e['name'], 'problem' => $ex->getMessage()]);

                return Problem::response(502, 'The service gave an unexpected answer');
            }
            if (isset($e['append']) && is_array($shaped) && isset($shaped['id'])) {
                foreach ($e['append'] as $k => $tpl) {
                    $shaped[$k] = str_replace(['{gw}', '{id}'], [$gateway, $shaped['id']], $tpl);
                }
            }
            $applied = $this->mitigations->apply($e['name'], $shaped);

            return response()->json($shaped, $status, $applied ? ['X-Mitigations' => implode(',', $applied)] : []);
        }
        if ($status === 401) {
            return Problem::response(401, 'Authentication failed');
        }
        if (in_array($status, self::PASS_STATUS, true)) {
            return Problem::response($status, self::title($status), $this->safeDetail($up), $this->safeErrors($up));
        }
        Log::error('bff upstream failure', ['endpoint' => $e['name'], 'service' => $e['service'], 'status' => $status]);

        return Problem::response(502, 'The service could not complete the request', 'Try again shortly.');
    }

    private static function title(int $status): string
    {
        return [400 => 'Bad request', 403 => 'Not allowed', 404 => 'Not found', 405 => 'Method not allowed', 409 => 'Conflict', 410 => 'Gone',
            413 => 'Too large', 415 => 'Unsupported media type', 422 => 'Invalid request', 429 => 'Too many requests'][$status];
    }

    /** A service's own explanation, if it is short plain text. Never an object, never a trace. */
    private function safeDetail(UpstreamResponse $up): ?string
    {
        $d = $up->json('detail');
        $d = is_string($d) ? $d : (is_string($up->json('message')) ? $up->json('message') : null);

        return $d !== null && strlen($d) <= 300 && ! preg_match('/\b(exception|stack ?trace|at [a-z]+\.[a-z.]+\()/i', $d) ? $d : null;
    }

    /** {field: [messages]} only. */
    private function safeErrors(UpstreamResponse $up): array
    {
        $errors = $up->json('errors');
        if (! is_array($errors)) {
            return [];
        }
        $out = [];
        foreach (array_slice($errors, 0, 50, true) as $field => $msgs) {
            $msgs = is_array($msgs) ? $msgs : [$msgs];
            $out[(string) $field] = array_values(array_map(fn ($m) => substr((string) (is_scalar($m) ? $m : ''), 0, 200), array_slice($msgs, 0, 10)));
        }

        return $out ? ['errors' => $out] : [];
    }

    /** Files (reports, generated sheets): only declared content types, a size cap, a rebuilt Content-Disposition. Streamed, never buffered. */
    private function download(Request $request, array $e, string $path): Response
    {
        $d = $e['download'];
        $method = $request->getMethod();
        $client = $this->upstream->client($request, $e['service'], (float) config('bff.timeouts.upload'), $e['headers'] ?? [])->withOptions(['stream' => true])->accept('*/*');
        $query = Upstream::query($request, $e['query'] ?? []);
        if ($method === 'POST') {
            $body = isset($e['input']) ? $this->boundBody($request, $e['input']) : [];
            if ($body instanceof JsonResponse) {
                return $body;
            }
            $up = $client->post($path.($query ? '?'.http_build_query($query) : ''), $body === [] ? new \stdClass : $body);
        } else {
            $up = $client->get($path, $query);
        }
        if (! $up->successful()) {
            return $this->deliver($up, ['name' => $e['name'], 'service' => $e['service'], 'kind' => 'error', 'schema' => ''], $request->route()->defaults['_gateway']);
        }
        $type = strtolower(trim(explode(';', (string) $up->header('Content-Type'))[0]));
        if (! in_array($type, $d['types'], true)) {
            Log::error('bff upstream file has an unexpected content type', ['endpoint' => $e['name'], 'type' => $type]);

            return Problem::response(502, 'The service gave an unexpected answer');
        }
        $name = 'download';
        if (preg_match('/filename="?([^";]+)"?/i', (string) $up->header('Content-Disposition'), $m)) {
            $name = preg_replace('/[^A-Za-z0-9._-]/', '_', basename($m[1])) ?: 'download';
        }
        $max = (int) ($d['max_bytes'] ?? config('bff.limits.max_download_bytes'));
        $body = $up->toPsrResponse()->getBody();
        $extra = array_filter(['X-Report-Truncated' => $up->header('X-Report-Truncated') ?: null]);

        return new StreamedResponse(function () use ($body, $max) {
            $sent = 0;
            while (! $body->eof() && $sent < $max) {
                $chunk = $body->read(8192);
                $sent += strlen($chunk);
                echo $chunk;
                @ob_flush();
                flush();
            }
        }, 200, $extra + ['Content-Type' => $type, 'Content-Disposition' => 'attachment; filename="'.$name.'"', 'X-Correlation-Id' => (string) $request->attributes->get('correlation_id'),
            'Cache-Control' => 'no-store']);
    }

    /** Server-Sent Events are re-emitted event by event through the same allowlist, so progress carries no more than a job read does. */
    private function stream(Request $request, array $e, string $path): Response
    {
        $client = $this->upstream->client($request, $e['service'], (float) config('bff.timeouts.stream'))->withOptions(['stream' => true])->accept('text/event-stream');
        $up = $client->get($path);
        if ($up->status() !== 200) {
            return $this->deliver($up, $e, $request->route()->defaults['_gateway']);
        }
        $shaper = $this->contract->shaper();
        $body = $up->toPsrResponse()->getBody();

        return new StreamedResponse(function () use ($body, $shaper, $e) {
            $buffer = '';
            while (! $body->eof()) {
                $buffer .= $body->read(1024);
                while (($pos = strpos($buffer, "\n\n")) !== false) {
                    $event = substr($buffer, 0, $pos);
                    $buffer = substr($buffer, $pos + 2);
                    echo $this->reshape($event, $shaper, $e['schema'])."\n\n";
                    @ob_flush();
                    flush();
                }
                if (strlen($buffer) > 65536) {
                    return;                                  // a runaway event: stop rather than buffer without bound
                }
            }
        }, 200, ['Content-Type' => 'text/event-stream', 'Cache-Control' => 'no-cache, no-transform', 'X-Accel-Buffering' => 'no',
            'X-Correlation-Id' => (string) $request->attributes->get('correlation_id')]);
    }

    private function reshape(string $event, $shaper, string $schema): string
    {
        $name = 'message';
        $data = null;
        foreach (explode("\n", $event) as $line) {
            if (str_starts_with($line, 'event:')) {
                $name = trim(substr($line, 6));
            } elseif (str_starts_with($line, 'data:')) {
                $data = json_decode(trim(substr($line, 5)), true);
            } elseif (str_starts_with($line, ':')) {
                return $line;                                // a keep-alive comment
            }
        }
        $name = preg_match('/^[a-z_]{1,30}$/', $name) ? $name : 'message';
        try {
            $payload = is_array($data) ? $shaper->shape($data, $schema) : ['error' => 'unreadable event'];
        } catch (ShapeException) {
            $payload = ['error' => 'unreadable event'];
        }

        return "event: $name\ndata: ".json_encode($payload);
    }
}
